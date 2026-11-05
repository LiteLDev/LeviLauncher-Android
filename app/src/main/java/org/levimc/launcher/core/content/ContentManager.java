package org.levimc.launcher.core.content;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.levimc.launcher.core.versions.GameVersion;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ContentManager {
    private static ContentManager instance;
    
    private final Context context;
    private final WorldManager worldManager;
    private final ResourcePackManager resourcePackManager;
    private final ScreenshotManager screenshotManager;
    private final ServerManager serverManager;
    private final ExecutorService refreshExecutor;
    private final ExecutorService countExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService operationExecutor = Executors.newSingleThreadExecutor();
    private final android.content.SharedPreferences countPreferences;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicLong storageGeneration = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLongArray refreshSerials = new java.util.concurrent.atomic.AtomicLongArray(7);
    private final java.util.concurrent.atomic.AtomicBoolean[] refreshing = new java.util.concurrent.atomic.AtomicBoolean[7];
    private volatile File[] locations = new File[6];
    private final MutableLiveData<ContentCounts> countsLiveData = new MutableLiveData<>();

    public static final class ContentCounts {
        public final int worlds;
        public final int resourcePacks;
        public final int skinPacks;
        ContentCounts(int worlds, int resourcePacks, int skinPacks) {
            this.worlds = worlds;
            this.resourcePacks = resourcePacks;
            this.skinPacks = skinPacks;
        }
    }
    
    private GameVersion currentVersion;
    private final MutableLiveData<List<WorldItem>> worldsLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ResourcePackItem>> resourcePacksLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ResourcePackItem>> behaviorPacksLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ResourcePackItem>> skinPacksLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ScreenshotItem>> screenshotsLiveData = new MutableLiveData<>();
    private final MutableLiveData<List<ServerItem>> serversLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> statusLiveData = new MutableLiveData<>();

    private ContentManager(Context context) {
        this.context = context.getApplicationContext();
        countPreferences = this.context.getSharedPreferences("content_counts", Context.MODE_PRIVATE);
        this.worldManager = new WorldManager(this.context);
        this.resourcePackManager = new ResourcePackManager(this.context);
        this.screenshotManager = new ScreenshotManager();
        this.serverManager = new ServerManager();
        this.refreshExecutor = Executors.newFixedThreadPool(2);
        for (int i = 0; i < refreshing.length; i++) refreshing[i] = new java.util.concurrent.atomic.AtomicBoolean();
    }

    public static synchronized ContentManager getInstance(Context context) {
        if (instance == null) {
            instance = new ContentManager(context);
        }
        return instance;
    }

    public void setCurrentVersion(GameVersion version) {
        this.currentVersion = version;
        File root = version != null && version.versionDir != null
                ? org.levimc.launcher.util.LauncherStorage.getProfileGameDataDir(context, version.getStorageProfileId()) : null;
        File[] previous = locations;
        configureStorageDirectories(root == null ? null : new File(root, "minecraftWorlds"),
                root == null ? null : new File(root, "resource_packs"),
                root == null ? null : new File(root, "behavior_packs"),
                root == null ? null : new File(root, "skin_packs"), previous[4], previous[5]);
        refreshContent();
    }

    public void setStorageDirectories(File worldsDir, File resourcePacksDir, File behaviorPacksDir, File skinPacksDir, File screenshotsDir, File minecraftPeDir) {
        configureStorageDirectories(worldsDir, resourcePacksDir, behaviorPacksDir, skinPacksDir, screenshotsDir, minecraftPeDir);
        refreshContent();
    }

    public synchronized void configureStorageDirectories(File worldsDir, File resourcePacksDir, File behaviorPacksDir, File skinPacksDir, File screenshotsDir, File minecraftPeDir) {
        File[] selected = {worldsDir, resourcePacksDir, behaviorPacksDir, skinPacksDir, screenshotsDir, minecraftPeDir};
        if (java.util.Arrays.equals(locations, selected)) return;
        storageGeneration.incrementAndGet();
        locations = selected;
        worldManager.setWorldsDirectory(worldsDir);
        resourcePackManager.setPackDirectories(resourcePacksDir, behaviorPacksDir, skinPacksDir);
        screenshotManager.setScreenshotsDirectory(screenshotsDir);
        serverManager.setMinecraftPeDirectory(minecraftPeDir);
        long generation = storageGeneration.incrementAndGet();
        Runnable clear = () -> {
            if (storageGeneration.get() != generation) return;
            worldsLiveData.setValue(java.util.Collections.emptyList());
            resourcePacksLiveData.setValue(java.util.Collections.emptyList());
            behaviorPacksLiveData.setValue(java.util.Collections.emptyList());
            skinPacksLiveData.setValue(java.util.Collections.emptyList());
            screenshotsLiveData.setValue(java.util.Collections.emptyList());
            serversLiveData.setValue(java.util.Collections.emptyList());
            String key = countKey(selected);
            countsLiveData.setValue(new ContentCounts(countPreferences.getInt(key + "w", 0),
                    countPreferences.getInt(key + "r", 0), countPreferences.getInt(key + "s", 0)));
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) clear.run();
        else mainHandler.post(clear);
    }

    public void refreshContent() {
        refreshWorlds();
        refreshResourcePacks();
        refreshBehaviorPacks();
        refreshSkinPacks();
        refreshScreenshots();
        refreshServers();
    }

    private <T> void requestRefresh(int index, ExecutorService executor,
                                    java.util.function.Supplier<T> loader,
                                    java.util.function.Consumer<T> publish) {
        refreshSerials.incrementAndGet(index);
        startRefresh(index, executor, loader, publish);
    }

    private <T> void startRefresh(int index, ExecutorService executor,
                                  java.util.function.Supplier<T> loader,
                                  java.util.function.Consumer<T> publish) {
        if (executor.isShutdown() || !refreshing[index].compareAndSet(false, true)) return;
        executor.execute(() -> {
            long processed = -1;
            try {
                do {
                    long serial = refreshSerials.get(index);
                    long generation = storageGeneration.get();
                    T result = loader.get();
                    mainHandler.post(() -> {
                        if ((generation & 1) == 0 && storageGeneration.get() == generation && refreshSerials.get(index) == serial) publish.accept(result);
                    });
                    processed = serial;
                } while (refreshSerials.get(index) != processed);
            } finally {
                refreshing[index].set(false);
                if (processed >= 0 && refreshSerials.get(index) != processed) startRefresh(index, executor, loader, publish);
            }
        });
    }

    public LiveData<ContentCounts> getCountsLiveData() { return countsLiveData; }

    private String countKey(File[] roots) {
        String value = String.valueOf(roots[0]) + '|' + roots[1] + '|' + roots[3];
        return java.util.UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    public void refreshCounts() {
        requestRefresh(6, countExecutor, () -> {
            File[] root = locations;
            ContentCounts counts = new ContentCounts(worldManager.countWorlds(root[0]),
                    resourcePackManager.countPacks(root[1], ResourcePackItem.PackType.RESOURCE_PACK),
                    resourcePackManager.countPacks(root[3], ResourcePackItem.PackType.SKIN_PACK));
            String key = countKey(root);
            countPreferences.edit().putInt(key + "w", counts.worlds)
                    .putInt(key + "r", counts.resourcePacks).putInt(key + "s", counts.skinPacks).apply();
            return counts;
        }, countsLiveData::setValue);
    }

    public void refreshWorlds() {
        requestRefresh(0, refreshExecutor, worldManager::getWorlds, worldsLiveData::setValue);
        refreshCounts();
    }

    public void refreshResourcePacks() {
        requestRefresh(1, refreshExecutor, resourcePackManager::getResourcePacks, resourcePacksLiveData::setValue);
        refreshCounts();
    }

    public void refreshBehaviorPacks() {
        requestRefresh(2, refreshExecutor, resourcePackManager::getBehaviorPacks, behaviorPacksLiveData::setValue);
    }

    public void refreshSkinPacks() {
        requestRefresh(3, refreshExecutor, resourcePackManager::getSkinPacks, skinPacksLiveData::setValue);
        refreshCounts();
    }

    public void refreshScreenshots() {
        requestRefresh(4, refreshExecutor, screenshotManager::getScreenshots, screenshotsLiveData::setValue);
    }

    public void refreshServers() {
        requestRefresh(5, refreshExecutor, serverManager::getServers, serversLiveData::setValue);
    }

    private void refreshPack(ResourcePackItem pack) {
        File parent = pack.getFile().getParentFile();
        File[] root = locations;
        if (java.util.Objects.equals(parent, root[1])) refreshResourcePacks();
        else if (java.util.Objects.equals(parent, root[2])) refreshBehaviorPacks();
        else if (java.util.Objects.equals(parent, root[3])) refreshSkinPacks();
        else { refreshResourcePacks(); refreshBehaviorPacks(); refreshSkinPacks(); }
    }

    public LiveData<List<WorldItem>> getWorldsLiveData() {
        return worldsLiveData;
    }

    public LiveData<List<ResourcePackItem>> getResourcePacksLiveData() {
        return resourcePacksLiveData;
    }

    public LiveData<List<ResourcePackItem>> getBehaviorPacksLiveData() {
        return behaviorPacksLiveData;
    }

    public LiveData<List<ResourcePackItem>> getSkinPacksLiveData() {
        return skinPacksLiveData;
    }

    public LiveData<List<ScreenshotItem>> getScreenshotsLiveData() {
        return screenshotsLiveData;
    }

    public LiveData<List<ServerItem>> getServersLiveData() {
        return serversLiveData;
    }

    public LiveData<String> getStatusLiveData() {
        return statusLiveData;
    }

    public void setStatus(String status) {
        statusLiveData.postValue(status);
    }

    public interface ContentOperationCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public void deleteScreenshot(ScreenshotItem screenshot, ContentOperationCallback callback) {
        setStatus("Deleting screenshot...");
        operationExecutor.execute(() -> {
            boolean success = screenshotManager.deleteScreenshot(screenshot);
            if (success) {
                refreshScreenshots();
                setStatus("Screenshot deleted");
                if (callback != null) callback.onSuccess("Screenshot deleted");
            } else {
                setStatus("Failed to delete screenshot");
                if (callback != null) callback.onError("Failed to delete screenshot");
            }
        });
    }

    public void deleteServer(ServerItem server, ContentOperationCallback callback) {
        File serverFile = serverManager.getServerListFile();
        setStatus("Deleting server...");
        operationExecutor.execute(() -> {
            boolean success = serverManager.deleteServer(server, serverFile);
            if (success) {
                refreshServers();
                setStatus("Server deleted");
                if (callback != null) callback.onSuccess("Server deleted");
            } else {
                setStatus("Failed to delete server");
                if (callback != null) callback.onError("Failed to delete server");
            }
        });
    }

    public void addServer(ServerItem server, ContentOperationCallback callback) {
        File serverFile = serverManager.getServerListFile();
        setStatus("Adding server...");
        operationExecutor.execute(() -> {
            boolean success = serverManager.addServer(server, serverFile);
            if (success) {
                refreshServers();
                setStatus("Server added");
                if (callback != null) callback.onSuccess("Server added");
            } else {
                setStatus("Failed to add server");
                if (callback != null) callback.onError("Failed to add server");
            }
        });
    }

    public void shutdown() {
        worldManager.shutdown();
        resourcePackManager.shutdown();
        refreshExecutor.shutdown();
        countExecutor.shutdown();
        operationExecutor.shutdown();
    }
    
    public void importWorld(android.net.Uri worldUri, WorldManager.WorldOperationCallback callback) {
        setStatus("Importing world...");
        worldManager.importWorld(worldUri, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                refreshWorlds();
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Import failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Importing world... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void exportWorld(WorldItem world, android.net.Uri exportUri, WorldManager.WorldOperationCallback callback) {
        setStatus("Exporting world...");
        worldManager.exportWorld(world, exportUri, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Export failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Exporting world... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void deleteWorld(WorldItem world, WorldManager.WorldOperationCallback callback) {
        deleteWorld(world, true, callback);
    }

    public void deleteWorld(WorldItem world, boolean refreshAfter, WorldManager.WorldOperationCallback callback) {
        setStatus("Deleting world...");
        worldManager.deleteWorld(world, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                if (refreshAfter) refreshWorlds();
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Delete failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void backupWorld(WorldItem world, WorldManager.WorldOperationCallback callback) {
        setStatus("Creating backup...");
        worldManager.backupWorld(world, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Backup failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Creating backup... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void importResourcePack(android.net.Uri packUri, ResourcePackManager.PackOperationCallback callback) {
        setStatus("Importing resource pack...");
        resourcePackManager.importPack(packUri, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                refreshResourcePacks();
                refreshBehaviorPacks();
                refreshSkinPacks();
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Import failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Importing resource pack... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void deleteResourcePack(ResourcePackItem pack, ResourcePackManager.PackOperationCallback callback) {
        deleteResourcePack(pack, true, callback);
    }

    public void deleteResourcePack(ResourcePackItem pack, boolean refreshAfter, ResourcePackManager.PackOperationCallback callback) {
        setStatus("Deleting resource pack...");
        resourcePackManager.deletePack(pack, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                if (refreshAfter) refreshPack(pack);
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Delete failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void exportResourcePack(ResourcePackItem pack, android.net.Uri exportUri, ResourcePackManager.PackOperationCallback callback) {
        setStatus("Exporting pack...");
        resourcePackManager.exportPack(pack, exportUri, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Export failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void transferWorld(WorldItem world, java.io.File targetDirectory, WorldManager.WorldOperationCallback callback) {
        transferWorld(world, targetDirectory, true, callback);
    }

    public void transferWorld(WorldItem world, java.io.File targetDirectory, boolean refreshAfter, WorldManager.WorldOperationCallback callback) {
        setStatus("Transferring world...");
        worldManager.transferWorld(world, targetDirectory, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                if (refreshAfter) refreshWorlds();
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Transfer failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Transferring world... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }

    public void transferResourcePack(ResourcePackItem pack, java.io.File targetDirectory, ResourcePackManager.PackOperationCallback callback) {
        transferResourcePack(pack, targetDirectory, true, callback);
    }

    public void transferResourcePack(ResourcePackItem pack, java.io.File targetDirectory, boolean refreshAfter, ResourcePackManager.PackOperationCallback callback) {
        setStatus("Transferring pack...");
        resourcePackManager.transferPack(pack, targetDirectory, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                if (refreshAfter) refreshPack(pack);
                setStatus(message);
                if (callback != null) callback.onSuccess(message);
            }

            @Override
            public void onError(String error) {
                setStatus("Transfer failed: " + error);
                if (callback != null) callback.onError(error);
            }

            @Override
            public void onProgress(int progress) {
                setStatus("Transferring pack... " + progress + "%");
                if (callback != null) callback.onProgress(progress);
            }
        });
    }
}
