package org.levimc.launcher.core.packages.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.OkHttpClient;

/**
 * A package manager for one workspace (a game instance directory) that follows the lip protocol: install,
 * update and uninstall (cli 5.1-5.3) as atomic operations (workspace 7.4), recovery of interrupted ones,
 * and the state of the workspace. Side {@code client}; platform (RID) as the state file records it, else the
 * device's. All calls block; call them off the main thread.
 */
public final class PackageEngine {
    public static final String SIDE = Manifest.SIDE_CLIENT;
    static final String TOOTH_LOCK = "tooth_lock.json";

    private enum Op { INSTALL, UPDATE, UNINSTALL }

    private final File root;
    private final Config config;
    private final OkHttpClient client;
    private final String devicePlatform, defaultGithubProxy;

    /** {@code defaultGithubProxy} (or null) applies when config.toml sets no {@code github_proxy}. */
    public PackageEngine(File workspace, Config config, OkHttpClient client, String devicePlatform, String defaultGithubProxy) {
        this.root = workspace;
        this.config = config;
        this.client = client;
        this.devicePlatform = devicePlatform;
        this.defaultGithubProxy = defaultGithubProxy;
    }

    /** The platform (RID) of an Android ABI ({@code Build.SUPPORTED_ABIS[0]}). */
    public static String platformForAbi(String abi) {
        switch (abi) {
            case "arm64-v8a": return "android-arm64";
            case "armeabi-v7a": return "android-arm";
            case "x86_64": return "android-x64";
            default: return "android-" + abi;
        }
    }

    /** {@code url} through the GitHub proxy prefix {@code proxy} when it is a github.com URL; else null. */
    public static String githubProxied(String proxy, String url) {
        return Http.proxied(proxy, url);
    }

    public static boolean hasJournal(File workspace) {
        return Journal.exists(workspace);
    }

    /** Installs packages; each becomes explicitly installed (cli 5.1). */
    public Plan install(List<String> sources, boolean ignoreScripts, Cancel cancel, Listener listener) throws PackageException {
        return run(Op.INSTALL, sources, ignoreScripts, cancel, listener);
    }

    /** Updates the installed packages the sources name, or every explicitly installed one without any (cli 5.3). */
    public Plan update(List<String> sources, boolean ignoreScripts, Cancel cancel, Listener listener) throws PackageException {
        return run(Op.UPDATE, sources, ignoreScripts, cancel, listener);
    }

    /** Uninstalls explicitly installed packages by identity, with the dependencies nothing else needs (cli 5.2). */
    public Plan uninstall(List<String> identities, boolean ignoreScripts, Cancel cancel, Listener listener) throws PackageException {
        return run(Op.UNINSTALL, identities, ignoreScripts, cancel, listener);
    }

    /** Recovers an interrupted operation under the workspace lock; false when there was none. */
    public boolean recover(Cancel cancel, Listener listener) throws PackageException {
        cancel.check();
        try (Journal j = Journal.lock(root)) {
            if (!Journal.exists(root)) return false;
            j.recover(listener);
            return true;
        }
    }

    /** The state file, read without the lock (workspace 7.2); a tooth_lock.json is read without commits. */
    public State state() throws PackageException {
        StateFile s = load(null);
        return new State(s == null ? new StateFile(SIDE, devicePlatform, Collections.<StateFile.Pkg>emptyList()) : s);
    }

    /** What an operation did. */
    public static final class Plan {
        public final List<Installed> install = new ArrayList<>(), uninstall = new ArrayList<>();
        public final List<String> markExplicit = new ArrayList<>(), unmarkExplicit = new ArrayList<>();

        public boolean isEmpty() {
            return install.isEmpty() && uninstall.isEmpty() && markExplicit.isEmpty() && unmarkExplicit.isEmpty();
        }
    }

    private Plan run(Op op, List<String> args, boolean ignoreScripts, Cancel cancel, Listener listener) throws PackageException {
        cancel.check();
        String proxy = config.githubProxy();
        Http http = new Http(client, config.cacheDir(), proxy != null ? proxy : defaultGithubProxy, cancel, listener);
        try (Journal journal = Journal.lock(root)) {
            if (Journal.exists(root)) journal.recover(listener);
            StateFile old = load(new Git(http));
            boolean migrated = old != null && !new File(root, StateFile.PATH).exists();
            if (old == null) old = new StateFile(SIDE, devicePlatform, Collections.<StateFile.Pkg>emptyList());
            if (!old.side.equals(SIDE)) {
                throw new PackageException(PackageException.Kind.ENV_MISMATCH, null, "The state file " + StateFile.PATH
                        + " records the side \"" + old.side + "\" in its \"side\" field, but LeviLauncher manages client"
                        + " workspaces only (\"" + SIDE + "\"). Server workspaces are managed by lip.");
            }
            try (Resolver r = new Resolver(SIDE, old.platform, http)) {
                List<Resolver.Member> members = solve(op, args, old, r);
                Map<String, StateFile.Pkg> byKey = new HashMap<>();
                for (StateFile.Pkg p : old.packages) byKey.put(Resolver.key(p.identity()), p);
                Set<String> kept = new HashSet<>();
                List<Resolver.Member> added = new ArrayList<>();
                Plan plan = new Plan();
                for (Resolver.Member m : members) {
                    StateFile.Pkg o = byKey.get(Resolver.key(m.candidate.identity));
                    if (o == null || !m.candidate.sameAs(o)) {
                        added.add(m);
                        continue;
                    }
                    kept.add(Resolver.key(o.identity()));
                    if (m.explicit && !o.explicit) plan.markExplicit.add(o.identity());
                    if (!m.explicit && o.explicit) plan.unmarkExplicit.add(o.identity());
                }
                List<StateFile.Pkg> removed = new ArrayList<>();
                for (StateFile.Pkg p : old.packages) if (!kept.contains(Resolver.key(p.identity()))) removed.add(0, p);
                listener.log(Listener.Level.INFO, "Uninstalling " + removed.size() + ", installing " + added.size() + " packages");
                Map<String, List<String>> files = steps(new Workspace(root, old.packages, null, false, cancel, listener), r, removed, added, old);
                cancel.check();
                if (!removed.isEmpty() || !added.isEmpty()) {
                    journal.begin();
                    try {
                        files = steps(new Workspace(root, old.packages, journal, !ignoreScripts, cancel, listener), r, removed, added, old);
                        journal.commit(newState(old, members, byKey, files, plan).toJson());
                    } catch (PackageException | RuntimeException e) {
                        try {
                            journal.undo();
                        } catch (PackageException u) {
                            e.addSuppressed(u);
                        }
                        throw e;
                    }
                } else {
                    try {
                        TextFormats.writeAtomically(new File(root, StateFile.PATH), newState(old, members, byKey, files, plan).toJson());
                    } catch (IOException e) {
                        throw Journal.io("Cannot write the state file", e);
                    }
                }
                for (StateFile.Pkg p : removed) plan.uninstall.add(new Installed(p, old.side, old.platform));
                if (migrated) new File(root, TOOTH_LOCK).delete();
                return plan;
            }
        }
    }

    /** The steps of an operation (design 1.5): uninstalls in reverse installation order, then installs in order. */
    private static Map<String, List<String>> steps(Workspace w, Resolver r, List<StateFile.Pkg> removed,
                                                   List<Resolver.Member> added, StateFile old) throws PackageException {
        if (!removed.isEmpty() || !added.isEmpty()) w.prepareMods(); // only an operation that changes the workspace (workspace 2)
        for (StateFile.Pkg p : removed) w.uninstall(p, r.installed(p).entry);
        Map<String, List<String>> files = new HashMap<>();
        for (Resolver.Member m : added) files.put(Resolver.key(m.candidate.identity), w.install(r, m.candidate));
        return files;
    }

    private static StateFile newState(StateFile old, List<Resolver.Member> members, Map<String, StateFile.Pkg> byKey,
                                      Map<String, List<String>> files, Plan plan) throws PackageException {
        List<StateFile.Pkg> pkgs = new ArrayList<>();
        for (Resolver.Member m : members) {
            Resolver.Candidate c = m.candidate;
            List<String> f = files.get(Resolver.key(c.identity));
            StateFile.Pkg o = byKey.get(Resolver.key(c.identity));
            StateFile.Pkg p = f == null ? new StateFile.Pkg(o.manifest, o.variant, m.explicit, c.source.toString(), o.commit, o.files)
                    : new StateFile.Pkg(c.manifest, c.source.variant, m.explicit, c.source.toString(), c.commit, f);
            pkgs.add(p);
            if (f != null) plan.install.add(new Installed(p, old.side, old.platform));
        }
        return new StateFile(old.side, old.platform, pkgs);
    }

    /**
     * The packages that satisfy the requests of the operation (cli 5.1-5.3), in installation order. An argument
     * names the installed package of its identity (for a URL or local path: of its first candidate).
     */
    private static List<Resolver.Member> solve(Op op, List<String> args, StateFile old, Resolver r) throws PackageException {
        List<Resolver.Request> requests = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (String a : args) {
            Source s = op == Op.UNINSTALL ? null : Source.parse(a);
            String identity = s == null ? a : s.kind == Source.Kind.REPO ? s.identity(s.location) : first(r.resolve(s, null, op == Op.UPDATE));
            StateFile.Pkg p = find(old, identity);
            if (p == null && op != Op.INSTALL) throw new PackageException(PackageException.Kind.NOT_INSTALLED, a, a + " is not installed");
            if (op == Op.UNINSTALL && !p.explicit) throw new PackageException(PackageException.Kind.NOT_EXPLICIT, a, a + " is not explicitly installed");
            if (op == Op.INSTALL && p != null && p.explicit) {
                throw new PackageException(PackageException.Kind.ALREADY_EXPLICITLY_INSTALLED, a, p.identity() + " is already explicitly installed");
            }
            if (!targets.add(Resolver.key(identity)) && s != null) {
                throw new PackageException(PackageException.Kind.INVALID_ARGUMENT, a, "Two package arguments request " + identity);
            }
            if (s != null) requests.add(new Resolver.Request(s, null, true, op == Op.UPDATE, op == Op.INSTALL, null));
        }
        for (StateFile.Pkg p : old.packages) {
            if (!p.explicit || targets.contains(Resolver.key(p.identity()))) continue;
            Source s = Source.parse(p.ref);
            boolean update = op == Op.UPDATE && args.isEmpty() && (s.kind != Source.Kind.LOCAL || new File(s.location).exists());
            requests.add(new Resolver.Request(s, null, true, update, !update, null));
        }
        List<Resolver.Candidate> installed = new ArrayList<>();
        for (StateFile.Pkg p : old.packages) installed.add(r.installed(p));
        return r.solve(requests, installed);
    }

    private static String first(Resolver.Candidates cs) throws PackageException {
        Resolver.Candidate c = cs.get(0);
        if (c == null) throw cs.none();
        return c.identity;
    }

    private static StateFile.Pkg find(StateFile s, String identity) {
        for (StateFile.Pkg p : s.packages) if (Resolver.key(p.identity()).equals(Resolver.key(identity))) return p;
        return null;
    }

    /**
     * The state file; without one, the state compatibility 3 derives from a tooth_lock.json (commits through
     * {@code git}, none when null); null when neither exists.
     */
    private StateFile load(Git git) throws PackageException {
        File state = new File(root, StateFile.PATH), lock = new File(root, TOOTH_LOCK);
        try {
            if (state.exists()) return StateFile.parse(Files.readAllBytes(state.toPath()));
            return lock.isFile() ? migrate(Files.readAllBytes(lock.toPath()), git) : null;
        } catch (IOException e) {
            throw Journal.io("Cannot read the state of the workspace", e);
        }
    }

    /** compatibility 3: the state file of a tooth_lock.json for (client, device platform); null when it is not one. */
    private StateFile migrate(byte[] data, Git git) throws PackageException {
        List<JsonObject> members = new ArrayList<>();
        try {
            JsonObject o = TextFormats.parseJson(data).getAsJsonObject();
            if (!Manifest.isNumber(o.get("format_version"), 3) || Manifest.array(o, "packages") == null) return null;
            for (JsonElement e : Manifest.array(o, "packages")) {
                JsonObject m = e.getAsJsonObject(); // members of other types throw: not a tooth_lock.json of compat 3
                Manifest.string(m, "variant", true);
                if (Manifest.object(m, "manifest") == null || Manifest.strings(m, "files") == null
                        || !m.get("locked").getAsJsonPrimitive().isBoolean()) return null;
                members.add(m);
            }
        } catch (RuntimeException e) {
            return null;
        }
        Map<String, StateFile.Pkg> pkgs = new LinkedHashMap<>();
        Map<String, List<String>> edges = new HashMap<>();
        Map<String, List<Git.Ref>> refs = new HashMap<>();
        for (JsonObject m : members) {
            Manifest manifest;
            String variant = m.get("variant").getAsString();
            try {
                manifest = Manifest.read(null, m.get("manifest").toString().getBytes(StandardCharsets.UTF_8), SIDE, devicePlatform, null);
            } catch (PackageException e) {
                continue;
            }
            Manifest.Entry entry = manifest.entry(variant, SIDE, devicePlatform);
            if (entry == null) continue;
            String ref = variant.isEmpty() ? manifest.id : manifest.id + "#" + variant, commit = null;
            if (git != null) {
                if (!refs.containsKey(Resolver.key(manifest.id))) refs.put(Resolver.key(manifest.id), git.lsRefs(manifest.id));
                for (Git.Ref t : refs.get(Resolver.key(manifest.id))) if (t.name.equals("refs/tags/v" + manifest.version)) commit = t.commit;
                if (commit == null) {
                    throw new PackageException(PackageException.Kind.NO_PACKAGE_FOUND, ref,
                            "The tag v" + manifest.version + " of " + manifest.id + " (from " + TOOTH_LOCK + ") is not found");
                }
            }
            List<String> files = new ArrayList<>();
            Path base = root.getAbsoluteFile().toPath().normalize();
            for (String f : Manifest.strings(m, "files")) {
                Path p = (new File(f).isAbsolute() ? new File(f).toPath() : base.resolve(f)).normalize();
                if (p.startsWith(base) && !p.equals(base)) files.add(base.relativize(p).toString().replace(File.separatorChar, '/'));
            }
            StateFile.Pkg pkg = new StateFile.Pkg(manifest, variant, m.get("locked").getAsBoolean(), ref, commit, files);
            pkgs.put(Resolver.key(pkg.identity()), pkg);
            edges.put(Resolver.key(pkg.identity()), new ArrayList<>(entry.deps.keySet()));
        }
        for (List<String> to : edges.values()) {
            to.replaceAll(Resolver::key);
            to.retainAll(pkgs.keySet());
        }
        List<StateFile.Pkg> ordered = new ArrayList<>();
        for (String id : Resolver.order(new ArrayList<>(pkgs.keySet()), edges)) ordered.add(pkgs.get(id));
        return new StateFile(SIDE, devicePlatform, ordered);
    }

    /** The state of a workspace (workspace 7.1). */
    public static final class State {
        public final String side, platform;
        /** In installation order. */
        public final List<Installed> packages;

        State(StateFile s) {
            side = s.side;
            platform = s.platform;
            List<Installed> list = new ArrayList<>();
            for (StateFile.Pkg p : s.packages) list.add(new Installed(p, s.side, s.platform));
            packages = Collections.unmodifiableList(list);
        }
    }

    /** One installed package. */
    public static final class Installed {
        public final String identity, id, variant, version, source, commit;
        public final boolean explicit;
        public final List<String> files;
        public final JsonObject manifest;
        private final Map<String, String> deps;

        Installed(StateFile.Pkg p, String side, String platform) {
            identity = p.identity();
            id = p.manifest.id;
            variant = p.variant;
            version = p.manifest.version;
            source = p.ref;
            commit = p.commit;
            explicit = p.explicit;
            files = p.files;
            manifest = p.manifest.json;
            Manifest.Entry e = p.manifest.entry(p.variant, side, platform);
            deps = Collections.unmodifiableMap(e == null ? Collections.<String, String>emptyMap() : e.deps);
        }

        /** The dependencies of its entry ("id" or "id#variant" to range). */
        public Map<String, String> deps() {
            return deps;
        }

        /** The mod name, from the {@code mods/<name>/manifest.json} it owns; or null. */
        public String modName() {
            return Resolver.modName(files);
        }
    }
}
