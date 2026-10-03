package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

final class HttpGameInstaller {
    private static final String TAG = "AbodehAutoDownload";
    private static final int BUFFER = 1024 * 1024;

    interface Progress {
        void onProgress(long done, long total, String path, long bytesPerSecond);
    }

    private HttpGameInstaller() {}

    static File rootDir(Context context) {
        File root = context.getExternalFilesDir(null);
        return root != null ? new File(root, "GameData") : null;
    }

    static File zeroHourDir(Context context) {
        File root = rootDir(context);
        return root != null ? new File(root, "ZeroHour") : null;
    }

    static File generalsDir(Context context) {
        File root = rootDir(context);
        return root != null ? new File(root, "Generals") : null;
    }

    static boolean isInstalled(Context context) {
        File zh = zeroHourDir(context);
        File base = generalsDir(context);
        return zh != null && base != null
            && new File(zh, "INIZH.big").isFile()
            && new File(zh, "TexturesZH.big").isFile()
            && new File(zh, "W3DZH.big").isFile()
            && new File(base, "INI.big").isFile()
            && new File(base, "Textures.big").isFile()
            && new File(base, "W3D.big").isFile();
    }

    static void activateIfInstalled(Context context) {
        if (!isInstalled(context)) return;

        File zh = zeroHourDir(context);
        File base = generalsDir(context);
        SharedPreferences prefs = context.getSharedPreferences(
            SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(SetupActivity.PREF_GAME_PATH, zh.getAbsolutePath())
            .putString(SetupActivity.PREF_BASE_GENERALS_PATH, base.getAbsolutePath())
            .apply();

        writeText(new File(context.getFilesDir(), "gamedata_path.txt"), zh.getAbsolutePath());
        writeText(new File(context.getFilesDir(), "generals_base_path.txt"), base.getAbsolutePath());

        File runtimeRoot = context.getExternalFilesDir(null);
        if (runtimeRoot != null) {
            SetupActivity.copyBundledRuntimeIfMissing(runtimeRoot, zh.getAbsolutePath());
        }
    }

    static void install(Context context, String manifestUrl, String bearerToken, Progress progress)
            throws Exception {
        JSONObject manifest = fetchManifest(manifestUrl, bearerToken);
        JSONArray files = manifest.getJSONArray("files");

        long total = 0;
        for (int i = 0; i < files.length(); i++) {
            total += files.getJSONObject(i).getLong("size");
        }

        File root = rootDir(context);
        if (root == null) throw new IllegalStateException("App storage unavailable.");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("Could not create game-data folder.");
        }

        long done = completedBytes(root, files);
        long started = System.currentTimeMillis();
        long transferredSinceStart = 0;

        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.getJSONObject(i);
            String path = item.getString("path");
            long size = item.getLong("size");
            String sha256 = item.optString("sha256", "").toLowerCase(Locale.ROOT);
            String md5 = item.optString("md5", "").toLowerCase(Locale.ROOT);
            if (sha256.isEmpty() && md5.isEmpty()) {
                throw new IllegalStateException("Manifest has no checksum for " + path);
            }
            String url = item.getString("url");

            File dest = safeFile(root, path);
            if (dest.isFile() && dest.length() == size && checksumMatches(dest, sha256, md5)) {
                if (progress != null) progress.onProgress(done, total, path, currentRate(started, transferredSinceStart));
                continue;
            }

            if (dest.getParentFile() != null && !dest.getParentFile().isDirectory()
                    && !dest.getParentFile().mkdirs()) {
                throw new IllegalStateException("Could not create " + dest.getParent());
            }

            File part = new File(dest.getAbsolutePath() + ".part");
            long existing = part.isFile() ? part.length() : 0;
            if (existing > size) {
                part.delete();
                existing = 0;
            }

            final long doneBefore = done;
            final long transferredBefore = transferredSinceStart;
            final long existingBefore = existing;
            long copied = download(url, part, existing, size, (written) -> {
                if (progress != null) {
                    long nowDone = doneBefore + written;
                    long newlyTransferred = Math.max(0, written - existingBefore);
                    long rate = currentRate(started, transferredBefore + newlyTransferred);
                    progress.onProgress(nowDone, total, path, rate);
                }
            });

            transferredSinceStart += copied;
            if (part.length() != size) {
                throw new IllegalStateException("Incomplete file: " + path);
            }
            if (!checksumMatches(part, sha256, md5)) {
                part.delete();
                throw new IllegalStateException("Checksum mismatch: " + path);
            }
            if (dest.exists() && !dest.delete()) {
                throw new IllegalStateException("Could not replace " + path);
            }
            if (!part.renameTo(dest)) {
                throw new IllegalStateException("Could not finalize " + path);
            }
            done += size;
        }

        if (!isInstalled(context)) {
            throw new IllegalStateException("Required Generals / Zero Hour files are missing after download.");
        }
        activateIfInstalled(context);
        Log.i(TAG, "Auto download complete.");
    }

    private interface CopyProgress { void onBytes(long totalWritten); }

    private static long download(String url, File part, long existing, long expected,
                                 CopyProgress cb) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");

        int status = c.getResponseCode();
        boolean append = existing > 0 && status == HttpURLConnection.HTTP_PARTIAL;
        if (existing > 0 && status == HttpURLConnection.HTTP_OK) {
            existing = 0;
            append = false;
        } else if (status != HttpURLConnection.HTTP_OK
                && status != HttpURLConnection.HTTP_PARTIAL) {
            throw new IllegalStateException("HTTP " + status + " while downloading game data.");
        }

        long written = existing;
        try (InputStream in = new BufferedInputStream(c.getInputStream(), BUFFER);
             BufferedOutputStream out = new BufferedOutputStream(
                 new FileOutputStream(part, append), BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
                if (cb != null) cb.onBytes(written);
            }
        } finally {
            c.disconnect();
        }
        return Math.max(0, written - existing);
    }

    private static JSONObject fetchManifest(String manifestUrl, String bearerToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(manifestUrl).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }

        int status = c.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("Manifest HTTP " + status);
        }
        try (InputStream in = c.getInputStream()) {
            byte[] data = readAll(in);
            JSONObject json = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            if (!json.optBoolean("ok", false)) {
                throw new IllegalStateException(json.optString("message", "Manifest rejected."));
            }
            return json;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static long completedBytes(File root, JSONArray files) throws Exception {
        long done = 0;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.getJSONObject(i);
            File dest = safeFile(root, item.getString("path"));
            long size = item.getLong("size");
            String sha256 = item.optString("sha256", "").toLowerCase(Locale.ROOT);
            String md5 = item.optString("md5", "").toLowerCase(Locale.ROOT);
            if (dest.isFile() && dest.length() == size && checksumMatches(dest, sha256, md5)) {
                done += size;
            }
        }
        return done;
    }

    private static File safeFile(File root, String relative) throws Exception {
        File f = new File(root, relative);
        String rootPath = root.getCanonicalPath() + File.separator;
        String filePath = f.getCanonicalPath();
        if (!filePath.startsWith(rootPath)) {
            throw new SecurityException("Unsafe manifest path.");
        }
        return f;
    }

    private static boolean checksumMatches(File file, String sha256, String md5) throws Exception {
        if (sha256 != null && !sha256.isEmpty()) return sha256.equals(digest(file, "SHA-256"));
        return md5 != null && !md5.isEmpty() && md5.equals(digest(file, "MD5"));
    }

    private static String digest(File file, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder b = new StringBuilder(md.getDigestLength() * 2);
        for (byte v : md.digest()) b.append(String.format(Locale.ROOT, "%02x", v & 0xff));
        return b.toString();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder b = new StringBuilder(64);
        for (byte v : md.digest()) b.append(String.format(Locale.ROOT, "%02x", v & 0xff));
        return b.toString();
    }

    private static long currentRate(long startedMs, long bytes) {
        long elapsed = Math.max(1, System.currentTimeMillis() - startedMs);
        return (bytes * 1000L) / elapsed;
    }

    private static void writeText(File file, String value) {
        try (java.io.FileWriter w = new java.io.FileWriter(file, false)) {
            w.write(value);
            w.write("\n");
        } catch (Exception e) {
            Log.w(TAG, "Could not write " + file, e);
        }
    }
}
