package org.levimc.launcher.core.play;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads Minecraft builds from Google Play into the launcher's storage and remembers which
 * builds the account has been offered. Downloads run one at a time in the background, keep
 * going when the download page is closed, and resume from where they stopped.
 */
public final class GameDownloads {
    public static final String MINECRAFT_PACKAGE = "com.mojang.minecraftpe";
    private static final String TAG = "GameDownloads";
    private static final String DOWNLOADS_DIR = "downloads";
    private static final String MANIFEST = "download.json";
    private static final String KNOWN_FILE = "known_versions.json";
    private static final Gson GSON = new Gson();

    public enum State { QUEUED, RESOLVING, DOWNLOADING, VERIFYING, DONE, FAILED, CANCELLED }

    /** A download in progress or just finished, as the UI shows it. */
    public static final class Task {
        public final long versionCode;
        public volatile String versionName;
        public volatile State state = State.QUEUED;
        public volatile long bytesDone;
        public volatile long bytesTotal;
        public volatile long bytesPerSecond;
        public volatile PlayException.Kind errorKind;
        public volatile String error;
        volatile boolean cancelled;
        volatile Call call;

        Task(long versionCode, String versionName) {
            this.versionCode = versionCode;
            this.versionName = versionName;
        }

        public boolean isActive() {
            return state == State.QUEUED || state == State.RESOLVING
                    || state == State.DOWNLOADING || state == State.VERIFYING;
        }

        public int percent() {
            if (bytesTotal <= 0) return 0;
            return (int) Math.min(100, bytesDone * 100 / bytesTotal);
        }
    }

    /** What a finished download left on disk. */
    public static final class Downloaded {
        public long versionCode;
        public String versionName;
        public long size;
        public long downloadedAt;
        public List<String> files = new ArrayList<>();
    }

    public interface Listener {
        void onTaskChanged(Task task);
    }

    private static volatile GameDownloads instance;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GameDownloads");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Long, Task> tasks = Collections.synchronizedMap(new LinkedHashMap<>());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    /** The offer type Play last listed Minecraft under; delivery must name the same one. */
    private volatile int offerType = 1;

    private GameDownloads(Context context) {
        this.context = context.getApplicationContext();
    }

    public static GameDownloads get(Context context) {
        GameDownloads current = instance;
        if (current == null) {
            synchronized (GameDownloads.class) {
                current = instance;
                if (current == null) {
                    current = new GameDownloads(context);
                    instance = current;
                }
            }
        }
        return current;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public Task task(long versionCode) {
        return tasks.get(versionCode);
    }

    public boolean hasActiveDownloads() {
        synchronized (tasks) {
            for (Task task : tasks.values()) {
                if (task.isActive()) return true;
            }
        }
        return false;
    }

    // ---- Catalog ----

    public File downloadsRoot() {
        File root = new File(LauncherStorage.getAppRoot(context), DOWNLOADS_DIR);
        if (!root.exists()) root.mkdirs();
        return root;
    }

    public File downloadDir(long versionCode) {
        return new File(downloadsRoot(), MINECRAFT_PACKAGE + "-" + versionCode);
    }

    /** The finished download of [versionCode], or null if it is not (completely) on disk. */
    public Downloaded downloaded(long versionCode) {
        File manifest = new File(downloadDir(versionCode), MANIFEST);
        if (!manifest.isFile()) return null;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(manifest), StandardCharsets.UTF_8)) {
            Downloaded downloaded = GSON.fromJson(reader, Downloaded.class);
            if (downloaded == null || downloaded.files == null || downloaded.files.isEmpty()) return null;
            for (String name : downloaded.files) {
                if (!new File(manifest.getParentFile(), name).isFile()) return null;
            }
            return downloaded;
        } catch (Exception e) {
            return null;
        }
    }

    /** The APK files of a finished download, base first. */
    public List<File> downloadedFiles(long versionCode) {
        Downloaded downloaded = downloaded(versionCode);
        List<File> files = new ArrayList<>();
        if (downloaded == null) return files;
        File dir = downloadDir(versionCode);
        for (String name : downloaded.files) files.add(new File(dir, name));
        return files;
    }

    public boolean delete(long versionCode) {
        Task task = tasks.get(versionCode);
        if (task != null && task.isActive()) return false;
        tasks.remove(versionCode);
        return deleteRecursively(downloadDir(versionCode));
    }

    /** Every build the page knows of: those Play has offered, plus those downloaded. */
    public synchronized List<GameRelease> knownReleases() {
        Map<Long, GameRelease> byCode = new LinkedHashMap<>();
        for (GameRelease release : readKnown()) byCode.put(release.versionCode, release);
        File[] dirs = downloadsRoot().listFiles();
        if (dirs != null) {
            String prefix = MINECRAFT_PACKAGE + "-";
            for (File dir : dirs) {
                if (!dir.isDirectory() || !dir.getName().startsWith(prefix)) continue;
                long code;
                try {
                    code = Long.parseLong(dir.getName().substring(prefix.length()));
                } catch (NumberFormatException e) {
                    continue;
                }
                Downloaded downloaded = downloaded(code);
                if (downloaded == null || byCode.containsKey(code)) continue;
                byCode.put(code, new GameRelease(code, downloaded.versionName, downloaded.size, 0));
            }
        }
        List<GameRelease> list = new ArrayList<>(byCode.values());
        Collections.sort(list, GameRelease::compareNewestFirst);
        return list;
    }

    /** Remembers a build Play offered, so it stays listed after Play moves on to a newer one. */
    public synchronized void remember(GameRelease release) {
        if (release == null || release.versionCode <= 0) return;
        List<GameRelease> known = readKnown();
        boolean replaced = false;
        for (int i = 0; i < known.size(); i++) {
            GameRelease existing = known.get(i);
            if (existing.versionCode != release.versionCode) continue;
            if (release.versionName == null || release.versionName.isEmpty()) release.versionName = existing.versionName;
            if (release.size <= 0) release.size = existing.size;
            release.seenAt = Math.max(release.seenAt, existing.seenAt);
            release.channel = GameRelease.channelOf(release.versionName);
            known.set(i, release);
            replaced = true;
            break;
        }
        if (!replaced) known.add(release);
        writeKnown(known);
    }

    public synchronized void forget(long versionCode) {
        List<GameRelease> known = readKnown();
        List<GameRelease> kept = new ArrayList<>();
        for (GameRelease release : known) {
            if (release.versionCode != versionCode) kept.add(release);
        }
        writeKnown(kept);
    }

    public void setOfferType(int offerType) {
        if (offerType > 0) this.offerType = offerType;
    }

    private File knownFile() {
        File dir = new File(context.getFilesDir(), "play");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, KNOWN_FILE);
    }

    private List<GameRelease> readKnown() {
        AtomicFile file = new AtomicFile(knownFile());
        if (!file.getBaseFile().exists()) return new ArrayList<>();
        try (InputStreamReader reader = new InputStreamReader(file.openRead(), StandardCharsets.UTF_8)) {
            List<GameRelease> list = GSON.fromJson(reader, new TypeToken<List<GameRelease>>() {}.getType());
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            Log.w(TAG, "known versions unreadable", e);
            return new ArrayList<>();
        }
    }

    private void writeKnown(List<GameRelease> list) {
        AtomicFile file = new AtomicFile(knownFile());
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(GSON.toJson(list).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
            Log.w(TAG, "known versions not saved", e);
        }
    }

    // ---- Downloads ----

    /** Queues [versionCode] for download; returns the (possibly already running) task. */
    public Task start(long versionCode, String versionName) {
        synchronized (tasks) {
            Task existing = tasks.get(versionCode);
            if (existing != null && existing.isActive()) return existing;
            Task task = new Task(versionCode, versionName);
            tasks.put(versionCode, task);
            notifyChanged(task);
            executor.execute(() -> run(task));
            return task;
        }
    }

    public void cancel(long versionCode) {
        Task task = tasks.get(versionCode);
        if (task == null || !task.isActive()) return;
        task.cancelled = true;
        Call call = task.call;
        if (call != null) call.cancel();
        if (task.state == State.QUEUED) {
            task.state = State.CANCELLED;
            notifyChanged(task);
        }
    }

    /** Forgets a finished, failed or cancelled task so its row goes back to its plain state. */
    public void dismiss(long versionCode) {
        Task task = tasks.get(versionCode);
        if (task != null && !task.isActive()) tasks.remove(versionCode);
    }

    private void run(Task task) {
        if (task.cancelled) {
            task.state = State.CANCELLED;
            notifyChanged(task);
            return;
        }
        File dir = downloadDir(task.versionCode);
        try {
            task.state = State.RESOLVING;
            notifyChanged(task);
            PlayClient client = new PlayClient(context);
            PlayClient.Delivery delivery = client.delivery(MINECRAFT_PACKAGE, task.versionCode, offerType);
            checkCancelled(task);

            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            // A previous, finished download of the same build is replaced only once this one completes.
            File manifest = new File(dir, MANIFEST);
            if (manifest.exists() && !manifest.delete()) throw new IOException("Cannot replace " + manifest);

            long total = 0;
            for (PlayClient.DeliveryFile file : delivery.files) total += Math.max(0, file.size);
            task.bytesTotal = total;
            task.bytesDone = 0;
            task.state = State.DOWNLOADING;
            notifyChanged(task);

            String cookie = cookieHeader(delivery.cookies);
            List<String> names = new ArrayList<>();
            long[] progress = {0, System.nanoTime(), 0};
            for (PlayClient.DeliveryFile file : delivery.files) {
                String name = fileName(file.name);
                names.add(name);
                File target = new File(dir, name);
                downloadFile(task, file, target, cookie, progress);
            }

            task.state = State.VERIFYING;
            notifyChanged(task);
            String versionName = readVersionName(new File(dir, names.get(0)));
            if (versionName != null) task.versionName = versionName;

            Downloaded downloaded = new Downloaded();
            downloaded.versionCode = task.versionCode;
            downloaded.versionName = task.versionName;
            downloaded.size = total;
            downloaded.downloadedAt = System.currentTimeMillis();
            downloaded.files = names;
            removeStrayFiles(dir, names);
            writeManifest(manifest, downloaded);
            remember(new GameRelease(task.versionCode, task.versionName, total, 0));

            task.bytesDone = task.bytesTotal;
            task.state = State.DONE;
            notifyChanged(task);
        } catch (CancelledException e) {
            task.state = State.CANCELLED;
            notifyChanged(task);
        } catch (PlayException e) {
            fail(task, e.kind, e.getMessage());
        } catch (Exception e) {
            if (task.cancelled) {
                task.state = State.CANCELLED;
                notifyChanged(task);
            } else {
                Log.w(TAG, "download failed", e);
                fail(task, PlayException.Kind.NETWORK, e.getMessage());
            }
        } finally {
            task.call = null;
        }
    }

    private void fail(Task task, PlayException.Kind kind, String message) {
        task.errorKind = kind;
        task.error = message;
        task.state = State.FAILED;
        notifyChanged(task);
    }

    private void downloadFile(Task task, PlayClient.DeliveryFile file, File target, String cookie,
                              long[] progress) throws Exception {
        if (target.isFile() && file.size > 0 && target.length() == file.size && hashMatches(target, file)) {
            advance(task, file.size, progress);
            return;
        }
        File part = new File(target.getPath() + ".part");
        if (target.exists()) target.delete();
        long offset = part.isFile() ? part.length() : 0;
        if (file.size > 0 && offset > file.size) {
            part.delete();
            offset = 0;
        }

        Request.Builder request = new Request.Builder().url(file.url);
        if (!cookie.isEmpty()) request.header("Cookie", cookie);
        if (offset > 0) request.header("Range", "bytes=" + offset + "-");
        Call call = http.newCall(request.build());
        task.call = call;
        checkCancelled(task);
        try (Response response = call.execute()) {
            if (offset > 0 && response.code() != 206) {
                offset = 0; // the server ignored the range: start this file over
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " while downloading " + target.getName());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response for " + target.getName());
            advance(task, offset, progress);
            try (InputStream in = body.byteStream();
                 RandomAccessFile out = new RandomAccessFile(part, "rw")) {
                out.setLength(offset);
                out.seek(offset);
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    checkCancelled(task);
                    out.write(buffer, 0, read);
                    advance(task, read, progress);
                }
            }
        }
        checkCancelled(task);
        if (file.size > 0 && part.length() != file.size) {
            throw new IOException(target.getName() + " is incomplete (" + part.length() + " of " + file.size + " bytes)");
        }
        if (!hashMatches(part, file)) {
            part.delete();
            throw new IOException(target.getName() + " failed its integrity check; try again");
        }
        if (!part.renameTo(target)) throw new IOException("Cannot finish " + target.getName());
    }

    private void advance(Task task, long bytes, long[] progress) {
        task.bytesDone += bytes;
        long now = System.nanoTime();
        long elapsed = now - progress[1];
        progress[2] += bytes;
        if (elapsed >= 500_000_000L) {
            task.bytesPerSecond = progress[2] * 1_000_000_000L / elapsed;
            progress[1] = now;
            progress[2] = 0;
            notifyChanged(task);
        }
    }

    private static boolean hashMatches(File file, PlayClient.DeliveryFile expected) {
        try {
            if (expected.sha256 != null) return expected.sha256.equalsIgnoreCase(digest(file, "SHA-256"));
            if (expected.sha1 != null) return expected.sha1.equalsIgnoreCase(digest(file, "SHA-1"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String digest(File file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    private String readVersionName(File baseApk) {
        try {
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(baseApk.getAbsolutePath(), 0);
            if (info != null && MINECRAFT_PACKAGE.equals(info.packageName)) return info.versionName;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String fileName(String splitName) {
        if (splitName == null || splitName.isEmpty() || "base".equals(splitName)) return "base.apk";
        return "split_" + splitName.replaceAll("[^A-Za-z0-9._-]", "_") + ".apk";
    }

    private static String cookieHeader(Map<String, String> cookies) {
        StringBuilder header = new StringBuilder();
        for (Map.Entry<String, String> cookie : cookies.entrySet()) {
            if (header.length() > 0) header.append("; ");
            header.append(cookie.getKey()).append('=').append(cookie.getValue());
        }
        return header.toString();
    }

    private static void removeStrayFiles(File dir, List<String> keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!keep.contains(file.getName())) deleteRecursively(file);
        }
    }

    private static void writeManifest(File manifest, Downloaded downloaded) throws IOException {
        AtomicFile file = new AtomicFile(manifest);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(GSON.toJson(downloaded).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        return file.delete();
    }

    private static void checkCancelled(Task task) throws CancelledException {
        if (task.cancelled) throw new CancelledException();
    }

    private void notifyChanged(Task task) {
        main.post(() -> {
            for (Listener listener : listeners) listener.onTaskChanged(task);
        });
    }

    private static final class CancelledException extends Exception {
    }
}
