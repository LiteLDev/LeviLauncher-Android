package org.levimc.launcher.core.packages.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The steps of installing and uninstalling packages (workspace 2, 4). It runs twice per operation: first
 * as a plan on a simulated workspace (no journal, no hooks), which makes every early failure (workspace 6
 * rule 1) happen before any change, each step checked against the workspace as the steps before it leave
 * it; then on disk, each change through the {@link Journal}. Ownership is tracked across the steps.
 */
final class Workspace {
    static final String MODS = "mods";
    private static final int NONE = 0, FILE = 1, DIR = 2;

    private final File root;
    /** Null while planning. */
    private final Journal journal;
    private final boolean runHooks;
    private final Cancel cancel;
    private final Listener listener;
    /** Owned path to the lowercase identity of its package. */
    private final Map<String, String> owners = new HashMap<>();
    /** Planning: what the earlier steps made of a path (it overrides the disk). */
    private final Map<String, Integer> simulated = new HashMap<>();

    Workspace(File root, List<StateFile.Pkg> installed, Journal journal, boolean runHooks, Cancel cancel, Listener listener) {
        this.root = root;
        this.journal = journal;
        this.runHooks = runHooks && journal != null;
        this.cancel = cancel;
        this.listener = listener;
        for (StateFile.Pkg p : installed) for (String f : p.files) owners.put(f, Resolver.key(p.identity()));
    }

    /** Creates {@code mods} when missing; a link there is a failure (workspace 2). */
    void prepareMods() throws PackageException {
        if (Files.isSymbolicLink(new File(root, MODS).toPath())) {
            throw new PackageException(PackageException.Kind.INVALID_WORKSPACE, null, "'mods' in the workspace is a link");
        }
        int k = kind(MODS);
        if (k == FILE) throw conflict("'mods' in the workspace is not a directory");
        if (k == NONE) mkdir(MODS);
    }

    /** Uninstalls an installed package (workspace 4). */
    void uninstall(StateFile.Pkg p, Manifest.Entry e) throws PackageException {
        String me = Resolver.key(p.identity());
        hooks(e.preUninstall, p.identity(), "pre_uninstall");
        for (String f : p.files) if (me.equals(owners.get(f))) owners.remove(f);
        int done = 0;
        for (String f : p.files) {
            cancel.check();
            listener.progress("remove:" + p.identity(), Listener.Stage.REMOVE, p.identity(), done++, p.files.size(), false);
            if (!matchesAny(e.preserve, f) && kind(f) == FILE) delete(f);
        }
        for (String glob : e.purge) {
            String[] segs = (glob.endsWith("/") ? glob.substring(0, glob.length() - 1) : glob).split("/");
            int literal = 0;
            while (literal < segs.length - 1 && !Glob.hasMeta(segs[literal])) literal++;
            String base = String.join("/", Arrays.asList(segs).subList(0, literal));
            int depth = glob.contains("**") ? Integer.MAX_VALUE : segs.length - literal;
            if (base.isEmpty() || kind(base) == DIR) purge(glob, base, depth);
        }
        listener.progress("remove:" + p.identity(), Listener.Stage.REMOVE, p.identity(), p.files.size(), p.files.size(), false);
        hooks(e.postUninstall, p.identity(), "post_uninstall");
    }

    /** Deletes the paths below {@code dir} that match {@code glob} exactly, {@code depth} levels down. */
    private void purge(String glob, String dir, int depth) throws PackageException {
        for (String name : list(dir)) {
            String path = dir.isEmpty() ? name : dir + "/" + name;
            boolean isDir = kind(path) == DIR;
            if (path.equals(Journal.DIR)) continue;
            boolean match = !path.equals(MODS) && (glob.endsWith("/")
                    ? isDir && Glob.matches(glob.substring(0, glob.length() - 1), path) : !isDir && Glob.matches(glob, path));
            if (match) deleteAll(path, isDir);
            else if (isDir && depth > 1) purge(glob, path, depth - 1);
        }
    }

    /** Deletes a file, or a directory with everything below it, sparing files that packages own. */
    private void deleteAll(String path, boolean isDir) throws PackageException {
        cancel.check();
        if (!isDir) {
            if (!owners.containsKey(path)) delete(path);
            return;
        }
        for (String name : list(path)) deleteAll(path + "/" + name, kind(path + "/" + name) == DIR);
        deleteOnCommit(path);
    }

    /** Installs a package (workspace 4); returns the files it owns, in the order written. */
    List<String> install(Resolver r, Resolver.Candidate c) throws PackageException {
        String me = Resolver.key(c.identity);
        Manifest.Entry e = c.entry;
        Map<String, Object[]> writes = new LinkedHashMap<>(); // destination -> {archive, path in it}
        for (Manifest.Asset a : e.assets) {
            Archive archive = r.asset(c, a);
            if (a.type.equals("mcmod")) {
                for (String f : archive.files()) put(writes, MODS + "/" + c.modName() + "/" + f, archive, f);
                continue;
            }
            List<String> files = new ArrayList<>(archive.files());
            if (a.type.equals("self")) files.remove(c.manifest.packageFile);
            for (Manifest.Place pl : a.place) {
                String prefix = pl.src;
                if (pl.form == Manifest.Place.Form.GLOB) {
                    String[] segs = pl.src.split("/");
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < segs.length - 1 && !Glob.hasMeta(segs[i]); i++) b.append(segs[i]).append('/');
                    prefix = b.toString();
                }
                int n = 0;
                for (String f : files) {
                    boolean hit = pl.form == Manifest.Place.Form.FILE ? f.equals(pl.src)
                            : pl.form == Manifest.Place.Form.DIRECTORY ? f.startsWith(pl.src) : Glob.matches(pl.src, f);
                    if (!hit) continue;
                    n++;
                    put(writes, pl.form == Manifest.Place.Form.FILE ? pl.dest : pl.dest + f.substring(prefix.length()), archive, f);
                }
                if (n == 0 && pl.form != Manifest.Place.Form.GLOB) {
                    throw new PackageException(PackageException.Kind.INVALID_PACKAGE, c.source.toString(),
                            "The " + a.type + " asset of " + c.identity + " has no file " + (pl.form == Manifest.Place.Form.FILE
                                    ? "'" + pl.src + "'" : "below '" + pl.src + "'"));
                }
            }
        }
        hooks(e.preInstall, c.identity, "pre_install");
        List<String> owned = new ArrayList<>();
        for (Map.Entry<String, Object[]> w : writes.entrySet()) {
            cancel.check();
            listener.progress("place:" + c.identity, Listener.Stage.PLACE, c.identity, owned.size(), writes.size(), false);
            String dest = w.getKey();
            checkWrite(dest, me);
            if (kind(dest) == FILE && matchesAny(e.preserve, dest)) continue;
            for (int i = dest.indexOf('/'); i >= 0; i = dest.indexOf('/', i + 1)) {
                if (kind(dest.substring(0, i)) == NONE) mkdir(dest.substring(0, i));
            }
            write(dest, (Archive) w.getValue()[0], (String) w.getValue()[1]);
            owners.put(dest, me);
            owned.add(dest);
        }
        listener.progress("place:" + c.identity, Listener.Stage.PLACE, c.identity, writes.size(), writes.size(), false);
        hooks(e.postInstall, c.identity, "post_install");
        return owned;
    }

    /** Adds a write, the later one winning; '.'-segments and empty segments of archive paths are dropped. */
    private static void put(Map<String, Object[]> writes, String dest, Archive a, String src) {
        StringBuilder b = new StringBuilder();
        for (String s : dest.split("/")) if (!s.isEmpty() && !s.equals(".")) b.append(b.length() == 0 ? "" : "/").append(s);
        if (b.length() == 0) return;
        writes.remove(b.toString());
        writes.put(b.toString(), new Object[] {a, src});
    }

    /** The write rules of workspace 4, and a file never replaces a directory or goes below a file. */
    private void checkWrite(String dest, String me) throws PackageException {
        String low = Resolver.key(dest); // .LIP names .lip on a case-insensitive file system (shared storage)
        if (low.equals(Journal.DIR) || low.startsWith(Journal.DIR + "/")) throw conflict("A package cannot write " + dest);
        String owner = owners.get(dest);
        if (owner != null && !owner.equals(me)) throw conflict(dest + " is owned by " + owner);
        if (kind(dest) == DIR) throw conflict(dest + " is a directory in the workspace");
        for (int i = dest.indexOf('/'); i >= 0; i = dest.indexOf('/', i + 1)) {
            if (kind(dest.substring(0, i)) == FILE) throw conflict(dest.substring(0, i) + " is not a directory in the workspace");
        }
    }

    private static PackageException conflict(String message) {
        return new PackageException(PackageException.Kind.CONFLICT, null, message);
    }

    static boolean matchesAny(List<String> globs, String path) {
        for (String g : globs) if (Glob.matches(g, path)) return true;
        return false;
    }

    // The workspace: simulated while planning, else on disk through the journal.

    /** NONE, FILE (a link too) or DIR; NONE below anything but a directory, so nothing is changed through a link. */
    private int kind(String path) {
        Integer k = simulated.get(path);
        if (k != null) return k;
        int slash = path.lastIndexOf('/');
        if (slash > 0 && kind(path.substring(0, slash)) != DIR) return NONE;
        File f = new File(root, path);
        return Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS) ? DIR : Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS) ? FILE : NONE;
    }

    /** The names in a directory, sorted. */
    private List<String> list(String dir) {
        List<String> names = new ArrayList<>();
        String[] onDisk = new File(root, dir).list();
        if (onDisk != null) names.addAll(Arrays.asList(onDisk));
        String prefix = dir.isEmpty() ? "" : dir + "/";
        for (String p : simulated.keySet()) {
            if (p.startsWith(prefix) && p.indexOf('/', prefix.length()) < 0 && !names.contains(p.substring(prefix.length()))) {
                names.add(p.substring(prefix.length()));
            }
        }
        names.removeIf(n -> kind(prefix + n) == NONE);
        names.sort(null);
        return names;
    }

    private void mkdir(String path) throws PackageException {
        if (journal == null) simulated.put(path, DIR);
        else journal.createDirectory(path);
    }

    private void write(String path, Archive a, String src) throws PackageException {
        if (journal == null) {
            simulated.put(path, FILE);
            return;
        }
        try {
            journal.writeFile(path, a.open(src));
        } catch (IOException e) {
            throw Journal.io("Cannot read " + src + " of an archive", e);
        }
    }

    /** Deletes a file; the directories above it, except {@code mods}, go when the operation completes if empty. */
    private void delete(String path) throws PackageException {
        if (journal == null) simulated.put(path, NONE);
        else journal.deleteFile(path);
        if (path.indexOf('/') > 0) deleteOnCommit(path.substring(0, path.lastIndexOf('/')));
    }

    /** Records {@code dir} and the directories above it, except {@code mods}, for deletion at completion when empty. */
    private void deleteOnCommit(String dir) throws PackageException {
        for (int i = dir.length(); journal != null && i > 0; i = dir.lastIndexOf('/', i - 1)) {
            if (!dir.substring(0, i).equals(MODS)) journal.deleteDirectoryOnCommit(dir.substring(0, i));
        }
    }

    /**
     * Runs hook commands with {@code sh -c} in the workspace. Each runs in its own session so that cancelling
     * kills its whole process group; standard output and error go to the log.
     */
    private void hooks(List<String> commands, String pkg, String member) throws PackageException {
        if (!runHooks) return;
        for (String command : commands) {
            cancel.check();
            listener.log(Listener.Level.INFO, pkg + " " + member + ": " + command);
            listener.progress("script:" + pkg, Listener.Stage.SCRIPTS, pkg, 0, -1, false);
            Process p;
            try {
                p = new ProcessBuilder("sh", "-c", "echo $$; command -v setsid >/dev/null 2>&1 && exec setsid sh -c \"$0\"; "
                        + "exec sh -c \"$0\"", command).directory(root).redirectErrorStream(true).start();
            } catch (IOException e) {
                throw Journal.io("Cannot run a hook command of " + pkg, e);
            }
            String[] pid = new String[1];
            int exit;
            try (Cancel.Handle h = cancel.onCancel(() -> kill(p, pid[0]));
                 BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                p.getOutputStream().close();
                pid[0] = out.readLine();
                if (cancel.isCancelled()) kill(p, pid[0]);
                for (String line; (line = out.readLine()) != null; ) listener.log(Listener.Level.INFO, line);
                exit = p.waitFor();
            } catch (IOException | InterruptedException e) {
                kill(p, pid[0]);
                cancel.check();
                throw new PackageException(PackageException.Kind.HOOK_FAILED, pkg, "A hook command of " + pkg + " failed: " + e, e);
            }
            cancel.check();
            listener.progress("script:" + pkg, Listener.Stage.SCRIPTS, pkg, 1, 1, false);
            if (exit != 0) {
                throw new PackageException(PackageException.Kind.HOOK_FAILED, pkg,
                        "The " + member + " hook command of " + pkg + " exited with " + exit + ": " + command);
            }
        }
    }

    /** Kills the hook's process group (its pid is its group id under setsid), then the process. */
    private static void kill(Process p, String pid) {
        if (pid != null && pid.matches("[0-9]+")) {
            try {
                new ProcessBuilder("sh", "-c", "kill -9 -" + pid + " 2>/dev/null || kill -9 " + pid + " 2>/dev/null").start().waitFor();
            } catch (IOException | InterruptedException ignored) {
                // destroyed below
            }
        }
        p.destroy();
    }
}
