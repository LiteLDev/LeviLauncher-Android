package org.levimc.launcher.core.content;

import android.content.Context;
import android.net.Uri;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorldManager {
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile File worldsDirectory;
    private volatile ConcurrentHashMap<String, CachedWorld> metadata = new ConcurrentHashMap<>();

    private static final class CachedWorld {
        final long levelModified;
        final long levelLength;
        final long nameModified;
        final long nameLength;
        final long directoryModified;
        final WorldItem item;
        CachedWorld(File directory, long levelModified, long levelLength,
                    long nameModified, long nameLength, long directoryModified) {
            this.levelModified = levelModified;
            this.levelLength = levelLength;
            this.nameModified = nameModified;
            this.nameLength = nameLength;
            this.directoryModified = directoryModified;
            item = new WorldItem(directory.getName(), directory);
            item.getStablePath();
        }
    }

    public interface WorldOperationCallback {
        void onSuccess(String message);
        void onError(String error);
        void onProgress(int progress);
    }

    public WorldManager(Context context) { this.context = context.getApplicationContext(); }

    public void setCurrentVersion(GameVersion version) {
        worldsDirectory = version != null && version.versionDir != null
                ? new File(LauncherStorage.getProfileGameDataDir(context, version.getStorageProfileId()), "minecraftWorlds") : null;
    }

    public void setWorldsDirectory(File directory) {
        if (!java.util.Objects.equals(worldsDirectory, directory)) metadata = new ConcurrentHashMap<>();
        worldsDirectory = directory;
    }

    public List<WorldItem> getWorlds() {
        File root = worldsDirectory;
        ConcurrentHashMap<String, CachedWorld> cache = metadata;
        List<WorldItem> result = new ArrayList<>();
        File[] files = root == null ? null : root.listFiles();
        if (files == null) return result;
        java.util.Set<String> live = new java.util.HashSet<>();
        for (File file : files) {
            if (!ContentFiles.visible(file) || !new File(file, "level.dat").isFile()) continue;
            live.add(file.getAbsolutePath());
            File level = new File(file, "level.dat");
            File name = new File(file, "levelname.txt");
            long lm = level.lastModified(), ll = level.length(), nm = name.lastModified(), nl = name.length(), dm = file.lastModified();
            CachedWorld cached = cache.compute(file.getAbsolutePath(), (key, old) -> old != null
                    && old.levelModified == lm && old.levelLength == ll && old.nameModified == nm
                    && old.nameLength == nl && old.directoryModified == dm ? old
                    : new CachedWorld(file, lm, ll, nm, nl, dm));
            if (cached.item.isValid()) result.add(new WorldItem(cached.item));
        }
        cache.entrySet().removeIf(entry -> java.util.Objects.equals(entry.getValue().item.getFile().getParentFile(), root)
                && !live.contains(entry.getKey()));
        return result;
    }

    int countWorlds(File directory) {
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File file : files) if (ContentFiles.visible(file) && new File(file, "level.dat").isFile()) count++;
        return count;
    }

    public void importWorld(Uri uri, WorldOperationCallback callback) {
        File directory = worldsDirectory;
        execute(callback, () -> {
            if (directory == null) throw new IOException("No version selected");
            ContentArchive.importUri(context, uri, directory, null, null, null, true, callback::onProgress);
            return "World imported successfully";
        });
    }

    public void exportWorld(WorldItem world, Uri uri, WorldOperationCallback callback) {
        execute(callback, () -> {
            try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IOException("Cannot create export file");
                ContentFiles.zip(world.getFile(), output);
            }
            return "World exported successfully";
        });
    }

    public void deleteWorld(WorldItem world, WorldOperationCallback callback) {
        execute(callback, () -> {
            createBackup(world);
            ContentFiles.deleteTree(world.getFile());
            metadata.remove(world.getFile().getAbsolutePath());
            return "World deleted successfully";
        });
    }

    public void backupWorld(WorldItem world, WorldOperationCallback callback) {
        execute(callback, () -> "Backup created: " + createBackup(world));
    }

    private String createBackup(WorldItem world) throws IOException {
        File directory = LauncherStorage.getWorldBackupsDir(context);
        ContentFiles.ensureDirectory(directory);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        String name = world.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        File target = ResourcePackManager.uniqueTarget(directory, name + '_' + timestamp + ".mcworld");
        File staged = File.createTempFile("world_backup_", ".tmp", directory);
        try {
            try (OutputStream output = new FileOutputStream(staged)) { ContentFiles.zip(world.getFile(), output); }
            Files.move(staged.toPath(), target.toPath());
            return target.getAbsolutePath();
        } finally {
            Files.deleteIfExists(staged.toPath());
        }
    }

    public void transferWorld(WorldItem world, File directory, WorldOperationCallback callback) {
        execute(callback, () -> {
            if (directory == null) throw new IOException("Target directory not available");
            File source = world.getFile();
            if (!source.isDirectory()) throw new IOException("Source world not found");
            if (source.getParentFile().getCanonicalFile().equals(directory.getCanonicalFile())) {
                throw new IOException("Content is already in this location");
            }
            ContentFiles.transfer(source, ResourcePackManager.uniqueTarget(directory, source.getName()));
            metadata.remove(source.getAbsolutePath());
            return "World transferred successfully";
        });
    }

    private interface Operation { String run() throws Exception; }

    private void execute(WorldOperationCallback callback, Operation operation) {
        if (executor.isShutdown()) {
            callback.onError("WorldManager has been shut down");
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
