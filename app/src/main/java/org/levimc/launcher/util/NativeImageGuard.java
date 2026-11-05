package org.levimc.launcher.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.concurrent.ConcurrentHashMap;

public final class NativeImageGuard {
    public static final String TOKEN = "img_v5";
    private static final ConcurrentHashMap<String, FileState> CLEAN_FILES = new ConcurrentHashMap<>();

    private NativeImageGuard() {
    }

    public static boolean shouldProcess(File soFile) {
        if (soFile == null || !soFile.isFile() || soFile.length() == 0) {
            return false;
        }

        String path = soFile.getAbsolutePath();
        FileState state = FileState.from(soFile);
        FileState cached = CLEAN_FILES.get(path);
        if (state.equals(cached) || state.equals(readCleanMarker(soFile))) {
            CLEAN_FILES.put(path, state);
            return false;
        }

        return scan(soFile);
    }

    public static boolean processIfNeeded(File soFile) {
        return process(soFile, false);
    }

    public static boolean processRequired(File soFile) {
        return process(soFile, true);
    }

    public static boolean isPrepared(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return false;
        FileState state = FileState.from(file);
        return state.equals(CLEAN_FILES.get(file.getAbsolutePath())) || state.equals(readCleanMarker(file));
    }

    public static void publishStagedImage(File stagedFile, File output) throws IOException {
        if (!stagedFile.isFile() || stagedFile.length() == 0) {
            throw new IOException("Native library is empty: " + output.getName());
        }
        if (!NativeBridgeHelper.ensureGxCoreLoaded()) {
            throw new IOException("Failed to load native image preparation");
        }
        if (!NativeBridgeHelper.rewriteImage(stagedFile.getAbsolutePath(), stagedFile.getAbsolutePath())) {
            throw new IOException("Failed to prepare native library: " + output.getName());
        }
        try (RandomAccessFile file = new RandomAccessFile(stagedFile, "rw")) {
            file.getFD().sync();
        }
        if (!stagedFile.setReadOnly()) {
            throw new IOException("Failed to keep native library read-only: " + output.getName());
        }
        try {
            Files.move(stagedFile.toPath(), output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(stagedFile.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        markClean(output);
    }

    private static boolean process(File soFile, boolean ignoreMarker) {
        if (soFile == null || !soFile.isFile() || soFile.length() == 0) {
            return false;
        }
        if (!NativeBridgeHelper.ensureGxCoreLoaded()) {
            return false;
        }
        try {
            String path = soFile.getAbsolutePath();
            if (ignoreMarker) {
                CLEAN_FILES.remove(path);
                clearCleanMarker(soFile);
            } else {
                FileState state = FileState.from(soFile);
                if (state.equals(CLEAN_FILES.get(path)) || state.equals(readCleanMarker(soFile))) {
                    CLEAN_FILES.put(path, state);
                    return true;
                }
            }
            if (!scan(soFile)) {
                markClean(soFile);
                return true;
            }
            File tempFile = new File(soFile.getAbsolutePath() + ".img.tmp");
            if (tempFile.exists() && !tempFile.delete()) {
                throw new IOException("Failed to delete stale temp file: " + tempFile.getAbsolutePath());
            }

            if (!NativeBridgeHelper.rewriteImage(inputPath(soFile.getAbsolutePath()), tempFile.getAbsolutePath())) {
                tempFile.delete();
                return false;
            }

            Files.move(tempFile.toPath(), soFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            soFile.setReadable(true, true);
            soFile.setReadOnly();
            markClean(soFile);
            return true;
        } catch (Exception e) {
            CLEAN_FILES.remove(soFile.getAbsolutePath());
            clearCleanMarker(soFile);
            return false;
        }
    }

    public static int processDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }

        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                count += processDirectory(file);
            } else if (file.getName().endsWith(".so") && processIfNeeded(file)) {
                count++;
            }
        }
        return count;
    }

    private static boolean scan(File soFile) {
        if (!NativeBridgeHelper.ensureGxCoreLoaded()) {
            return false;
        }
        return NativeBridgeHelper.scanImage(inputPath(soFile.getAbsolutePath()));
    }

    private static File cleanMarker(File file) {
        File parent = file.getParentFile();
        String name = "." + file.getName() + "." + TOKEN + ".ok";
        return parent == null ? new File(name) : new File(parent, name);
    }

    private static FileState readCleanMarker(File file) {
        File marker = cleanMarker(file);
        if (!marker.isFile()) {
            return null;
        }
        try {
            String[] parts = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim().split(":");
            if (parts.length != 3 || !TOKEN.equals(parts[0])) {
                return null;
            }
            return new FileState(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void markClean(File file) {
        FileState state = FileState.from(file);
        CLEAN_FILES.put(file.getAbsolutePath(), state);
        try {
            File marker = cleanMarker(file);
            File parent = marker.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            String data = TOKEN + ":" + state.length + ":" + state.lastModified;
            Files.write(marker.toPath(), data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static void clearCleanMarker(File file) {
        try {
            File marker = cleanMarker(file);
            if (marker.exists()) {
                marker.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static String inputPath(String path) {
        return path;
    }

    private static final class FileState {
        private final long length;
        private final long lastModified;

        private FileState(long length, long lastModified) {
            this.length = length;
            this.lastModified = lastModified;
        }

        static FileState from(File file) {
            return new FileState(file.length(), file.lastModified());
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof FileState)) {
                return false;
            }
            FileState other = (FileState) obj;
            return length == other.length && lastModified == other.lastModified;
        }

        @Override
        public int hashCode() {
            long value = length ^ (length >>> 32) ^ lastModified ^ (lastModified >>> 32);
            return (int) value;
        }
    }
}
