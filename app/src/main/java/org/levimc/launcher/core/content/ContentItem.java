package org.levimc.launcher.core.content;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public abstract class ContentItem {
    protected String name;
    protected File file;
    protected volatile long size;
    protected volatile String identityPath;
    private final java.util.List<SizeRequest> sizeRequests = new java.util.ArrayList<>();
    private boolean measuringSize;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.concurrent.ThreadPoolExecutor SIZE_WORKERS = createSizeWorkers();

    private static final class SizeRequest {
        final java.util.function.BooleanSupplier active;
        final Runnable ready;
        SizeRequest(java.util.function.BooleanSupplier active, Runnable ready) {
            this.active = active;
            this.ready = ready;
        }
    }

    private static java.util.concurrent.ThreadPoolExecutor createSizeWorkers() {
        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
                2, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
                task -> new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    task.run();
                }, "ContentSize"));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
    protected long lastModified;
    protected boolean enabled;

    public ContentItem(String name, File file) {
        this.name = name;
        this.file = file;
        this.size = file != null && file.isFile() ? file.length() : -1;
        this.lastModified = file == null ? 0 : file.lastModified();
        this.enabled = false;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public File getFile() {
        return file;
    }

    public String getStablePath() {
        String path = identityPath;
        if (path != null) return path;
        try { path = file == null ? "" : file.getCanonicalPath(); }
        catch (java.io.IOException error) { path = file.getAbsolutePath(); }
        identityPath = path;
        return path;
    }

    public long getSize() {
        long measured = size;
        if (measured >= 0) return measured;
        try {
            measured = ContentFiles.directorySize(file);
            size = measured;
            return measured;
        } catch (java.io.IOException | RuntimeException error) {
            return -1;
        }
    }

    public void loadSizeAsync(java.util.function.BooleanSupplier active, Runnable ready) {
        synchronized (sizeRequests) {
            if (size >= 0) {
                MAIN.post(() -> { if (active.getAsBoolean()) ready.run(); });
                return;
            }
            sizeRequests.removeIf(request -> !request.active.getAsBoolean());
            sizeRequests.add(new SizeRequest(active, ready));
            if (measuringSize) return;
            measuringSize = true;
        }
        SIZE_WORKERS.execute(() -> {
            synchronized (sizeRequests) {
                sizeRequests.removeIf(request -> !request.active.getAsBoolean());
                if (sizeRequests.isEmpty()) {
                    measuringSize = false;
                    return;
                }
            }
            getSize();
            java.util.List<SizeRequest> completed;
            synchronized (sizeRequests) {
                completed = new java.util.ArrayList<>(sizeRequests);
                sizeRequests.clear();
                measuringSize = false;
            }
            MAIN.post(() -> {
                for (SizeRequest request : completed) if (request.active.getAsBoolean()) request.ready.run();
            });
        });
    }

    public String getFormattedSize() {
        if (size < 0) return "…";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0));
        return String.format(Locale.getDefault(), "%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    public String getFormattedLastModified() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
        return sdf.format(new Date(lastModified));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public abstract String getType();
    public abstract String getDescription();
    public abstract boolean isValid();

}
