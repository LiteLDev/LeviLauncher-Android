package org.levimc.launcher.core.content;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ContentFiles {
    static final int BUFFER_SIZE = 256 * 1024;
    static final String STAGING_PREFIX = ".levi-import-";

    private ContentFiles() {}

    static boolean visible(File file) {
        return !file.getName().startsWith(STAGING_PREFIX);
    }

    static void ensureDirectory(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs() && !file.isDirectory()) {
            throw new IOException("Cannot create directory: " + file);
        }
    }

    static long copy(InputStream input, OutputStream output, byte[] buffer) throws IOException {
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Operation interrupted");
            output.write(buffer, 0, count);
            total += count;
        }
        return total;
    }

    static void deleteTree(File file) throws IOException {
        if (file == null) return;
        Path root = file.toPath();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void cleanup(File file) {
        try { deleteTree(file); }
        catch (IOException error) { android.util.Log.w("ContentFiles", "Failed to clean temporary content", error); }
    }

    static long directorySize(File file) throws IOException {
        if (file == null || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0;
        long[] size = {0};
        Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) size[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return size[0];
    }

    static void copyTree(File source, File destination) throws IOException {
        Path root = source.toPath();
        Path target = destination.toPath();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(root.relativize(path)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()) throw new IOException("Unsupported content file: " + path);
                Files.copy(path, target.resolve(root.relativize(path)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void transfer(File source, File target) throws IOException {
        ensureDirectory(target.getParentFile());
        try {
            Files.move(source.toPath(), target.toPath());
            return;
        } catch (IOException moveError) {
            if (!source.isDirectory() || target.exists()) throw moveError;
        }
        File staged = new File(target.getParentFile(), STAGING_PREFIX + java.util.UUID.randomUUID());
        try {
            copyTree(source, staged);
            Files.move(staged.toPath(), target.toPath());
        } finally {
            cleanup(staged);
        }
        deleteTree(source);
    }

    static void zip(File directory, OutputStream output) throws IOException {
        Path root = directory.toPath();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output, BUFFER_SIZE))) {
            zip.setLevel(Deflater.BEST_SPEED);
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    if (!root.equals(path)) {
                        zip.putNextEntry(new ZipEntry(root.relativize(path).toString().replace(File.separatorChar, '/') + '/'));
                        zip.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile()) throw new IOException("Unsupported content file: " + path);
                    ZipEntry entry = new ZipEntry(root.relativize(path).toString().replace(File.separatorChar, '/'));
                    entry.setTime(attributes.lastModifiedTime().toMillis());
                    zip.putNextEntry(entry);
                    try (InputStream input = new FileInputStream(path.toFile())) {
                        copy(input, zip, buffer);
                    }
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    static String withoutComments(String text) {
        StringBuilder output = new StringBuilder(text.length());
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (quoted) {
                output.append(value);
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else if (value == '"') {
                quoted = true;
                output.append(value);
            } else if (value == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                i += 2;
                while (i < text.length() && text.charAt(i) != '\n') i++;
                output.append('\n');
            } else if (value == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < text.length() && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) i++;
                i++;
                output.append(' ');
            } else {
                output.append(value);
            }
        }
        return output.toString();
    }
}
