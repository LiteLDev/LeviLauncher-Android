package org.levimc.launcher.core.modcatalog;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.FileHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class ModCatalogInstaller {
    public interface Callback {
        void onProgress(int progress, boolean importing);
        void onSuccess();
        void onError(String message);
    }

    private static final String TAG = "ModCatalogInstaller";
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private final Context context;
    private final FileHandler fileHandler;

    public ModCatalogInstaller(Context context, FileHandler fileHandler) {
        this.context = context;
        this.fileHandler = fileHandler;
    }

    public void install(ModCatalog.CatalogMod mod, ModCatalog.CatalogRelease release,
                        ModCatalog.CatalogAsset asset, Callback callback) {
        EXECUTOR.execute(() -> {
            File downloadedFile = null;
            try {
                String extension = extensionFor(asset);
                if (extension == null) throw new IllegalArgumentException("Unsupported download file type");
                File downloadDir = new File(context.getCacheDir(), "external_mod_downloads");
                if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                    throw new IllegalStateException("Could not prepare download folder");
                }
                String assetName = safeName(asset.name);
                if (!assetName.toLowerCase(Locale.ROOT).endsWith(extension)) assetName += extension;
                downloadedFile = new File(downloadDir,
                        safeName(mod.id) + "-" + safeName(release.version) + "-" + assetName);
                // Through the GitHub proxy when it applies, then directly (as package downloads do).
                Exception failure = null;
                for (String url : GithubUrls.candidates(GithubUrls.currentProxy(), asset.downloadUrl)) {
                    try {
                        download(url, downloadedFile, callback);
                        if (!verifySha256(downloadedFile, asset.sha256)) {
                            throw new IllegalStateException(context.getString(R.string.external_mods_verification_failed));
                        }
                        failure = null;
                        break;
                    } catch (Exception error) {
                        failure = error;
                        if (!url.equals(asset.downloadUrl)) {
                            Log.w(TAG, "Download through the GitHub proxy failed, trying GitHub directly: " + url, error);
                            deliverProgress(callback, 0, false);
                        }
                    }
                }
                if (failure != null) throw failure;

                File resultFile = downloadedFile;
                context.getMainExecutor().execute(() -> importDownloadedFile(resultFile, mod, release, callback));
            } catch (Exception error) {
                if (downloadedFile != null) downloadedFile.delete();
                deliverError(callback, message(error));
            }
        });
    }

    private void download(String url, File target, Callback callback) throws Exception {
        Request request = new Request.Builder().url(url).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Download failed with HTTP " + response.code());
            }
            long total = response.body().contentLength();
            try (InputStream input = response.body().byteStream();
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[16384];
                long completed = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    completed += read;
                    if (total > 0) {
                        int progress = Math.min(85, (int) ((completed * 85L) / total));
                        deliverProgress(callback, progress, false);
                    }
                }
                output.getFD().sync();
            }
        }
    }

    private void importDownloadedFile(File file, ModCatalog.CatalogMod mod,
                                      ModCatalog.CatalogRelease release, Callback callback) {
        try {
            deliverProgress(callback, 88, true);
            Uri uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fileHandler.processCatalogModDirectly(intent, mod.name, mod.author,
                    release.version, release.minecraftVersions,
                    new FileHandler.FileOperationCallback() {
                        @Override
                        public void onSuccess(int processedFiles) {
                            file.delete();
                            if (callback != null) callback.onSuccess();
                        }

                        @Override
                        public void onError(String errorMessage) {
                            file.delete();
                            if (callback != null) callback.onError(errorMessage);
                        }

                        @Override
                        public void onProgressUpdate(int progress) {
                            deliverProgress(callback, 88 + progress * 12 / 100, true);
                        }
                    });
        } catch (Exception error) {
            file.delete();
            if (callback != null) callback.onError(message(error));
        }
    }

    private boolean verifySha256(File file, String expected) throws Exception {
        if (expected == null || expected.isEmpty()) return true;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return expected.equalsIgnoreCase(actual.toString());
    }

    private void deliverProgress(Callback callback, int progress, boolean importing) {
        if (callback == null) return;
        context.getMainExecutor().execute(() -> callback.onProgress(progress, importing));
    }

    private void deliverError(Callback callback, String message) {
        if (callback == null) return;
        context.getMainExecutor().execute(() -> callback.onError(message));
    }

    private String extensionFor(ModCatalog.CatalogAsset asset) {
        String name = asset == null ? null : asset.name;
        String extension = extensionForName(name);
        if (extension != null) return extension;
        String path = asset == null || asset.downloadUrl == null ? null : Uri.parse(asset.downloadUrl).getPath();
        return extensionForName(path);
    }

    private String extensionForName(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".levipack")) return ".levipack";
        if (lower.endsWith(".mcmod")) return ".mcmod";
        if (lower.endsWith(".zip")) return ".zip";
        if (lower.endsWith(".so")) return ".so";
        return null;
    }

    private String safeName(String value) {
        String safe = value == null ? "mod" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "mod" : safe;
    }

    private String message(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
