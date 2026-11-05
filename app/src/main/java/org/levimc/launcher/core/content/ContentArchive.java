package org.levimc.launcher.core.content;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ContentArchive {
    static final class Result {
        int worlds;
        int resources;
        int behaviors;
        int skins;
        final List<String> warnings = new ArrayList<>();

        int total() { return worlds + resources + behaviors + skins; }

        void add(Result result) {
            worlds += result.worlds;
            resources += result.resources;
            behaviors += result.behaviors;
            skins += result.skins;
            warnings.addAll(result.warnings);
        }
    }

    private static final class Entry {
        final ZipEntry zip;
        final String name;
        Entry(ZipEntry zip, String name) { this.zip = zip; this.name = name; }
    }

    private static final class Pack {
        final String prefix;
        final boolean[] types = new boolean[3];
        final List<Entry> entries = new ArrayList<>();
        Pack(String prefix) { this.prefix = prefix; }
    }

    private ContentArchive() {}

    static Result importUri(Context context, Uri uri, File worlds, File resources,
                            File behaviors, File skins, boolean worldOnly, IntConsumer progress) throws IOException {
        String name = uri.getLastPathSegment();
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            } catch (RuntimeException ignored) {}
        }
        if (name == null) name = "content.zip";
        File source = "file".equals(uri.getScheme()) && uri.getPath() != null ? new File(uri.getPath()) : null;
        File temporary = null;
        progress.accept(0);
        try {
            if (source == null || !source.isFile() || !source.canRead()) {
                temporary = File.createTempFile("content_import_", ".zip", context.getCacheDir());
                try (InputStream input = context.getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    if (input == null) throw new IOException("Cannot open content file");
                    ContentFiles.copy(input, output, new byte[ContentFiles.BUFFER_SIZE]);
                }
                source = temporary;
            }
            progress.accept(10);
            boolean world = worldOnly || name.toLowerCase(Locale.ROOT).endsWith(".mcworld")
                    || name.toLowerCase(Locale.ROOT).endsWith(".mctemplate");
            String lowerName = name.toLowerCase(Locale.ROOT);
            boolean packOnly = lowerName.endsWith(".mcpack") || lowerName.endsWith(".mcaddon");
            Result result = importZip(context, source, worlds, resources, behaviors, skins, world, packOnly,
                    value -> progress.accept(10 + value * 90 / 100));
            if (result.total() == 0) throw new IOException("No valid content found in " + name);
            progress.accept(100);
            return result;
        } finally {
            if (temporary != null) ContentFiles.cleanup(temporary);
        }
    }

    private static Result importZip(Context context, File source, File worlds, File resources,
                                     File behaviors, File skins, boolean worldOnly, boolean packOnly,
                                     IntConsumer progress) throws IOException {
        Result result = new Result();
        try (ZipFile zip = new ZipFile(source)) {
            List<Entry> entries = new ArrayList<>();
            TreeMap<String, Pack> candidates = new TreeMap<>();
            List<Entry> nested = new ArrayList<>();
            String worldPrefix = null;
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = normalize(entry.getName());
                if (name.isEmpty()) continue;
                Entry item = new Entry(entry, name);
                entries.add(item);
                if (entry.isDirectory()) continue;
                if (name.equals("level.dat") || name.endsWith("/level.dat")) {
                    String prefix = name.substring(0, name.length() - "level.dat".length());
                    if (worldPrefix == null || prefix.length() < worldPrefix.length()) worldPrefix = prefix;
                }
                if (name.equals("manifest.json") || name.endsWith("/manifest.json")) {
                    Pack pack = readPack(zip, item);
                    if (pack != null) candidates.put(pack.prefix, pack);
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".mcpack") || lower.endsWith(".mcaddon")) nested.add(item);
            }
            boolean worldContainer = worldPrefix != null;
            if (worldContainer) {
                for (String prefix : candidates.keySet()) if (!prefix.startsWith(worldPrefix)) worldContainer = false;
                for (Entry entry : nested) if (!entry.name.startsWith(worldPrefix)) worldContainer = false;
            }
            if (worldOnly || (!packOnly && worldContainer)) {
                if (worlds == null) throw new IOException("World storage is unavailable");
                if (worldPrefix == null) throw new IOException("No world data found");
                List<Entry> selected = new ArrayList<>();
                for (Entry entry : entries) if (entry.name.startsWith(worldPrefix)) selected.add(entry);
                extract(zip, selected, worldPrefix, worlds, progress);
                result.worlds++;
                return result;
            }
            TreeMap<String, Pack> packs = new TreeMap<>();
            for (Pack candidate : candidates.values()) {
                Map.Entry<String, Pack> parent = packs.floorEntry(candidate.prefix);
                if (parent == null || !candidate.prefix.startsWith(parent.getKey())) packs.put(candidate.prefix, candidate);
            }
            for (Entry entry : entries) {
                Map.Entry<String, Pack> pack = packs.floorEntry(entry.name);
                if (pack != null && entry.name.startsWith(pack.getKey())) pack.getValue().entries.add(entry);
            }
            nested.removeIf(entry -> {
                Map.Entry<String, Pack> parent = packs.floorEntry(entry.name);
                return parent != null && entry.name.startsWith(parent.getKey());
            });
            int tasks = nested.size();
            File[] destinations = {resources, behaviors, skins};
            for (Pack pack : packs.values()) {
                for (int i = 0; i < destinations.length; i++) if (pack.types[i] && destinations[i] != null) tasks++;
            }
            final int totalTasks = Math.max(1, tasks);
            int completed = 0;
            for (Pack pack : packs.values()) {
                for (int i = 0; i < destinations.length; i++) {
                    if (!pack.types[i] || destinations[i] == null) continue;
                    final int task = completed++;
                    try {
                        extract(zip, pack.entries, pack.prefix, destinations[i],
                                value -> progress.accept((task * 100 + value) / totalTasks));
                        if (i == 0) result.resources++;
                        else if (i == 1) result.behaviors++;
                        else result.skins++;
                    } catch (IOException error) {
                        result.warnings.add(error.getMessage());
                    }
                }
            }
            for (Entry entry : nested) {
                final int task = completed++;
                File temporary = File.createTempFile("content_nested_", ".zip", context.getCacheDir());
                try {
                    try (InputStream input = zip.getInputStream(entry.zip);
                         FileOutputStream output = new FileOutputStream(temporary)) {
                        ContentFiles.copy(input, output, new byte[ContentFiles.BUFFER_SIZE]);
                    }
                    result.add(importZip(context, temporary, worlds, resources, behaviors, skins, false, true,
                            value -> progress.accept((task * 100 + value) / totalTasks)));
                } catch (IOException error) {
                    result.warnings.add(error.getMessage());
                } finally {
                    ContentFiles.cleanup(temporary);
                }
            }
        }
        if (result.total() == 0 && !result.warnings.isEmpty()) throw new IOException(String.join("\n", result.warnings));
        progress.accept(100);
        return result;
    }

    private static Pack readPack(ZipFile zip, Entry entry) {
        try (InputStream input = zip.getInputStream(entry.zip)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            ContentFiles.copy(input, output, new byte[8192]);
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            JSONObject manifest = new JSONObject(ContentFiles.withoutComments(text));
            JSONArray modules = manifest.optJSONArray("modules");
            if (modules == null) return null;
            Pack pack = new Pack(entry.name.substring(0, entry.name.length() - "manifest.json".length()));
            for (int i = 0; i < modules.length(); i++) {
                JSONObject module = modules.optJSONObject(i);
                if (module == null) continue;
                String type = module.optString("type").toLowerCase(Locale.ROOT);
                if (type.equals("resources")) pack.types[0] = true;
                if (type.equals("data") || type.equals("script")) pack.types[1] = true;
                if (type.equals("skin_pack")) pack.types[2] = true;
            }
            return pack.types[0] || pack.types[1] || pack.types[2] ? pack : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void extract(ZipFile zip, List<Entry> entries, String prefix,
                                 File parent, IntConsumer progress) throws IOException {
        ContentFiles.ensureDirectory(parent);
        String id = UUID.randomUUID().toString().replace("-", "");
        File staged = new File(parent, ContentFiles.STAGING_PREFIX + id);
        File destination = new File(parent, id);
        ContentFiles.ensureDirectory(staged);
        Path root = staged.toPath();
        Set<Path> directories = new HashSet<>();
        directories.add(root);
        long total = 0;
        for (Entry entry : entries) if (!entry.zip.isDirectory()) total += Math.max(0, entry.zip.getSize());
        long written = 0;
        int lastPercent = -1;
        byte[] buffer = new byte[ContentFiles.BUFFER_SIZE];
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort((left, right) -> Boolean.compare(isMarker(left.name), isMarker(right.name)));
        try {
            for (Entry entry : ordered) {
                String relative = entry.name.substring(prefix.length());
                if (relative.isEmpty()) continue;
                Path target = root.resolve(relative);
                Path directory = entry.zip.isDirectory() ? target : target.getParent();
                if (directories.add(directory)) Files.createDirectories(directory);
                if (entry.zip.isDirectory()) continue;
                try (InputStream input = zip.getInputStream(entry.zip);
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(target.toFile()), 32 * 1024)) {
                    long copied = ContentFiles.copy(input, output, buffer);
                    if (entry.zip.getSize() >= 0 && copied != entry.zip.getSize()) throw new IOException("Incomplete file: " + relative);
                    written += copied;
                }
                int percent = total <= 0 ? 0 : (int) Math.min(99, written * 100 / total);
                if (percent != lastPercent) {
                    progress.accept(percent);
                    lastPercent = percent;
                }
            }
            Files.move(root, destination.toPath());
            progress.accept(100);
        } finally {
            ContentFiles.cleanup(staged);
        }
    }

    private static boolean isMarker(String name) {
        return name.equals("manifest.json") || name.endsWith("/manifest.json")
                || name.equals("level.dat") || name.endsWith("/level.dat");
    }

    private static String normalize(String name) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || (normalized.length() > 1 && normalized.charAt(1) == ':')) {
            throw new IOException("Invalid archive path: " + name);
        }
        for (String component : normalized.split("/")) {
            if (component.equals("..")) throw new IOException("Invalid archive path: " + name);
        }
        Path path = Paths.get(normalized).normalize();
        return path.toString().replace(File.separatorChar, '/');
    }
}
