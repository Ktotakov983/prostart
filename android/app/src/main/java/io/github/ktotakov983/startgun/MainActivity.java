package io.github.ktotakov983.startgun;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen WebView over the ProStart site. Exposes three bridges to the page:
 * AndroidVolumeBridge (hardware volume keys → GUN / mode while the app's screen
 * lock is on, same contract as the first StartGun), StartGunGps (native GPS
 * from {@link GpsService}) and StartGunCompass ({@link CompassSensor}).
 */
public class MainActivity extends Activity {
    private static final String APP_URL = "https://ktotakov983.github.io/prostart/";
    private static final int REQUEST_PERMISSIONS = 1;

    private WebView web;
    private CompassSensor compass;
    private volatile boolean volumeKeysEnabled = false;
    private boolean permissionsAsked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });
        web.addJavascriptInterface(new VolumeBridge(), "AndroidVolumeBridge");
        web.addJavascriptInterface(new GpsBridge(), "StartGunGps");
        compass = new CompassSensor(this);
        web.addJavascriptInterface(compass, "StartGunCompass");
        web.loadUrl(APP_URL);

        requestNeededPermissions();
    }

    // The compass is only needed while the screen shows it; GPS keeps running
    // in GpsService regardless.
    @Override
    protected void onResume() {
        super.onResume();
        compass.start();
    }

    @Override
    protected void onPause() {
        compass.stop();
        super.onPause();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        if (permissionsAsked) return;
        List<String> perms = new ArrayList<>();
        if (!hasLocationPermission()) perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (perms.isEmpty()) return;
        permissionsAsked = true;
        requestPermissions(perms.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (hasLocationPermission()) {
            web.evaluateJavascript("window.__onStartGunGpsReady && window.__onStartGunGpsReady()", null);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (volumeKeysEnabled && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                String dir = code == KeyEvent.KEYCODE_VOLUME_UP ? "up" : "down";
                web.evaluateJavascript("window.__onHardwareVolumeKey && window.__onHardwareVolumeKey('" + dir + "')", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // WebView timers are deliberately not paused in onPause: the page keeps
    // draining GPS fixes while the screen is off for as long as Android lets it.

    private class VolumeBridge {
        @JavascriptInterface
        public void setEnabled(boolean enabled) {
            volumeKeysEnabled = enabled;
        }
    }

    private class GpsBridge {
        @JavascriptInterface
        public boolean start() {
            if (!hasLocationPermission()) {
                runOnUiThread(MainActivity.this::requestNeededPermissions);
                return false;
            }
            if (!GpsService.running) {
                Intent intent = new Intent(MainActivity.this, GpsService.class);
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
                    else startService(intent);
                } catch (RuntimeException e) {
                    // Not allowed to start a foreground service from the background;
                    // the page retries once the app is in front again.
                    return false;
                }
            }
            return true;
        }

        @JavascriptInterface
        public void stop() {
            stopService(new Intent(MainActivity.this, GpsService.class));
        }

        @JavascriptInterface
        public String drain() {
            return GpsService.drain();
        }

        @JavascriptInterface
        public String status() {
            return GpsService.statusJson();
        }
    }
}
