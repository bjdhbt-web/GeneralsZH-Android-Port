/*
** Command & Conquer Generals Zero Hour(tm)
** Copyright 2025 Electronic Arts Inc.
**
** GPLv3-or-later; see repository license.
*/
package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Imports the user's own Generals + Zero Hour installation directly from an
 * Android Storage Access Framework tree. Google Drive exposes folders through
 * this API, so there is no Drive API key, OAuth client secret, file-size limit,
 * or public download URL in the APK.
 *
 * Completed files are retained. If the transfer is interrupted, launching the
 * importer again skips every already-complete file and continues from the
 * first incomplete one.
 */
final class DriveFolderInstaller {
    private static final String TAG = "AbodehDriveImport";
    private static final int BUFFER_SIZE = 1024 * 1024;

    private static final String ZH_FOLDER = "Command and Conquer Generals Zero Hour";
    private static final String BASE_FOLDER = "Command and Conquer Generals";

    interface Progress {
        void onProgress(long doneBytes, long totalBytes, String relativePath);
    }

    private DriveFolderInstaller() {}

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
        if (zh == null || base == null) return;

        SharedPreferences prefs = context.getSharedPreferences(
            SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(SetupActivity.PREF_GAME_PATH, zh.getAbsolutePath())
            .putString(SetupActivity.PREF_BASE_GENERALS_PATH, base.getAbsolutePath())
            .apply();

        writeMarker(new File(context.getFilesDir(), "gamedata_path.txt"), zh.getAbsolutePath());
        writeMarker(new File(context.getFilesDir(), "generals_base_path.txt"), base.getAbsolutePath());

        File runtimeRoot = context.getExternalFilesDir(null);
        if (runtimeRoot != null) {
            SetupActivity.copyBundledRuntimeIfMissing(runtimeRoot, zh.getAbsolutePath());
        }
    }

    static void install(Context context, Uri treeUri, Progress progress) throws IOException {
        DocumentFile picked = DocumentFile.fromTreeUri(context, treeUri);
        if (picked == null || !picked.isDirectory()) {
            throw new IOException("The selected Drive location is not a readable folder.");
        }

        // Do not depend on the visible Drive folder names. Drive providers,
        // shortcuts and localized clients can expose the same tree with a
        // different display name. Detect each game by its archive signature
        // and search a few levels below the selected folder.
        GameSources sources = discoverGameSources(picked, 4);
        DocumentFile zhSource = sources.zeroHour;
        DocumentFile baseSource = sources.generals;

        if (zhSource == null || baseSource == null) {
            String selected = safeName(picked);
            String found = zhSource != null
                ? "Zero Hour only"
                : baseSource != null ? "Generals only" : "neither game";
            throw new IOException(
                "Could not find both game folders under '" + selected
                + "' (" + found + "). Select the parent folder that contains both games.");
        }

        File root = rootDir(context);
        File zhDest = zeroHourDir(context);
        File baseDest = generalsDir(context);
        if (root == null || zhDest == null || baseDest == null) {
            throw new IOException("App storage is unavailable.");
        }
        if ((!zhDest.isDirectory() && !zhDest.mkdirs())
                || (!baseDest.isDirectory() && !baseDest.mkdirs())) {
            throw new IOException("Could not create the local game-data folders.");
        }

        long total = measureTree(zhSource, true) + measureTree(baseSource, true);
        long done = 0;

        done = copyTree(context, zhSource, zhDest, "ZeroHour", done, total, progress);
        copyTree(context, baseSource, baseDest, "Generals", done, total, progress);

        if (!isInstalled(context)) {
            throw new IOException("Drive copy completed, but required Generals / Zero Hour archives are missing.");
        }

        activateIfInstalled(context);
        Log.i(TAG, "Drive game import complete: " + root);
    }

    private static long measureTree(DocumentFile dir, boolean root) {
        long total = 0;
        for (DocumentFile child : dir.listFiles()) {
            String name = safeName(child);
            if (child.isDirectory()) {
                if (shouldSkipDirectory(name)) continue;
                total += measureTree(child, false);
            } else if (child.isFile() && shouldCopyFile(name)) {
                long length = child.length();
                if (length > 0) total += length;
            }
        }
        return total;
    }

    private static long copyTree(Context context, DocumentFile sourceDir, File destDir,
                                 String relativeDir, long done, long total, Progress progress)
            throws IOException {
        for (DocumentFile child : sourceDir.listFiles()) {
            String name = safeName(child);
            if (name.isEmpty()) continue;

            if (child.isDirectory()) {
                if (shouldSkipDirectory(name)) continue;
                File next = new File(destDir, name);
                if (!next.isDirectory() && !next.mkdirs()) {
                    throw new IOException("Could not create " + next);
                }
                done = copyTree(context, child, next, relativeDir + "/" + name,
                    done, total, progress);
                continue;
            }

            if (!child.isFile() || !shouldCopyFile(name)) continue;

            long expected = child.length();
            File dest = new File(destDir, name);
            String rel = relativeDir + "/" + name;

            if (dest.isFile() && expected > 0 && dest.length() == expected) {
                done += expected;
                if (progress != null) progress.onProgress(done, total, rel);
                continue;
            }

            File part = new File(dest.getAbsolutePath() + ".part");
            if (part.exists() && !part.delete()) {
                throw new IOException("Could not replace partial file " + part);
            }

            long copied = 0;
            try (InputStream in = context.getContentResolver().openInputStream(child.getUri());
                 OutputStream out = new FileOutputStream(part)) {
                if (in == null) {
                    throw new IOException("Drive returned no stream for " + rel);
                }
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    copied += n;
                    if (progress != null) {
                        progress.onProgress(done + copied, total, rel);
                    }
                }
            } catch (IOException e) {
                part.delete();
                throw e;
            }

            if (expected > 0 && copied != expected) {
                part.delete();
                throw new IOException("Incomplete Drive file " + rel + ": " + copied + " / " + expected);
            }

            if (dest.exists() && !dest.delete()) {
                part.delete();
                throw new IOException("Could not replace " + dest);
            }
            if (!part.renameTo(dest)) {
                part.delete();
                throw new IOException("Could not finalize " + dest);
            }

            done += copied;
        }
        return done;
    }

    private static final class GameSources {
        DocumentFile zeroHour;
        DocumentFile generals;
    }

    private static GameSources discoverGameSources(DocumentFile root, int maxDepth) {
        GameSources out = new GameSources();
        discoverInto(root, 0, maxDepth, out);
        return out;
    }

    private static void discoverInto(DocumentFile dir, int depth, int maxDepth, GameSources out) {
        if (dir == null || !dir.isDirectory() || (out.zeroHour != null && out.generals != null)) {
            return;
        }

        if (out.zeroHour == null && isZeroHourFolder(dir)) {
            out.zeroHour = dir;
        }
        if (out.generals == null && isBaseGeneralsFolder(dir)) {
            out.generals = dir;
        }
        if ((out.zeroHour != null && out.generals != null) || depth >= maxDepth) {
            return;
        }

        for (DocumentFile child : dir.listFiles()) {
            if (!child.isDirectory()) continue;
            String name = safeName(child);
            if (shouldSkipDirectory(name)) continue;
            discoverInto(child, depth + 1, maxDepth, out);
            if (out.zeroHour != null && out.generals != null) return;
        }
    }

    private static boolean isZeroHourFolder(DocumentFile dir) {
        return hasFileIgnoreCase(dir, "INIZH.big");
    }

    private static boolean isBaseGeneralsFolder(DocumentFile dir) {
        return hasFileIgnoreCase(dir, "INI.big")
            && hasFileIgnoreCase(dir, "Textures.big")
            && hasFileIgnoreCase(dir, "W3D.big");
    }

    private static boolean hasFileIgnoreCase(DocumentFile dir, String wanted) {
        for (DocumentFile child : dir.listFiles()) {
            if (child.isFile() && wanted.equalsIgnoreCase(safeName(child))) {
                return true;
            }
        }
        return false;
    }

    private static DocumentFile findDirectoryIgnoreCase(DocumentFile parent, String wanted) {
        for (DocumentFile child : parent.listFiles()) {
            String name = safeName(child);
            if (child.isDirectory() && wanted.equalsIgnoreCase(name)) {
                return child;
            }
        }
        return null;
    }

    private static boolean shouldSkipDirectory(String name) {
        return "EasyAntiCheat".equalsIgnoreCase(name)
            || "plugins".equalsIgnoreCase(name)
            || "MSS".equalsIgnoreCase(name)
            || "UserData".equalsIgnoreCase(name);
    }

    private static boolean shouldCopyFile(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        // Windows runtimes are unused by the native Android engine. Keep the
        // actual game data and loose overrides, but don't waste bandwidth on
        // Windows launchers/drivers/debug helpers.
        return !(lower.endsWith(".exe")
            || lower.endsWith(".dll")
            || lower.endsWith(".sys")
            || lower.endsWith(".ico")
            || lower.endsWith(".bmp"));
    }

    private static String safeName(DocumentFile file) {
        String name = file.getName();
        return name != null ? name : "";
    }

    private static void writeMarker(File marker, String value) {
        try (java.io.FileWriter w = new java.io.FileWriter(marker, false)) {
            w.write(value);
            w.write("\n");
        } catch (IOException e) {
            Log.w(TAG, "Could not write marker " + marker, e);
        }
    }
}
