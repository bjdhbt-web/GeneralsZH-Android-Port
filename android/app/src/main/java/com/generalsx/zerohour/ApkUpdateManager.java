package com.generalsx.zerohour;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ApkUpdateManager {
    private static final String LATEST_RELEASE_API =
        "https://api.github.com/repos/bjdhbt-web/GeneralsZH-Android-Port/releases/latest";

    // Release assets created by build-android.yml use:
    // Abodeh-Play-<versionName>-vc<versionCode>.apk
    private static final Pattern RELEASE_APK =
        Pattern.compile("^Abodeh-Play-(.+)-vc(\\d+)\\.apk$");

    private static final String PREFS = "abodeh_play_apk_update";
    private static final String KEY_VERSION_CODE = "version_code";
    private static final String KEY_VERSION_NAME = "version_name";
    private static final String KEY_URL = "url";
    private static final String KEY_FILE = "file";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_PENDING_INSTALL = "pending_install";

    static final String EXTRA_INSTALL_UPDATE = "abodeh_play_install_update";

    static final class Result {
        boolean ok;
        boolean offline;
        String error;
        boolean updateAvailable;
        long versionCode;
        String versionName;
        String downloadUrl;
        String fileName;
    }

    private ApkUpdateManager() {}

    static long currentVersionCode(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String currentVersionName(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "";
        } catch (Exception e) {
            return "";
        }
    }

    static Result check(Context ctx) {
        Result out = new Result();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "AbodehPlay-Android/" + currentVersionName(ctx));
            c.setInstanceFollowRedirects(true);

            int status = c.getResponseCode();
            if (status == 404) {
                out.ok = true; // no public release yet
                return out;
            }
            if (status < 200 || status >= 300) {
                out.error = "HTTP " + status;
                return out;
            }

            JSONObject release = new JSONObject(readAll(c.getInputStream()));
            if (release.optBoolean("draft", false)) {
                out.ok = true;
                return out;
            }

            JSONArray assets = release.optJSONArray("assets");
            long bestCode = 0;
            String bestName = null;
            String bestUrl = null;
            String bestFile = null;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    Matcher m = RELEASE_APK.matcher(name);
                    if (!m.matches()) continue;

                    long code;
                    try {
                        code = Long.parseLong(m.group(2));
                    } catch (NumberFormatException ignored) {
                        continue;
                    }

                    String url = asset.optString("browser_download_url", "");
                    if (url.isEmpty() || !url.startsWith(
                            "https://github.com/bjdhbt-web/GeneralsZH-Android-Port/releases/download/")) {
                        continue;
                    }
                    if (code > bestCode) {
                        bestCode = code;
                        bestName = m.group(1);
                        bestUrl = url;
                        bestFile = name;
                    }
                }
            }

            out.ok = true;
            out.versionCode = bestCode;
            out.versionName = bestName;
            out.downloadUrl = bestUrl;
            out.fileName = bestFile;
            out.updateAvailable = bestCode > currentVersionCode(ctx) && bestUrl != null;
            if (out.updateAvailable) cacheOffer(ctx, out);
            return out;
        } catch (java.net.UnknownHostException | java.net.ConnectException
                 | java.net.SocketTimeoutException | java.net.NoRouteToHostException e) {
            out.offline = true;
            out.error = "offline";
            return out;
        } catch (Exception e) {
            out.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return out;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static Result cachedOffer(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Result r = new Result();
        r.ok = true;
        r.versionCode = p.getLong(KEY_VERSION_CODE, 0);
        r.versionName = p.getString(KEY_VERSION_NAME, "");
        r.downloadUrl = p.getString(KEY_URL, "");
        r.fileName = p.getString(KEY_FILE, "");
        r.updateAvailable = r.versionCode > currentVersionCode(ctx)
            && r.downloadUrl != null && !r.downloadUrl.isEmpty();
        return r;
    }

    static long startDownload(Context ctx, Result offer) {
        if (offer == null || !offer.updateAvailable || offer.downloadUrl == null
                || offer.fileName == null || offer.fileName.isEmpty()) {
            return -1;
        }

        File old = updateFile(ctx, offer.fileName);
        if (old.isFile() && old.length() > 0) {
            prefs(ctx).edit().putBoolean(KEY_PENDING_INSTALL, true).apply();
            return 0;
        }

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(offer.downloadUrl))
            .setTitle(ctx.getString(R.string.apk_update_download_title))
            .setDescription(offer.versionName != null ? offer.versionName : "")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(
                ctx, Environment.DIRECTORY_DOWNLOADS, offer.fileName);

        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return -1;
        long id = dm.enqueue(req);
        prefs(ctx).edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putBoolean(KEY_PENDING_INSTALL, false)
            .apply();
        return id;
    }

    static boolean isDownloadComplete(Context ctx) {
        long id = prefs(ctx).getLong(KEY_DOWNLOAD_ID, -1);
        if (id < 0) {
            Result cached = cachedOffer(ctx);
            return cached.fileName != null && updateFile(ctx, cached.fileName).isFile();
        }
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return false;
        android.database.Cursor cursor = dm.query(
            new DownloadManager.Query().setFilterById(id));
        if (cursor == null) return false;
        try {
            if (!cursor.moveToFirst()) return false;
            int col = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            return col >= 0 && cursor.getInt(col) == DownloadManager.STATUS_SUCCESSFUL;
        } finally {
            cursor.close();
        }
    }

    static boolean installDownloaded(Activity activity) {
        Result cached = cachedOffer(activity);
        if (cached.fileName == null || cached.fileName.isEmpty()) return false;
        File apk = updateFile(activity, cached.fileName);
        if (!apk.isFile()) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            prefs(activity).edit().putBoolean(KEY_PENDING_INSTALL, true).apply();
            Intent settings = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            return true;
        }

        prefs(activity).edit().putBoolean(KEY_PENDING_INSTALL, false).apply();
        Uri uri = FileProvider.getUriForFile(
            activity,
            activity.getPackageName() + ".fileprovider",
            apk);

        Intent install = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(install);
        return true;
    }

    static void handleInstallIntent(Activity activity, Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_INSTALL_UPDATE, false)) {
            prefs(activity).edit().putBoolean(KEY_PENDING_INSTALL, true).apply();
            installDownloaded(activity);
            intent.removeExtra(EXTRA_INSTALL_UPDATE);
        }
    }

    static void resumePendingInstall(Activity activity) {
        if (prefs(activity).getBoolean(KEY_PENDING_INSTALL, false)
                && isDownloadComplete(activity)) {
            installDownloaded(activity);
        }
    }

    static long trackedDownloadId(Context ctx) {
        return prefs(ctx).getLong(KEY_DOWNLOAD_ID, -1);
    }

    static void markDownloadComplete(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_PENDING_INSTALL, true).apply();
    }

    private static void cacheOffer(Context ctx, Result r) {
        prefs(ctx).edit()
            .putLong(KEY_VERSION_CODE, r.versionCode)
            .putString(KEY_VERSION_NAME, r.versionName != null ? r.versionName : "")
            .putString(KEY_URL, r.downloadUrl != null ? r.downloadUrl : "")
            .putString(KEY_FILE, r.fileName != null ? r.fileName : "")
            .apply();
    }

    private static File updateFile(Context ctx, String fileName) {
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = ctx.getFilesDir();
        return new File(dir, fileName);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) b.append(buf, 0, n);
        }
        return b.toString();
    }
}
