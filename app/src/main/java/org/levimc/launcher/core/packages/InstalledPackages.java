package org.levimc.launcher.core.packages;

import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.ModManager;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.packages.engine.Listener;
import org.levimc.launcher.core.packages.engine.PackageEngine;
import org.levimc.launcher.core.packages.engine.PackageException;
import org.levimc.launcher.core.packages.engine.SemVer;
import org.levimc.launcher.core.versions.GameVersion;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The packages installed in one instance, seen from its mods: which package owns a mod folder, what
 * depends on what, and the mod load order that follows the dependencies.
 */
public final class InstalledPackages {
    /** One installed package. */
    public static final class Pkg {
        public final String identity, id, variant, version, ref;
        public final boolean explicit;
        public final List<String> files;
        /** Mod folders below mods/ holding its files. */
        public final List<String> folders;
        /** Identities of the installed packages it depends on. */
        public final List<String> dependencies;

        public Pkg(String identity, String id, String variant, String version, boolean explicit, String ref,
                   List<String> files, List<String> folders, List<String> dependencies) {
            this.identity = identity == null ? "" : identity;
            this.id = id == null ? "" : id;
            this.variant = variant == null ? "" : variant;
            this.version = version == null ? "" : version;
            this.explicit = explicit;
            this.ref = ref == null ? "" : ref;
            this.files = list(files);
            this.folders = list(folders);
            this.dependencies = list(dependencies);
        }

        /** Installed from a local file or directory. */
        public boolean isLocal() {
            return ref.regionMatches(true, 0, "file:", 0, 5);
        }

        @Override
        public String toString() {
            return identity + "@" + version + (explicit ? "" : " (dependency)");
        }
    }

    private final File dir;
    private final String side, platform;
    private final List<Pkg> packages;

    InstalledPackages(File dir, String side, String platform, List<Pkg> packages) {
        this.dir = dir;
        this.side = side;
        this.platform = platform;
        this.packages = Collections.unmodifiableList(packages);
    }

    /** The last read of each workspace, with the stamp of the files it was read from. */
    private static final Map<String, Object[]> READS = new HashMap<>();

    /** The packages of the workspace dir; a read is reused while its state files are unchanged. */
    public static InstalledPackages read(File dir) throws PackageService.Failure {
        String key = dir.getAbsolutePath();
        String stamp = stamp(new File(dir, ".lip/state.json")) + "|" + stamp(new File(dir, "tooth_lock.json"));
        synchronized (READS) {
            Object[] last = READS.get(key);
            if (last != null && last[0].equals(stamp)) return (InstalledPackages) last[1];
        }
        InstalledPackages ws;
        try {
            ws = of(dir, new PackageEngine(dir, null, null, PackageService.rid(), null).state());
        } catch (PackageException e) {
            throw new PackageService.Failure(PackageService.codeOf(e, PackageService.ERR_PKG_WORKSPACE_INVALID), PackageService.message(e), e);
        }
        synchronized (READS) {
            READS.put(key, new Object[]{stamp, ws});
        }
        return ws;
    }

    /** Identity, time and size of a file (an atomic rewrite changes its identity); "-" when it is missing. */
    private static String stamp(File f) {
        try {
            BasicFileAttributes a = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
            return a.fileKey() + "@" + a.lastModifiedTime() + "#" + a.size();
        } catch (IOException e) {
            return "-";
        }
    }

    public static InstalledPackages of(GameVersion version) throws PackageService.Failure {
        return read(PackageService.get().workspaceDir(version));
    }

    static InstalledPackages of(File dir, PackageEngine.State state) {
        List<Pkg> out = new ArrayList<>();
        for (PackageEngine.Installed p : state.packages) {
            List<String> folders = new ArrayList<>();
            for (String f : p.files) {
                String folder = modFolderOf(f);
                if (folder != null && !containsKey(folders, folder)) folders.add(folder);
            }
            List<String> deps = new ArrayList<>();
            for (Map.Entry<String, String> dep : p.deps().entrySet()) {
                SemVer.Range range = SemVer.Range.parse(dep.getValue());
                for (PackageEngine.Installed q : state.packages) {
                    if (q != p && key(q.identity).equals(key(dep.getKey()))
                            && (range == null || range.matches(q.version)) && !deps.contains(q.identity)) {
                        deps.add(q.identity);
                    }
                }
            }
            out.add(new Pkg(p.identity, p.id, p.variant, p.version, p.explicit, p.source, p.files, folders, deps));
        }
        return new InstalledPackages(dir, state.side, state.platform, out);
    }

    /** The mod folder of a workspace path "mods/&lt;folder&gt;/...", else null. */
    public static String modFolderOf(String file) {
        String[] parts = file.trim().replace('\\', '/').split("/", -1);
        return parts.length >= 3 && parts[0].equalsIgnoreCase("mods") && !parts[1].isEmpty() ? parts[1] : null;
    }

    public File dir() {
        return dir;
    }

    public String side() {
        return side;
    }

    public String platform() {
        return platform;
    }

    /** Whether the workspace has a state file (else it is empty or read from a tooth_lock.json). */
    public boolean hasStateFile() {
        return new File(dir, ".lip/state.json").isFile();
    }

    public List<Pkg> packages() {
        return packages;
    }

    public boolean isEmpty() {
        return packages.isEmpty();
    }

    public int explicitCount() {
        int n = 0;
        for (Pkg p : packages) if (p.explicit) n++;
        return n;
    }

    /** The package owning the mod folder (case-insensitive), or null. */
    public Pkg ownerOf(String folder) {
        if (folder == null) return null;
        for (Pkg p : packages) if (containsKey(p.folders, folder)) return p;
        return null;
    }

    public boolean ownsFile(String relativePath) {
        String k = key(relativePath.replace('\\', '/').replaceFirst("^(\\./)+", ""));
        for (Pkg p : packages) for (String f : p.files) if (key(f).equals(k)) return true;
        return false;
    }

    public Pkg byIdentity(String identity) {
        for (Pkg p : packages) if (key(p.identity).equals(key(identity))) return p;
        return null;
    }

    public Pkg byId(String id) {
        for (Pkg p : packages) if (key(p.id).equals(key(id))) return p;
        return null;
    }

    /** The installed LeviLamina (any variant); null when none. */
    public Pkg leviLamina() {
        for (Pkg p : packages) if (PackageService.isLeviLamina(p.id)) return p;
        return null;
    }

    /**
     * Lowercased names of the mod folders that make up the loader: LeviLamina's and those of the packages installed
     * only as its dependencies. They are shown as the loader, not as mods.
     */
    public Set<String> loaderFolderKeys() {
        Set<String> out = new HashSet<>();
        Pkg ll = leviLamina();
        if (ll == null) return out;
        Set<Pkg> loader = new HashSet<>();
        loader.add(ll);
        loader.addAll(transitiveDependenciesOf(ll));
        for (Pkg p : loader) {
            if (p != ll && (p.explicit || !loader.containsAll(dependentsOf(p)))) continue;
            for (String f : p.folders) out.add(key(f));
        }
        return out;
    }

    /** The installed packages that depend on p directly. */
    public List<Pkg> dependentsOf(Pkg p) {
        List<Pkg> out = new ArrayList<>();
        if (p != null) for (Pkg q : packages) if (q != p && containsKey(q.dependencies, p.identity)) out.add(q);
        return out;
    }

    /** The explicitly installed packages that need p, directly or through other packages. */
    public List<Pkg> explicitDependentsOf(Pkg p) {
        Set<Pkg> seen = closure(p, true);
        List<Pkg> out = new ArrayList<>();
        for (Pkg q : packages) if (q != p && q.explicit && seen.contains(q)) out.add(q);
        return out;
    }

    /** Every installed package p needs, directly or through other packages. */
    public List<Pkg> transitiveDependenciesOf(Pkg p) {
        Set<Pkg> seen = closure(p, false);
        List<Pkg> out = new ArrayList<>();
        for (Pkg q : packages) if (q != p && seen.contains(q)) out.add(q);
        return out;
    }

    private Set<Pkg> closure(Pkg p, boolean dependents) {
        Set<Pkg> seen = new HashSet<>();
        Deque<Pkg> queue = new ArrayDeque<>();
        if (p != null) {
            queue.add(p);
            seen.add(p);
        }
        while (!queue.isEmpty()) {
            Pkg next = queue.poll();
            List<Pkg> step = new ArrayList<>();
            if (dependents) step.addAll(dependentsOf(next));
            else for (String identity : next.dependencies) if (byIdentity(identity) != null) step.add(byIdentity(identity));
            for (Pkg q : step) if (seen.add(q)) queue.add(q);
        }
        return seen;
    }

    // #region Mod folders

    /** The result of removing a mod folder. */
    public static final class RemoveResult {
        public final String code, detail;
        public final Pkg owner;
        public final List<Pkg> dependents;
        /** Mod folders of the uninstalled package that are still there (kept by preserve). */
        public final List<String> keptFolders;

        RemoveResult(String code, Pkg owner, List<Pkg> dependents, String detail, List<String> keptFolders) {
            this.code = code == null ? "" : code;
            this.owner = owner;
            this.dependents = dependents == null ? Collections.emptyList() : dependents;
            this.detail = detail == null ? "" : detail;
            this.keptFolders = keptFolders == null ? Collections.emptyList() : keptFolders;
        }

        public boolean ok() {
            return code.isEmpty();
        }
    }

    /**
     * Removes a mod folder of the instance: a package-owned folder uninstalls its package (refused for
     * a dependency), any other folder is deleted through the mod manager.
     */
    public static RemoveResult removeFolder(Cancel c, Listener l, GameVersion version, String folder) {
        if (version == null || folder == null || folder.trim().isEmpty()) {
            return new RemoveResult(PackageService.ERR_TARGET_NOT_FOUND, null, null, "", null);
        }
        InstalledPackages ws;
        try {
            ws = of(version);
        } catch (PackageService.Failure e) {
            return new RemoveResult(PackageService.ERR_PKG_WORKSPACE_INVALID, null, null, e.getMessage(), null);
        }
        Pkg owner = ws.ownerOf(folder);
        if (owner == null) {
            if (PackageService.get().busyReason(ws.dir) != null) {
                return new RemoveResult(PackageService.ERR_PKG_WORKSPACE_BUSY, null, null, folder, null);
            }
            return ModManager.forVersion(version).deleteMod(folder) ? new RemoveResult("", null, null, "", null)
                    : new RemoveResult(PackageService.ERR_MOD_DELETE_FAILED, null, null, folder, null);
        }
        String code = PackageService.get().uninstallById(c, l, version, owner.id, owner.identity);
        if (code.equals(PackageService.ERR_PACKAGE_REQUIRED_BY_DEPENDENTS) || code.equals(PackageService.ERR_PACKAGE_DEMOTED_TO_DEPENDENCY)) {
            List<Pkg> dependents = Collections.emptyList();
            try {
                InstalledPackages after = of(version);
                Pkg now = after.byId(owner.id);
                dependents = after.explicitDependentsOf(now != null ? now : owner);
            } catch (PackageService.Failure e) {
                PackageService.warn("cannot read the packages after uninstalling " + owner.identity + ": " + e.getMessage());
            }
            return new RemoveResult(code, owner, dependents, owner.identity, null);
        }
        return new RemoveResult(code, owner, null, owner.identity, code.isEmpty() ? keptFolders(ws.dir, owner) : null);
    }

    /** The mod folders of pkg that still exist in the workspace root. */
    public static List<String> keptFolders(File root, Pkg pkg) {
        List<String> out = new ArrayList<>();
        for (String folder : pkg.folders) if (new File(new File(root, "mods"), folder).isDirectory()) out.add(folder);
        return out;
    }

    // #endregion

    // #region Load order

    /**
     * current reordered so that every package's mod folders come after the folders of the packages it
     * depends on (stable otherwise; dependency cycles have no order).
     */
    public static List<String> order(List<String> current, InstalledPackages ws) {
        List<String> out = new ArrayList<>();
        if (current == null) return out;
        Set<String> present = new HashSet<>();
        for (String id : current) present.add(key(id));
        if (ws == null || ws.isEmpty()) return new ArrayList<>(current);
        Map<String, Set<String>> after = new HashMap<>();
        Map<Pkg, Set<String>> byPackage = new HashMap<>();
        Map<Pkg, List<Pkg>> deps = new HashMap<>();
        for (String id : current) {
            Pkg owner = ws.ownerOf(id);
            if (owner == null) continue;
            Set<String> prereq = byPackage.get(owner);
            if (prereq == null) {
                prereq = new HashSet<>();
                for (Pkg dep : deps.computeIfAbsent(owner, ws::transitiveDependenciesOf)) {
                    if (deps.computeIfAbsent(dep, ws::transitiveDependenciesOf).contains(owner)) continue;
                    for (String f : dep.folders) {
                        if (!containsKey(owner.folders, f) && present.contains(key(f))) prereq.add(key(f));
                    }
                }
                byPackage.put(owner, prereq);
            }
            if (!prereq.isEmpty()) after.put(key(id), prereq);
        }
        List<String> remaining = new ArrayList<>(current);
        Set<String> emitted = new HashSet<>();
        while (!remaining.isEmpty()) {
            int pick = 0;
            for (int i = 0; i < remaining.size(); i++) {
                Set<String> prereq = after.get(key(remaining.get(i)));
                if (prereq == null || emitted.containsAll(prereq)) {
                    pick = i;
                    break;
                }
            }
            String id = remaining.remove(pick);
            emitted.add(key(id));
            out.add(id);
        }
        return out;
    }

    /** Applies {@link #order} to the mod manager's list; true when it moved something. */
    public static boolean applyLoadOrder(ModManager modManager, InstalledPackages ws) {
        if (ws == null || ws.isEmpty()) return false;
        synchronized (modManager) {
            List<String> ids = new ArrayList<>();
            Map<String, Mod> byId = new HashMap<>();
            for (Mod m : modManager.getMods()) {
                ids.add(m.getId());
                byId.put(m.getId(), m);
            }
            List<String> ordered = order(ids, ws);
            if (ordered.equals(ids)) return false;
            List<Mod> reordered = new ArrayList<>();
            for (String id : ordered) reordered.add(byId.get(id));
            modManager.reorderMods(reordered);
            PackageService.info("reordered mods after their dependencies: " + ordered);
            return true;
        }
    }

    /** After an operation on dir: reloads the mod list of the instance the mod manager shows, in load order. */
    static void sync(File dir) {
        try {
            ModManager modManager = ModManager.getInstance();
            GameVersion current = modManager.getCurrentVersion();
            if (current != null && current.modsDir != null && sameDir(current.modsDir.getParentFile(), dir)) {
                sync(modManager, current);
            }
        } catch (RuntimeException e) {
            PackageService.warn("cannot reload the mods of " + dir, e);
        }
    }

    public static void sync(ModManager modManager, GameVersion version) {
        synchronized (modManager) {
            if (modManager.getCurrentVersion() != version) return;
            modManager.reloadMods();
            File dir = version.modsDir.getParentFile();
            try {
                applyLoadOrder(modManager, read(dir));
            } catch (PackageService.Failure e) {
                PackageService.warn("cannot order the mods of " + dir + ": " + e.getMessage());
            }
            modManager.refreshMods();
        }
    }

    public static boolean sameDir(File a, File b) {
        if (a == null || b == null) return false;
        return canonical(a).equals(canonical(b));
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    // #endregion

    private static List<String> list(List<String> l) {
        return l == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(l));
    }

    private static String key(String s) {
        return PackageService.key(s);
    }

    private static boolean containsKey(List<String> list, String s) {
        for (String x : list) if (key(x).equals(key(s))) return true;
        return false;
    }
}
