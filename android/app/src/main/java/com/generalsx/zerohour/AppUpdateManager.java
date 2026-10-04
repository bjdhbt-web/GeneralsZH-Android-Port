package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AppUpdateManager {
    private static final String LATEST_RELEASE_URL =
        "https://api.github.com/repos/bjdhbt-web/GeneralsZH-Android-Port/releases/latest";
    private static final Pattern APK_NAME =
        Pattern.compile("^Abodeh-Play-.+-vc(\\d+)\\.apk$", Pattern.CASE_INSENSITIVE);
    private static final int BUFFER = 1024 * 1024;

    static final class Release {
        final long versionCode;
        final String versionName;
        final String assetName;
        final String downloadUrl;
        final long size;
        final String sha256;

        Release(long versionCode, String versionName, String assetName,
                String downloadUrl, long size, String sha256) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    private AppUpdateManager() {}

    static Release checkLatest(Context context) throws Exception {
        HttpURLConnection c = open(LATEST_RELEASE_URL);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

        int status = c.getResponseCode();
        if (status != 200) {
            c.disconnect();
            throw new IOException("GitHub update check HTTP " + status);
        }
        String text;
        try (InputStream in = c.getInputStream()) {
            text = new String(readAll(in), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }

        JSONObject json = new JSONObject(text);
        String tag = json.optString("tag_name", "");
        String versionName = tag.startsWith("v") ? tag.substring(1) : tag;
        JSONArray assets = json.optJSONArray("assets");
        if (assets == null) return null;

        Release best = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            Matcher m = APK_NAME.matcher(name);
            if (!m.matches()) continue;

            long vc;
            try {
                vc = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }

            String url = a.optString("browser_download_url", "");
            long size = a.optLong("size", 0);
            String digest = a.optString("digest", "");
            String sha256 = digest.startsWith("sha256:") ? digest.substring(7) : "";
            if (!url.startsWith("https://github.com/") || size <= 0) continue;

            Release candidate = new Release(vc, versionName, name, url, size, sha256);
            if (best == null || candidate.versionCode > best.versionCode) best = candidate;
        }

        return best != null && best.versionCode > installedVersionCode(context) ? best : null;
    }

    static long installedVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static File downloadedFile(Context context, Release release) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(context.getFilesDir(), "downloads");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, "Abodeh-Play-update-vc" + release.versionCode + ".apk");
    }

    static File download(Context context, Release release, Progress progress) throws Exception {
        File target = downloadedFile(context, release);
        File part = new File(target.getAbsolutePath() + ".part");

        if (target.isFile() && target.length() == release.size && verifyShaIfPresent(target, release.sha256)) {
            return target;
        }
        if (target.exists()) target.delete();

        long existing = part.isFile() ? part.length() : 0;
        if (existing > release.size) {
            part.delete();
            existing = 0;
        }

        HttpURLConnection c = open(release.downloadUrl);
        c.setRequestProperty("Accept", "application/octet-stream");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");

        int status = c.getResponseCode();
        if (existing > 0 && status != 206) {
            c.disconnect();
            part.delete();
            existing = 0;
            c = open(release.downloadUrl);
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setRequestProperty("Accept-Encoding", "identity");
            status = c.getResponseCode();
        }
        if (status != 200 && status != 206) {
            c.disconnect();
            throw new IOException("APK download HTTP " + status);
        }

        long done = existing;
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(part, existing > 0)) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, release.size);
            }
            out.getFD().sync();
        } finally {
            c.disconnect();
        }

        if (part.length() != release.size) {
            throw new IOException("APK size mismatch (" + part.length() + " / " + release.size + ")");
        }
        if (!verifyShaIfPresent(part, release.sha256)) {
            part.delete();
            throw new IOException("APK SHA-256 mismatch");
        }

        if (!part.renameTo(target)) {
            throw new IOException("Could not finalize APK update");
        }
        return target;
    }

    static boolean canInstallPackages(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            || context.getPackageManager().canRequestPackageInstalls();
    }

    static void openUnknownSourcesSettings(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(i);
        }
    }

    static void launchInstaller(Activity activity, File apk) {
        Uri uri = FileProvider.getUriForFile(
            activity,
            activity.getPackageName() + ".fileprovider",
            apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "AbodehPlay-Android-Updater");
        return c;
    }

    private static boolean verifyShaIfPresent(File file, String expected) throws Exception {
        if (expected == null || expected.isEmpty()) return true;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) > 0) md.update(buffer, 0, n);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte b : md.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return expected.equalsIgnoreCase(actual.toString());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
