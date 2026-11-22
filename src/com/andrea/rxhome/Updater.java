package com.andrea.rxhome;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.IntConsumer;

/**
 * Updates rxhome from its GitHub releases (release.sh publishes one per version: tag "v<versionCode>"
 * with the APK attached). Android checks the APK is signed with the same key before installing it.
 * The first time, Android asks to allow installs from rxhome.
 */
final class Updater {

    private static final String LATEST = "https://api.github.com/repos/RexSAS/rxhome/releases/latest";

    /** An install is under way (download or the system's confirm); MainActivity keeps its progress dialog up meanwhile. */
    static volatile boolean busy;

    static final class Release {
        final int code;
        final String apk;

        Release(int code, String apk) {
            this.code = code;
            this.apk = apk;
        }
    }

    private Updater() {}

    /** The latest release if it's newer than this build, else null. Network: off the main thread. */
    static Release newer(Context c) throws IOException {
        Release r = parse(read(open(LATEST)));
        int installed;
        try {
            installed = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return null;
        }
        return r != null && r.code > installed ? r : null;
    }

    /** "v42" with an .apk asset; null for anything else. */
    static Release parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String tag = o.optString("tag_name");
            if (!tag.matches("v\\d+")) return null;
            JSONArray assets = o.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                String url = assets.getJSONObject(i).optString("browser_download_url");
                if (url.endsWith(".apk")) return new Release(Integer.parseInt(tag.substring(1)), url);
            }
        } catch (Exception e) {
            // not a release we can use
        }
        return null;
    }

    /**
     * Downloads the APK into an install session (progress 0..100 as it goes) and commits it; Status hears how it
     * ends. Network: off the main thread.
     */
    static void install(Context c, Release r, IntConsumer progress) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            HttpURLConnection d = open(r.apk);
            long total = d.getContentLengthLong(), done = 0;
            try (InputStream in = d.getInputStream(); OutputStream out = s.openWrite("rxhome.apk", 0, total)) {
                byte[] buf = new byte[64 * 1024];
                int lastPct = -1;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    done += n;
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    if (pct != lastPct) progress.accept(lastPct = pct);
                }
                s.fsync(out);
            }
            if (total > 0 && done != total) throw new IOException("download cut short");
            PendingIntent status = PendingIntent.getBroadcast(c, id, new Intent(c, Status.class), PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(status.getIntentSender());
        } catch (IOException | RuntimeException e) {
            pi.abandonSession(id);
            throw e;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "rxhome"); // GitHub's API refuses requests without one
        if (url.startsWith("https://api.github.com/")) c.setRequestProperty("Accept", "application/vnd.github+json");
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        return c;
    }

    private static String read(HttpURLConnection c) throws IOException {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** How the install ended: shows the system's confirm, or notes the failure. Success kills us; Replaced takes over. */
    public static class Status extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) return;
            busy = false;
            Toast.makeText(c, status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Update cancelled"
                : "Update failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), Toast.LENGTH_LONG).show();
            MainActivity.updateEnded();
        }
    }

    /** The new version is in: open it. */
    public static class Replaced extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
