package com.generalsx.zerohour;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class SubscriptionApi {
    private static final int PROTOCOL = 2;
    private static final int EXPECTED_KDF_ITERATIONS = 600000;

    static final class ManifestEntry {
        final String path;
        final long size;
        final String md5;
        final String url;

        ManifestEntry(String path, long size, String md5, String url) {
            this.path = path;
            this.size = size;
            this.md5 = md5;
            this.url = url;
        }
    }

    static final class Manifest {
        final String version;
        final long expiresAt;
        final List<ManifestEntry> files;

        Manifest(String version, long expiresAt, List<ManifestEntry> files) {
            this.version = version;
            this.expiresAt = expiresAt;
            this.files = Collections.unmodifiableList(files);
        }

        ManifestEntry find(String path) {
            for (ManifestEntry entry : files) {
                if (entry.path.equals(path)) return entry;
            }
            return null;
        }
    }

    static final class Result {
        boolean ok;
        String code;
        String message;
        String token;
        String username;
        String challengeId;
        String challenge;
        String passwordSalt;
        int kdfIterations;
        int protocol;
        long subscriptionExpires;
        long offlineUntil;
        int httpStatus;
        String manifestVersion;
        int manifestFileCount;
        Manifest manifest;
    }

    private SubscriptionApi() {}

    static Result challenge(Context ctx, String username) throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("device_hash", DeviceIdentity.deviceHash(ctx));
        body.put("public_key", DeviceIdentity.publicKeyBase64(ctx));
        return request("POST", "/challenge", body, null, null);
    }

    static Result login(Context ctx, String username, String password, Result challenge) throws Exception {
        if (challenge == null || !challenge.ok || challenge.challengeId == null
                || challenge.challenge == null || challenge.passwordSalt == null) {
            throw new IllegalArgumentException("Challenge is incomplete.");
        }
        if (challenge.kdfIterations != EXPECTED_KDF_ITERATIONS) {
            throw new IllegalStateException("Unsupported password KDF settings.");
        }

        String canonicalUser = challenge.username != null && !challenge.username.isEmpty()
            ? challenge.username : canonicalUsername(username);
        String deviceHash = DeviceIdentity.deviceHash(ctx);
        String publicKey = DeviceIdentity.publicKeyBase64(ctx);

        byte[] salt = Base64.decode(challenge.passwordSalt, Base64.DEFAULT);
        byte[] verifier = derivePasswordVerifier(password, salt, challenge.kdfIterations);
        String loginMessage = "abodeh-play-login-v2\n"
            + canonicalUser + "\n"
            + challenge.challengeId + "\n"
            + challenge.challenge + "\n"
            + deviceHash + "\n"
            + publicKey;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(verifier, "HmacSHA256"));
        String passwordProof = Base64.encodeToString(
            mac.doFinal(loginMessage.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);

        JSONObject body = new JSONObject();
        body.put("username", canonicalUser);
        body.put("device_hash", deviceHash);
        body.put("public_key", publicKey);
        body.put("challenge_id", challenge.challengeId);
        body.put("signature", DeviceIdentity.signChallenge(challenge.challenge));
        body.put("protocol", PROTOCOL);
        body.put("password_proof", passwordProof);
        body.put("app_version", appVersion(ctx));
        return request("POST", "/login", body, null, null);
    }

    static Result validate(Context ctx) throws Exception {
        JSONObject body = new JSONObject();
        body.put("device_hash", DeviceIdentity.deviceHash(ctx));
        body.put("app_version", appVersion(ctx));
        return request("POST", "/session", body, SubscriptionManager.token(ctx), null);
    }

    static Result logout(Context ctx) throws Exception {
        JSONObject body = new JSONObject();
        body.put("device_hash", DeviceIdentity.deviceHash(ctx));
        return request("POST", "/logout", body, SubscriptionManager.token(ctx), null);
    }

    static Result gameManifest(Context ctx) throws Exception {
        String deviceHash = DeviceIdentity.deviceHash(ctx);
        return request("GET", "/game-manifest", null, SubscriptionManager.token(ctx), deviceHash);
    }

    private static String appVersion(Context ctx) {
        try {
            android.content.pm.PackageInfo info = ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "android";
        } catch (Exception e) {
            return "android";
        }
    }

    private static byte[] derivePasswordVerifier(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static String canonicalUsername(String username) {
        return Normalizer.normalize(username.trim(), Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
    }

    private static Result request(String method, String path, JSONObject body,
                                  String bearer, String deviceHeader) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(SubscriptionManager.API_BASE + path).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "AbodehPlay/Android");
        if (bearer != null && !bearer.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        if (deviceHeader != null && !deviceHeader.isEmpty()) {
            c.setRequestProperty("X-Abodeh-Device", deviceHeader);
        }
        if (body != null) {
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
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
        r.passwordSalt = json.optString("password_salt", null);
        r.kdfIterations = json.optInt("kdf_iterations", 0);
        r.protocol = json.optInt("protocol", 0);
        r.subscriptionExpires = json.optLong("subscription_expires", 0);
        r.offlineUntil = json.optLong("offline_until", 0);
        r.manifestVersion = json.optString("version", null);

        JSONArray files = json.optJSONArray("files");
        r.manifestFileCount = files != null ? files.length() : 0;
        if (r.ok && files != null) {
            ArrayList<ManifestEntry> entries = new ArrayList<>(files.length());
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String filePath = f.optString("path", "");
                long size = f.optLong("size", -1);
                String md5 = f.optString("md5", "");
                String url = f.optString("url", "");
                if (filePath.isEmpty() || size < 0 || !md5.matches("(?i)^[a-f0-9]{32}$")
                        || url.isEmpty()) {
                    throw new IllegalStateException("Server returned an invalid game manifest entry.");
                }
                entries.add(new ManifestEntry(filePath, size, md5.toLowerCase(Locale.ROOT), url));
            }
            r.manifest = new Manifest(
                r.manifestVersion != null ? r.manifestVersion : "",
                json.optLong("expires_at", 0),
                entries);
        }
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
