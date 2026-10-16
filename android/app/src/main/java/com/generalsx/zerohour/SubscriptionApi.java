package com.generalsx.zerohour;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class SubscriptionApi {
    static final class Result {
        boolean ok;
        String code;
        String message;
        String token;
        String username;
        String challengeId;
        String challenge;
        long subscriptionExpires;
        long offlineUntil;
        int httpStatus;
    }

    private SubscriptionApi() {}

    static Result challenge(Context ctx, String username) throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("device_hash", DeviceIdentity.deviceHash(ctx));
        body.put("public_key", DeviceIdentity.publicKeyBase64(ctx));
        return request("/challenge", body, null);
    }

    static Result login(Context ctx, String username, String password,
                        String challengeId, String challenge) throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("password", password);
        body.put("device_hash", DeviceIdentity.deviceHash(ctx));
        body.put("public_key", DeviceIdentity.publicKeyBase64(ctx));
        body.put("challenge_id", challengeId);
        body.put("signature", DeviceIdentity.signChallenge(challenge));
        body.put("app_version", BuildConfig.VERSION_NAME);
        return request("/login", body, null);
    }

    static Result validate(Context ctx) throws Exception {
        JSONObject body = new JSONObject();
        body.put("device_hash", DeviceIdentity.deviceHash(ctx));
        body.put("app_version", BuildConfig.VERSION_NAME);
        return request("/session", body, SubscriptionManager.token(ctx));
    }

    static Result logout(Context ctx) throws Exception {
        JSONObject body = new JSONObject();
        body.put("device_hash", DeviceIdentity.deviceHash(ctx));
        return request("/logout", body, SubscriptionManager.token(ctx));
    }

    private static Result request(String path, JSONObject body, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(SubscriptionManager.API_BASE + path).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "AbodehPlay/" + BuildConfig.VERSION_NAME);
        if (bearer != null && !bearer.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        c.setDoOutput(true);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
        }

        int status = c.getResponseCode();
        InputStream stream = status >= 200 && status < 400 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(stream);
        JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);

        Result r = new Result();
        r.httpStatus = status;
        r.ok = status >= 200 && status < 300 && json.optBoolean("ok", false);
        r.code = json.optString("code", r.ok ? "OK" : "ERROR");
        r.message = json.optString("message", "");
        r.token = json.optString("token", null);
        r.username = json.optString("username", null);
        r.challengeId = json.optString("challenge_id", null);
        r.challenge = json.optString("challenge", null);
        r.subscriptionExpires = json.optLong("subscription_expires", 0);
        r.offlineUntil = json.optLong("offline_until", 0);
        return r;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) b.append(buf, 0, n);
        }
        return b.toString();
    }
}
