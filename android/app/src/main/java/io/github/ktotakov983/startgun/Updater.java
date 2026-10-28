package io.github.ktotakov983.startgun;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update from the repository's GitHub releases. The CI workflow publishes
 * each build as release "startgun-2.N" with StartGun.apk, and the app's
 * versionName is "2.N", so comparing N is enough. Android always asks the user
 * to confirm the install; nothing is installed silently.
 */
class Updater {
    private static final String LATEST_RELEASE = "https://api.github.com/repos/Ktotakov983/prostart/releases/latest";
    private static final String APK_NAME = "StartGun.apk";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final long POLL_MS = 1000;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy = false;

    Updater(Activity activity) {
        this.activity = activity;
    }

    /** Checks in the background; when {@code manual}, also reports "up to date" and errors. */
    void check(boolean manual) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                JSONObject release = new JSONObject(httpGet(LATEST_RELEASE));
                int latest = buildNumber(release.getString("tag_name").replace("startgun-", ""));
                int mine = buildNumber(currentVersion());
                String apkUrl = null;
                JSONArray assets = release.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (APK_NAME.equals(a.getString("name"))) apkUrl = a.getString("browser_download_url");
                }
                final String url = apkUrl;
                activity.runOnUiThread(() -> {
                    busy = false;
                    if (latest > mine && url != null) offer(latest, mine, url);
                    else if (manual) toast("Установлена последняя версия StartGun 2." + mine);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    busy = false;
                    if (manual) toast("Не удалось проверить обновление: нет интернета?");
                });
            }
        }).start();
    }

    String currentVersion() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** "2.17" → 17; anything else → 0. */
    private static int buildNumber(String version) {
        int dot = version.lastIndexOf('.');
        try {
            return Integer.parseInt(version.substring(dot + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void offer(int latest, int mine, String url) {
        new AlertDialog.Builder(activity)
                .setTitle("Обновление StartGun")
                .setMessage("Доступна версия 2." + latest + " (у вас 2." + mine + "). Скачать и установить?")
                .setPositiveButton("Обновить", (d, w) -> download(latest, url))
                .setNegativeButton("Позже", null)
                .show();
    }

    private void download(int latest, String url) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            toast("Разрешите StartGun 2 устанавливать приложения, затем нажмите «Проверить обновление» в меню");
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        DownloadManager dm = (DownloadManager) activity.getSystemService(Activity.DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle("StartGun 2." + latest)
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "StartGun-2." + latest + ".apk");
        long id = dm.enqueue(request);
        toast("Скачиваю StartGun 2." + latest + "…");
        handler.postDelayed(() -> waitForDownload(dm, id), POLL_MS);
    }

    private void waitForDownload(DownloadManager dm, long id) {
        int status;
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c == null || !c.moveToFirst()) {
                toast("Загрузка обновления прервана");
                return;
            }
            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        }
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            Uri apk = dm.getUriForDownloadedFile(id);
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        } else if (status == DownloadManager.STATUS_FAILED) {
            toast("Не удалось скачать обновление");
        } else {
            handler.postDelayed(() -> waitForDownload(dm, id), POLL_MS);
        }
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
