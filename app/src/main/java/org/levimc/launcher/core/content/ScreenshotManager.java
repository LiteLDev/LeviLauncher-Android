package org.levimc.launcher.core.content;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ScreenshotManager {
    private volatile File screenshotsDir;
    private volatile Map<String, Metadata> metadata = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class Metadata {
        long imageModified;
        long jsonModified;
        long jsonLength;
        long captureTime;
    }

    public void setScreenshotsDirectory(File directory) {
        if (!java.util.Objects.equals(screenshotsDir, directory)) metadata = new java.util.concurrent.ConcurrentHashMap<>();
        screenshotsDir = directory;
    }

    public synchronized List<ScreenshotItem> getScreenshots() {
        List<ScreenshotItem> items = new ArrayList<>();
        File root = screenshotsDir;
        File[] files = root == null ? null : root.listFiles();
        if (files == null) return items;
        for (File file : files) {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) for (File child : children) addImage(child, file.getName(), items);
            } else addImage(file, root.getName(), items);
        }
        items.sort((left, right) -> Long.compare(right.captureTime, left.captureTime));
        return items;
    }

    private void addImage(File file, String directory, List<ScreenshotItem> items) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg")) || !file.isFile()) return;
        File json = sidecar(file);
        long modified = file.lastModified(), jsonModified = json.lastModified(), jsonLength = json.length();
        Metadata cached = metadata.get(file.getAbsolutePath());
        if (cached == null || cached.imageModified != modified || cached.jsonModified != jsonModified || cached.jsonLength != jsonLength) {
            cached = new Metadata();
            cached.imageModified = modified;
            cached.jsonModified = jsonModified;
            cached.jsonLength = jsonLength;
            cached.captureTime = modified;
            if (json.isFile()) {
                try {
                    JSONObject document = new JSONObject(new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8));
                    if (document.has("CaptureTime")) cached.captureTime = document.getLong("CaptureTime") * 1000L;
                } catch (Exception ignored) {}
            }
            metadata.put(file.getAbsolutePath(), cached);
        }
        items.add(new ScreenshotItem(file.getName(), file, directory, cached.captureTime));
    }

    private static File sidecar(File file) {
        String name = file.getName();
        return new File(file.getParentFile(), name.substring(0, name.lastIndexOf('.')) + ".json");
    }

    public synchronized boolean deleteScreenshot(ScreenshotItem item) {
        try {
            boolean deleted = Files.deleteIfExists(item.file.toPath());
            if (deleted) {
                try { Files.deleteIfExists(sidecar(item.file).toPath()); } catch (java.io.IOException ignored) {}
                metadata.remove(item.file.getAbsolutePath());
            }
            return deleted;
        } catch (java.io.IOException error) {
            return false;
        }
    }
}
