package org.levimc.launcher.core.packages.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The workspace lock (workspace 7.2) and journal (7.3): every change of an operation is recorded
 * before it is made, so that it can be completed or undone (7.5), also by the next operation after
 * a crash. Held for one operation; closing it releases the lock.
 */
final class Journal implements Closeable {
    static final String DIR = ".lip", FILE = DIR + "/journal", STATE_TMP = DIR + "/state.json.tmp";
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** The {@code .lip} directories whose lock this process holds. */
    private static final Set<String> HELD = Collections.synchronizedSet(new HashSet<>());

    final File root;
    private final String key;
    private final RandomAccessFile lockFile;
    private final List<JsonObject> records = new ArrayList<>();
    private final Set<String> onCommit = new HashSet<>();
    private OutputStream out;

    private Journal(File root, String key, RandomAccessFile lockFile) {
        this.root = root;
        this.key = key;
        this.lockFile = lockFile;
    }

    /**
     * Takes the workspace lock: {@code WORKSPACE_LOCKED} when another process or operation holds it. Android's
     * {@code FileChannel.tryLock} is an fcntl lock, not flock; no other process of the protocol runs on the device.
     * Operations of this process are excluded before the file is opened: closing a second handle on it would
     * release the fcntl lock of the first.
     */
    static Journal lock(File root) throws PackageException {
        File dir = new File(root, DIR);
        String key = dir.getAbsoluteFile().toPath().normalize().toString();
        RandomAccessFile f = null;
        try {
            if (HELD.add(key)) {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                f = new RandomAccessFile(new File(dir, "lock"), "rw");
                if (f.getChannel().tryLock() != null) return new Journal(root, key, f);
                HELD.remove(key);
            }
        } catch (IOException e) {
            HELD.remove(key);
            closeQuietly(f);
            throw io("Cannot lock the workspace " + root, e);
        }
        closeQuietly(f);
        throw new PackageException(PackageException.Kind.WORKSPACE_LOCKED, null, "Another operation holds the workspace " + root);
    }

    static boolean exists(File root) {
        return new File(root, FILE).exists();
    }

    /** Recovers an interrupted operation (7.5): completes it when it committed, else undoes it. */
    void recover(Listener listener) throws PackageException {
        records.clear();
        try {
            String text = new String(Files.readAllBytes(file(FILE).toPath()), StandardCharsets.UTF_8);
            int end = text.lastIndexOf('\n');
            for (String line : end < 0 ? new String[0] : text.substring(0, end).split("\n", -1)) {
                JsonObject r = parse(line);
                if (r != null) records.add(r);
            }
        } catch (IOException e) {
            throw io("Cannot read the journal", e);
        }
        boolean committed = false;
        for (JsonObject r : records) committed |= op(r).equals("Commit");
        listener.log(Listener.Level.WARN, "Recovering an interrupted operation: " + (committed ? "completing it" : "undoing it"));
        if (committed) complete();
        else undo();
    }

    /** A valid record of a journal line, or null (7.3: other lines are ignored). */
    private static JsonObject parse(String line) {
        try {
            JsonElement v = TextFormats.parseJson(line.getBytes(StandardCharsets.UTF_8));
            JsonObject o = v.getAsJsonObject();
            String op = Manifest.string(o, "op", true);
            if (op.equals("Commit")) return o;
            if (!op.matches("Begin|CreateDirectory|CreateFile|Backup|DeleteDirectoryOnCommit")) return null;
            if (!safe(Manifest.string(o, "path", true)) || op.equals("Backup") && !safe(Manifest.string(o, "to", true))) return null;
            return o;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A relative path that stays in the workspace. */
    private static boolean safe(String p) {
        return !p.startsWith("/") && !("/" + p + "/").contains("/../") && p.indexOf('\0') < 0;
    }

    private static String op(JsonObject r) {
        return r.get("op").getAsString();
    }

    private static String path(JsonObject r, String member) {
        return r.get(member).getAsString();
    }

    /** Starts the journal with a "Begin" record; an existing journal is never overwritten (7.4 step 5). */
    void begin() throws PackageException {
        records.clear();
        onCommit.clear();
        try {
            if (!file(FILE).createNewFile()) throw new IOException("a journal already exists");
            out = new FileOutputStream(file(FILE), true);
        } catch (IOException e) {
            throw io("Cannot create the journal", e);
        }
        append(record("Begin", "", Instant.now().toString()));
    }

    private static JsonObject record(String op, String path, String to) {
        JsonObject r = new JsonObject();
        r.addProperty("op", op);
        if (path != null) r.addProperty("path", path);
        if (to != null) r.addProperty("to", to);
        return r;
    }

    /** Appends a record and hands it to the operating system before the change it records. */
    private void append(JsonObject r) throws PackageException {
        try {
            out.write((r + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            records.add(r);
        } catch (IOException e) {
            throw io("Cannot write the journal", e);
        }
    }

    void createDirectory(String path) throws PackageException {
        append(record("CreateDirectory", path, null));
        if (!file(path).mkdir()) throw io("Cannot create the directory " + path, null);
    }

    /** Writes a file, backing up the one it replaces. */
    void writeFile(String path, InputStream in) throws PackageException {
        try (InputStream src = in) {
            if (Files.exists(file(path).toPath(), NOFOLLOW)) deleteFile(path);
            append(record("CreateFile", path, null));
            Files.copy(src, file(path).toPath());
        } catch (IOException e) {
            throw io("Cannot write " + path, e);
        }
    }

    /** Deletes a file (or link) by renaming it to a backup that is deleted when the operation completes. */
    void deleteFile(String path) throws PackageException {
        String to;
        do {
            to = path + String.format(Locale.ROOT, ".%08x.bak", RANDOM.nextInt());
        } while (Files.exists(file(to).toPath(), NOFOLLOW));
        append(record("Backup", path, to));
        try {
            Files.move(file(path).toPath(), file(to).toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw io("Cannot delete or replace " + path, e);
        }
    }

    void deleteDirectoryOnCommit(String path) throws PackageException {
        if (onCommit.add(path)) append(record("DeleteDirectoryOnCommit", path, null));
    }

    /**
     * Commits (7.4 steps 7-9): writes the state file as {@code state.json.tmp}, appends "Commit", renames it to
     * {@code state.json} and completes. When the rename fails, the journal loses its "Commit" (the caller undoes it).
     */
    void commit(byte[] state) throws PackageException {
        File tmp = file(STATE_TMP);
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(state);
            o.getFD().sync();
        } catch (IOException e) {
            throw io("Cannot write " + STATE_TMP, e);
        }
        append(record("Commit", null, null));
        try {
            Files.move(tmp.toPath(), file(StateFile.PATH).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            records.remove(records.size() - 1);
            rewrite(new ArrayList<>(records)); // the caller then undoes
            throw io("Cannot replace the state file", e);
        }
        complete();
    }

    /** Completes a committed operation (7.5). Files and directories that cannot be deleted stay. */
    private void complete() throws PackageException {
        closeOut();
        File tmp = file(STATE_TMP);
        if (tmp.exists()) {
            try {
                Files.move(tmp.toPath(), file(StateFile.PATH).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw io("Cannot replace the state file", e);
            }
        }
        List<String> dirs = new ArrayList<>();
        for (JsonObject r : records) {
            if (op(r).equals("Backup")) deleteQuietly(file(path(r, "to")).toPath());
            if (op(r).equals("DeleteDirectoryOnCommit")) dirs.add(path(r, "path"));
        }
        dirs.sort(Comparator.comparingInt(String::length).reversed());
        for (String d : dirs) {
            if (!d.isEmpty() && Files.isDirectory(file(d).toPath(), NOFOLLOW)) file(d).delete(); // only when empty
        }
        file(FILE).delete();
        records.clear();
    }

    /**
     * Undoes the operation (7.5) from its last record to its first. Records that cannot be undone stay in a
     * rewritten journal (with the directories above them) and make this fail with {@code RECOVERY_FAILED}.
     */
    void undo() throws PackageException {
        closeOut();
        deleteQuietly(file(STATE_TMP).toPath());
        List<JsonObject> kept = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0; i--) {
            JsonObject r = records.get(i);
            String op = op(r), p = r.has("path") ? path(r, "path") : null;
            Path at = p == null ? null : file(p).toPath();
            boolean done = true;
            try {
                if (op.equals("CreateFile") && Files.exists(at, NOFOLLOW) && !backupLost(i, p)) {
                    Files.delete(at);
                } else if (op.equals("Backup") && Files.exists(file(path(r, "to")).toPath(), NOFOLLOW)) {
                    Files.deleteIfExists(at);
                    Files.move(file(path(r, "to")).toPath(), at, StandardCopyOption.ATOMIC_MOVE);
                } else if (op.equals("CreateDirectory")) {
                    for (JsonObject k : kept) done &= !path(k, "path").startsWith(p + "/");
                    if (done && Files.isDirectory(at, NOFOLLOW)) file(p).delete(); // only when empty
                }
            } catch (IOException | RuntimeException e) {
                done = false;
            }
            if (!done) kept.add(0, r);
        }
        if (kept.isEmpty()) {
            file(FILE).delete();
            records.clear();
            return;
        }
        kept.add(0, record("Begin", "", null));
        rewrite(kept);
        throw new PackageException(PackageException.Kind.RECOVERY_FAILED, null,
                (kept.size() - 1) + " changes of the operation cannot be undone; the journal keeps them");
    }

    /** Whether an earlier "Backup" of {@code path} exists whose backup no longer exists. */
    private boolean backupLost(int before, String path) {
        for (int i = 0; i < before; i++) {
            JsonObject r = records.get(i);
            if (op(r).equals("Backup") && path(r, "path").equals(path) && !Files.exists(file(path(r, "to")).toPath(), NOFOLLOW)) {
                return true;
            }
        }
        return false;
    }

    /** Replaces the journal atomically by {@code list}. */
    private void rewrite(List<JsonObject> list) throws PackageException {
        StringBuilder b = new StringBuilder();
        for (JsonObject r : list) b.append(r).append('\n');
        try {
            closeOut();
            TextFormats.writeAtomically(file(FILE), b.toString().getBytes(StandardCharsets.UTF_8));
            records.clear();
            records.addAll(list);
        } catch (IOException e) {
            throw io("Cannot rewrite the journal", e);
        }
    }

    File file(String path) {
        return path.isEmpty() ? root : new File(root, path);
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // left in place (7.5)
        }
    }

    private void closeOut() {
        closeQuietly(out);
        out = null;
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    static PackageException io(String message, Exception cause) {
        return new PackageException(PackageException.Kind.IO, null,
                message + (cause == null ? "" : ": " + cause.getMessage()), cause);
    }

    /** Releases the workspace lock. */
    @Override
    public void close() {
        closeOut();
        closeQuietly(lockFile); // releases the lock
        HELD.remove(key);
    }
}
