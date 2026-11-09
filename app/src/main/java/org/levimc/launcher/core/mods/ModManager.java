package org.levimc.launcher.core.mods;

import android.os.FileObserver;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.levimc.launcher.core.packages.InstalledPackages;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.mods.config.ModConfigManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ModManager {
    private static final String TAG = "ModManager";
    private static final String MANIFEST_FILE_NAME = "manifest.json";
    private static final String PRELOAD_NATIVE_TYPE = "preload-native";
    private static final String MINECRAFT_VERSIONS_FIELD = "minecraft_versions";
    private static final String DEFAULT_MOD_AUTHOR = "Unknown";
    private static final String DEFAULT_MOD_ICON = "";
    private static final String DEFAULT_MOD_VERSION = "1.0.0";
    private static final int COPY_BUFFER_SIZE = 8192;

    private static volatile ModManager instance;
    private static volatile boolean preloaderLoadAttempted;
    private static volatile boolean preloaderLoaded;
    private File modsDir;
    private File configFile;
    private final Map<String, Boolean> enabledMap = new LinkedHashMap<>();
    private final List<String> modOrder = new ArrayList<>();
    private FileObserver modDirObserver;
    private GameVersion currentVersion;
    private final MutableLiveData<Void> modsChangedLiveData = new MutableLiveData<>();
    private final Gson gson = new Gson();
    private final Gson manifestGson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private final ModConfigManager modConfigManager = new ModConfigManager();
    private List<ModDescriptor> descriptorCache = new ArrayList<>();
    private boolean descriptorCacheDirty = true;

    private static final class ModDescriptor {
        final String id;
        final String fileName;
        final String entryPath;
        final String displayName;
        final List<String> minecraftVersions;
        final String author;
        final String version;
        final String iconPath;
        final String manifestPath;
        final String description;
        final File modRoot;
        final File configDir;
        final int configFileCount;

        ModDescriptor(String id, String fileName, String entryPath, String displayName, List<String> minecraftVersions,
                      String author, String version, String iconPath, String manifestPath, String description,
                      File modRoot, File configDir, int configFileCount) {
            this.id = id;
            this.fileName = fileName;
            this.entryPath = entryPath;
            this.displayName = displayName;
            this.minecraftVersions = minecraftVersions;
            this.author = author;
            this.version = version;
            this.iconPath = iconPath;
            this.manifestPath = manifestPath;
            this.description = description;
            this.modRoot = modRoot;
            this.configDir = configDir;
            this.configFileCount = configFileCount;
        }
    }

    private ModManager() {}

    private static native boolean nativeLoadMod(String libPath, String modRootPath, Mod modObj);
    private static native void nativeEnableLoadedMods();
    private static native void nativeDisableAndUnloadLoadedMods();

    public static ModManager getInstance() {
        ModManager result = instance;
        if (result == null) {
            synchronized (ModManager.class) {
                result = instance;
                if (result == null) {
                    instance = result = new ModManager();
                }
            }
        }
        return result;
    }

    public static boolean ensurePreloaderLoaded() {
        if (preloaderLoaded) return true;
        if (preloaderLoadAttempted) return false;

        synchronized (ModManager.class) {
            if (preloaderLoaded) return true;
            if (preloaderLoadAttempted) return false;

            preloaderLoadAttempted = true;
            try {
                System.loadLibrary("preloader");
                preloaderLoaded = true;
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "Failed to load preloader in launcher process", e);
            }
            return preloaderLoaded;
        }
    }

    public static boolean initializeLoadedMod(String libPath, Mod mod) {
        return initializeLoadedMod(libPath, null, mod);
    }

    public static boolean initializeLoadedMod(String libPath, String modRootPath, Mod mod) {
        if (!ensurePreloaderLoaded()) {
            return false;
        }

        try {
            return nativeLoadMod(libPath, modRootPath, mod);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to invoke nativeLoadMod for " + libPath, e);
            return false;
        }
    }

    public static void enableLoadedMods() {
        if (!ensurePreloaderLoaded()) {
            return;
        }

        try {
            nativeEnableLoadedMods();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to invoke nativeEnableLoadedMods", e);
        }
    }

    public static void disableAndUnloadLoadedMods() {
        if (!ensurePreloaderLoaded()) {
            return;
        }

        try {
            nativeDisableAndUnloadLoadedMods();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to invoke nativeDisableAndUnloadLoadedMods", e);
        }
    }

    public synchronized void setCurrentVersion(GameVersion version) {
        if (Objects.equals(currentVersion, version)) return;
        stopFileObserver();
        currentVersion = version;
        invalidateDescriptorCache();

        if (version != null && version.modsDir != null) {
            modsDir = version.modsDir;
            modsDir.mkdirs();
            configFile = new File(modsDir, "mods_config.json");
            loadConfig();
            reconcileModsState();
            initFileObserver();
        } else {
            modsDir = null;
            configFile = null;
            enabledMap.clear();
            modOrder.clear();
            invalidateDescriptorCache();
        }
        notifyModsChanged();
    }

    public GameVersion getCurrentVersion() {
        return currentVersion;
    }

    /**
     * The manager of version's mods: the app's manager when it is bound to
     * that instance (same mods directory), else a detached manager of that
     * instance (no file observer; its change notifications reach nobody
     * else). Screens that work on another instance than the launcher's
     * selected one (the Installed tab of LeviMC Mods) read and toggle its mods
     * through it without rebinding the app's manager. There is one detached manager
     * per mods directory, so concurrent callers (switch toggles, list
     * reloads, load-order fixes on worker threads) are serialized by its
     * lock instead of overwriting each other's mods_config.json; each call
     * reads that file again to see later changes. Blocking (reads the
     * config): call it off the main thread.
     */
    public static ModManager forVersion(GameVersion version) {
        ModManager app = getInstance();
        synchronized (app) {
            GameVersion cur = app.currentVersion;
            if (cur == version || cur != null && version != null && cur.modsDir != null && version.modsDir != null
                    && cur.modsDir.getAbsoluteFile().equals(version.modsDir.getAbsoluteFile())) {
                return app;
            }
        }
        ModManager detached;
        if (version == null || version.modsDir == null) {
            detached = new ModManager();
        } else {
            String key = version.modsDir.getAbsolutePath();
            synchronized (DETACHED) {
                detached = DETACHED.get(key);
                if (detached == null) {
                    detached = new ModManager();
                    DETACHED.put(key, detached);
                }
            }
        }
        detached.bindDetached(version);
        return detached;
    }

    /** The {@link #forVersion} managers of instances the app's manager is not bound to, by mods directory; the last few. */
    private static final Map<String, ModManager> DETACHED = new java.util.LinkedHashMap<String, ModManager>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ModManager> eldest) {
            return size() > 4;
        }
    };

    /** The mods_config.json time of the last {@link #bindDetached} read; another time means another writer. */
    private long detachedConfigTime = -1;

    /** Whether this is the app's manager (bound by setCurrentVersion), not a {@link #forVersion} copy. */
    public boolean isAppManager() {
        return this == instance;
    }

    private synchronized void bindDetached(GameVersion version) {
        if (version != null && version == currentVersion && configFile != null && configFile.lastModified() == detachedConfigTime) {
            return;
        }
        currentVersion = version;
        invalidateDescriptorCache();
        if (version != null && version.modsDir != null && version.modsDir.isDirectory()) {
            modsDir = version.modsDir;
            configFile = new File(modsDir, "mods_config.json");
            loadConfig();
            reconcileModsState();
            detachedConfigTime = configFile.lastModified();
        } else {
            modsDir = null;
            configFile = null;
            enabledMap.clear();
            modOrder.clear();
        }
    }

    public synchronized List<Mod> getMods() {
        if (modsDir == null) return new ArrayList<>();

        reconcileModsState();
        List<ModDescriptor> descriptors = discoverMods();
        Map<String, ModDescriptor> descriptorMap = new LinkedHashMap<>();
        for (ModDescriptor descriptor : descriptors) {
            descriptorMap.put(descriptor.id, descriptor);
        }

        List<Mod> mods = new ArrayList<>();
        for (int i = 0; i < modOrder.size(); i++) {
            String modId = modOrder.get(i);
            ModDescriptor descriptor = descriptorMap.get(modId);
            if (descriptor == null) {
                continue;
            }
            mods.add(new Mod(
                    descriptor.id,
                    descriptor.fileName,
                    descriptor.entryPath,
                    descriptor.displayName,
                    descriptor.minecraftVersions,
                    descriptor.author,
                    descriptor.version,
                    descriptor.iconPath,
                    descriptor.manifestPath,
                    descriptor.description,
                    descriptor.modRoot.getAbsolutePath(),
                    descriptor.configDir.getAbsolutePath(),
                    descriptor.configFileCount > 0,
                    descriptor.configFileCount,
                    enabledMap.getOrDefault(modId, true),
                    i
            ));
        }

        return mods;
    }

    public synchronized void setModEnabled(String modId, boolean enabled) {
        if (modsDir == null) return;
        if (enabledMap.containsKey(modId)) {
            enabledMap.put(modId, enabled);
            saveConfig();
            notifyModsChanged();
        }
    }

    private void loadConfig() {
        enabledMap.clear();
        modOrder.clear();

        if (!configFile.exists()) {
            updateConfigFromDirectory();
            return;
        }

        try (FileReader reader = new FileReader(configFile)) {
            Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> configList = gson.fromJson(reader, type);

            if (configList != null) {
                for (Map<String, Object> item : configList) {
                    String name = (String) item.get("name");
                    Boolean enabled = (Boolean) item.get("enabled");
                    if (name != null && enabled != null) {
                        enabledMap.put(name, enabled);
                        modOrder.add(name);
                    }
                }
            } else {
                updateConfigFromDirectory();
            }
        } catch (Exception e) {
            updateConfigFromDirectory();
        }
    }

    private void updateConfigFromDirectory() {
        for (ModDescriptor descriptor : discoverMods()) {
            enabledMap.put(descriptor.id, true);
            modOrder.add(descriptor.id);
        }
        saveConfig();
    }

    private void saveConfig() {
        if (configFile == null) return;
        try (FileWriter writer = new FileWriter(configFile)) {
            List<Map<String, Object>> configList = new ArrayList<>();
            for (int i = 0; i < modOrder.size(); i++) {
                String modId = modOrder.get(i);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", modId);
                item.put("enabled", enabledMap.get(modId));
                item.put("order", i);
                configList.add(item);
            }
            gson.toJson(configList, writer);
        } catch (Exception ignored) {}
        if (detachedConfigTime >= 0) detachedConfigTime = configFile.lastModified();
    }

    private void initFileObserver() {
        if (modsDir == null) return;
        modDirObserver = new FileObserver(modsDir.getAbsolutePath(),
                FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_FROM | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                synchronized (ModManager.this) {
                    invalidateDescriptorCache();
                }
                notifyModsChanged();
            }
        };
        modDirObserver.startWatching();
    }

    private void stopFileObserver() {
        if (modDirObserver != null) {
            modDirObserver.stopWatching();
            modDirObserver = null;
        }
    }

    /**
     * Deletes the mod folder modId. A folder a package owns is refused (returns false): it is removed by
     * uninstalling its package ({@code InstalledPackages.removeFolder}). Also refused when the package
     * state cannot be read, since ownership cannot be told then.
     */
    public synchronized boolean deleteMod(String modId) {
        if (modsDir == null) return false;

        String pkgRefusal = pkgDeleteRefusal(modId);
        if (pkgRefusal != null) {
            Log.w(TAG, "Not deleting mod " + modId + ": " + pkgRefusal);
            return false;
        }

        File modDirectory = new File(modsDir, modId);
        boolean deleted = modDirectory.isDirectory() && deleteRecursively(modDirectory);

        if (deleted) {
            invalidateDescriptorCache();
            enabledMap.remove(modId);
            modOrder.remove(modId);
            saveConfig();
            notifyModsChanged();
        }
        return deleted;
    }

    /** Why the package manager forbids deleting mod folder modId, or null when it does not. */
    private String pkgDeleteRefusal(String modId) {
        File workspace = modsDir.getParentFile();
        if (workspace == null) {
            return null;
        }
        // While a package operation runs (or an interrupted one awaits recovery)
        // the state file does not list the folders it is placing yet.
        String busy = PackageService.get().busyReason(workspace);
        if (busy != null) {
            return busy;
        }
        try {
            InstalledPackages ws = InstalledPackages.read(workspace);
            InstalledPackages.Pkg owner = ws.ownerOf(modId);
            return owner == null ? null : "it belongs to the package " + owner.identity;
        } catch (PackageService.Failure e) {
            return "the package state cannot be read: " + PackageService.message(e);
        }
    }

    public synchronized void reorderMods(List<Mod> reorderedMods) {
        if (modsDir == null) return;

        modOrder.clear();
        for (Mod mod : reorderedMods) {
            modOrder.add(mod.getId());
        }
        saveConfig();
        notifyModsChanged();
    }

    private void notifyModsChanged() {
        modsChangedLiveData.postValue(null);
    }

    public MutableLiveData<Void> getModsChangedLiveData() {
        return modsChangedLiveData;
    }

    public synchronized void refreshMods() {
        invalidateDescriptorCache();
        notifyModsChanged();
    }

    public synchronized List<Mod> reloadMods() {
        invalidateDescriptorCache();
        return getMods();
    }

    private void invalidateDescriptorCache() {
        descriptorCacheDirty = true;
        descriptorCache = new ArrayList<>();
    }

    private void reconcileModsState() {
        boolean changed = migrateTopLevelSoMods();
        if (changed) invalidateDescriptorCache();
        changed |= syncConfigWithDiscoveredMods(discoverMods());
        if (changed) {
            saveConfig();
        }
    }

    private List<ModDescriptor> discoverMods() {
        if (!descriptorCacheDirty) return new ArrayList<>(descriptorCache);

        List<ModDescriptor> descriptors = new ArrayList<>();
        if (modsDir != null && modsDir.exists()) {
            File[] entries = modsDir.listFiles();
            if (entries != null) {
                Arrays.sort(entries, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File entry : entries) {
                    if (!entry.isDirectory()) continue;
                    ModDescriptor descriptor = parseDirectoryMod(entry);
                    if (descriptor != null) descriptors.add(descriptor);
                }
            }
        }

        descriptorCache = descriptors;
        descriptorCacheDirty = false;
        return new ArrayList<>(descriptorCache);
    }

    private boolean migrateTopLevelSoMods() {
        if (modsDir == null || !modsDir.exists()) {
            return false;
        }

        File[] looseLibraries = modsDir.listFiles(file ->
                file.isFile() && file.getName().toLowerCase().endsWith(".so"));
        if (looseLibraries == null || looseLibraries.length == 0) {
            return false;
        }

        // A loose library a package placed is the package's file. While a package
        // operation runs, the state does not list the files it is placing.
        String pkgBusy = PackageService.get().busyReason(modsDir.getParentFile());
        if (pkgBusy != null) {
            Log.i(TAG, "Not migrating loose mod libraries now: " + pkgBusy);
            return false;
        }
        InstalledPackages pkgWorkspace;
        try {
            pkgWorkspace = InstalledPackages.read(modsDir.getParentFile());
        } catch (PackageService.Failure e) {
            Log.w(TAG, "Not migrating loose mod libraries: the package state cannot be read: "
                    + PackageService.message(e));
            return false;
        }

        boolean changed = false;
        Arrays.sort(looseLibraries, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File looseLibrary : looseLibraries) {
            if (pkgWorkspace.ownsFile(modsDir.getName() + "/" + looseLibrary.getName())) {
                continue;
            }
            try {
                changed |= migrateLooseSoMod(looseLibrary);
            } catch (Exception e) {
                Log.e(TAG, "Failed to migrate loose mod library: " + looseLibrary.getAbsolutePath(), e);
            }
        }
        return changed;
    }

    private boolean migrateLooseSoMod(File looseLibrary) throws IOException {
        String fileName = looseLibrary.getName();
        String displayName = deriveDisplayNameFromLibrary(fileName);
        String targetId = buildTargetId(displayName, stripExtension(fileName));
        File targetDirectory = allocateModDirectory(targetId);
        File migratedLibrary = new File(targetDirectory, fileName);

        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new IOException("Failed to create mod directory: " + targetDirectory.getAbsolutePath());
        }

        moveFile(looseLibrary, migratedLibrary);
        writeManifest(targetDirectory, displayName, fileName);
        migrateConfigId(fileName, targetDirectory.getName());
        return true;
    }

    private void migrateConfigId(String oldId, String newId) {
        if (oldId.equals(newId)) {
            return;
        }
        if (!enabledMap.containsKey(oldId) || enabledMap.containsKey(newId)) {
            return;
        }

        Boolean enabled = enabledMap.remove(oldId);
        if (enabled == null) {
            return;
        }
        enabledMap.put(newId, enabled);

        int orderIndex = modOrder.indexOf(oldId);
        if (orderIndex >= 0) {
            modOrder.set(orderIndex, newId);
        }
    }

    private File allocateModDirectory(String preferredId) {
        File targetDirectory = new File(modsDir, preferredId);
        if (!targetDirectory.exists()) {
            return targetDirectory;
        }

        int index = 1;
        while (true) {
            File candidate = new File(modsDir, preferredId + "_" + index);
            if (!candidate.exists()) {
                return candidate;
            }
            index++;
        }
    }

    private void moveFile(File source, File target) throws IOException {
        if (source.renameTo(target)) {
            return;
        }

        copyFile(source, target);
        if (!source.delete()) {
            throw new IOException("Failed to delete original file after migration: " + source.getAbsolutePath());
        }
    }

    private void writeManifest(File modDirectory, String displayName, String entryFileName) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("type", PRELOAD_NATIVE_TYPE);
        manifest.addProperty("name", displayName);
        manifest.addProperty("entry", entryFileName);
        manifest.addProperty("author", DEFAULT_MOD_AUTHOR);
        manifest.addProperty("icon", DEFAULT_MOD_ICON);
        manifest.addProperty("version", DEFAULT_MOD_VERSION);

        File manifestFile = new File(modDirectory, MANIFEST_FILE_NAME);
        try (FileWriter writer = new FileWriter(manifestFile)) {
            manifestGson.toJson(manifest, writer);
        }
    }

    private void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private ModDescriptor parseDirectoryMod(File modDirectory) {
        File manifestFile = new File(modDirectory, MANIFEST_FILE_NAME);
        if (!manifestFile.isFile()) {
            return null;
        }

        try (FileReader reader = new FileReader(manifestFile)) {
            JsonObject manifest = gson.fromJson(reader, JsonObject.class);
            if (manifest == null || !manifest.has("type") || !manifest.get("type").isJsonPrimitive()) {
                return null;
            }

            String type = manifest.get("type").getAsString();
            if (!PRELOAD_NATIVE_TYPE.equals(type)) {
                return null;
            }

            String entryPath = manifest.has("entry") && manifest.get("entry").isJsonPrimitive()
                    ? sanitizeEntryPath(manifest.get("entry").getAsString())
                    : null;
            if (entryPath == null) {
                Log.w(TAG, "Ignoring mod with invalid entry path: " + modDirectory.getAbsolutePath());
                return null;
            }

            File entryFile = new File(modDirectory, entryPath);
            if (!entryFile.isFile() || !entryFile.getName().endsWith(".so")) {
                Log.w(TAG, "Ignoring mod with missing entry file: " + entryFile.getAbsolutePath());
                return null;
            }

            String displayName = manifest.has("name") && manifest.get("name").isJsonPrimitive()
                    ? manifest.get("name").getAsString()
                    : modDirectory.getName();
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = modDirectory.getName();
            }

            File configDir = new File(modDirectory, ModConfigManager.CONFIG_DIR_NAME);
            int configFileCount = modConfigManager.scanConfigFiles(modDirectory).size();
            File iconFile = resolveSafeIconFile(modDirectory, parseOptionalString(manifest, "icon", DEFAULT_MOD_ICON));
            String description = parseOptionalString(manifest, "description", null);

            return new ModDescriptor(
                    modDirectory.getName(),
                    entryFile.getName(),
                    entryPath,
                    displayName,
                    parseMinecraftVersions(manifest),
                    parseOptionalString(manifest, "author", DEFAULT_MOD_AUTHOR),
                    parseOptionalString(manifest, "version", DEFAULT_MOD_VERSION),
                    iconFile == null ? null : iconFile.getAbsolutePath(),
                    manifestFile.getAbsolutePath(),
                    description,
                    modDirectory,
                    configDir,
                    configFileCount
            );
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse mod manifest: " + manifestFile.getAbsolutePath(), e);
            return null;
        }
    }

    private List<String> parseMinecraftVersions(JsonObject manifest) {
        List<String> versions = new ArrayList<>();
        if (manifest == null || !manifest.has(MINECRAFT_VERSIONS_FIELD)) {
            return versions;
        }

        JsonElement value = manifest.get(MINECRAFT_VERSIONS_FIELD);
        if (value == null || value.isJsonNull()) {
            return versions;
        }

        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (JsonElement element : array) {
                addMinecraftVersionPattern(versions, element);
            }
            return versions;
        }

        addMinecraftVersionPattern(versions, value);
        return versions;
    }

    private String parseOptionalString(JsonObject manifest, String fieldName, String fallback) {
        if (manifest == null || !manifest.has(fieldName)) {
            return fallback;
        }

        JsonElement value = manifest.get(fieldName);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return fallback;
        }

        try {
            String text = value.getAsString();
            if (text == null || text.trim().isEmpty()) {
                return fallback;
            }
            return text.trim();
        } catch (UnsupportedOperationException | IllegalStateException ignored) {
            return fallback;
        }
    }

    private void addMinecraftVersionPattern(List<String> versions, JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return;
        }

        try {
            String pattern = element.getAsString();
            if (pattern != null && !pattern.trim().isEmpty()) {
                versions.add(pattern.trim());
            }
        } catch (UnsupportedOperationException | IllegalStateException ignored) {
        }
    }

    private boolean syncConfigWithDiscoveredMods(List<ModDescriptor> descriptors) {
        Map<String, ModDescriptor> discoveredMap = new LinkedHashMap<>();
        for (ModDescriptor descriptor : descriptors) {
            discoveredMap.put(descriptor.id, descriptor);
        }

        boolean changed = false;
        for (ModDescriptor descriptor : descriptors) {
            if (enabledMap.containsKey(descriptor.id)) {
                continue;
            }

            enabledMap.put(descriptor.id, true);
            modOrder.add(descriptor.id);
            changed = true;
        }

        List<String> staleKeys = new ArrayList<>();
        for (String configKey : enabledMap.keySet()) {
            if (!discoveredMap.containsKey(configKey)) {
                staleKeys.add(configKey);
            }
        }
        for (String staleKey : staleKeys) {
            enabledMap.remove(staleKey);
            changed = true;
        }

        List<String> normalizedOrder = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String modId : modOrder) {
            if (discoveredMap.containsKey(modId) && seen.add(modId)) {
                normalizedOrder.add(modId);
            } else {
                changed = true;
            }
        }
        for (ModDescriptor descriptor : descriptors) {
            if (seen.add(descriptor.id)) {
                normalizedOrder.add(descriptor.id);
                changed = true;
            }
        }

        if (!normalizedOrder.equals(modOrder)) {
            modOrder.clear();
            modOrder.addAll(normalizedOrder);
            changed = true;
        }

        return changed;
    }

    private boolean deleteRecursively(File file) {
        if (!file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    private String sanitizeEntryPath(String entryPath) {
        if (entryPath == null) {
            return null;
        }

        String normalized = entryPath.trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/")) {
            return null;
        }

        String[] parts = normalized.split("/");
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return null;
            }
        }

        return normalized;
    }

    private File resolveSafeIconFile(File modDirectory, String iconPath) {
        String safePath = sanitizeEntryPath(iconPath);
        if (safePath == null) {
            return null;
        }

        File iconFile = new File(modDirectory, safePath);
        try {
            String modRoot = modDirectory.getCanonicalPath();
            String iconCanonicalPath = iconFile.getCanonicalPath();
            if (!iconCanonicalPath.equals(modRoot) && !iconCanonicalPath.startsWith(modRoot + File.separator)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }

        if (!iconFile.isFile() || !isSupportedIconFile(iconFile)) {
            return null;
        }
        return iconFile;
    }

    private boolean isSupportedIconFile(File iconFile) {
        String name = iconFile.getName().toLowerCase();
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".webp");
    }

    private String deriveDisplayNameFromLibrary(String fileName) {
        String baseName = stripExtension(fileName);
        if (baseName.startsWith("lib") && baseName.length() > 3) {
            baseName = baseName.substring(3);
        }
        return baseName.isEmpty() ? "mod" : baseName;
    }

    private String buildTargetId(String preferredName, String fallbackName) {
        String candidate = sanitizeDirectoryName(preferredName);
        if (!candidate.isEmpty()) {
            return candidate;
        }

        candidate = sanitizeDirectoryName(fallbackName);
        if (!candidate.isEmpty()) {
            return candidate;
        }

        return "mod_" + System.currentTimeMillis();
    }

    private String sanitizeDirectoryName(String value) {
        if (value == null) {
            return "";
        }

        String sanitized = value.trim()
                .replace('/', '_')
                .replace('\\', '_')
                .replace(':', '_')
                .replace('*', '_')
                .replace('?', '_')
                .replace('"', '_')
                .replace('<', '_')
                .replace('>', '_')
                .replace('|', '_');

        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized.trim();
    }

    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }
}
