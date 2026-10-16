/*
** Command & Conquer Generals Zero Hour(tm)
** Copyright 2025 Electronic Arts Inc.
**
** GPLv3-or-later; see repository license.
*/
package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Abodeh Play Full Edition support.
 *
 * Proprietary game data is never committed to the public repository. A private/local
 * full build may stage the user's own Zero Hour installation under APK assets/fullgame/
 * plus assets/fullgame-manifest.txt. On first launch this class extracts the data to
 * the app-specific external GameData directory, verifies each file against the manifest,
 * and activates that folder for the existing engine path logic.
 */
final class BundledGameData {
    private static final String TAG = "AbodehFullBundle";
    private static final String ASSET_ROOT = "fullgame";
    private static final String MANIFEST_ASSET = "fullgame-manifest.txt";
    private static final String INSTALL_MARKER = ".abodeh_full_bundle";
    private static final String REQUIRED_A = "INIZH.big";
    private static final String REQUIRED_B = "INI.big";
    private static final int BUFFER_SIZE = 1024 * 1024;

    interface Progress {
        void onProgress(long doneBytes, long totalBytes, String relativePath);
    }

    private static final class Entry {
        final long size;
        final String sha256;
        final String path;

        Entry(long size, String sha256, String path) {
            this.size = size;
            this.sha256 = sha256;
            this.path = path;
        }
    }

    private static final class ManifestData {
        final String version;
        final List<Entry> entries;
        final long totalBytes;

        ManifestData(String version, List<Entry> entries, long totalBytes) {
            this.version = version;
            this.entries = entries;
            this.totalBytes = totalBytes;
        }
    }

    private BundledGameData() {}

    static boolean isBundled(Context context) {
        try {
            ManifestData manifest = readManifest(context);
            return manifest != null && !manifest.entries.isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    static File installDir(Context context) {
        File root = context.getExternalFilesDir(null);
        return root != null ? new File(root, "GameData") : null;
    }

    static boolean isInstalled(Context context) {
        File dir = installDir(context);
        if (dir == null || !new File(dir, REQUIRED_A).isFile() || !new File(dir, REQUIRED_B).isFile()) {
            return false;
        }
        File marker = new File(dir, INSTALL_MARKER);
        if (!marker.isFile()) {
            return false;
        }
        try {
            ManifestData manifest = readManifest(context);
            if (manifest == null) return false;
            try (BufferedReader r = new BufferedReader(new FileReader(marker))) {
                return manifest.version.equals(r.readLine());
            }
        } catch (IOException e) {
            return false;
        }
    }

    static void activateIfInstalled(Context context) {
        if (!isInstalled(context)) return;
        File dir = installDir(context);
        if (dir == null) return;

        SharedPreferences prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(SetupActivity.PREF_GAME_PATH, dir.getAbsolutePath()).apply();

        File marker = new File(context.getFilesDir(), "gamedata_path.txt");
        try (java.io.FileWriter w = new java.io.FileWriter(marker, false)) {
            w.write(dir.getAbsolutePath());
            w.write("\n");
        } catch (IOException e) {
            Log.w(TAG, "Could not write game path marker", e);
        }
    }

    static void install(Context context, Progress progress) throws IOException {
        ManifestData manifest = readManifest(context);
        if (manifest == null || manifest.entries.isEmpty()) {
            throw new IOException("Full bundle manifest is missing or empty.");
        }

        File dir = installDir(context);
        if (dir == null) {
            throw new IOException("External app storage is unavailable.");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }

        AssetManager assets = context.getAssets();
        long done = 0;

        for (Entry entry : manifest.entries) {
            validateRelativePath(entry.path);
            File dest = new File(dir, entry.path);
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create " + parent);
            }

            if (dest.isFile() && dest.length() == entry.size) {
                done += entry.size;
                if (progress != null) progress.onProgress(done, manifest.totalBytes, entry.path);
                continue;
            }

            File part = new File(dest.getAbsolutePath() + ".part");
            if (part.exists() && !part.delete()) {
                throw new IOException("Could not replace partial file " + part);
            }

            MessageDigest digest = sha256();
            long copied = 0;
            try (InputStream in = assets.open(ASSET_ROOT + "/" + entry.path, AssetManager.ACCESS_STREAMING);
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    copied += n;
                    if (progress != null) {
                        progress.onProgress(done + copied, manifest.totalBytes, entry.path);
                    }
                }
            } catch (IOException e) {
                part.delete();
                throw e;
            }

            if (copied != entry.size) {
                part.delete();
                throw new IOException("Size mismatch for " + entry.path + ": " + copied + " != " + entry.size);
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(entry.sha256)) {
                part.delete();
                throw new IOException("SHA-256 mismatch for " + entry.path);
            }

            if (dest.exists() && !dest.delete()) {
                part.delete();
                throw new IOException("Could not replace " + dest);
            }
            if (!part.renameTo(dest)) {
                part.delete();
                throw new IOException("Could not finalize " + dest);
            }
            done += entry.size;
        }

        if (!new File(dir, REQUIRED_A).isFile() || !new File(dir, REQUIRED_B).isFile()) {
            throw new IOException("Bundle does not contain required Zero Hour archives.");
        }

        try (java.io.FileWriter w = new java.io.FileWriter(new File(dir, INSTALL_MARKER), false)) {
            w.write(manifest.version);
            w.write("\n");
        }

        activateIfInstalled(context);
        Log.i(TAG, "Full bundle installed at " + dir);
    }

    private static ManifestData readManifest(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                assets.open(MANIFEST_ASSET, AssetManager.ACCESS_BUFFER)))) {
            String version = r.readLine();
            if (version == null || !version.startsWith("ABODEH_PLAY_FULL_BUNDLE_")) {
                throw new IOException("Unsupported full bundle manifest header.");
            }
            List<Entry> entries = new ArrayList<>();
            long total = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) {
                    throw new IOException("Malformed bundle manifest line.");
                }
                long size = Long.parseLong(parts[0]);
                String sha = parts[1];
                String path = parts[2].replace('\\', '/');
                validateRelativePath(path);
                entries.add(new Entry(size, sha, path));
                total += size;
            }
            return new ManifestData(version, entries, total);
        } catch (java.io.FileNotFoundException e) {
            return null;
        } catch (NumberFormatException e) {
            throw new IOException("Malformed bundle manifest size.", e);
        }
    }

    private static void validateRelativePath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("../")
                || path.contains("/..") || path.equals("..")) {
            throw new IOException("Unsafe bundle path: " + path);
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable.", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            b.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return b.toString();
    }
}
