package com.generalsx.zerohour;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

final class ServerGameInstaller {
    private static final String TAG = "AbodehServerInstall";
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final int MAX_REFRESH_RETRIES = 2;

    interface Progress {
        void onProgress(long doneBytes, long totalBytes, int fileIndex, int fileCount, String path);
    }

    private ServerGameInstaller() {}

    static boolean isInstalled(Context context) {
        return DriveFolderInstaller.isInstalled(context);
    }

    static void activateIfInstalled(Context context) {
        DriveFolderInstaller.activateIfInstalled(context);
    }

    static void install(Context context, Progress progress) throws Exception {
        SubscriptionApi.Result response = SubscriptionApi.gameManifest(context);
        if (!response.ok || response.manifest == null || response.manifest.files.isEmpty()) {
            throw new IOException("Game manifest is unavailable: " + safeMessage(response));
        }

        File root = DriveFolderInstaller.rootDir(context);
        if (root == null) throw new IOException("App storage is unavailable.");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Could not create game-data folder.");
        }

        SubscriptionApi.Manifest manifest = response.manifest;
        long total = 0;
        for (SubscriptionApi.ManifestEntry entry : manifest.files) {
            validateEntry(entry);
            total = Math.addExact(total, entry.size);
        }

        final long totalBytes = total;
        long done = 0;
        final int count = manifest.files.size();
        for (int i = 0; i < count; i++) {
            SubscriptionApi.ManifestEntry entry = manifest.files.get(i);
            File dest = destination(root, entry.path);
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create " + parent);
            }

            if (dest.isFile() && dest.length() == entry.size
                    && entry.md5.equalsIgnoreCase(md5(dest))) {
                done += entry.size;
                emit(progress, done, totalBytes, i + 1, count, entry.path);
                continue;
            }

            File part = new File(dest.getAbsolutePath() + ".part");
            if (part.isFile() && part.length() > entry.size) {
                if (!part.delete()) throw new IOException("Could not reset partial file " + part);
            }
            if (!part.exists()) {
                File partParent = part.getParentFile();
                if (partParent != null && !partParent.isDirectory() && !partParent.mkdirs()) {
                    throw new IOException("Could not create " + partParent);
                }
            }

            long already = part.isFile() ? part.length() : 0;
            if (already > 0) {
                emit(progress, done + already, totalBytes, i + 1, count, entry.path);
            }

            SubscriptionApi.ManifestEntry active = entry;
            int refreshes = 0;
            while (already < entry.size) {
                int code;
                try {
                    final long progressBase = done + already;
                    final int progressIndex = i + 1;
                    code = downloadRange(active.url, part, already, entry.size, (copied) ->
                        emit(progress, progressBase + copied, totalBytes, progressIndex, count, entry.path));
                } catch (IOException e) {
                    throw new IOException("Download failed for " + entry.path + ": " + e.getMessage(), e);
                }

                if (code == 401 && refreshes < MAX_REFRESH_RETRIES) {
                    active = refreshEntry(context, entry.path);
                    refreshes++;
                    already = part.isFile() ? part.length() : 0;
                    continue;
                }
                if (code != 200 && code != 206) {
                    throw new IOException("Server returned HTTP " + code + " for " + entry.path);
                }
                already = part.length();
                if (already > entry.size) {
                    if (!part.delete()) throw new IOException("Downloaded file grew past manifest size.");
                    already = 0;
                    continue;
                }
                if (already < entry.size) {
                    throw new IOException("Download ended early for " + entry.path
                        + " (" + already + " / " + entry.size + ").");
                }
            }

            if (part.length() != entry.size) {
                throw new IOException("Size mismatch for " + entry.path);
            }
            String actualMd5 = md5(part);
            if (!entry.md5.equalsIgnoreCase(actualMd5)) {
                part.delete();
                throw new IOException("MD5 mismatch for " + entry.path + ". Retry the install.");
            }

            if (dest.exists() && !dest.delete()) {
                throw new IOException("Could not replace " + dest);
            }
            if (!part.renameTo(dest)) {
                throw new IOException("Could not finalize " + dest);
            }

            done += entry.size;
            emit(progress, done, totalBytes, i + 1, count, entry.path);
        }

        if (!DriveFolderInstaller.isInstalled(context)) {
            throw new IOException("Required Generals / Zero Hour files are still missing.");
        }

        DriveFolderInstaller.activateIfInstalled(context);
        SubscriptionManager.saveManifestSummary(context, manifest.version, manifest.files.size());
        Log.i(TAG, "Server game install complete: " + root);
    }

    private interface ChunkProgress {
        void onBytes(long bytes);
    }

    private static int downloadRange(String url, File part, long start, long size,
                                     ChunkProgress progress) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/octet-stream");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (start > 0) {
            c.setRequestProperty("Range", "bytes=" + start + "-" + (size - 1));
        }

        int code = c.getResponseCode();
        if (code == 401) {
            drainAndClose(c);
            return code;
        }
        if (start > 0 && code != 206) {
            String message = errorBody(c);
            c.disconnect();
            throw new IOException("Resume was rejected (HTTP " + code + ") " + message);
        }
        if (start == 0 && code != 200) {
            String message = errorBody(c);
            c.disconnect();
            return code;
        }

        long copied = 0;
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(part, start > 0)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                copied += n;
                if (progress != null) progress.onBytes(copied);
            }
            out.getFD().sync();
        } finally {
            c.disconnect();
        }
        return code;
    }

    private static SubscriptionApi.ManifestEntry refreshEntry(Context context, String path)
            throws Exception {
        SubscriptionApi.Result refreshed = SubscriptionApi.gameManifest(context);
        if (!refreshed.ok || refreshed.manifest == null) {
            throw new IOException("Could not refresh expired download ticket: " + safeMessage(refreshed));
        }
        SubscriptionApi.ManifestEntry entry = refreshed.manifest.find(path);
        if (entry == null) {
            throw new IOException("File disappeared from the active manifest: " + path);
        }
        return entry;
    }

    private static File destination(File root, String path) throws IOException {
        validatePath(path);
        File dest = new File(root, path);
        String rootPath = root.getCanonicalPath() + File.separator;
        String destPath = dest.getCanonicalPath();
        if (!destPath.startsWith(rootPath)) {
            throw new IOException("Unsafe manifest path: " + path);
        }
        return dest;
    }

    private static void validateEntry(SubscriptionApi.ManifestEntry entry) throws IOException {
        validatePath(entry.path);
        if (entry.size < 0 || entry.size > 5L * 1024L * 1024L * 1024L) {
            throw new IOException("Invalid manifest size: " + entry.path);
        }
        if (entry.md5 == null || !entry.md5.matches("(?i)^[a-f0-9]{32}$")) {
            throw new IOException("Invalid manifest MD5: " + entry.path);
        }
        if (entry.url == null || !entry.url.startsWith(SubscriptionManager.API_BASE + "/download?")) {
            throw new IOException("Invalid download URL for " + entry.path);
        }
    }

    private static void validatePath(String path) throws IOException {
        if (path == null || !(path.startsWith("Generals/") || path.startsWith("ZeroHour/"))
                || path.startsWith("/") || path.contains("\\")
                || path.contains("/../") || path.endsWith("/..")
                || path.contains("/./") || path.endsWith("/.")
                || path.indexOf('\0') >= 0) {
            throw new IOException("Unsafe manifest path: " + path);
        }
    }

    private static String md5(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
            }
            StringBuilder out = new StringBuilder(32);
            for (byte b : digest.digest()) {
                out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("MD5 unavailable.", e);
        }
    }

    private static void emit(Progress progress, long done, long total,
                             int fileIndex, int fileCount, String path) {
        if (progress != null) progress.onProgress(done, total, fileIndex, fileCount, path);
    }

    private static String safeMessage(SubscriptionApi.Result r) {
        if (r == null) return "unknown error";
        if (r.message != null && !r.message.isEmpty()) return r.code + ": " + r.message;
        return r.code != null ? r.code : "unknown error";
    }

    private static void drainAndClose(HttpURLConnection c) {
        try {
            InputStream in = c.getErrorStream();
            if (in != null) in.close();
        } catch (IOException ignored) {
        } finally {
            c.disconnect();
        }
    }

    private static String errorBody(HttpURLConnection c) {
        try {
            InputStream in = c.getErrorStream();
            if (in == null) return "";
            byte[] b = new byte[1024];
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8) : "";
        } catch (IOException ignored) {
            return "";
        }
    }
}
