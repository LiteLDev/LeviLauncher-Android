package org.levimc.launcher.core.versions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

public class VersionProfileMetadataStore {
    public static final String PROFILE_FILE_NAME = "profile.json";

    private static final String LEGACY_VERSION_FILE = "version.txt";
    private static final String LEGACY_DISPLAY_NAME_FILE = "display_name.txt";
    private static final String LEGACY_VERSION_ISOLATION_FILE = "version_isolation.txt";
    private static final String LEGACY_LAUNCH_VERTICALLY_FILE = "launch_vertically.txt";

    private static final ConcurrentHashMap<String, Object> PROFILE_LOCKS = new ConcurrentHashMap<>();

    private final Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private static Object profileLock(File metadataDir) {
        String key = metadataDir == null ? "" : metadataDir.getAbsoluteFile().toPath().normalize().toString();
        return PROFILE_LOCKS.computeIfAbsent(key, ignored -> new Object());
    }

    public VersionProfileMetadata loadOrCreate(File metadataDir, Defaults defaults) throws IOException {
        synchronized (profileLock(metadataDir)) {
            return loadLocked(metadataDir, defaults);
        }
    }

    private VersionProfileMetadata loadLocked(File metadataDir, Defaults defaults) throws IOException {
        File profileFile = getProfileFile(metadataDir);
        JsonReadResult readResult = readJson(profileFile, defaults);
        if (readResult == null) {
            VersionProfileMetadata metadata = readLegacyOrDefault(metadataDir, defaults);
            save(metadataDir, metadata);
            return metadata;
        }

        VersionProfileMetadata metadata = readResult.metadata;
        boolean changed = applyMissingDefaults(metadata, defaults) || readResult.completedMissingFields;
        if (changed) {
            save(metadataDir, metadata);
        }
        return metadata;
    }

    public void save(File metadataDir, VersionProfileMetadata metadata) throws IOException {
        synchronized (profileLock(metadataDir)) {
            saveLocked(metadataDir, metadata);
        }
    }

    private void saveLocked(File metadataDir, VersionProfileMetadata metadata) throws IOException {
        if (metadataDir == null) {
            throw new IOException("metadataDir is null");
        }
        if (!metadataDir.exists() && !metadataDir.mkdirs()) {
            throw new IOException("Failed to create metadata directory: " + metadataDir);
        }
        metadata.schemaVersion = VersionProfileMetadata.CURRENT_SCHEMA_VERSION;
        if (metadata.profileId == null || metadata.profileId.trim().isEmpty()) {
            metadata.profileId = LauncherStorage.sanitizeProfileId(metadata.directoryName);
        }
        metadata.updatedAt = System.currentTimeMillis();

        File target = getProfileFile(metadataDir);
        File tmp = new File(metadataDir, PROFILE_FILE_NAME + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp, false);
             Writer writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            gson.toJson(metadata, writer);
            writer.flush();
            fos.getFD().sync();
        }

        replaceFile(tmp, target);
    }

    private void replaceFile(File source, File target) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public VersionProfileMetadata update(File metadataDir, Defaults defaults, Mutator mutator) throws IOException {
        synchronized (profileLock(metadataDir)) {
            VersionProfileMetadata metadata = loadOrCreate(metadataDir, defaults);
            mutator.mutate(metadata);
            save(metadataDir, metadata);
            return metadata;
        }
    }

    public File getProfileFile(File metadataDir) {
        return new File(metadataDir, PROFILE_FILE_NAME);
    }

    private JsonReadResult readJson(File profileFile, Defaults defaults) {
        if (profileFile == null || !profileFile.isFile() || profileFile.length() <= 0) {
            return null;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(profileFile), StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject object = element.getAsJsonObject();
            VersionProfileMetadata metadata = gson.fromJson(object, VersionProfileMetadata.class);
            boolean completedMissingFields = hasMissingSchemaFields(object);
            if (metadata != null) {
                if (!object.has("versionIsolation")) {
                    metadata.versionIsolation = defaults.versionIsolation;
                }
                if (!object.has("launchVertically")) {
                    metadata.launchVertically = defaults.launchVertically;
                }
                if (!object.has("modMenuEnabled") || object.get("modMenuEnabled").isJsonNull()) {
                    metadata.modMenuEnabled = true;
                }
                if (!object.has("installed")) {
                    metadata.installed = defaults.installed;
                }
            }
            return isUsable(metadata) ? new JsonReadResult(metadata, completedMissingFields) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private VersionProfileMetadata readLegacyOrDefault(File metadataDir, Defaults defaults) {
        VersionProfileMetadata metadata = fromDefaults(defaults);
        String legacyVersion = readTrimmed(new File(metadataDir, LEGACY_VERSION_FILE));
        if (!legacyVersion.isEmpty()) {
            metadata.versionName = legacyVersion;
        }
        String legacyDisplayName = readTrimmed(new File(metadataDir, LEGACY_DISPLAY_NAME_FILE));
        if (!legacyDisplayName.isEmpty()) {
            metadata.displayName = legacyDisplayName;
        }
        String legacyIsolation = readTrimmed(new File(metadataDir, LEGACY_VERSION_ISOLATION_FILE));
        if (!legacyIsolation.isEmpty()) {
            metadata.versionIsolation = Boolean.parseBoolean(legacyIsolation);
        }
        String legacyLaunchVertically = readTrimmed(new File(metadataDir, LEGACY_LAUNCH_VERTICALLY_FILE));
        if (!legacyLaunchVertically.isEmpty()) {
            metadata.launchVertically = Boolean.parseBoolean(legacyLaunchVertically);
        }
        return metadata;
    }

    private VersionProfileMetadata fromDefaults(Defaults defaults) {
        VersionProfileMetadata metadata = new VersionProfileMetadata();
        metadata.profileId = defaults.profileId;
        metadata.directoryName = defaults.directoryName;
        metadata.versionName = defaults.versionName;
        metadata.displayName = defaults.displayName;
        metadata.versionIsolation = defaults.versionIsolation;
        metadata.launchVertically = defaults.launchVertically;
        metadata.installed = defaults.installed;
        metadata.packageName = defaults.packageName;
        return metadata;
    }

    private boolean applyMissingDefaults(VersionProfileMetadata metadata, Defaults defaults) {
        boolean changed = false;
        if (metadata.schemaVersion != VersionProfileMetadata.CURRENT_SCHEMA_VERSION) {
            metadata.schemaVersion = VersionProfileMetadata.CURRENT_SCHEMA_VERSION;
            changed = true;
        }
        if (isBlank(metadata.profileId)) {
            metadata.profileId = defaults.profileId;
            changed = true;
        }
        if (isBlank(metadata.directoryName)) {
            metadata.directoryName = defaults.directoryName;
            changed = true;
        }
        if (isBlank(metadata.versionName)) {
            metadata.versionName = defaults.versionName;
            changed = true;
        }
        if (metadata.installed != defaults.installed && isBlank(metadata.packageName) && defaults.installed) {
            metadata.installed = true;
            changed = true;
        }
        if (isBlank(metadata.packageName) && defaults.packageName != null) {
            metadata.packageName = defaults.packageName;
            changed = true;
        }
        return changed;
    }

    private boolean isUsable(VersionProfileMetadata metadata) {
        return metadata != null;
    }

    private boolean hasMissingSchemaFields(JsonObject object) {
        return !object.has("schemaVersion")
                || !object.has("profileId")
                || !object.has("directoryName")
                || !object.has("versionName")
                || !object.has("displayName")
                || !object.has("versionIsolation")
                || !object.has("launchVertically")
                || !object.has("modMenuEnabled")
                || object.get("modMenuEnabled").isJsonNull()
                || !object.has("installed")
                || !object.has("packageName")
                || !object.has("updatedAt");
    }

    private String readTrimmed(File file) {
        if (file == null || !file.isFile() || file.length() <= 0) {
            return "";
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int len = in.read(data);
            if (len <= 0) return "";
            return new String(data, 0, len, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public interface Mutator {
        void mutate(VersionProfileMetadata metadata);
    }

    private static class JsonReadResult {
        final VersionProfileMetadata metadata;
        final boolean completedMissingFields;

        JsonReadResult(VersionProfileMetadata metadata, boolean completedMissingFields) {
            this.metadata = metadata;
            this.completedMissingFields = completedMissingFields;
        }
    }

    public static class Defaults {
        public final String profileId;
        public final String directoryName;
        public final String versionName;
        public final String displayName;
        public final boolean versionIsolation;
        public final boolean launchVertically;
        public final boolean installed;
        public final String packageName;

        private Defaults(
                String profileId,
                String directoryName,
                String versionName,
                String displayName,
                boolean versionIsolation,
                boolean launchVertically,
                boolean installed,
                String packageName
        ) {
            this.profileId = profileId;
            this.directoryName = directoryName;
            this.versionName = versionName;
            this.displayName = displayName;
            this.versionIsolation = versionIsolation;
            this.launchVertically = launchVertically;
            this.installed = installed;
            this.packageName = packageName;
        }

        public static Defaults custom(String directoryName, String versionName) {
            String safeDirectoryName = directoryName == null ? "default" : directoryName;
            String safeVersionName = isBlank(versionName) ? safeDirectoryName : versionName;
            return new Defaults(
                    LauncherStorage.sanitizeProfileId(safeDirectoryName),
                    safeDirectoryName,
                    safeVersionName,
                    null,
                    true,
                    false,
                    false,
                    null
            );
        }

        public static Defaults installed(String packageName, String versionName) {
            String safePackageName = isBlank(packageName)
                    ? LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID
                    : packageName;
            String safeVersionName = isBlank(versionName) ? safePackageName : versionName;
            return new Defaults(
                    LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID,
                    LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID,
                    safeVersionName,
                    null,
                    false,
                    false,
                    true,
                    safePackageName
            );
        }
    }
}
