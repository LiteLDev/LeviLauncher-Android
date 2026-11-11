package org.levimc.launcher.core.play;

import android.content.Context;
import android.util.AtomicFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class VersionCatalog {
    public static final String SOURCE_URL = "https://github.com/minecraft-linux/mcpelauncher-versiondb/blob/master/versions.arm64-v8a.json.min";
    private static final String DATA_URL = "https://raw.githubusercontent.com/minecraft-linux/mcpelauncher-versiondb/master/versions.arm64-v8a.json.min";
    private static final String BUNDLED_FILE = "play/versions.arm64-v8a.json.min";
    private static final String MIN_VERSION = "1.21.80";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build();

    private final Context context;
    private final AtomicFile cache;
    private volatile Call activeCall;
    private volatile boolean closed;

    public VersionCatalog(Context context) {
        this.context = context.getApplicationContext();
        cache = new AtomicFile(new File(this.context.getFilesDir(), "play/version_catalog_arm64.json"));
    }

    public List<GameRelease> cached() {
        try (Reader reader = new InputStreamReader(cache.openRead(), StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (Exception ignored) {
        }
        try (Reader reader = new InputStreamReader(context.getAssets().open(BUNDLED_FILE), StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    public List<GameRelease> refresh() throws IOException {
        if (closed) throw new IOException("Version catalog closed");
        Request request = new Request.Builder()
                .url(DATA_URL)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "LeviLauncher")
                .build();
        Call call = HTTP.newCall(request);
        activeCall = call;
        if (closed) call.cancel();
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw new IOException("Version catalog HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty version catalog");
            byte[] bytes;
            try (InputStream in = body.byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (closed) throw new IOException("Version catalog closed");
                    if (out.size() + read > MAX_BYTES) throw new IOException("Version catalog too large");
                    out.write(buffer, 0, read);
                }
                bytes = out.toByteArray();
            }
            List<GameRelease> releases = parse(new java.io.StringReader(new String(bytes, StandardCharsets.UTF_8)));
            if (closed) throw new IOException("Version catalog closed");
            FileOutputStream out = null;
            try {
                out = cache.startWrite();
                out.write(bytes);
                cache.finishWrite(out);
            } catch (IOException error) {
                if (out != null) cache.failWrite(out);
            }
            return releases;
        } finally {
            activeCall = null;
        }
    }

    public void close() {
        closed = true;
        Call call = activeCall;
        if (call != null) call.cancel();
    }

    static List<GameRelease> parse(Reader reader) throws IOException {
        try {
            JsonElement document = JsonParser.parseReader(reader);
            if (!document.isJsonArray()) throw new IOException("Invalid version catalog");
            Map<Long, GameRelease> unique = new LinkedHashMap<>();
            for (JsonElement entry : document.getAsJsonArray()) {
                if (!entry.isJsonArray()) continue;
                JsonArray row = entry.getAsJsonArray();
                if (row.size() < 3 || !row.get(0).isJsonPrimitive() || !row.get(1).isJsonPrimitive()
                        || !row.get(2).isJsonPrimitive() || !row.get(1).getAsJsonPrimitive().isString()) continue;
                String codeText = row.get(0).getAsString();
                String name = row.get(1).getAsString().trim();
                String beta = row.get(2).getAsString();
                if (!row.get(0).getAsJsonPrimitive().isNumber() || !codeText.matches("[0-9]+")
                        || !name.matches("[0-9]+(?:\\.[0-9]+){2,3}")
                        || !row.get(2).getAsJsonPrimitive().isNumber() || !("0".equals(beta) || "1".equals(beta))) continue;
                long code;
                try {
                    code = Long.parseLong(codeText);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (code <= 0 || GameRelease.compareVersionNames(name, MIN_VERSION) < 0) continue;
                GameRelease release = new GameRelease(code, name, 0, 0);
                release.channel = "1".equals(beta) ? GameRelease.CHANNEL_BETA : GameRelease.CHANNEL_RELEASE;
                unique.put(code, release);
            }
            if (unique.isEmpty()) throw new IOException("No supported versions in catalog");
            List<GameRelease> releases = new ArrayList<>(unique.values());
            releases.sort(GameRelease::compareNewestFirst);
            return releases;
        } catch (RuntimeException error) {
            throw new IOException("Invalid version catalog", error);
        }
    }
}
