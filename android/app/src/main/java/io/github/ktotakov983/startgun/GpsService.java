package io.github.ktotakov983.startgun;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import java.util.Locale;

/**
 * Foreground service that reads the satellite receiver directly (GPS_PROVIDER,
 * 1 Hz, no Wi-Fi/cell blending) and keeps doing so while the screen is off.
 * Fixes are buffered here as JSON and drained by the web page through
 * {@link MainActivity}'s StartGunGps bridge, so nothing is lost while the
 * WebView is asleep.
 */
public class GpsService extends Service implements LocationListener {
    static final String ACTION_STOP = "io.github.ktotakov983.startgun.STOP";
    private static final String CHANNEL_ID = "track";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_BUFFER = 24 * 3600;  // a day of 1 Hz fixes
    private static final long WAKE_LOCK_MS = 12L * 3600 * 1000;

    private static final Object LOCK = new Object();
    private static final ArrayList<String> buffer = new ArrayList<>();
    static volatile boolean running = false;
    private static volatile int satsUsed = -1;
    private static volatile int satsVisible = -1;
    private static volatile boolean fullTracking = false;
    // Last fix, for the compass's magnetic declination.
    static volatile boolean hasFix = false;
    static volatile double lastLat = 0;
    static volatile double lastLon = 0;

    private LocationManager locationManager;
    private PowerManager.WakeLock wakeLock;
    private GnssStatus.Callback statusCallback;
    private GnssMeasurementsEvent.Callback measurementsCallback;

    static String drain() {
        synchronized (LOCK) {
            String out = "[" + String.join(",", buffer) + "]";
            buffer.clear();
            return out;
        }
    }

    static String statusJson() {
        return String.format(Locale.US,
                "{\"running\":%b,\"satsUsed\":%d,\"satsVisible\":%d,\"fullTracking\":%b}",
                running, satsUsed, satsVisible, fullTracking);
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
                }
                satsUsed = used;
                satsVisible = status.getSatelliteCount();
            }
        };
        try {
            locationManager.registerGnssStatusCallback(statusCallback, new Handler(Looper.getMainLooper()));
        } catch (SecurityException ignored) {
        }

        // Android 12+: ask for full GNSS tracking, the same as the developer
        // option "Force full GNSS measurements" - no duty cycling of the receiver.
        if (Build.VERSION.SDK_INT >= 31) {
            measurementsCallback = new GnssMeasurementsEvent.Callback() {
            };
            try {
                GnssMeasurementRequest request = new GnssMeasurementRequest.Builder().setFullTracking(true).build();
                fullTracking = locationManager.registerGnssMeasurementsCallback(request, getMainExecutor(), measurementsCallback);
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
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

    @Override
    public void onLocationChanged(Location l) {
        lastLat = l.getLatitude();
        lastLon = l.getLongitude();
        hasFix = true;
        // Wall-clock time of the fix, in the same clock as the page's Date.now().
        long ageMs = (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
        long t = System.currentTimeMillis() - Math.max(0, ageMs);
        String speedAccuracy = (Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy())
                ? String.format(Locale.US, "%.3f", l.getSpeedAccuracyMetersPerSecond()) : "null";
        String json = String.format(Locale.US,
                "{\"t\":%d,\"lat\":%.7f,\"lon\":%.7f,\"acc\":%s,\"spd\":%s,\"sacc\":%s,\"su\":%d,\"sv\":%d}",
                t, l.getLatitude(), l.getLongitude(),
                l.hasAccuracy() ? String.format(Locale.US, "%.1f", l.getAccuracy()) : "null",
                l.hasSpeed() ? String.format(Locale.US, "%.3f", l.getSpeed()) : "null",
                speedAccuracy, satsUsed, satsVisible);
        synchronized (LOCK) {
            buffer.add(json);
            if (buffer.size() > MAX_BUFFER) buffer.remove(0);
        }
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
        if (locationManager != null) {
            locationManager.removeUpdates(this);
            if (statusCallback != null) locationManager.unregisterGnssStatusCallback(statusCallback);
            if (measurementsCallback != null) locationManager.unregisterGnssMeasurementsCallback(measurementsCallback);
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
