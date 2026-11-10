package org.levimc.launcher.core.modcatalog;

import android.content.Context;
import android.net.Uri;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class ModCatalogRepository {
    public interface Callback {
        void onResult(ModCatalog catalog, Throwable error);
    }

    public static final String REMOTE_URL = "https://qycottage.github.io/LeviModHub/catalog.json";
    private static final String ASSET_PATH = "launcher/mod_catalog.json";
    private static final String CACHE_FILE = "launcher_mod_catalog.json";
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Gson GSON = new Gson();
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private ModCatalogRepository() {
    }

    public static void loadCached(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> deliver(context, callback, loadLocal(appContext), null));
    }

    public static void refresh(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(REMOTE_URL)
                        .header("Cache-Control", "no-cache")
                        .build();
                try (Response response = HTTP.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IllegalStateException("Catalog request failed with HTTP " + response.code());
                    }
                    String json = response.body().string();
                    ModCatalog catalog = parse(json);
                    writeCache(appContext, json);
                    deliver(context, callback, catalog, null);
                }
            } catch (Throwable error) {
                deliver(context, callback, loadLocal(appContext), error);
            }
        });
    }

    private static ModCatalog loadLocal(Context context) {
        File cache = new File(context.getFilesDir(), CACHE_FILE);
        if (cache.isFile()) {
            try (InputStream input = new FileInputStream(cache)) {
                return parse(readUtf8(input));
            } catch (Exception ignored) {
            }
        }
        try (InputStream input = context.getAssets().open(ASSET_PATH)) {
            return parse(readUtf8(input));
        } catch (Exception ignored) {
            ModCatalog catalog = new ModCatalog();
            catalog.schemaVersion = 2;
            return catalog;
        }
    }

    private static ModCatalog parse(String json) {
        ModCatalog catalog = GSON.fromJson(json, ModCatalog.class);
        if (catalog == null || (catalog.schemaVersion != 1 && catalog.schemaVersion != 2)) {
            throw new IllegalArgumentException("Unsupported mod catalog schema");
        }
        int schemaVersion = catalog.schemaVersion;
        if (catalog.mods == null) catalog.mods = new ArrayList<>();
        catalog.mods.removeIf(mod -> !isValidMod(mod));
        for (ModCatalog.CatalogMod mod : catalog.mods) {
            List<ModCatalog.CatalogRelease> releases = new ArrayList<>();
            for (ModCatalog.CatalogRelease release : mod.releases) {
                if (normalizeRelease(release, schemaVersion)) releases.add(release);
            }
            releases.sort(Comparator.comparing(
                    (ModCatalog.CatalogRelease release) -> value(release.publishedAt)).reversed());
            mod.releases = releases;
            if (!isHttps(mod.iconUrl)) mod.iconUrl = "";
            if (!isHttps(mod.homepageUrl)) mod.homepageUrl = "";
            if (mod.tags == null) mod.tags = new ArrayList<>();
        }
        catalog.mods.removeIf(mod -> mod.releases.isEmpty());
        catalog.schemaVersion = 2;
        return catalog;
    }

    private static boolean isValidMod(ModCatalog.CatalogMod mod) {
        return mod != null
                && !TextUtils.isEmpty(mod.id)
                && ID_PATTERN.matcher(mod.id).matches()
                && !TextUtils.isEmpty(mod.name)
                && !TextUtils.isEmpty(mod.author)
                && !TextUtils.isEmpty(mod.description)
                && mod.releases != null;
    }

    private static boolean normalizeRelease(ModCatalog.CatalogRelease release, int schemaVersion) {
        if (release == null
                || TextUtils.isEmpty(release.version)
                || release.minecraftVersions == null
                || release.minecraftVersions.isEmpty()) {
            return false;
        }
        String type = value(release.downloadType).toLowerCase(Locale.ROOT);
        if (!"direct".equals(type) && !"browser".equals(type) && !"ad".equals(type)) {
            return false;
        }
        release.downloadType = type;
        if (release.assets == null) release.assets = new ArrayList<>();
        if ("direct".equals(type)) {
            if (release.assets.isEmpty() && isHttps(release.downloadUrl)) {
                ModCatalog.CatalogAsset legacyAsset = new ModCatalog.CatalogAsset();
                legacyAsset.name = fileNameFromUrl(release.downloadUrl);
                legacyAsset.downloadUrl = release.downloadUrl;
                release.assets.add(legacyAsset);
            }
            release.assets.removeIf(asset -> !isValidAsset(asset));
            release.downloadUrl = "";
            return !release.assets.isEmpty();
        }
        release.assets.clear();
        return isHttps(release.downloadUrl);
    }

    private static boolean isValidAsset(ModCatalog.CatalogAsset asset) {
        if (asset == null || TextUtils.isEmpty(asset.downloadUrl) || !isHttps(asset.downloadUrl)) {
            return false;
        }
        if (TextUtils.isEmpty(asset.name)) asset.name = fileNameFromUrl(asset.downloadUrl);
        String lowerName = value(asset.name).toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".levipack") && !lowerName.endsWith(".mcmod")
                && !lowerName.endsWith(".zip") && !lowerName.endsWith(".so")) {
            return false;
        }
        if (asset.size < 0) return false;
        if (!TextUtils.isEmpty(asset.sha256)) {
            asset.sha256 = asset.sha256.trim().toLowerCase(Locale.ROOT);
            if (!SHA256_PATTERN.matcher(asset.sha256).matches()) return false;
        }
        return true;
    }

    private static String fileNameFromUrl(String url) {
        try {
            String segment = Uri.parse(url).getLastPathSegment();
            return TextUtils.isEmpty(segment) ? "download" : segment;
        } catch (Exception ignored) {
            return "download";
        }
    }

    private static boolean isHttps(String value) {
        if (TextUtils.isEmpty(value)) return false;
        try {
            return "https".equalsIgnoreCase(Uri.parse(value).getScheme());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
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
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deliver(Context context, Callback callback, ModCatalog catalog, Throwable error) {
        if (callback == null) return;
        if (catalog != null) catalog.githubProxy = GithubUrls.currentProxy();
        context.getMainExecutor().execute(() -> callback.onResult(catalog, error));
    }
}
