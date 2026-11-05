package org.levimc.launcher.core.content;

import android.content.Context;
import android.net.Uri;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ResourcePackManager {
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile ConcurrentHashMap<String, CachedPack> metadata = new ConcurrentHashMap<>();
    private volatile File resourcePacksDirectory;
    private volatile File behaviorPacksDirectory;
    private volatile File skinPacksDirectory;

    private static final class CachedPack {
        final long modified;
        final long length;
        final ResourcePackItem item;
        CachedPack(File directory, ResourcePackItem.PackType type, long modified, long length) {
            this.modified = modified;
            this.length = length;
            item = new ResourcePackItem(directory.getName(), directory, type);
        }
    }

    public interface PackOperationCallback {
        void onSuccess(String message);
        void onError(String error);
        void onProgress(int progress);
    }

    public ResourcePackManager(Context context) { this.context = context.getApplicationContext(); }

    public void setCurrentVersion(GameVersion version) {
        File root = version != null && version.versionDir != null
                ? LauncherStorage.getProfileGameDataDir(context, version.getStorageProfileId()) : null;
        setPackDirectories(root == null ? null : new File(root, "resource_packs"),
                root == null ? null : new File(root, "behavior_packs"),
                root == null ? null : new File(root, "skin_packs"));
    }

    public void setPackDirectories(File resources, File behaviors, File skins) {
        if (!java.util.Objects.equals(resourcePacksDirectory, resources)
                || !java.util.Objects.equals(behaviorPacksDirectory, behaviors)
                || !java.util.Objects.equals(skinPacksDirectory, skins)) metadata = new ConcurrentHashMap<>();
        resourcePacksDirectory = resources;
        behaviorPacksDirectory = behaviors;
        skinPacksDirectory = skins;
    }

    public List<ResourcePackItem> getResourcePacks() { return getPacks(resourcePacksDirectory, ResourcePackItem.PackType.RESOURCE_PACK); }
    public List<ResourcePackItem> getBehaviorPacks() { return getPacks(behaviorPacksDirectory, ResourcePackItem.PackType.BEHAVIOR_PACK); }
    public List<ResourcePackItem> getSkinPacks() { return getPacks(skinPacksDirectory, ResourcePackItem.PackType.SKIN_PACK); }

    private ResourcePackItem metadata(File directory, ResourcePackItem.PackType type, ConcurrentHashMap<String, CachedPack> cache) {
        File manifest = new File(directory, "manifest.json");
        long modified = manifest.lastModified();
        long length = manifest.length();
        String key = type.name() + '|' + directory.getAbsolutePath();
        return cache.compute(key, (path, cached) -> cached != null
                && cached.modified == modified && cached.length == length ? cached
                : new CachedPack(directory, type, modified, length)).item;
    }

    private List<ResourcePackItem> getPacks(File directory, ResourcePackItem.PackType type) {
        List<ResourcePackItem> result = new ArrayList<>();
        ConcurrentHashMap<String, CachedPack> cache = metadata;
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) return result;
        java.util.Set<String> live = new java.util.HashSet<>();
        for (File file : files) {
            if (!ContentFiles.visible(file) || !file.isDirectory()) continue;
            live.add(file.getAbsolutePath());
            ResourcePackItem cached = metadata(file, type, cache);
            if (!cached.isValid()) continue;
            cached.prepareDisplayStrings();
            result.add(new ResourcePackItem(cached));
        }
        prune(cache, directory, live);
        return result;
    }

    private void prune(ConcurrentHashMap<String, CachedPack> cache, File root, java.util.Set<String> live) {
        cache.entrySet().removeIf(entry -> java.util.Objects.equals(entry.getValue().item.getFile().getParentFile(), root)
                && !live.contains(entry.getValue().item.getFile().getAbsolutePath()));
    }

    int countPacks(File directory, ResourcePackItem.PackType type) {
        ConcurrentHashMap<String, CachedPack> cache = metadata;
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) return 0;
        int count = 0;
        java.util.Set<String> live = new java.util.HashSet<>();
        for (File file : files) {
            if (!ContentFiles.visible(file) || !file.isDirectory()) continue;
            live.add(file.getAbsolutePath());
            if (metadata(file, type, cache).isValid()) count++;
        }
        prune(cache, directory, live);
        return count;
    }

    public void importPack(Uri uri, PackOperationCallback callback) {
        File resources = resourcePacksDirectory;
        File behaviors = behaviorPacksDirectory;
        File skins = skinPacksDirectory;
        execute(callback, () -> {
            if (resources == null && behaviors == null && skins == null) throw new IOException("No version selected");
            ContentArchive.Result result = ContentArchive.importUri(context, uri, null, resources,
                    behaviors, skins, false, callback::onProgress);
            String message = result.total() == 1 ? "Pack imported successfully"
                    : "Imported: " + result.resources + " resource pack(s), " + result.behaviors
                        + " behavior pack(s), " + result.skins + " skin pack(s)";
            if (!result.warnings.isEmpty()) message += "\nErrors:\n" + String.join("\n", result.warnings);
            return message;
        });
    }

    public void deletePack(ResourcePackItem pack, PackOperationCallback callback) {
        execute(callback, () -> {
            ContentFiles.deleteTree(pack.getFile());
            forget(pack.getFile());
            return "Pack deleted successfully";
        });
    }

    public void exportPack(ResourcePackItem pack, Uri uri, PackOperationCallback callback) {
        execute(callback, () -> {
            try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IOException("Cannot create export file");
                ContentFiles.zip(pack.getFile(), output);
            }
            return "Pack exported successfully";
        });
    }

    public void transferPack(ResourcePackItem pack, File targetDirectory, PackOperationCallback callback) {
        execute(callback, () -> {
            if (targetDirectory == null) throw new IOException("Target directory not available");
            File source = pack.getFile();
            if (source == null || !source.isDirectory()) throw new IOException("Source pack not found");
            if (source.getParentFile().getCanonicalFile().equals(targetDirectory.getCanonicalFile())) {
                throw new IOException("Content is already in this location");
            }
            ContentFiles.transfer(source, uniqueTarget(targetDirectory, source.getName()));
            forget(source);
            return "Pack transferred successfully";
        });
    }

    private void forget(File directory) {
        metadata.entrySet().removeIf(entry -> entry.getValue().item.getFile().equals(directory));
    }

    static File uniqueTarget(File directory, String name) {
        File target = new File(directory, name);
        for (int suffix = 1; target.exists(); suffix++) target = new File(directory, name + '_' + suffix);
        return target;
    }

    private interface Operation { String run() throws Exception; }

    private void execute(PackOperationCallback callback, Operation operation) {
        if (executor.isShutdown()) {
            callback.onError("ResourcePackManager has been shut down");
            return;
        }
        executor.execute(() -> {
            try {
                callback.onProgress(0);
                String message = operation.run();
                callback.onProgress(100);
                callback.onSuccess(message);
            } catch (Exception error) {
                callback.onError(error.getMessage() == null ? "Content operation failed" : error.getMessage());
            }
        });
    }

    public void shutdown() { executor.shutdown(); }
}
