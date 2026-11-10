package org.levimc.launcher.core.play;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The signed-in Google account used to download Minecraft from Google Play. The whole
 * record is encrypted with a key that never leaves the Android Keystore.
 */
public final class PlayAccountStore {
    private static final String TAG = "PlayAccountStore";
    private static final String KEY_ALIAS = "levilauncher_play_account";
    private static final String FILE_NAME = "play_account.bin";
    private static final Gson GSON = new Gson();

    /** Everything a signed-in session needs; tokens other than [aasToken] are re-minted when lost. */
    public static final class Account {
        public String email;
        public String aasToken;
        public String gsfId;
        public String checkinConsistencyToken;
        public String deviceConfigToken;
        public String authToken;
        public long authTokenExpiry;
        /** The device fingerprint the checkin was made for; a different one needs a new checkin. */
        public String deviceFingerprint;
    }

    private PlayAccountStore() {
    }

    private static File file(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "play");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE_NAME);
    }

    public static synchronized Account load(Context context) {
        AtomicFile file = new AtomicFile(file(context));
        if (!file.getBaseFile().exists()) return null;
        try (FileInputStream in = file.openRead()) {
            byte[] sealed = readAll(in);
            byte[] plain = decrypt(sealed);
            Account account = GSON.fromJson(new String(plain, StandardCharsets.UTF_8), Account.class);
            if (account == null || account.email == null || account.aasToken == null) return null;
            return account;
        } catch (Exception e) {
            Log.w(TAG, "The saved Google account could not be read", e);
            return null;
        }
    }

    public static synchronized void save(Context context, Account account) throws Exception {
        byte[] sealed = encrypt(GSON.toJson(account).getBytes(StandardCharsets.UTF_8));
        AtomicFile file = new AtomicFile(file(context));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(sealed);
            file.finishWrite(out);
        } catch (Exception e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
    }

    public static synchronized void clear(Context context) {
        new AtomicFile(file(context)).delete();
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception e) {
            Log.w(TAG, "The account key could not be deleted", e);
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static byte[] encrypt(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(plain);
        return ByteBuffer.allocate(1 + iv.length + body.length)
                .put((byte) iv.length).put(iv).put(body).array();
    }

    private static byte[] decrypt(byte[] sealed) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(sealed);
        byte[] iv = new byte[buffer.get() & 0xFF];
        buffer.get(iv);
        byte[] body = new byte[buffer.remaining()];
        buffer.get(body);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(body);
    }

    private static byte[] readAll(FileInputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    static String redact(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        return at <= 1 ? email : email.charAt(0) + "***" + email.substring(at);
    }

    @SuppressWarnings("unused")
    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
