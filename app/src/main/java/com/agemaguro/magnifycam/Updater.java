package com.agemaguro.magnifycam;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Checks the public GitHub release when the app opens. If a newer build is there, asks to update, downloads the
 * APK and hands it to Android's installer (Android always asks you to confirm the install).
 *
 * The release holds MagnifyCam.apk and version.txt (just the build number = the app's versionCode).
 */
final class Updater {
    private static final String BASE =
            "https://github.com/AgeMaguro/MagnifyCam/releases/download/magnifycam-latest/";
    private static final String FILE = "MagnifyCam-update.apk";

    private final Activity act;
    private long downloadId = -1;
    private BroadcastReceiver receiver;

    Updater(Activity activity) {
        act = activity;
    }

    /** Look for a newer version in the background; quietly do nothing if offline or no newer version. */
    void checkOnOpen() {
        new Thread(() -> {
            try {
                final int latest = fetchLatest();
                final int mine = installed();
                if (latest > mine) {
                    act.runOnUiThread(() -> {
                        if (!act.isFinishing() && !act.isDestroyed()) ask(latest, mine);
                    });
                }
            } catch (Exception ignored) {
                // offline, release not found, etc.: no update prompt
            }
        }, "update-check").start();
    }

    private int installed() throws Exception {
        return (int) act.getPackageManager().getPackageInfo(act.getPackageName(), 0).getLongVersionCode();
    }

    private int fetchLatest() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "version.txt").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line = br.readLine();
                if (line == null) throw new IOException("empty version file");
                return Integer.parseInt(line.trim());
            }
        } finally {
            c.disconnect();
        }
    }

    private void ask(int latest, int mine) {
        new AlertDialog.Builder(act)
                .setTitle("Update available")
                .setMessage("MagnifyCam 1.0." + latest + " is ready (you have 1.0." + mine + "). Update now?")
                .setPositiveButton("Update", (d, w) -> download())
                .setNegativeButton("Later", null)
                .show();
    }

    private void download() {
        try {
            if (!act.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(act, "Allow MagnifyCam to install updates, then open the app again.", Toast.LENGTH_LONG).show();
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
                return;
            }
            File f = new File(act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FILE);
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            final DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(BASE + "MagnifyCam.apk"));
            rq.setTitle("MagnifyCam update");
            rq.setMimeType("application/vnd.android.package-archive");
            rq.setDestinationInExternalFilesDir(act, Environment.DIRECTORY_DOWNLOADS, FILE);
            rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            downloadId = dm.enqueue(rq);
            if (receiver == null) {
                receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                        if (id != downloadId) return;
                        Uri u = dm.getUriForDownloadedFile(id);
                        if (u == null) {
                            Toast.makeText(act, "The update download failed.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(u, "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            act.startActivity(i);
                        } catch (Exception e) {
                            Toast.makeText(act, "Could not start the installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                };
                IntentFilter ff = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
                if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(receiver, ff, Context.RECEIVER_EXPORTED);
                else act.registerReceiver(receiver, ff);
            }
            Toast.makeText(act, "Downloading the update...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(act, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Call from the activity's onDestroy. */
    void release() {
        if (receiver != null) {
            try {
                act.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
            }
            receiver = null;
        }
    }
}
