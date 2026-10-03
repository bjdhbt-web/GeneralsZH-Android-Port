package com.generalsx.zerohour;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

final class DeviceIdentity {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "abodeh_play_device_key_v1";
    private static final String APP_BINDING = "ABODEH_PLAY_DEVICE_V1";

    private DeviceIdentity() {}

    static String deviceHash(Context context) throws Exception {
        String androidId = Settings.Secure.getString(
            context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = "unknown";

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(APP_BINDING.getBytes(StandardCharsets.UTF_8));
        sha.update((byte) 0);
        sha.update(androidId.getBytes(StandardCharsets.UTF_8));
        sha.update((byte) 0);
        sha.update(signingCertificateDigest(context));
        return hex(sha.digest());
    }

    static String publicKeyBase64(Context context) throws Exception {
        KeyPair pair = getOrCreateKeyPair();
        return Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP);
    }

    static String signChallenge(String challenge) throws Exception {
        KeyPair pair = getOrCreateKeyPair();
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(pair.getPrivate());
        sig.update(challenge.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP);
    }

    private static KeyPair getOrCreateKeyPair() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            PrivateKey privateKey = (PrivateKey) ks.getKey(ALIAS, null);
            Certificate cert = ks.getCertificate(ALIAS);
            return new KeyPair(cert.getPublicKey(), privateKey);
        }

        KeyPairGenerator gen = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build();
        gen.initialize(spec);
        return gen.generateKeyPair();
    }

    private static byte[] signingCertificateDigest(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.Signature[] sigs = info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : null;
            byte[] cert = sigs != null && sigs.length > 0 ? sigs[0].toByteArray() : new byte[0];
            return MessageDigest.getInstance("SHA-256").digest(cert);
        } else {
            info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            byte[] cert = info.signatures != null && info.signatures.length > 0
                ? info.signatures[0].toByteArray() : new byte[0];
            return MessageDigest.getInstance("SHA-256").digest(cert);
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
