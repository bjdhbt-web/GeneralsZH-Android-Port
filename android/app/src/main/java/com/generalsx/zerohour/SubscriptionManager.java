package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;

final class SubscriptionManager {
    static final String PREFS = "abodeh_play_subscription";
    static final String API_BASE = "https://abodeh-play-api.bjdhbt.workers.dev/v1";

    private static final String TOKEN = "token";
    private static final String USERNAME = "username";
    private static final String SUBSCRIPTION_EXPIRES = "subscription_expires";
    private static final String OFFLINE_UNTIL = "offline_until";
    private static final String STATUS = "status";
    private static final String MANIFEST_VERSION = "manifest_version";
    private static final String MANIFEST_FILE_COUNT = "manifest_file_count";

    private SubscriptionManager() {}

    static void saveSession(Context ctx, String token, String username,
                            long subscriptionExpires, long offlineUntil, String status) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TOKEN, token)
            .putString(USERNAME, username)
            .putLong(SUBSCRIPTION_EXPIRES, subscriptionExpires)
            .putLong(OFFLINE_UNTIL, offlineUntil)
            .putString(STATUS, status)
            .apply();
    }

    static void saveManifestSummary(Context ctx, String version, int fileCount) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MANIFEST_VERSION, version != null ? version : "")
            .putInt(MANIFEST_FILE_COUNT, Math.max(fileCount, 0))
            .apply();
    }

    static String token(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN, null);
    }

    static String username(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(USERNAME, "");
    }

    static boolean hasToken(Context ctx) {
        String token = token(ctx);
        return token != null && !token.isEmpty();
    }

    static boolean hasValidOfflineLease(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis() / 1000L;
        long offlineUntil = p.getLong(OFFLINE_UNTIL, 0);
        long subExpires = p.getLong(SUBSCRIPTION_EXPIRES, 0);
        String status = p.getString(STATUS, "");
        return "active".equals(status)
            && offlineUntil > now
            && (subExpires == 0 || subExpires > now);
    }

    static void clearSession(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
