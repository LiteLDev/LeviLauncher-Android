package org.levimc.launcher.core.news;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class NewsRepository {
    public interface Callback {
        void onResult(NewsFeed feed, Throwable error);
    }

    public static final String REMOTE_URL = "https://raw.githubusercontent.com/LiteLDev/LeviLauncher-Android/main/resources/launcher/news.json";
    private static final String ASSET_PATH = "launcher/news.json";
    private static final String CACHE_FILE = "launcher_news.json";
    private static final String PREFS = "launcher_news_repository";
    private static final String KEY_LAST_REFRESH = "last_refresh";
    private static final long REFRESH_INTERVAL_MS = 5L * 60L * 1000L;
    private static final Gson GSON = new Gson();
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object REFRESH_LOCK = new Object();
    private static final List<Callback> REFRESH_CALLBACKS = new ArrayList<>();
    private static boolean refreshing;
    private static volatile NewsFeed memoryCache;

    private NewsRepository() {
    }

    public static void loadCached(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> deliver(callback, loadLocal(appContext), null));
    }

    public static void refreshIfStale(Context context, Callback callback) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long elapsed = System.currentTimeMillis() - prefs.getLong(KEY_LAST_REFRESH, 0L);
        if (elapsed < REFRESH_INTERVAL_MS) {
            loadCached(context, callback);
            return;
        }
        refresh(context, callback);
    }

    public static void refresh(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        synchronized (REFRESH_LOCK) {
            if (callback != null) REFRESH_CALLBACKS.add(callback);
            if (refreshing) return;
            refreshing = true;
        }
        EXECUTOR.execute(() -> {
            NewsFeed result;
            Throwable resultError = null;
            try {
                Request request = new Request.Builder()
                        .url(REMOTE_URL)
                        .header("Cache-Control", "no-cache")
                        .build();
                try (Response response = HTTP.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IllegalStateException("News request failed with HTTP " + response.code());
                    }
                    String json = response.body().string();
                    NewsFeed feed = parse(json);
                    writeCache(appContext, json);
                    memoryCache = feed;
                    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putLong(KEY_LAST_REFRESH, System.currentTimeMillis()).apply();
                    NewsState.notifyChanged(appContext);
                    result = feed;
                }
            } catch (Throwable error) {
                result = loadLocal(appContext);
                resultError = error;
            }

            List<Callback> callbacks;
            synchronized (REFRESH_LOCK) {
                refreshing = false;
                callbacks = new ArrayList<>(REFRESH_CALLBACKS);
                REFRESH_CALLBACKS.clear();
            }
            for (Callback pending : callbacks) deliver(pending, result, resultError);
        });
    }

    private static NewsFeed loadLocal(Context context) {
        NewsFeed cached = memoryCache;
        if (cached != null) return cached;
        File cacheFile = new File(context.getFilesDir(), CACHE_FILE);
        if (cacheFile.isFile()) {
            try (InputStream input = new FileInputStream(cacheFile)) {
                cached = parse(readUtf8(input));
                memoryCache = cached;
                return cached;
            } catch (Exception ignored) {
            }
        }
        try (InputStream input = context.getAssets().open(ASSET_PATH)) {
            cached = parse(readUtf8(input));
        } catch (Exception ignored) {
            cached = new NewsFeed();
        }
        memoryCache = cached;
        return cached;
    }

    private static NewsFeed parse(String json) {
        NewsFeed feed = GSON.fromJson(json, NewsFeed.class);
        if (feed == null || feed.schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported news feed schema");
        }
        if (feed.items == null) feed.items = new ArrayList<>();
        feed.items.removeIf(item -> item == null
                || TextUtils.isEmpty(item.id)
                || TextUtils.isEmpty(item.title)
                || TextUtils.isEmpty(item.publishedAt));
        feed.items.sort(Comparator.comparing((NewsItem item) -> item.publishedAt).reversed());
        return feed;
    }

    private static String readUtf8(InputStream input) throws Exception {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void writeCache(Context context, String json) throws Exception {
        File target = new File(context.getFilesDir(), CACHE_FILE);
        File temporary = new File(context.getFilesDir(), CACHE_FILE + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deliver(Callback callback, NewsFeed feed, Throwable error) {
        if (callback == null) return;
        MAIN.post(() -> callback.onResult(feed, error));
    }
}
