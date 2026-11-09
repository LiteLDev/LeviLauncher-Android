package org.levimc.launcher.preloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.levimc.launcher.util.JsonIOUtils;
import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class PreloaderSignatureRulesManager {
    private static final String TAG = "PreloaderSigs";
    private static final String ASSET_DEFAULT_RULES = "preloader/preloader_signature_rules_source.json";
    private static final String RESOURCE_DIR = "resources/preloader";
    private static final String LOCAL_RULES_FILE = "preloader_signature_rules.json";
    private static final String PREFS_NAME = "preloader_signature_rules";
    private static final String KEY_LAST_SUCCESSFUL_UPDATE_TIME = "last_successful_update_time";

    private static final String REMOTE_RULES_URL = "https://raw.githubusercontent.com/LiteLDev/LeviLauncher-Android/refs/heads/main/resources/preloader/preloader_signature_rules_source.json";

    private static final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    private PreloaderSignatureRulesManager() {
    }

    public interface RefreshCallback {
        void onFinished(RefreshResult result);
    }

    public static final class RefreshResult {
        public final boolean success;
        public final boolean updated;
        public final String message;
        public final long lastSuccessfulUpdateTime;

        private RefreshResult(boolean success, boolean updated, String message, long lastSuccessfulUpdateTime) {
            this.success = success;
            this.updated = updated;
            this.message = message == null ? "" : message;
            this.lastSuccessfulUpdateTime = lastSuccessfulUpdateTime;
        }
    }

    public static void refreshOnLauncherStart(Context context) {
        refresh(context, null);
    }

    public static void refreshNow(Context context, RefreshCallback callback) {
        refresh(context, callback);
    }

    public static boolean hasRemoteRulesUrl() {
        return !isBlank(REMOTE_RULES_URL);
    }

    public static long getLastSuccessfulUpdateTime(Context context) {
        if (context == null) {
            return 0L;
        }
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SUCCESSFUL_UPDATE_TIME, 0L);
    }

    public static File getRulesFile(Context context) {
        if (context == null) {
            return null;
        }
        return ensureLocalRulesFile(context.getApplicationContext());
    }

    private static void refresh(Context context, RefreshCallback callback) {
        if (context == null) {
            postCallback(callback, new RefreshResult(false, false, "Context is null", 0L));
            return;
        }
        if (!refreshRunning.compareAndSet(false, true)) {
            postCallback(callback, new RefreshResult(
                    false,
                    false,
                    "Refresh already running",
                    getLastSuccessfulUpdateTime(context)
            ));
            return;
        }

        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            RefreshResult result;
            try {
                ensureLocalRulesFile(appContext);
                result = refreshRemoteRules(appContext);
            } catch (Exception e) {
                Log.w(TAG, "Failed to refresh preloader signature rules", e);
                result = new RefreshResult(false, false, e.getMessage(), getLastSuccessfulUpdateTime(appContext));
            } finally {
                refreshRunning.set(false);
            }
            postCallback(callback, result);
        }, "preloader-sig-refresh").start();
    }

    private static File ensureLocalRulesFile(Context context) {
        File localFile = getLocalRulesFile(context);
        String localRules = JsonIOUtils.read(localFile);
        String bundledRules = readAsset(context, ASSET_DEFAULT_RULES);

        if (!hasValidRules(bundledRules)) {
            Log.w(TAG, "Bundled preloader signature rules are invalid");
            return localFile;
        }

        if (!hasValidRules(localRules) || getRulesRevision(bundledRules) > getRulesRevision(localRules)) {
            writeFileIfChanged(localFile, bundledRules);
        }
        return localFile;
    }

    private static RefreshResult refreshRemoteRules(Context context) {
        if (isBlank(REMOTE_RULES_URL)) {
            return new RefreshResult(
                    false,
                    false,
                    "Remote preloader signature rules URL is empty",
                    getLastSuccessfulUpdateTime(context)
            );
        }

        String remoteRules = fetchText(REMOTE_RULES_URL.trim());
        if (isBlank(remoteRules)) {
            return new RefreshResult(
                    false,
                    false,
                    "Failed to fetch remote preloader signature rules",
                    getLastSuccessfulUpdateTime(context)
            );
        }
        if (!hasValidRules(remoteRules)) {
            Log.w(TAG, "Remote preloader signature rules are invalid");
            return new RefreshResult(
                    false,
                    false,
                    "Remote preloader signature rules are invalid",
                    getLastSuccessfulUpdateTime(context)
            );
        }

        File localFile = getLocalRulesFile(context);
        String localRules = JsonIOUtils.read(localFile);
        if (hasValidRules(localRules) && getRulesRevision(remoteRules) < getRulesRevision(localRules)) {
            return new RefreshResult(
                    true,
                    false,
                    "Remote preloader signature rules are older than the installed rules",
                    getLastSuccessfulUpdateTime(context)
            );
        }

        WriteResult writeResult = writeFileIfChanged(localFile, remoteRules);
        if (!writeResult.success) {
            return new RefreshResult(
                    false,
                    false,
                    "Failed to write preloader signature rules",
                    getLastSuccessfulUpdateTime(context)
            );
        }

        long updateTime = System.currentTimeMillis();
        setLastSuccessfulUpdateTime(context, updateTime);
        return new RefreshResult(true, writeResult.changed, "", updateTime);
    }

    private static String fetchText(String url) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "Remote preloader signature rules request failed: " + response.code());
                return null;
            }

            ResponseBody body = response.body();
            return body == null ? null : body.string();
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch remote preloader signature rules", e);
            return null;
        }
    }

    private static boolean hasValidRules(String content) {
        if (isBlank(content)) {
            return false;
        }

        try {
            JSONObject root = new JSONObject(content);
            JSONArray rules = root.optJSONArray("rules");
            if (rules == null || rules.length() == 0) {
                return false;
            }

            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.optJSONObject(i);
                if (rule != null && hasCompleteSignatures(rule)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean hasCompleteSignatures(JSONObject rule) {
        JSONObject sigs = rule.optJSONObject("sigs");
        if (sigs == null) {
            sigs = rule;
        }

        return !isBlank(sigs.optString("pauseMenuDtorSig", ""))
                && !isBlank(sigs.optString("pauseMenuOpenSig", ""))
                && !isBlank(sigs.optString("hudScreenDtorSig", ""))
                && !isBlank(sigs.optString("hudScreenOpenSig", ""))
                && (!isBlank(sigs.optString("isShowingMenuSig", ""))
                    || rule.optInt("isShowingMenuVtableIndex", -1) >= 0);
    }

    private static int getRulesRevision(String content) {
        if (isBlank(content)) {
            return 0;
        }
        try {
            return Math.max(0, new JSONObject(content).optInt("revision", 0));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static File getLocalRulesFile(Context context) {
        File dir = new File(LauncherStorage.getAppRoot(context), RESOURCE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create preloader resource directory: " + dir.getAbsolutePath());
        }
        return new File(dir, LOCAL_RULES_FILE);
    }

    private static String readAsset(Context context, String path) {
        try (InputStream input = context.getAssets().open(path);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Log.w(TAG, "Failed to read asset " + path, e);
            return null;
        }
    }

    private static WriteResult writeFileIfChanged(File file, String content) {
        if (file == null || content == null) {
            return WriteResult.failed();
        }

        String existing = JsonIOUtils.read(file);
        if (content.equals(existing)) {
            return WriteResult.unchanged();
        }

        File parent = file.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            Log.w(TAG, "Failed to create directory for preloader signature rules");
            return WriteResult.failed();
        }

        File temp = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (Exception e) {
            Log.w(TAG, "Failed to write preloader signature rules", e);
            if (!temp.delete() && temp.exists()) {
                Log.w(TAG, "Failed to delete temporary file: " + temp.getAbsolutePath());
            }
            return WriteResult.failed();
        }

        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to replace preloader signature rules: " + file.getAbsolutePath());
            if (!temp.delete() && temp.exists()) {
                Log.w(TAG, "Failed to delete temporary file: " + temp.getAbsolutePath());
            }
            return WriteResult.failed();
        }
        if (!temp.renameTo(file)) {
            Log.w(TAG, "Failed to commit preloader signature rules: " + file.getAbsolutePath());
            if (!temp.delete() && temp.exists()) {
                Log.w(TAG, "Failed to delete temporary file: " + temp.getAbsolutePath());
            }
            return WriteResult.failed();
        }
        return WriteResult.changed();
    }

    private static void setLastSuccessfulUpdateTime(Context context, long updateTime) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putLong(KEY_LAST_SUCCESSFUL_UPDATE_TIME, updateTime)
                .apply();
    }

    private static void postCallback(RefreshCallback callback, RefreshResult result) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> callback.onFinished(result));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class WriteResult {
        final boolean success;
        final boolean changed;

        private WriteResult(boolean success, boolean changed) {
            this.success = success;
            this.changed = changed;
        }

        static WriteResult changed() {
            return new WriteResult(true, true);
        }

        static WriteResult unchanged() {
            return new WriteResult(true, false);
        }

        static WriteResult failed() {
            return new WriteResult(false, false);
        }
    }
}
