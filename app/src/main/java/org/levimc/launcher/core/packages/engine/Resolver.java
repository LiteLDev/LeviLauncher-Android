package org.levimc.launcher.core.packages.engine;

import com.google.gson.JsonElement;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolution for one operation (package 4): the candidates of a source for a range in the
 * environment, read lazily in their order and memoised; mod names; asset archives; and the solving
 * of workspace 3 (depth-first, earlier candidates first, installed packages kept when they still
 * fit) with the installation order of workspace 5. Close it to close the archives it opened.
 */
final class Resolver implements Closeable {
    private static final Pattern MOD_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    final String side, platform;
    final Http http;
    private final Git git;
    private final Map<String, Object> refs = new HashMap<>();
    private final Map<String, Read> reads = new HashMap<>();
    private final Map<String, Candidates> resolved = new HashMap<>();
    private final Map<String, Archive> assets = new HashMap<>();
    private final List<Archive> opened = new ArrayList<>();

    Resolver(String side, String platform, Http http) {
        this.side = side;
        this.platform = platform;
        this.http = http;
        this.git = new Git(http);
    }

    /** ASCII lowercase: how ids, identities, dependency keys and mod names compare. */
    static String key(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** A package with its selected entry (package 4). */
    final class Candidate {
        final Source source;
        final Manifest manifest;
        final Manifest.Entry entry;
        /** The commit of a remote repository's archive (lowercase), else null. */
        final String commit;
        final String identity;
        private Archive archive;
        private String modName;
        private boolean modNameRead;

        Candidate(Source source, Manifest manifest, Manifest.Entry entry, String commit, Archive archive) {
            this.source = source;
            this.manifest = manifest;
            this.entry = entry;
            this.commit = commit;
            this.identity = source.identity(manifest.id);
            this.archive = archive;
        }

        /** The archive of the package (for a package of the state file: as its source names it now). */
        Archive archive() throws PackageException {
            if (archive == null) {
                Read r = read(source, commit, null, false);
                if (r.archive == null && r.manifest != null) r.archive = git.archive(source.location, commit, source.toString());
                if (r.archive == null) throw r.failure.withArgument(source.toString());
                archive = r.archive;
            }
            return archive;
        }

        /** The mod name (package 4), or null for a file package; unreadable is {@code INVALID_PACKAGE}. */
        String modName() throws PackageException {
            if (!modNameRead) {
                if (entry.mcmod() != null) {
                    JsonElement name = null;
                    try {
                        byte[] m = asset(this, entry.mcmod()).read("manifest.json");
                        JsonElement v = m == null ? null : TextFormats.parseJson(m);
                        if (v != null && v.isJsonObject()) name = v.getAsJsonObject().get("name");
                    } catch (IOException | IllegalArgumentException e) {
                        name = null;
                    }
                    if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()
                            || !MOD_NAME.matcher(name.getAsString()).matches()) {
                        throw new PackageException(PackageException.Kind.INVALID_PACKAGE, source.toString(),
                                "The mod name of " + identity + " cannot be read from the manifest.json of its mcmod asset");
                    }
                    modName = name.getAsString();
                }
                modNameRead = true;
            }
            return modName;
        }

        String version() {
            return manifest.version;
        }

        /** Whether it has the identity, version and commit of {@code p} (workspace 5). */
        boolean sameAs(StateFile.Pkg p) {
            return key(identity).equals(key(p.identity())) && version().equals(p.manifest.version)
                    && (commit == null ? p.commit == null : commit.equals(p.commit));
        }
    }

    /** The candidate that a package of the state file is (workspace 5, reinstalling). */
    Candidate installed(StateFile.Pkg p) throws PackageException {
        Manifest.Entry e = p.manifest.entry(p.variant, side, platform);
        if (e == null) {
            throw new PackageException(PackageException.Kind.INVALID_WORKSPACE, p.identity(),
                    "The package file that the state file (" + StateFile.PATH + ") records for " + p.identity() + " "
                            + p.manifest.version + " has no entry of " + (p.variant.isEmpty() ? "the default variant"
                            : "the variant \"" + p.variant + "\"") + " for the side " + side + " and the platform " + platform + ". If you passed --platform, pass the platform the workspace was installed for instead.");
        }
        Candidate c = new Candidate(Source.parse(p.ref), p.manifest, e, p.commit, null);
        if (e.mcmod() != null && (c.modName = modName(p.files)) != null) c.modNameRead = true;
        return c;
    }

    /** The mod name of installed files: the mcmod asset writes its manifest.json to {@code mods/<mod name>/}. */
    static String modName(List<String> files) {
        for (String f : files) {
            String[] parts = f.split("/");
            if (parts.length == 3 && parts[0].equals("mods") && parts[2].equals("manifest.json") && MOD_NAME.matcher(parts[1]).matches()) {
                return parts[1];
            }
        }
        return null;
    }

    /** The archive of an asset: the package's own for "self", else the first of its urls that can be obtained. */
    Archive asset(Candidate c, Manifest.Asset a) throws PackageException {
        if (a.type.equals("self")) return c.archive();
        PackageException failure = null;
        for (String url : a.urls) {
            Archive cached = assets.get(url);
            if (cached != null) return cached;
            try {
                Archive z = http.zip(url, false, c.identity);
                opened.add(z);
                assets.put(url, z);
                return z;
            } catch (PackageException e) {
                if (!e.kind.equals(PackageException.Kind.NETWORK) && !e.kind.equals(PackageException.Kind.INVALID_PACKAGE)) throw e;
                failure = e;
            }
        }
        throw new PackageException(PackageException.Kind.NETWORK, c.source.toString(),
                "No url of a " + a.type + " asset of " + c.identity + " can be obtained: " + failure.getMessage(), failure);
    }

    private static final class Read {
        Archive archive;
        Manifest manifest;
        PackageException failure;
    }

    /** Obtains an archive and reads its package file (memoised); failures that mean "no candidate" are kept in it. */
    private Read read(Source s, String commit, String tagVersion, boolean fresh) throws PackageException {
        String k = s.kind + " " + (s.kind == Source.Kind.REPO ? key(s.location) + "@" + commit + "@" + tagVersion : s.location);
        Read r = fresh ? null : reads.get(k);
        if (r != null) return r;
        r = new Read();
        try {
            Archive a;
            if (s.kind == Source.Kind.REPO) {
                a = git.archive(s.location, commit, s.toString());
            } else if (s.kind == Source.Kind.URL) {
                a = http.zip(s.location, fresh, s.toString());
            } else if (new File(s.location).exists()) {
                a = Archive.local(new File(s.location));
            } else {
                throw new PackageException(PackageException.Kind.INVALID_ARGUMENT, s.toString(), s.location + " does not exist");
            }
            opened.add(a);
            r.archive = a;
            a.prefetch(Arrays.asList(Manifest.FILE, ToothCompat.FILE));
            Manifest m = Manifest.read(a.read(Manifest.FILE), a.read(ToothCompat.FILE), side, platform, tagVersion);
            if (s.kind == Source.Kind.REPO && !key(m.id).equals(key(s.location))) {
                throw notPackage(s, "its id " + m.id + " is not " + s.location);
            }
            if (tagVersion != null && m.packageFile.equals(Manifest.FILE) && !m.version.equals(tagVersion)) {
                throw notPackage(s, "its version " + m.version + " is not the tag version " + tagVersion);
            }
            r.manifest = m;
        } catch (IOException e) {
            r.failure = notPackage(s, String.valueOf(e.getMessage()));
        } catch (PackageException e) {
            if (e.kind != PackageException.Kind.NETWORK && e.kind != PackageException.Kind.INVALID_PACKAGE) throw e;
            r.failure = e;
        }
        if (r.failure != null) {
            http.listener.log(Listener.Level.WARN, s + (commit == null ? "" : " at " + commit) + ": " + r.failure.getMessage());
        }
        reads.put(k, r);
        return r;
    }

    /** The archives that reads still hold (tests: resolving many tags keeps memory bounded). */
    int heldArchives() {
        int n = 0;
        for (Read r : reads.values()) if (r.archive != null) n++;
        return n;
    }

    private static PackageException notPackage(Source s, String why) {
        return new PackageException(PackageException.Kind.INVALID_PACKAGE, s.toString(), s + " is not a package: " + why);
    }

    /** The branches and tags of a remote repository (memoised for the operation), or the failure. */
    private List<Git.Ref> refs(String repo) throws PackageException {
        Object v = refs.get(key(repo));
        if (v == null) {
            try {
                v = git.lsRefs(repo);
            } catch (PackageException e) {
                if (e.kind != PackageException.Kind.NETWORK) throw e;
                v = e;
            }
            refs.put(key(repo), v);
        }
        if (v instanceof PackageException) throw (PackageException) v;
        @SuppressWarnings("unchecked") List<Git.Ref> list = (List<Git.Ref>) v;
        return list;
    }

    /** Resolve(source, range, environment); {@code fresh} downloads a URL again (workspace 5). */
    Candidates resolve(Source s, String range, boolean fresh) throws PackageException {
        String k = s + "\n" + range + "\n" + fresh;
        Candidates c = resolved.get(k);
        if (c == null) resolved.put(k, c = new Candidates(s, range, fresh));
        return c;
    }

    /** The candidates of one source and range, read as far as they are asked for. */
    final class Candidates {
        final Source source;
        final String rangeText;
        private final SemVer.Range range;
        private final boolean fresh;
        /** {commit, tag version} of each archive in order; {null, null} for a URL or a local path. */
        private final List<String[]> archives = new ArrayList<>();
        private final List<Candidate> loaded = new ArrayList<>();
        private int next;
        private boolean entryless;
        private PackageException failure;

        Candidates(Source s, String rangeText, boolean fresh) throws PackageException {
            this.source = s;
            this.rangeText = rangeText;
            this.range = rangeText == null ? null : SemVer.Range.parse(rangeText);
            this.fresh = fresh;
            if (rangeText != null && range == null) {
                throw new PackageException(PackageException.Kind.INVALID_ARGUMENT, rangeText, "'" + rangeText + "' is not a range");
            }
            if (s.kind != Source.Kind.REPO) {
                archives.add(new String[2]);
            } else if (s.rev != null) {
                archives.add(new String[] {s.rev, null});
            } else {
                try {
                    selectRefs(refs(s.location));
                } catch (PackageException e) {
                    failure = e;
                }
            }
        }

        private void selectRefs(List<Git.Ref> refs) {
            String want = source.version != null ? "refs/tags/v" + source.version : source.branch != null
                    ? "refs/heads/" + source.branch : source.tag != null ? "refs/tags/" + source.tag : null;
            List<Object[]> tags = new ArrayList<>();
            for (Git.Ref r : refs) {
                String tag = r.name.startsWith("refs/tags/v") ? r.name.substring(11) : null;
                SemVer v = SemVer.parse(tag);
                if (want != null && r.name.equals(want)) {
                    archives.add(new String[] {r.commit, source.branch == null && v != null ? tag : null});
                } else if (want == null && v != null && (range == null || range.matches(v))) {
                    tags.add(new Object[] {v, r.name, r.commit});
                }
            }
            tags.sort(Comparator.comparing((Object[] t) -> (SemVer) t[0]).thenComparing(t -> (String) t[1]).reversed());
            for (Object[] t : tags) archives.add(new String[] {(String) t[2], ((String) t[1]).substring(11)});
        }

        /** The candidate at {@code i}, or null past the last. */
        Candidate get(int i) throws PackageException {
            while (loaded.size() <= i && next < archives.size()) {
                String[] a = archives.get(next++);
                http.listener.progress("resolve:" + source, Listener.Stage.RESOLVE, source.toString(), next - 1, archives.size(), false);
                Read r = read(source, a[0], a[1], fresh);
                Manifest.Entry e = r.manifest == null ? null : r.manifest.entry(source.variant, side, platform);
                if (r.manifest == null) failure = r.failure;
                else if (e == null) entryless = true;
                else if (range == null || range.matches(r.manifest.version)) {
                    loaded.add(new Candidate(source, r.manifest, e, a[0], r.archive));
                    continue;
                }
                // A git archive (its file list) is kept only for candidates; Candidate.archive() fetches it again.
                if (source.kind == Source.Kind.REPO) r.archive = null;
            }
            return i < loaded.size() ? loaded.get(i) : null;
        }

        /** Why there is no candidate (after {@code get(0)} gave null). */
        PackageException none() {
            String what = source + (rangeText == null ? "" : " " + rangeText);
            if (entryless) {
                return new PackageException(PackageException.Kind.NO_ENTRY_FOR_ENVIRONMENT, source.toString(),
                        "No package of " + what + " has an entry for the side " + side + " and the platform " + platform);
            }
            if (failure != null && failure.kind == PackageException.Kind.NETWORK) return failure.withArgument(source.toString());
            return new PackageException(PackageException.Kind.NO_PACKAGE_FOUND, source.toString(), "No package found for "
                    + what + (failure != null ? ": " + failure.getMessage() : archives.isEmpty() ? ": no matching tag" : ""));
        }
    }

    /** A request or dependency to satisfy (workspace 3). */
    static final class Request {
        final Source source;
        final String range;
        final boolean explicit;
        /** Download a URL again (an update target). */
        final boolean fresh;
        /** Prefer the installed package of the identity when it satisfies this request (cli 5.1). */
        final boolean keep;
        /** The package whose dependency this is, or null for a request. */
        final String neededBy;

        Request(Source source, String range, boolean explicit, boolean fresh, boolean keep, String neededBy) {
            this.source = source;
            this.range = range;
            this.explicit = explicit;
            this.fresh = fresh;
            this.keep = keep;
            this.neededBy = neededBy;
        }
    }

    /** A package of the solution. */
    static final class Member {
        final Candidate candidate;
        boolean explicit;

        Member(Candidate candidate, boolean explicit) {
            this.candidate = candidate;
            this.explicit = explicit;
        }
    }

    /** Whether {@code c} is a candidate of {@code r} or stands in for one (workspace 3). */
    static boolean satisfies(Candidate c, Request r) {
        Source a = c.source, b = r.source;
        SemVer.Range range = r.range == null ? null : SemVer.Range.parse(r.range);
        if (r.range != null && (range == null || !range.matches(c.version()))) return false;
        if (a.kind != b.kind || !a.variant.equals(b.variant)) return false;
        if (a.kind != Source.Kind.REPO) return a.location.equals(b.location);
        return key(a.location).equals(key(b.location)) && (!b.hasRef() || refKey(a).equals(refKey(b)));
    }

    private static String refKey(Source s) {
        return s.version != null ? "v" + s.version : s.branch != null ? "b" + s.branch : s.tag != null ? "t" + s.tag : "r" + s.rev;
    }

    /**
     * A set of packages satisfying workspace 3 for {@code requests}, in installation order. {@code installed}
     * are the packages of the state file, kept by requests with {@link Request#keep} and by dependencies.
     */
    List<Member> solve(List<Request> requests, List<Candidate> installed) throws PackageException {
        Search s = new Search();
        for (Candidate c : installed) s.installed.put(key(c.identity), c);
        List<Request> agenda = new ArrayList<>();
        for (Request r : requests) if (r.source.hasRef()) agenda.add(r);
        for (Request r : requests) if (!r.source.hasRef()) agenda.add(r);
        if (!s.search(agenda, 0)) throw s.failure;
        return order(s.members);
    }

    private final class Search {
        final Map<String, Member> members = new LinkedHashMap<>();
        final Map<String, String> mods = new HashMap<>();
        final Map<String, Candidate> installed = new HashMap<>();
        PackageException failure;

        boolean search(List<Request> agenda, int i) throws PackageException {
            if (i == agenda.size()) return true;
            Request r = agenda.get(i);
            for (Member m : members.values()) {
                if (!standsIn(m.candidate, r)) continue;
                boolean was = m.explicit;
                m.explicit |= r.explicit;
                if (search(agenda, i + 1)) return true;
                m.explicit = was;
                return false;
            }
            String staticIdentity = r.source.kind == Source.Kind.REPO ? key(r.source.identity(r.source.location)) : null;
            if (staticIdentity != null && members.containsKey(staticIdentity)) {
                return fail(r, null, "it needs another package of the identity " + members.get(staticIdentity).candidate.identity);
            }
            Candidate keep = null;
            if (r.keep) for (Candidate c : installed.values()) if (standsIn(c, r)) keep = c;
            Candidates cs = null; // resolved only when the installed package does not do (no network then)
            int tried = 0;
            for (int k = keep == null ? 0 : -1; ; k++) {
                if (k >= 0 && cs == null) cs = resolve(r.source, r.range, r.fresh);
                Candidate c = k < 0 ? keep : cs.get(k);
                if (c == null) break;
                if (k >= 0 && keep != null && c.version().equals(keep.version()) && key(c.identity).equals(key(keep.identity))) continue;
                tried++;
                String id = key(c.identity);
                if (members.containsKey(id)) {
                    fail(r, null, "its package " + c.identity + " is already selected from " + members.get(id).candidate.source);
                    continue;
                }
                String mod = c.modName() == null ? null : key(c.modName());
                if (mod != null && mods.containsKey(mod)) {
                    fail(r, null, c.identity + " has the mod name " + c.modName() + " of " + members.get(mods.get(mod)).candidate.identity);
                    continue;
                }
                members.put(id, new Member(c, r.explicit));
                if (mod != null) mods.put(mod, id);
                int size = agenda.size();
                for (Map.Entry<String, String> d : c.entry.deps.entrySet()) {
                    agenda.add(new Request(Source.parse(d.getKey()), d.getValue(), false, false, true, c.identity));
                }
                if (search(agenda, i + 1)) return true;
                agenda.subList(size, agenda.size()).clear();
                members.remove(id);
                if (mod != null) mods.remove(mod);
            }
            if (tried == 0) {
                PackageException none = cs.none();
                if (r.neededBy == null) throw none;
                return fail(r, none, none.getMessage());
            }
            return false;
        }

        /** {@link #satisfies}, and a package of a ref stands in only when the request has a candidate (workspace 3). */
        private boolean standsIn(Candidate c, Request r) throws PackageException {
            return satisfies(c, r) && (!c.source.hasRef() || r.source.hasRef() || resolve(r.source, r.range, r.fresh).get(0) != null);
        }

        /** Records the first failure (the deepest point the search reached first); returns false. */
        private boolean fail(Request r, PackageException cause, String why) {
            if (failure == null) {
                PackageException.Kind kind = cause != null && cause.kind == PackageException.Kind.NETWORK
                        ? PackageException.Kind.NETWORK : PackageException.Kind.UNSATISFIABLE;
                String what = r.source + (r.range == null ? "" : " " + r.range) + (r.neededBy == null ? "" : " (needed by " + r.neededBy + ")");
                failure = new PackageException(kind, r.source.toString(), "Cannot satisfy " + what + ": " + why, cause);
            }
            return false;
        }
    }

    /** Installation order (workspace 5): dependencies first; a cycle in ascending lowercase identity. */
    private List<Member> order(Map<String, Member> members) {
        List<String> ids = new ArrayList<>(members.keySet());
        Map<String, List<String>> edges = new HashMap<>();
        for (String id : ids) {
            List<String> to = new ArrayList<>();
            for (Map.Entry<String, String> d : members.get(id).candidate.entry.deps.entrySet()) {
                Request r;
                try {
                    r = new Request(Source.parse(d.getKey()), d.getValue(), false, false, true, null);
                } catch (PackageException e) {
                    continue;
                }
                for (String other : ids) if (satisfies(members.get(other).candidate, r)) to.add(other);
            }
            edges.put(id, to);
        }
        List<Member> out = new ArrayList<>();
        for (String id : order(ids, edges)) out.add(members.get(id));
        return out;
    }

    /**
     * The lowercase identities {@code ids} in installation order, {@code edges} mapping each to the ones it
     * depends on (Tarjan's strongly connected components; a component in ascending order).
     */
    static List<String> order(List<String> ids, Map<String, List<String>> edges) {
        List<String> sorted = new ArrayList<>(ids), out = new ArrayList<>();
        sorted.sort(null);
        for (List<String> to : edges.values()) to.sort(null);
        new Object() {
            final Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
            final List<String> stack = new ArrayList<>();

            void run() {
                for (String id : sorted) if (!index.containsKey(id)) visit(id);
            }

            void visit(String v) {
                index.put(v, index.size());
                low.put(v, index.get(v));
                stack.add(v);
                for (String w : edges.getOrDefault(v, Collections.<String>emptyList())) {
                    if (!index.containsKey(w)) {
                        visit(w);
                        low.put(v, Math.min(low.get(v), low.get(w)));
                    } else if (stack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                }
                if (low.get(v).equals(index.get(v))) {
                    List<String> scc = new ArrayList<>();
                    String w;
                    do {
                        w = stack.remove(stack.size() - 1);
                        scc.add(w);
                    } while (!w.equals(v));
                    scc.sort(null);
                    out.addAll(scc);
                }
            }
        }.run();
        return out;
    }

    @Override
    public void close() {
        for (Archive a : opened) a.close();
        opened.clear();
        assets.clear();
        reads.clear();
    }
}
