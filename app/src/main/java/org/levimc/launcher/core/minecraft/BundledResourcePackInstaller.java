package org.levimc.launcher.core.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.levimc.launcher.core.mods.Mod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BundledResourcePackInstaller {
    private static final String RESOURCE_ROOT = "resources/minecraft_resource_packs";
    private static final String BEHAVIOR_ROOT = "resources/minecraft_behavior_packs";
    private static final String MANAGED_STATE = "levilauncher_bundled_mod_packs.json";
    private static final String LEGACY_STATE = "levilauncher_bundled_resource_packs.json";
    private static final String GLOBAL_RESOURCE_PACKS = "global_resource_packs.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BundledResourcePackInstaller() {}

    static int sync(List<Mod> mods, Set<String> loadedModIds, File... gameDataDirs) throws IOException {
        List<Pack> packs = discover(mods, loadedModIds);
        for (Pack pack : packs) pack.sourceFingerprint = fingerprint(pack.source);
        IOException failure = null;
        Set<String> visited = new HashSet<>();
        for (File gameDataDir : gameDataDirs) {
            if (gameDataDir == null || !visited.add(gameDataDir.getCanonicalPath())) continue;
            try {
                syncRoot(gameDataDir, packs);
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
        return packs.size();
    }

    private static List<Pack> discover(List<Mod> mods, Set<String> loadedModIds) {
        Map<String, Pack> packs = new LinkedHashMap<>();
        if (mods == null || loadedModIds == null) return new ArrayList<>();
        for (Mod mod : mods) {
            if (mod == null || !mod.isEnabled() || !loadedModIds.contains(mod.getId()) || mod.getModRootPath() == null) continue;
            discoverRoot(packs, mod.getId(), new File(mod.getModRootPath(), RESOURCE_ROOT), PackType.RESOURCE);
            discoverRoot(packs, mod.getId(), new File(mod.getModRootPath(), BEHAVIOR_ROOT), PackType.BEHAVIOR);
        }
        return new ArrayList<>(packs.values());
    }

    private static void discoverRoot(Map<String, Pack> packs, String modId, File root, PackType type) {
        File[] entries = root.listFiles(File::isDirectory);
        if (entries == null) return;
        java.util.Arrays.sort(entries, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File entry : entries) {
            Pack pack = readPack(modId, entry, type);
            if (pack != null) packs.put(type.name() + ':' + pack.uuid, pack);
        }
    }

    private static Pack readPack(String modId, File root, PackType type) {
        File manifest = new File(root, "manifest.json");
        if (!manifest.isFile()) return null;
        try (FileReader reader = new FileReader(manifest)) {
            JsonObject document = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject header = document.getAsJsonObject("header");
            JsonArray modules = document.getAsJsonArray("modules");
            if (header == null || !header.has("uuid") || !header.has("version") || modules == null) return null;
            boolean compatibleModule = false;
            for (JsonElement element : modules) {
                if (!element.isJsonObject()) continue;
                JsonObject module = element.getAsJsonObject();
                if (!module.has("type")) continue;
                String moduleType = module.get("type").getAsString().toLowerCase(Locale.ROOT);
                if (type == PackType.RESOURCE && "resources".equals(moduleType)) compatibleModule = true;
                if (type == PackType.BEHAVIOR && ("data".equals(moduleType) || "script".equals(moduleType))) compatibleModule = true;
            }
            if (!compatibleModule) return null;
            String uuid = header.get("uuid").getAsString().trim().toLowerCase(Locale.ROOT);
            JsonArray version = header.getAsJsonArray("version");
            if (uuid.isEmpty() || version == null || version.size() < 3) return null;
            JsonArray versionCopy = new JsonArray();
            for (JsonElement element : version) versionCopy.add(element.deepCopy());
            String prefix = type == PackType.RESOURCE ? "levilauncher_rp_" : "levilauncher_bp_";
            String targetName = prefix + uuid.replaceAll("[^a-z0-9._-]", "_");
            return new Pack(modId, root, uuid, versionCopy, targetName, type);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void syncRoot(File gameDataDir, List<Pack> desired) throws IOException {
        File resourcePacksDir = new File(gameDataDir, "resource_packs");
        File behaviorPacksDir = new File(gameDataDir, "behavior_packs");
        File minecraftPeDir = new File(gameDataDir, "minecraftpe");
        File stateFile = new File(minecraftPeDir, MANAGED_STATE);
        File legacyStateFile = new File(minecraftPeDir, LEGACY_STATE);
        List<ManagedPack> previous = readState(stateFile);
        if (previous.isEmpty()) previous = readState(legacyStateFile);
        if (desired.isEmpty() && previous.isEmpty() && !stateFile.exists() && !legacyStateFile.exists()) return;
        ensureDirectory(resourcePacksDir);
        ensureDirectory(behaviorPacksDir);
        ensureDirectory(minecraftPeDir);
        Map<String, ManagedPack> previousByTarget = new LinkedHashMap<>();
        for (ManagedPack pack : previous) {
            previousByTarget.put(pack.type.name() + ':' + pack.targetName, pack);
        }
        Map<String, String> targetFingerprints = new LinkedHashMap<>();
        Set<String> desiredTargets = new HashSet<>();
        Set<String> managedResourceUuids = new HashSet<>();
        for (ManagedPack pack : previous) {
            if (pack.type == PackType.RESOURCE) managedResourceUuids.add(pack.uuid);
        }
        for (Pack pack : desired) {
            desiredTargets.add(pack.type.name() + ':' + pack.targetName);
            if (pack.type == PackType.RESOURCE) managedResourceUuids.add(pack.uuid);
        }

        for (ManagedPack pack : previous) {
            if (!desiredTargets.contains(pack.type.name() + ':' + pack.targetName)) {
                deleteRecursively(new File(packDirectory(pack.type, resourcePacksDir, behaviorPacksDir), pack.targetName));
            }
        }

        for (Pack pack : desired) {
            File parent = packDirectory(pack.type, resourcePacksDir, behaviorPacksDir);
            File target = new File(parent, pack.targetName);
            String key = pack.type.name() + ':' + pack.targetName;
            ManagedPack cached = previousByTarget.get(key);
            if (cached != null && target.isDirectory()
                    && pack.sourceFingerprint.equals(cached.sourceFingerprint)
                    && cached.targetFingerprint.equals(fingerprint(target))) {
                targetFingerprints.put(key, cached.targetFingerprint);
                continue;
            }
            File staging = new File(parent, pack.targetName + ".levilauncher_tmp");
            deleteRecursively(staging);
            copyRecursively(pack.source, staging);
            deleteRecursively(target);
            moveReplace(staging, target);
            targetFingerprints.put(key, fingerprint(target));
        }

        mergeGlobalResourcePacks(new File(minecraftPeDir, GLOBAL_RESOURCE_PACKS), managedResourceUuids, desired);
        writeState(stateFile, desired, targetFingerprints);
        if (legacyStateFile.isFile() && !legacyStateFile.delete()) {
            throw new IOException("Failed to remove legacy bundled-pack state");
        }
    }

    private static File packDirectory(PackType type, File resourcePacksDir, File behaviorPacksDir) {
        return type == PackType.RESOURCE ? resourcePacksDir : behaviorPacksDir;
    }

    private static List<ManagedPack> readState(File stateFile) {
        List<ManagedPack> result = new ArrayList<>();
        if (!stateFile.isFile()) return result;
        try (FileReader reader = new FileReader(stateFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray entries = root.getAsJsonArray("packs");
            if (entries == null) return result;
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (!object.has("uuid") || !object.has("target")) continue;
                PackType type = PackType.RESOURCE;
                if (object.has("type") && "behavior".equalsIgnoreCase(object.get("type").getAsString())) {
                    type = PackType.BEHAVIOR;
                }
                String target = object.get("target").getAsString();
                if (!target.startsWith("levilauncher_") || !target.equals(new File(target).getName())) continue;
                String sourceFingerprint = object.has("source_fingerprint")
                        ? object.get("source_fingerprint").getAsString() : "";
                String targetFingerprint = object.has("target_fingerprint")
                        ? object.get("target_fingerprint").getAsString() : "";
                result.add(new ManagedPack(object.get("uuid").getAsString(), target, type,
                        sourceFingerprint, targetFingerprint));
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static void writeState(File stateFile, List<Pack> desired,
                                   Map<String, String> targetFingerprints) throws IOException {
        JsonObject root = new JsonObject();
        JsonArray entries = new JsonArray();
        for (Pack pack : desired) {
            JsonObject entry = new JsonObject();
            entry.addProperty("mod_id", pack.modId);
            entry.addProperty("type", pack.type == PackType.RESOURCE ? "resource" : "behavior");
            entry.addProperty("uuid", pack.uuid);
            entry.addProperty("target", pack.targetName);
            entry.add("version", pack.version.deepCopy());
            entry.addProperty("source_fingerprint", pack.sourceFingerprint);
            entry.addProperty("target_fingerprint", targetFingerprints.get(pack.type.name() + ':' + pack.targetName));
            entries.add(entry);
        }
        root.add("packs", entries);
        writeJsonAtomic(stateFile, root);
    }

    private static void mergeGlobalResourcePacks(File globalFile, Set<String> managedUuids, List<Pack> desired) throws IOException {
        JsonArray output = new JsonArray();
        if (globalFile.isFile()) {
            try (FileReader reader = new FileReader(globalFile)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonArray()) throw new IOException("Invalid global_resource_packs.json: expected a JSON array");
                for (JsonElement element : parsed.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        output.add(element.deepCopy());
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    String uuid = object.has("pack_id") ? object.get("pack_id").getAsString().toLowerCase(Locale.ROOT) : "";
                    if (!managedUuids.contains(uuid)) output.add(object.deepCopy());
                }
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("Failed to parse global_resource_packs.json", error);
            }
        }
        for (Pack pack : desired) {
            if (pack.type != PackType.RESOURCE) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("pack_id", pack.uuid);
            entry.add("version", pack.version.deepCopy());
            output.add(entry);
        }
        writeJsonAtomic(globalFile, output);
    }

    private static void writeJsonAtomic(File target, JsonElement json) throws IOException {
        if (target.isFile()) {
            try (FileReader reader = new FileReader(target)) {
                if (json.equals(JsonParser.parseReader(reader))) return;
            } catch (Exception ignored) {}
        }
        ensureDirectory(target.getParentFile());
        File temp = new File(target.getParentFile(), target.getName() + ".levilauncher_tmp");
        try (FileWriter writer = new FileWriter(temp, false)) {
            GSON.toJson(json, writer);
        }
        moveReplace(temp, target);
    }

    private static String fingerprint(File root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fingerprintTree(root, "", digest);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static void fingerprintTree(File file, String relative, MessageDigest digest) throws IOException {
        if (file.isDirectory()) {
            digest.update(("d:" + relative + '\n').getBytes(StandardCharsets.UTF_8));
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Failed to list " + file);
            java.util.Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
            for (File child : children) fingerprintTree(child, relative + '/' + child.getName(), digest);
        } else if (file.isFile()) {
            digest.update(("f:" + relative + '|' + file.length() + '|' + file.lastModified() + '\n')
                    .getBytes(StandardCharsets.UTF_8));
        } else {
            throw new IOException("Missing bundled pack file: " + file);
        }
    }

    private static void copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            ensureDirectory(target);
            File[] children = source.listFiles();
            if (children == null) return;
            for (File child : children) copyRecursively(child, new File(target, child.getName()));
            return;
        }
        ensureDirectory(target.getParentFile());
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void moveReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) throw new IOException("Failed to delete " + file);
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir == null || dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Failed to create " + dir);
    }

    private enum PackType { RESOURCE, BEHAVIOR }

    private static final class Pack {
        final String modId;
        final File source;
        final String uuid;
        final JsonArray version;
        final String targetName;
        final PackType type;
        String sourceFingerprint;

        Pack(String modId, File source, String uuid, JsonArray version, String targetName, PackType type) {
            this.modId = modId;
            this.source = source;
            this.uuid = uuid;
            this.version = version;
            this.targetName = targetName;
            this.type = type;
        }
    }

    private static final class ManagedPack {
        final String uuid;
        final String targetName;
        final PackType type;
        final String sourceFingerprint;
        final String targetFingerprint;

        ManagedPack(String uuid, String targetName, PackType type,
                    String sourceFingerprint, String targetFingerprint) {
            this.uuid = uuid;
            this.targetName = targetName;
            this.type = type;
            this.sourceFingerprint = sourceFingerprint;
            this.targetFingerprint = targetFingerprint;
        }
    }
}
