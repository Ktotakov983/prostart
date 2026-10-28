package io.github.ktotakov983.startgun;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Foreground service that reads the satellite receiver directly (GPS_PROVIDER,
 * 1 Hz, no Wi-Fi/cell blending) and keeps doing so while the screen is off.
 * Each fix is buffered as JSON and drained by the web page through
 * {@link MainActivity}'s StartGunGps bridge, so nothing is lost while the
 * WebView is asleep. Besides position and Doppler speed a fix carries:
 * - the receiver's own course over ground and its accuracy;
 * - the mean horizontal acceleration since the previous fix (world frame);
 * - a carrier-phase velocity, when the phone reports carrier phase (ADR).
 * All callbacks run on the main looper, so the state below needs no locking
 * except the buffer shared with the JS bridge thread.
 */
public class GpsService extends Service implements LocationListener, SensorEventListener {
    static final String ACTION_STOP = "io.github.ktotakov983.startgun.STOP";
    private static final String CHANNEL_ID = "track";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_BUFFER = 24 * 3600;  // a day of 1 Hz fixes
    private static final long WAKE_LOCK_MS = 12L * 3600 * 1000;

    // Carrier phase: how long a fix waits for the same epoch's measurements,
    // and the sanity limits for the correction.
    private static final long PAIR_WAIT_MS = 400;
    private static final long PAIR_MAX_SKEW_NS = 600_000_000L;
    private static final int CP_MIN_SATS = 6;
    private static final double CP_MAX_RMS = 0.15;         // m/s
    private static final double CP_MAX_CORRECTION = 1.0;   // m/s
    private static final double CP_MIN_CHIP_SPEED = 0.5;   // m/s, below it the chip's direction is undefined
    private static final float CP_MIN_ELEVATION = 10f;     // deg

    private static final Object LOCK = new Object();
    private static final ArrayList<String> buffer = new ArrayList<>();
    static volatile boolean running = false;
    private static volatile int satsUsed = -1;
    private static volatile int satsVisible = -1;
    private static volatile boolean fullTracking = false;
    private static volatile int adrSats = -1;       // satellites with usable carrier phase, -1 = no measurements
    private static volatile int cpUsedSats = 0;
    // Last fix, for the compass's magnetic declination.
    static volatile boolean hasFix = false;
    static volatile double lastLat = 0;
    static volatile double lastLon = 0;

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private PowerManager.WakeLock wakeLock;
    private GnssStatus.Callback statusCallback;
    private GnssMeasurementsEvent.Callback measurementsCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Accelerometer, world frame (east, north), summed between fixes.
    private final float[] rotationMatrix = new float[9];
    private final float[] rotationVector = new float[4];
    private boolean haveRotation = false;
    private double accSumE = 0, accSumN = 0;
    private int accCount = 0;

    // Carrier phase.
    private final Map<Integer, float[]> azEl = new HashMap<>();       // key → {azimuth, elevation}
    private final Map<Integer, double[]> lastAdr = new HashMap<>();   // key → {adr m, time ns}
    private List<double[]> pendingRows = null;                        // rows of the last epoch
    private long pendingRowsAt = 0;                                   // elapsedRealtime ns
    private Location pendingFix = null;
    private final Runnable emitPendingFix = () -> {
        if (pendingFix != null) emitFix(pendingFix, null);
    };

    static String drain() {
        synchronized (LOCK) {
            String out = "[" + String.join(",", buffer) + "]";
            buffer.clear();
            return out;
        }
    }

    static String statusJson() {
        return String.format(Locale.US,
                "{\"running\":%b,\"satsUsed\":%d,\"satsVisible\":%d,\"fullTracking\":%b,\"adrSats\":%d,\"cpSats\":%d}",
                running, satsUsed, satsVisible, fullTracking, adrSats, cpUsedSats);
    }

    private static int satKey(int constellation, int svid) {
        return constellation * 1000 + svid;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        startInForeground();

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
        } catch (SecurityException e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        statusCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                int used = 0;
                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    if (status.usedInFix(i)) used++;
                    azEl.put(satKey(status.getConstellationType(i), status.getSvid(i)),
                            new float[]{status.getAzimuthDegrees(i), status.getElevationDegrees(i)});
                }
                satsUsed = used;
                satsVisible = status.getSatelliteCount();
            }
        };
        try {
            locationManager.registerGnssStatusCallback(statusCallback, handler);
        } catch (SecurityException ignored) {
        }

        measurementsCallback = new GnssMeasurementsEvent.Callback() {
            @Override
            public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
                onMeasurements(event);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // Full GNSS tracking, the same as the developer option "Force full
                // GNSS measurements": no duty cycling, and carrier phase stays locked.
                GnssMeasurementRequest request = new GnssMeasurementRequest.Builder().setFullTracking(true).build();
                fullTracking = locationManager.registerGnssMeasurementsCallback(request, getMainExecutor(), measurementsCallback);
            } else {
                locationManager.registerGnssMeasurementsCallback(measurementsCallback, handler);
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
        }

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        Sensor linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (rotation != null && linear != null) {
            sensorManager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME, handler);
            sensorManager.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME, handler);
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StartGun:track");
        wakeLock.acquire(WAKE_LOCK_MS);
        running = true;
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Запись трека", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, GpsService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("StartGun записывает трек")
                .setContentText("GPS работает и при выключенном экране")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ---- accelerometer ----

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            if (e.values.length > 4) {
                System.arraycopy(e.values, 0, rotationVector, 0, 4);
                SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
            } else {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, e.values);
            }
            haveRotation = true;
        } else if (e.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION && haveRotation) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            // Rows of the rotation matrix: east, north, up.
            accSumE += rotationMatrix[0] * x + rotationMatrix[1] * y + rotationMatrix[2] * z;
            accSumN += rotationMatrix[3] * x + rotationMatrix[4] * y + rotationMatrix[5] * z;
            accCount++;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ---- carrier phase ----
    // The receiver's velocity comes from Doppler (pseudorange rate, ±0.1 m/s per
    // satellite). The change of carrier phase (ADR) over one second measures the
    // same range rate about 100× more precisely. Satellite motion is identical in
    // both, so per satellite r = ADR rate − Doppler rate = −δv·e + clock drift,
    // where e is the direction to the satellite (from azimuth/elevation) and δv
    // the error of the receiver's velocity. A least-squares fit over ≥6 satellites
    // gives δv; receiver velocity + δv is the carrier-phase velocity.

    private void onMeasurements(GnssMeasurementsEvent event) {
        long clockNs = event.getClock().getTimeNanos();
        List<double[]> rows = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        Map<Integer, double[]> next = new HashMap<>();
        int valid = 0;
        for (GnssMeasurement m : event.getMeasurements()) {
            int key = satKey(m.getConstellationType(), m.getSvid());
            if (seen.contains(key)) continue;  // one signal per satellite
            int state = m.getAccumulatedDeltaRangeState();
            boolean usable = (state & GnssMeasurement.ADR_STATE_VALID) != 0
                    && (state & (GnssMeasurement.ADR_STATE_RESET | GnssMeasurement.ADR_STATE_CYCLE_SLIP)) == 0;
            if (!usable) continue;
            seen.add(key);
            valid++;
            double adr = m.getAccumulatedDeltaRangeMeters();
            next.put(key, new double[]{adr, clockNs});
            double[] prev = lastAdr.get(key);
            float[] ae = azEl.get(key);
            if (prev == null || ae == null || ae[1] < CP_MIN_ELEVATION) continue;
            double dt = (clockNs - prev[1]) / 1e9;
            if (dt < 0.5 || dt > 2.5) continue;
            double adrRate = (adr - prev[0]) / dt;
            double az = Math.toRadians(ae[0]), el = Math.toRadians(ae[1]);
            double eE = Math.sin(az) * Math.cos(el), eN = Math.cos(az) * Math.cos(el), eU = Math.sin(el);
            rows.add(new double[]{-eE, -eN, -eU, 1, adrRate - m.getPseudorangeRateMetersPerSecond()});
        }
        lastAdr.clear();
        lastAdr.putAll(next);
        adrSats = valid;

        pendingRows = rows;
        pendingRowsAt = SystemClock.elapsedRealtimeNanos();
        if (pendingFix != null && Math.abs(pendingRowsAt - pendingFix.getElapsedRealtimeNanos()) < PAIR_MAX_SKEW_NS) {
            handler.removeCallbacks(emitPendingFix);
            emitFix(pendingFix, rows);
        }
    }

    /** Returns {vE, vN, sigma, sats} or null when the correction can't be trusted. */
    private double[] carrierPhaseVelocity(Location l, List<double[]> rows) {
        if (rows == null || rows.size() < CP_MIN_SATS) return null;
        if (!l.hasSpeed() || !l.hasBearing() || l.getSpeed() < CP_MIN_CHIP_SPEED) return null;
        double[] d = leastSquares4(rows);
        if (d == null) return null;
        double ss = 0;
        for (double[] r : rows) {
            double fit = r[0] * d[0] + r[1] * d[1] + r[2] * d[2] + r[3] * d[3];
            ss += (r[4] - fit) * (r[4] - fit);
        }
        double rms = Math.sqrt(ss / (rows.size() - 4));
        if (rms > CP_MAX_RMS || Math.hypot(d[0], d[1]) > CP_MAX_CORRECTION) return null;
        double b = Math.toRadians(l.getBearing());
        double vE = l.getSpeed() * Math.sin(b) + d[0];
        double vN = l.getSpeed() * Math.cos(b) + d[1];
        return new double[]{vE, vN, Math.max(0.03, rms), rows.size()};
    }

    /** Least squares for 4 unknowns via the normal equations; null if singular. */
    private static double[] leastSquares4(List<double[]> rows) {
        double[][] a = new double[4][5];
        for (double[] r : rows) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) a[i][j] += r[i] * r[j];
                a[i][4] += r[i] * r[4];
            }
        }
        for (int c = 0; c < 4; c++) {
            int p = c;
            for (int i = c + 1; i < 4; i++) if (Math.abs(a[i][c]) > Math.abs(a[p][c])) p = i;
            if (Math.abs(a[p][c]) < 1e-9) return null;
            double[] tmp = a[c]; a[c] = a[p]; a[p] = tmp;
            for (int i = 0; i < 4; i++) {
                if (i == c) continue;
                double f = a[i][c] / a[c][c];
                for (int j = c; j < 5; j++) a[i][j] -= f * a[c][j];
            }
        }
        return new double[]{a[0][4] / a[0][0], a[1][4] / a[1][1], a[2][4] / a[2][2], a[3][4] / a[3][3]};
    }

    // ---- fixes ----

    @Override
    public void onLocationChanged(Location l) {
        lastLat = l.getLatitude();
        lastLon = l.getLongitude();
        hasFix = true;
        if (pendingFix != null) {  // previous fix never got its measurements
            handler.removeCallbacks(emitPendingFix);
            emitFix(pendingFix, null);
        }
        if (pendingRows != null && Math.abs(pendingRowsAt - l.getElapsedRealtimeNanos()) < PAIR_MAX_SKEW_NS) {
            emitFix(l, pendingRows);
        } else {
            // Wait briefly for this epoch's measurements; emit without them otherwise.
            pendingFix = l;
            handler.postDelayed(emitPendingFix, PAIR_WAIT_MS);
        }
    }

    private void emitFix(Location l, List<double[]> rows) {
        pendingFix = null;
        if (rows != null && rows == pendingRows) pendingRows = null;

        // Wall-clock time of the fix, in the same clock as the page's Date.now().
        long ageMs = (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
        long t = System.currentTimeMillis() - Math.max(0, ageMs);

        String speedAccuracy = (Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy())
                ? fmt(l.getSpeedAccuracyMetersPerSecond(), 3) : "null";
        String bearing = l.hasBearing() ? fmt(l.getBearing(), 1) : "null";
        String bearingAccuracy = (Build.VERSION.SDK_INT >= 26 && l.hasBearingAccuracy())
                ? fmt(l.getBearingAccuracyDegrees(), 1) : "null";

        String accE = "null", accN = "null";
        if (accCount > 0) {
            accE = fmt(accSumE / accCount, 3);
            accN = fmt(accSumN / accCount, 3);
        }
        accSumE = accSumN = 0;
        accCount = 0;

        double[] cp = carrierPhaseVelocity(l, rows);
        cpUsedSats = cp != null ? (int) cp[3] : 0;
        String cpJson = cp == null ? "null"
                : String.format(Locale.US, "{\"e\":%.3f,\"n\":%.3f,\"sig\":%.3f,\"sats\":%d}", cp[0], cp[1], cp[2], (int) cp[3]);

        String json = String.format(Locale.US,
                "{\"t\":%d,\"lat\":%.7f,\"lon\":%.7f,\"acc\":%s,\"spd\":%s,\"sacc\":%s,\"brg\":%s,\"bacc\":%s," +
                        "\"ae\":%s,\"an\":%s,\"cp\":%s,\"su\":%d,\"sv\":%d}",
                t, l.getLatitude(), l.getLongitude(),
                l.hasAccuracy() ? fmt(l.getAccuracy(), 1) : "null",
                l.hasSpeed() ? fmt(l.getSpeed(), 3) : "null",
                speedAccuracy, bearing, bearingAccuracy, accE, accN, cpJson, satsUsed, satsVisible);
        synchronized (LOCK) {
            buffer.add(json);
            if (buffer.size() > MAX_BUFFER) buffer.remove(0);
        }
    }

    private static String fmt(double v, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    // Required by LocationListener before Android 11.
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onDestroy() {
        running = false;
        fullTracking = false;
        adrSats = -1;
        handler.removeCallbacks(emitPendingFix);
        if (locationManager != null) {
            locationManager.removeUpdates(this);
            if (statusCallback != null) locationManager.unregisterGnssStatusCallback(statusCallback);
            if (measurementsCallback != null) locationManager.unregisterGnssMeasurementsCallback(measurementsCallback);
        }
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
