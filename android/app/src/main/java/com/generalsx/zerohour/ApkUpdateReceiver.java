package com.generalsx.zerohour;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public final class ApkUpdateReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "abodeh_play_apk_update";
    private static final int NOTIFICATION_ID = 2610;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;

        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (id < 0 || id != ApkUpdateManager.trackedDownloadId(context)) return;
        if (!ApkUpdateManager.isDownloadComplete(context)) return;

        ApkUpdateManager.markDownloadComplete(context);
        ensureChannel(context);

        Intent open = new Intent(context, SetupActivity.class)
            .putExtra(ApkUpdateManager.EXTRA_INSTALL_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pending = PendingIntent.getActivity(
            context,
            2610,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.apk_update_ready_title))
            .setContentText(context.getString(R.string.apk_update_ready_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM);

        NotificationManager nm =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, b.build());
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.apk_update_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.apk_update_channel_desc));
            nm.createNotificationChannel(channel);
        }
    }
}
