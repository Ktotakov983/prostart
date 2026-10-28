package io.github.ktotakov983.startgun;

import android.app.Activity;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.webkit.JavascriptInterface;

import java.util.Locale;

/**
 * Compass for the page (JS bridge "StartGunCompass"): Android's fused
 * rotation-vector sensor, tilt-compensated, plus the magnetometer's
 * calibration state and the magnetic declination at the last GPS fix.
 * Runs only while the app is on screen.
 */
public class CompassSensor implements SensorEventListener {
    private static final long DECLINATION_REFRESH_MS = 60_000;

    private final Activity activity;
    private final SensorManager sensors;
    private final Sensor rotation;
    private final Sensor magnetic;
    private final float[] matrix = new float[9];
    private final float[] rotationVector = new float[4];

    private volatile double headingDeg = Double.NaN;
    private volatile double accuracyDeg = Double.NaN;
    private volatile int calibration = -1;  // SensorManager.SENSOR_STATUS_*: 0 unreliable … 3 high
    private volatile double declinationDeg = Double.NaN;
    private long declinationAt = 0;

    CompassSensor(Activity activity) {
        this.activity = activity;
        sensors = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
        rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        magnetic = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
    }

    void start() {
        if (rotation != null) sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME);
        if (magnetic != null) sensors.registerListener(this, magnetic, SensorManager.SENSOR_DELAY_NORMAL);
    }

    void stop() {
        sensors.unregisterListener(this);
    }

    @JavascriptInterface
    public String read() {
        return String.format(Locale.US, "{\"h\":%s,\"acc\":%s,\"cal\":%s,\"decl\":%s}",
                num(headingDeg), num(accuracyDeg), calibration < 0 ? "null" : String.valueOf(calibration), num(declinationDeg));
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "null" : String.format(Locale.US, "%.2f", v);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            calibration = e.accuracy;
            return;
        }
        // Some devices fail on vectors longer than 4 values.
        if (e.values.length > 4) {
            System.arraycopy(e.values, 0, rotationVector, 0, 4);
            SensorManager.getRotationMatrixFromVector(matrix, rotationVector);
        } else {
            SensorManager.getRotationMatrixFromVector(matrix, e.values);
        }

        // Rows: east, north, up; columns: device x, y, z.
        // Upright phone (screen to the helmsman): forward is the back, -z.
        double backE = -matrix[2], backN = -matrix[5];
        // Flat phone: forward is the top of the screen, a side edge in landscape.
        double a = Math.toRadians(screenRotationDeg());
        double topE = Math.sin(a) * matrix[0] + Math.cos(a) * matrix[1];
        double topN = Math.sin(a) * matrix[3] + Math.cos(a) * matrix[4];
        boolean upright = Math.hypot(backE, backN) >= Math.hypot(topE, topN);
        double h = Math.toDegrees(upright ? Math.atan2(backE, backN) : Math.atan2(topE, topN));
        headingDeg = (h + 360) % 360;

        if (e.values.length > 4 && e.values[4] >= 0) accuracyDeg = Math.toDegrees(e.values[4]);
        refreshDeclination();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) calibration = accuracy;
    }

    private int screenRotationDeg() {
        switch (activity.getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private void refreshDeclination() {
        long now = System.currentTimeMillis();
        if (!GpsService.hasFix || now - declinationAt < DECLINATION_REFRESH_MS) return;
        declinationAt = now;
        GeomagneticField field = new GeomagneticField(
                (float) GpsService.lastLat, (float) GpsService.lastLon, 0f, now);
        declinationDeg = field.getDeclination();
    }
}
