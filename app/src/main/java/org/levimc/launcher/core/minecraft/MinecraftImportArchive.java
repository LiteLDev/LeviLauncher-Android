package org.levimc.launcher.core.minecraft;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MinecraftImportArchive {
    private MinecraftImportArchive() {
    }

    public static File stage(InputStream input, String displayName, File cacheDir) throws IOException {
        if (input == null) throw new IOException("Cannot open the selected file");
        File directory = new File(cacheDir, "minecraft_imports");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot prepare import cache");
        File temporary = File.createTempFile("import-", ".tmp", directory);
        boolean complete = false;
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[262144];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled");
                    output.write(buffer, 0, read);
                }
            }
            String name = displayName == null ? "content" : displayName.trim();
            name = name.replaceAll("[\\\\/\\p{Cntrl}&]", "_");
            if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "content";
            String lower = name.toLowerCase(Locale.ROOT);
            String extension = null;
            for (String candidate : new String[]{".mcworld", ".mcpack", ".mcaddon", ".mctemplate"}) {
                if (lower.endsWith(candidate)) extension = candidate;
            }
            String detected = detect(temporary);
            if (extension == null) {
                extension = detected;
                if (lower.endsWith(".zip")) name = name.substring(0, name.length() - 4);
                name += extension;
            }
            if (name.length() > 140) name = name.substring(0, 140 - extension.length()) + extension;
            File target = new File(directory, UUID.randomUUID() + "_" + name);
            if (!temporary.renameTo(target)) throw new IOException("Cannot prepare Minecraft import");
            complete = true;
            return target;
        } finally {
            if (!complete) temporary.delete();
        }
    }

    private static String detect(File archive) throws IOException {
        boolean pack = false;
        boolean addon = false;
        boolean world = false;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                while (name.startsWith("./")) name = name.substring(2);
                if (name.equals("level.dat")) world = true;
                if (name.equals("manifest.json")) pack = true;
                if (name.endsWith("/manifest.json") || name.endsWith(".mcpack") || name.endsWith(".mcaddon")) addon = true;
            }
        }
        if (world) return ".mcworld";
        if (pack) return ".mcpack";
        if (addon) return ".mcaddon";
        throw new IOException("Choose a Minecraft pack or world archive");
    }
}
