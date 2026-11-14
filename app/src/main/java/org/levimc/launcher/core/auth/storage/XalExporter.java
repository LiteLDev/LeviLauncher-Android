package org.levimc.launcher.core.auth.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.levimc.launcher.core.auth.MsftAccountStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class XalExporter {
    private static final String TAG = "XalExporter";
    private static final Gson GSON = new Gson();
    private static final String TITLE_ID = "1739947436";

    public static void exportActiveAccount(Context ctx) {
        try {
            exportActiveAccountOrThrow(ctx);
        } catch (Exception e) {
            Log.e(TAG, "Failed to export XAL data", e);
        }
    }

    public static void exportActiveAccountOrThrow(Context ctx) {
        MsftAccountStore.MsftAccount active = MsftAccountStore.getActive(ctx);
        if (active == null || active.serializedAuthManager == null) {
            clearXalData(ctx);
            return;
        }
        JsonObject authJson = JsonParser.parseString(active.serializedAuthManager).getAsJsonObject();
        export(ctx, authJson, active.msUserId, active.xboxGamertag, active.xuid);
    }

    public static boolean prepareCachedAccount(Context ctx, MsftAccountStore.MsftAccount account) {
        try {
            return prepareCachedAccountOrThrow(ctx, account);
        } catch (RuntimeException error) {
            Log.w(TAG, "Saved Microsoft sign-in is unavailable for offline play");
            return false;
        }
    }

    private static boolean prepareCachedAccountOrThrow(Context ctx, MsftAccountStore.MsftAccount account) {
        if (account == null || account.msUserId == null || account.msUserId.isEmpty()
                || account.serializedAuthManager == null) return false;
        JsonObject json;
        try {
            json = JsonParser.parseString(account.serializedAuthManager).getAsJsonObject();
        } catch (RuntimeException error) {
            return false;
        }
        String user = encodeUserId(account.msUserId);
        File directory = new File(ctx.getApplicationContext().getFilesDir(), "xal/" + user);
        File nativeMsa = new File(directory, "Xal." + TITLE_ID + ".Production.Msa." + user + ".json");
        File nativeDefault = new File(directory, "Xal." + TITLE_ID + ".Production.Default.json");
        if (nativeMsa.isFile() && nativeDefault.isFile()) {
            String expectedId = "{" + stringValue(json, "deviceId") + "}";
            SharedPreferences prefs = ctx.getSharedPreferences("org.levimc.xal.crypto", Context.MODE_PRIVATE);
            if (!expectedId.equals(prefs.getString("id", ""))) writeDeviceIdentity(ctx, json);
            return true;
        }
        if (!hasValidToken(json, "xboxLiveXstsToken") || !json.has("msaToken")) return false;
        JsonObject msa = json.getAsJsonObject("msaToken");
        if (!msa.has("expireTimeMs") || msa.get("expireTimeMs").getAsLong() <= System.currentTimeMillis()
                || stringValue(msa, "accessToken").isEmpty()) return false;
        export(ctx, json, account.msUserId, account.xboxGamertag, account.xuid);
        return true;
    }

    public static boolean importRefreshToken(Context ctx, MsftAccountStore.MsftAccount account, JsonObject authJson) {
        if (account.msUserId == null || account.msUserId.isEmpty() || !authJson.has("msaToken")) return false;
        String b64User = encodeUserId(account.msUserId);
        File userDir = new File(ctx.getApplicationContext().getFilesDir(), "xal/" + b64User);
        File cache = new File(userDir, "Xal." + TITLE_ID + ".Production.Msa." + b64User + ".json");
        if (!cache.exists() || cache.lastModified() < account.lastUpdated) return false;
        try (InputStreamReader reader = new InputStreamReader(new AtomicFile(cache).openRead(), StandardCharsets.UTF_8)) {
            JsonObject nativeMsa = JsonParser.parseReader(reader).getAsJsonObject();
            if (!account.msUserId.equals(stringValue(nativeMsa, "user_id"))) return false;
            String refreshToken = stringValue(nativeMsa, "refresh_token");
            JsonObject msa = authJson.getAsJsonObject("msaToken");
            if (refreshToken.isEmpty() || refreshToken.equals(stringValue(msa, "refreshToken"))) return false;
            msa.addProperty("refreshToken", refreshToken);
            msa.addProperty("expireTimeMs", 0L);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not read Minecraft's refreshed credentials", e);
            return false;
        }
    }

    private static String stringValue(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static String encodeUserId(String userId) {
        return Base64.encodeToString(userId.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static void clearXalData(Context ctx) {
        File xalDir = new File(ctx.getApplicationContext().getFilesDir(), "xal");
        File[] files = xalDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith("Xal.Accounts.json")) continue;
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        if (!ctx.getSharedPreferences("org.levimc.xal.crypto", Context.MODE_PRIVATE).edit().clear().commit()) {
            throw new IllegalStateException("Minecraft credentials could not be cleared.");
        }
    }

    public static void removeAccountData(Context ctx, String userId) {
        if (userId == null || userId.isEmpty()) return;
        File userDir = new File(ctx.getApplicationContext().getFilesDir(), "xal/" + encodeUserId(userId));
        deleteDirectory(userDir);
    }

    private static void writeJson(File destination, JsonObject json) {
        AtomicFile file = new AtomicFile(destination);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("Minecraft credentials could not be saved.", error);
        }
    }

    private static boolean hasValidToken(JsonObject authJson, String key) {
        if (!authJson.has(key) || !authJson.get(key).isJsonObject()) return false;
        JsonObject token = authJson.getAsJsonObject(key);
        return token.has("expireTimeMs")
                && token.get("expireTimeMs").getAsLong() > System.currentTimeMillis()
                && !stringValue(token, "token").isEmpty();
    }

    private static boolean deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return false;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return dir.delete();
    }

    public static void export(Context ctx, JsonObject authJson, String msaUserId, String gamertag, String xuid) {
        File root = new File(ctx.getApplicationContext().getFilesDir(), "xal");
        if (!root.exists()) root.mkdirs();

        if (msaUserId == null || msaUserId.isEmpty() || !authJson.has("msaToken")
                || !hasValidToken(authJson, "xboxLiveXstsToken")) {
            throw new IllegalStateException("Minecraft credentials are missing or expired.");
        }
        JsonObject msaToken = authJson.getAsJsonObject("msaToken");
        if (msaToken.get("expireTimeMs").getAsLong() <= System.currentTimeMillis()
                || stringValue(msaToken, "accessToken").isEmpty()) {
            throw new IllegalStateException("Microsoft credentials are expired. Refresh the account before launching.");
        }
        String b64User = encodeUserId(msaUserId);
        File userDir = new File(root, b64User);
        if (!userDir.exists()) userDir.mkdirs();

        String tid = TITLE_ID;

        if (authJson.has("deviceId") && !authJson.get("deviceId").isJsonNull()) {
            String deviceId = authJson.get("deviceId").getAsString();
            JsonObject di = new JsonObject();
            di.addProperty("Id", "{" + deviceId + "}");
            di.addProperty("Key", "Serialized to SharedPreferences");
            File diFile = new File(userDir, "Xal.Production.RETAIL.DeviceIdentity.json");
            writeJson(diFile, di);
        }

        JsonObject def = new JsonObject();
        def.addProperty("default", msaUserId);
        File defFile = new File(userDir, "Xal." + tid + ".Production.Default.json");
        writeJson(defFile, def);

        if (authJson.has("msaToken")) {
            JsonObject msaJson = authJson.getAsJsonObject("msaToken");
            JsonObject rootMsa = new JsonObject();
            rootMsa.addProperty("user_id", msaUserId);
            rootMsa.addProperty("refresh_token", msaJson.has("refreshToken") && !msaJson.get("refreshToken").isJsonNull() ? msaJson.get("refreshToken").getAsString() : "");
            rootMsa.addProperty("foci", "");

            JsonObject at = new JsonObject();
            String tokenVal = msaJson.has("accessToken") ? msaJson.get("accessToken").getAsString() : "";
            at.addProperty("access_token", "t=" + tokenVal);
            long expireTimeMs = msaJson.has("expireTimeMs") ? msaJson.get("expireTimeMs").getAsLong() : System.currentTimeMillis();
            at.addProperty("xal_expires", formatDate(expireTimeMs));
            at.addProperty("scopes", "service::user.auth.xboxlive.com::mbi_ssl");

            JsonArray ats = new JsonArray();
            ats.add(at);
            rootMsa.add("access_tokens", ats);
            File msaFile = new File(userDir, "Xal." + tid + ".Production.Msa." + b64User + ".json");
            writeJson(msaFile, rootMsa);
        }

        JsonObject uRoot = new JsonObject();
        uRoot.addProperty("deviceId", "{" + (authJson.has("deviceId") ? authJson.get("deviceId").getAsString() : "") + "}");
        JsonArray tokens = new JsonArray();

        if (hasValidToken(authJson, "xboxLiveXstsToken")) tokens.add(buildXstsEnvelope("Xtoken", "http://xboxlive.com", msaUserId, gamertag, xuid, authJson.getAsJsonObject("xboxLiveXstsToken")));
        if (hasValidToken(authJson, "playFabXstsToken")) tokens.add(buildXstsEnvelope("Xtoken", "https://b980a380.minecraft.playfabapi.com/", msaUserId, gamertag, xuid, authJson.getAsJsonObject("playFabXstsToken")));
        if (hasValidToken(authJson, "realmsXstsToken")) tokens.add(buildXstsEnvelope("Xtoken", "https://pocket.realms.minecraft.net/", msaUserId, gamertag, xuid, authJson.getAsJsonObject("realmsXstsToken")));
        
        if (hasValidToken(authJson, "xblUserToken")) tokens.add(buildXstsEnvelope("Utoken", "http://auth.xboxlive.com", msaUserId, gamertag, xuid, authJson.getAsJsonObject("xblUserToken")));

        uRoot.add("tokens", tokens);
        File uFile = new File(userDir, "Xal." + tid + ".Production.RETAIL.User." + b64User + ".json");
        writeJson(uFile, uRoot);

        writeDeviceIdentity(ctx, authJson);
    }

    private static void writeDeviceIdentity(Context ctx, JsonObject authJson) {
        if (authJson.has("deviceKeyPair") && authJson.get("deviceKeyPair").isJsonObject()) {
            JsonObject kp = authJson.getAsJsonObject("deviceKeyPair");
            if (kp.has("publicKey") && kp.has("privateKey")) {
                SharedPreferences.Editor edit = ctx.getSharedPreferences("org.levimc.xal.crypto", Context.MODE_PRIVATE).edit();
                edit.putString("id", "{" + (authJson.has("deviceId") ? authJson.get("deviceId").getAsString() : "") + "}");
                
                String pubB64 = kp.get("publicKey").getAsString();
                String privB64 = kp.get("privateKey").getAsString();
                
                try {
                    byte[] pubBytes = android.util.Base64.decode(pubB64, android.util.Base64.DEFAULT);
                    byte[] privBytes = android.util.Base64.decode(privB64, android.util.Base64.DEFAULT);
                    edit.putString("public", Base64.encodeToString(pubBytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE));
                    edit.putString("private", Base64.encodeToString(privBytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE));
                } catch (Exception e) {
                    edit.putString("public", pubB64);
                    edit.putString("private", privB64);
                }
                if (!edit.commit()) {
                    throw new IllegalStateException("Minecraft device credentials could not be saved.");
                }
            }
        }
    }

    private static JsonObject buildXstsEnvelope(String identityType, String relyingParty, String msaUserId, String gamertag, String xuid, JsonObject tokenObj) {
        long expireTimeMs = tokenObj.has("expireTimeMs") ? tokenObj.get("expireTimeMs").getAsLong() : System.currentTimeMillis();
        String tokenStr = tokenObj.has("token") ? tokenObj.get("token").getAsString() : "";
        String uhs = tokenObj.has("userHash") ? tokenObj.get("userHash").getAsString() : "";

        JsonObject xui = new JsonObject();
        xui.addProperty("uhs", uhs);
        xui.addProperty("gtg", gamertag);
        xui.addProperty("mgt", "");
        xui.addProperty("mgs", "");
        xui.addProperty("umg", "");
        xui.addProperty("xid", xuid);
        xui.addProperty("agg", "");
        xui.addProperty("prv", "");
        xui.addProperty("usr", "");
        xui.addProperty("uer", "");
        xui.addProperty("utr", "");

        JsonArray xuiArr = new JsonArray();
        xuiArr.add(xui);

        JsonObject displayClaims = new JsonObject();
        displayClaims.add("xui", xuiArr);

        JsonObject data = new JsonObject();
        data.addProperty("Token", tokenStr);
        data.addProperty("NotAfter", formatDate(expireTimeMs));
        data.addProperty("IssueInstant", formatDate(expireTimeMs - 8 * 60 * 60 * 1000L));
        data.addProperty("ClientAttested", false);
        data.add("DisplayClaims", displayClaims);

        JsonObject env = new JsonObject();
        env.addProperty("MsaUserId", msaUserId);
        env.addProperty("HasSignInDisplayClaims", true);
        env.addProperty("IdentityType", identityType);
        env.addProperty("Environment", "Production");
        env.addProperty("Sandbox", "RETAIL");
        env.addProperty("TokenType", "JWT");
        env.addProperty("RelyingParty", relyingParty);
        env.addProperty("SubRelyingParty", "");
        env.add("TokenData", data);

        return env;
    }

    private static String formatDate(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(millis));
    }
}
