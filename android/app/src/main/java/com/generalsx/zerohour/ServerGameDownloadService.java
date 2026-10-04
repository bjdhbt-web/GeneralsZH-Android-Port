package com.generalsx.zerohour;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.ContextCompat;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerGameDownloadService extends Service {
    private static final String CHANNEL_ID = "abodeh_play_game_download";
    private static final int NOTIFICATION_ID = 2604;
    private static final String ACTION_START = "com.generalsx.zerohour.action.START_GAME_DOWNLOAD";
    private static final String PREFS = "abodeh_play_game_download";

    private static final String KEY_STATE = "state";
    private static final String KEY_DONE = "done";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_FILE_INDEX = "file_index";
    private static final String KEY_FILE_COUNT = "file_count";
    private static final String KEY_PATH = "path";
    private static final String KEY_ERROR = "error";

    static final String STATE_IDLE = "idle";
    static final String STATE_RUNNING = "running";
    static final String STATE_SUCCESS = "success";
    static final String STATE_ERROR = "error";

    private final AtomicBoolean workerRunning = new AtomicBoolean(false);

    static final class State {
        final String state;
        final long done;
        final long total;
        final int fileIndex;
        final int fileCount;
        final String path;
        final String error;

        State(String state, long done, long total, int fileIndex, int fileCount,
              String path, String error) {
            this.state = state;
            this.done = done;
            this.total = total;
            this.fileIndex = fileIndex;
            this.fileCount = fileCount;
            this.path = path;
            this.error = error;
        }

        int progress1000() {
            if (total <= 0) return 0;
            return (int) Math.min(1000L, Math.max(0L, (done * 1000L) / total));
        }
    }

    public static void startDownload(Context context) {
        Intent i = new Intent(context, ServerGameDownloadService.class);
        i.setAction(ACTION_START);
        ContextCompat.startForegroundService(context, i);
    }

    static State readState(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        return new State(
            p.getString(KEY_STATE, STATE_IDLE),
            p.getLong(KEY_DONE, 0),
            p.getLong(KEY_TOTAL, 0),
            p.getInt(KEY_FILE_INDEX, 0),
            p.getInt(KEY_FILE_COUNT, 0),
            p.getString(KEY_PATH, ""),
            p.getString(KEY_ERROR, "")
        );
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        startForeground(NOTIFICATION_ID,
            buildNotification(getString(R.string.server_game_notification_preparing), 0, 0, true));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (workerRunning.compareAndSet(false, true)) {
            writeState(STATE_RUNNING, 0, 0, 0, 0, "", "");
            Thread worker = new Thread(this::runInstall, "AbodehPlayBackgroundGameDownload");
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Foreground service intentionally survives launcher/task dismissal.
        // Android may still stop it for system reasons; START_STICKY plus .part
        // files makes the next service instance resume instead of restarting.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runInstall() {
        final long[] lastNotifyAt = {0};
        try {
            ServerGameInstaller.install(getApplicationContext(),
                (doneBytes, totalBytes, fileIndex, fileCount, relativePath) -> {
                    writeState(STATE_RUNNING, doneBytes, totalBytes,
                        fileIndex, fileCount, relativePath, "");

                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastNotifyAt[0] >= 1000 || doneBytes >= totalBytes) {
                        lastNotifyAt[0] = now;
                        int percent = totalBytes > 0
                            ? (int) Math.min(100L, (doneBytes * 100L) / totalBytes)
                            : 0;
                        String line = getString(
                            R.string.server_game_notification_progress,
                            percent, fileIndex, fileCount);
                        notifyProgress(line, doneBytes, totalBytes, false);
                    }
                });

            State done = readState(this);
            writeState(STATE_SUCCESS,
                done.total > 0 ? done.total : done.done,
                done.total,
                done.fileCount,
                done.fileCount,
                "",
                "");
            NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID,
                buildNotification(getString(R.string.server_game_notification_complete),
                    1000, 1000, false));
        } catch (Throwable t) {
            State previous = readState(this);
            String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            writeState(STATE_ERROR, previous.done, previous.total,
                previous.fileIndex, previous.fileCount, previous.path, message);
            NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID,
                buildNotification(getString(R.string.server_game_notification_failed),
                    0, 0, false));
        } finally {
            workerRunning.set(false);
            stopForeground(false);
            stopSelf();
        }
    }

    private void notifyProgress(String text, long done, long total, boolean indeterminate) {
        int max = 1000;
        int value = total > 0
            ? (int) Math.min(1000L, Math.max(0L, (done * 1000L) / total))
            : 0;
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text, max, value,
            indeterminate || total <= 0));
    }

    private Notification buildNotification(String text, int max, int value, boolean indeterminate) {
        Intent open = new Intent(this, SetupActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.server_game_notification_title))
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(STATE_RUNNING.equals(readState(this).state))
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS);

        if (max > 0) b.setProgress(max, value, indeterminate);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.server_game_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.server_game_notification_channel_desc));
            channel.setSound(null, null);
            nm.createNotificationChannel(channel);
        }
    }

    private void writeState(String state, long done, long total,
                            int fileIndex, int fileCount, String path, String error) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATE, state)
            .putLong(KEY_DONE, Math.max(0, done))
            .putLong(KEY_TOTAL, Math.max(0, total))
            .putInt(KEY_FILE_INDEX, Math.max(0, fileIndex))
            .putInt(KEY_FILE_COUNT, Math.max(0, fileCount))
            .putString(KEY_PATH, path != null ? path : "")
            .putString(KEY_ERROR, error != null ? error : "")
            .apply();
    }
}
