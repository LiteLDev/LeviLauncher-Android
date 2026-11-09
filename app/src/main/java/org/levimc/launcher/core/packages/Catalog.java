package org.levimc.launcher.core.packages;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.packages.engine.PackageEngine;
import org.levimc.launcher.core.packages.engine.TextFormats;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.ResponseBody;

public final class Catalog {
    public static final long MAX_AGE_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long FUTURE_SKEW_MS = TimeUnit.MINUTES.toMillis(1);
    static final Gson GSON = new Gson();
    /** Text order of names and labels: case-insensitive, then upper case first. */
    static final Comparator<String> TEXT_ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.reverseOrder());

    public static final class Result {
        public final Catalog.Index index;
        public final long fetchedAtMs;
        public final boolean stale;
        public final PackageService.Failure error;

        Result(Catalog.Index index, long fetchedAtMs, boolean stale, PackageService.Failure error) {
            this.index = index;
            this.fetchedAtMs = fetchedAtMs;
            this.stale = stale;
            this.error = error;
        }
    }

    /** The catalogue copy on disk (catalog.v4.json, so that a copy of the older shape is never read): the details without their READMEs, and when they were fetched. */
    public static final class Cache {
        public static final class Doc {
            public String fetchedAt;
            public List<CatalogModels.Detail> details;

            long fetchedAtMs() throws IOException {
                try {
                    return fetchedAt == null ? 0 : OffsetDateTime.parse(fetchedAt).toInstant().toEpochMilli();
                } catch (DateTimeParseException e) {
                    throw new IOException("invalid time " + fetchedAt, e);
                }
            }
        }

        private final File path;

        public Cache(File path) {
            this.path = path;
        }

        public File path() {
            return path;
        }

        public Doc load() throws IOException {
            try (Reader r = Files.newBufferedReader(path.toPath(), StandardCharsets.UTF_8)) {
                Doc d = GSON.fromJson(r, Doc.class);
                if (d == null) throw new IOException("empty catalogue cache");
                List<CatalogModels.Detail> details = new ArrayList<>();
                for (CatalogModels.Detail x : CatalogModels.Detail.list(d.details)) if (x != null && !PackageService.trim(x.id).isEmpty()) details.add(x);
                d.details = details;
                return d;
            } catch (RuntimeException e) {
                throw new IOException("invalid catalogue cache: " + e.getMessage(), e);
            }
        }

        public void save(List<CatalogModels.Detail> details, long atMs) throws IOException {
            Doc d = new Doc();
            d.fetchedAt = Instant.ofEpochMilli(atMs).toString();
            d.details = details;
            TextFormats.writeAtomically(path, GSON.toJson(d).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static volatile Catalog instance;

    private final Catalog.Client client = new Catalog.Client();
    private final Cache cache;

    private final Object lock = new Object();
    private Result memory;
    private CompletableFuture<Result> inflight;
    private boolean inflightRefresh;
    /** When the last fetch failed. */
    private long failedAt;
    /** Package documents fetched one by one (package pages, README); the most recent few. */
    private final Map<String, CatalogModels.Detail> direct = new LinkedHashMap<String, CatalogModels.Detail>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CatalogModels.Detail> eldest) {
            return size() > 20;
        }
    };
    /** The disk copy as an index, read once for {@link #offlineIndex}. */
    private volatile Catalog.Index diskIndex;

    private Catalog(Cache cache) {
        this.cache = cache;
    }

    public static Catalog get() {
        Catalog s = instance;
        if (s == null) {
            synchronized (Catalog.class) {
                s = instance;
                if (s == null) {
                    s = new Catalog(new Cache(new File(PackageService.get().dataDir(), "catalog.v4.json")));
                    instance = s;
                }
            }
        }
        return s;
    }

    public Result cached() {
        synchronized (lock) {
            return memory;
        }
    }

    /** When the catalogue was last fetched; 0 when never (the copy is written right after each fetch). */
    public long lastFetchedAtMs() {
        Result m = cached();
        if (m != null) {
            return m.fetchedAtMs;
        }
        return cache.path().lastModified();
    }

    /** The catalogue without the network: the one in memory, else the disk copy whatever its age; null when none. */
    public Catalog.Index offlineIndex() {
        Result m = cached();
        if (m != null) {
            return m.index;
        }
        Catalog.Index idx = diskIndex;
        if (idx == null && cache.path().isFile()) {
            try {
                diskIndex = idx = Catalog.Index.build(cache.load().details, PackageService.rid());
            } catch (IOException e) {
                PackageService.warn("cannot read the catalogue copy: " + e.getMessage());
            }
        }
        return idx;
    }

    private boolean fresh(long atMs) {
        long now = System.currentTimeMillis();
        return now - atMs < MAX_AGE_MS && atMs < now + FUTURE_SKEW_MS;
    }

    public Result load(Cancel c, boolean refresh) throws PackageService.Failure {
        PackageService.check(c);
        CompletableFuture<Result> f;
        boolean start = false;
        synchronized (lock) {
            // A stale result is kept for a while after a failed fetch instead of fetching again at once.
            if (!refresh && memory != null && (memory.stale ? System.currentTimeMillis() - failedAt < LeviLamina.DbClient.RETRY_AFTER_MS
                    : fresh(memory.fetchedAtMs))) {
                return memory;
            }
            if (inflight != null && (inflightRefresh || !refresh)) {
                f = inflight;
            } else {
                f = new CompletableFuture<>();
                inflight = f;
                inflightRefresh = refresh;
                start = true;
            }
        }
        if (start) {
            CompletableFuture<Result> mine = f;
            PackageService.EXECUTOR.execute(() -> runLoad(mine, refresh));
        }
        return await(c, f);
    }

    private void runLoad(CompletableFuture<Result> f, boolean refresh) {
        try {
            Result r = loadUncached(refresh);
            synchronized (lock) {
                memory = r;
                if (r.stale) failedAt = System.currentTimeMillis();
                if (inflight == f) {
                    inflight = null;
                }
            }
            f.complete(r);
        } catch (Throwable t) {
            Result fallback;
            synchronized (lock) {
                fallback = memory;
                failedAt = System.currentTimeMillis();
                if (inflight == f) {
                    inflight = null;
                }
            }
            if (fallback != null && t instanceof PackageService.Failure) {
                // The window keeps showing the older catalogue when a refresh fails.
                Result stale = new Result(fallback.index, fallback.fetchedAtMs, true, (PackageService.Failure) t);
                synchronized (lock) {
                    memory = stale;
                }
                f.complete(stale);
            } else {
                f.completeExceptionally(t);
            }
        }
    }

    private static Result await(Cancel c, CompletableFuture<Result> f) throws PackageService.Failure {
        while (true) {
            try {
                return f.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                PackageService.check(c);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw PackageService.cancelled();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof PackageService.Failure) throw (PackageService.Failure) cause;
                throw unavailable(String.valueOf(cause), cause);
            }
        }
    }

    private Result loadUncached(boolean refresh) throws PackageService.Failure {
        List<CatalogModels.Detail> cachedDetails = Collections.emptyList();
        long fetchedAt = 0;
        try {
            Cache.Doc doc = cache.load();
            cachedDetails = doc.details;
            fetchedAt = doc.fetchedAtMs();
            if (!refresh && fresh(fetchedAt)) {
                return new Result(Catalog.Index.build(cachedDetails, PackageService.rid()), fetchedAt, false, null);
            }
        } catch (IOException ignored) {
            // no usable copy
        }
        Map<String, CatalogModels.Detail> prev = new HashMap<>();
        for (CatalogModels.Detail d : cachedDetails) {
            prev.put(d.id, d);
        }
        List<CatalogModels.Detail> details;
        try {
            details = client.fetchDetails(null, prev);
        } catch (PackageService.Failure e) {
            if (!cachedDetails.isEmpty()) {
                return new Result(Catalog.Index.build(cachedDetails, PackageService.rid()), fetchedAt, true, e);
            }
            throw e;
        }
        long at = System.currentTimeMillis();
        try {
            cache.save(details, at);
        } catch (IOException ignored) {
            // the catalogue still loaded; the next start fetches again
        }
        return new Result(Catalog.Index.build(details, PackageService.rid()), at, false, null);
    }

    public CatalogModels.Detail fetchDetail(Cancel ctx, String identifier, boolean refresh) throws PackageService.Failure {
        String key = Catalog.Index.lookupKey(identifier);
        if (!refresh) {
            synchronized (lock) {
                CatalogModels.Detail d = direct.get(key);
                if (d != null) {
                    return d;
                }
            }
        }
        // "owner/repo" (as typed or linked) names "github.com/owner/repo".
        Catalog.Index.Reference ref = Catalog.Index.parseReference(identifier);
        CatalogModels.Detail d = client.fetchDetail(ctx, ref != null ? ref.id : identifier);
        synchronized (lock) {
            direct.put(key, d);
        }
        return d;
    }

    public CatalogModels.Pkg findPackage(Cancel ctx, String identifier) throws PackageService.Failure {
        Result r;
        PackageService.Failure loadError = null;
        try {
            r = load(ctx, false);
        } catch (PackageService.Failure e) {
            if (e.code.equals(PackageService.ERR_PKG_TASK_CANCELLED)) throw e;
            r = null;
            loadError = e;
        }
        if (r != null) {
            CatalogModels.Pkg p = r.index.find(identifier);
            if (p != null) {
                return p;
            }
        }
        CatalogModels.Detail d;
        try {
            d = fetchDetail(ctx, identifier, false);
        } catch (PackageService.Failure e) {
            if (loadError != null && !e.code.equals(PackageService.ERR_PACKAGE_NOT_FOUND)) {
                throw loadError;
            }
            throw e;
        }
        CatalogModels.Pkg p = Catalog.Index.parsePackage(d, PackageService.rid());
        if (p == null) {
            throw notFound(PackageService.trim(identifier) + " has no variant for client on " + PackageService.rid());
        }
        if (r != null) {
            r.index.add(p);
        }
        return p;
    }

    public List<Readme.Entry> readmes(Cancel ctx, String identifier) throws PackageService.Failure {
        CatalogModels.Detail d = fetchDetail(ctx, identifier, false);
        List<Readme.Entry> out = new ArrayList<>();
        for (CatalogModels.Detail.Readme r : CatalogModels.Detail.list(d.readmes)) {
            if (r != null) out.add(new Readme.Entry(r.file, r.lang, r.text));
        }
        return out;
    }

    public final static class Index {
        private final List<CatalogModels.Pkg> packages = new ArrayList<>();
        private final Map<String, CatalogModels.Pkg> byId = new HashMap<>();

        private Index() {
        }

        public synchronized List<CatalogModels.Pkg> packages() {
            return Collections.unmodifiableList(new ArrayList<>(packages));
        }

        public synchronized int size() {
            return packages.size();
        }

        // ---- identifiers -------------------------------------------------------

        private static String withoutScheme(String s) {
            return s.replaceFirst("(?i)^https?://", "");
        }

        private static String before(String s, char c) {
            int i = s.indexOf(c);
            return i < 0 ? s : s.substring(0, i);
        }

        private static List<String> segments(String path) {
            List<String> out = new ArrayList<>();
            for (String p : path.split("/")) if (!p.isEmpty()) out.add(p);
            return out;
        }

        /** The lower-case "host/owner/repo" an identifier names ("owner/repo" is on github.com); no variant or version. */
        public static String lookupKey(String v) {
            List<String> parts = segments(before(before(withoutScheme(PackageService.key(v)), '@'), '#'));
            if (parts.size() == 2 && !parts.get(0).contains(".")) {
                return "github.com/" + String.join("/", parts);
            }
            return String.join("/", parts);
        }

        public static final class Reference {
            public final String id;
            public final String variant;
            public final String version;

            Reference(String id, String variant, String version) {
                this.id = id;
                this.variant = variant;
                this.version = version;
            }
        }

        /** "[https://][host/]owner/repo[#variant][@version]"; null when s names no package. */
        public static Reference parseReference(String s) {
            s = PackageService.trim(s);
            String version = "";
            String variant = "";
            int at = s.lastIndexOf('@');
            if (at >= 0) {
                version = s.substring(at + 1).trim();
                s = s.substring(0, at).trim();
            }
            int hash = s.indexOf('#');
            if (hash >= 0) {
                variant = s.substring(hash + 1).trim();
                s = s.substring(0, hash).trim();
            }
            List<String> parts = segments(withoutScheme(s));
            String id;
            if (parts.size() == 2 && !parts.get(0).contains(".")) {
                id = "github.com/" + parts.get(0) + "/" + parts.get(1);
            } else if (parts.size() == 3 && parts.get(0).contains(".")) {
                id = String.join("/", parts);
            } else {
                return null;
            }
            return id.matches(".*[ \\\\:*?\"<>|].*") ? null : new Reference(id, variant, version);
        }

        public static String detailUrl(String base, String identifier) {
            HttpUrl b = HttpUrl.parse(base == null ? "" : base);
            List<String> parts = segments(withoutScheme(before(PackageService.trim(identifier), '#')));
            if (b == null || parts.isEmpty()) {
                return "";
            }
            HttpUrl.Builder u = b.newBuilder();
            for (String seg : parts) u.addPathSegment(seg);
            return u.addPathSegment("index.json").build().toString();
        }

        /** raw as an absolute http(s) URL; "" when it is not one. */
        static String normalizeHttpUrl(String raw) {
            HttpUrl u = HttpUrl.parse(PackageService.trim(raw));
            return u == null ? "" : u.toString();
        }

        static String inferAuthor(String identifier) {
            List<String> parts = segments(identifier);
            if (parts.size() >= 3 && parts.get(0).contains(".")) {
                return parts.get(1);
            }
            if (parts.size() >= 2) {
                return parts.get(parts.size() - 2);
            }
            return "";
        }

        static String inferProjectUrl(String identifier) {
            String direct = normalizeHttpUrl(identifier);
            if (!direct.isEmpty()) {
                return direct;
            }
            List<String> parts = segments(PackageService.trim(identifier));
            if (parts.size() < 2) {
                return "";
            }
            if (parts.size() == 2 && !parts.get(0).contains(".") && !parts.get(0).contains(":")) {
                return normalizeHttpUrl("https://github.com/" + parts.get(0) + "/" + parts.get(1));
            }
            return normalizeHttpUrl("https://" + String.join("/", parts));
        }

        // ---- parsing -----------------------------------------------------------

        /** Variant names as rule A2 prefers them: "client", the default "", then the others by name (exact). */
        static int compareVariantNames(String a, String b) {
            int d = variantRank(a) - variantRank(b);
            return d != 0 ? d : a.compareTo(b);
        }

        private static int variantRank(String k) {
            return k.equals("client") ? 0 : k.isEmpty() ? 1 : 2;
        }

        private static boolean isClient(CatalogModels.Detail.Entry e) {
            return e != null && PackageEngine.SIDE.equalsIgnoreCase(PackageService.trim(e.side));
        }

        /** Whether lip selects an entry of platform for rid: exactly the same RID ("" never). */
        static boolean selects(String platform, String rid) {
            return platform != null && !platform.isEmpty() && platform.equals(rid);
        }

        /**
         * The variant to install (the site's preferredVariant, rule A2): in the newest version (by version
         * number) where a variant has a client entry for platform, the best such variant; null when none has.
         */
        static String preferredVariant(CatalogModels.Detail d, String platform) {
            List<CatalogModels.Detail.Version> versions = new ArrayList<>();
            for (CatalogModels.Detail.Version v : CatalogModels.Detail.list(d.versions)) {
                if (v != null && !PackageService.trim(v.version).isEmpty() && v.variants != null) versions.add(v);
            }
            versions.sort((x, y) -> -CatalogModels.Versions.compare(PackageService.trim(x.version), PackageService.trim(y.version)));
            for (CatalogModels.Detail.Version v : versions) {
                String best = null;
                for (Map.Entry<String, List<CatalogModels.Detail.Entry>> e : v.variants.entrySet()) {
                    for (CatalogModels.Detail.Entry x : CatalogModels.Detail.list(e.getValue())) {
                        if (isClient(x) && selects(PackageService.trim(x.platform), platform)) {
                            if (best == null || compareVariantNames(e.getKey(), best) < 0) best = e.getKey();
                            break;
                        }
                    }
                }
                if (best != null) return best;
            }
            return null;
        }

        private static String variantPackageIdentifier(String identifier, String key) {
            String base = before(PackageService.trim(identifier), '#').trim();
            // The default variant "" is the bare id ("id#" is not a valid source).
            return base.isEmpty() || key.trim().isEmpty() ? base : base + "#" + key.trim();
        }

        /** Entries' dependencies merged (each in key order, blank keys and ranges dropped; the first entry naming a key wins). */
        private static Map<String, String> mergedDeps(List<CatalogModels.Detail.Entry> entries) {
            Map<String, String> out = new LinkedHashMap<>();
            for (CatalogModels.Detail.Entry e : entries) {
                for (Map.Entry<String, String> x : new TreeMap<>(e.deps == null ? Collections.<String, String>emptyMap() : e.deps).entrySet()) {
                    String nk = PackageService.trim(x.getKey()), rng = PackageService.trim(x.getValue());
                    if (!nk.isEmpty() && !rng.isEmpty()) out.putIfAbsent(nk, rng);
                }
            }
            return out;
        }

        /**
         * The variants with a client entry for platform in some version, the preferred one first, then in rule
         * A2's order. A variant's files are its versions with client entries of any platform (newest first; one
         * for another platform only is unavailable); a file depends on what its entries for platform depend on
         * (as lip derives it), else on what all its entries do.
         */
        static List<CatalogModels.Variant> variantsOf(CatalogModels.Detail d, String identifier, String platform) {
            Map<String, Map<String, List<CatalogModels.Detail.Entry>>> byName = new LinkedHashMap<>();
            Set<String> qualifying = new HashSet<>();
            for (CatalogModels.Detail.Version ver : CatalogModels.Detail.list(d.versions)) {
                String version = ver == null ? "" : PackageService.trim(ver.version);
                if (version.isEmpty() || ver.variants == null) continue;
                for (Map.Entry<String, List<CatalogModels.Detail.Entry>> va : ver.variants.entrySet()) {
                    for (CatalogModels.Detail.Entry e : CatalogModels.Detail.list(va.getValue())) {
                        if (!isClient(e)) continue;
                        byName.computeIfAbsent(va.getKey(), k -> new LinkedHashMap<>())
                                .computeIfAbsent(version, k -> new ArrayList<>()).add(e);
                        if (selects(PackageService.trim(e.platform), platform)) qualifying.add(va.getKey());
                    }
                }
            }
            String preferred = preferredVariant(d, platform);
            List<CatalogModels.Variant> out = new ArrayList<>();
            for (Map.Entry<String, Map<String, List<CatalogModels.Detail.Entry>>> v : byName.entrySet()) {
                if (!qualifying.contains(v.getKey())) continue;
                List<CatalogModels.PackageFile> files = new ArrayList<>();
                for (Map.Entry<String, List<CatalogModels.Detail.Entry>> f : v.getValue().entrySet()) {
                    List<String> platforms = new ArrayList<>();
                    List<CatalogModels.Detail.Entry> mine = new ArrayList<>();
                    for (CatalogModels.Detail.Entry e : f.getValue()) {
                        platforms.add(PackageService.trim(e.platform));
                        if (selects(PackageService.trim(e.platform), platform)) mine.add(e);
                    }
                    files.add(new CatalogModels.PackageFile(f.getKey(), mergedDeps(mine.isEmpty() ? f.getValue() : mine), platforms));
                }
                files.sort((x, y) -> -CatalogModels.Versions.compare(x.version, y.version));
                out.add(new CatalogModels.Variant(v.getKey(), variantPackageIdentifier(identifier, v.getKey()), files));
            }
            out.sort((x, y) -> x.key.equals(preferred) != y.key.equals(preferred) ? (x.key.equals(preferred) ? -1 : 1)
                    : compareVariantNames(x.key, y.key));
            return out;
        }

        /** The site's depsOf for the latest version: bare ids as the catalogue names them, the package left out. */
        static Map<String, String> latestDeps(CatalogModels.Detail d) {
            CatalogModels.Detail.Version latest = null;
            String want = d.latest == null ? "" : PackageService.trim(d.latest.version);
            for (CatalogModels.Detail.Version v : CatalogModels.Detail.list(d.versions)) {
                String s = v == null ? "" : PackageService.trim(v.version);
                if (s.isEmpty()) continue;
                if (latest == null) latest = v;
                if (!want.isEmpty() && s.equals(want)) {
                    latest = v;
                    break;
                }
            }
            Map<String, String> canonical = new HashMap<>();
            if (d.dependencies != null) {
                for (Map.Entry<String, CatalogModels.Detail.Ref> e : d.dependencies.entrySet()) {
                    CatalogModels.Detail.Ref r = e.getValue();
                    if (r != null && !PackageService.trim(r.id).isEmpty() && !PackageService.trim(r.url).isEmpty()) {
                        canonical.put(PackageService.key(e.getKey()), r.id.trim());
                    }
                }
            }
            Map<String, List<String>> ranges = new TreeMap<>();
            if (latest != null && latest.variants != null) {
                // The document's order: variant by variant, entry by entry, key by key.
                for (List<CatalogModels.Detail.Entry> entries : latest.variants.values()) {
                    for (CatalogModels.Detail.Entry e : CatalogModels.Detail.list(entries)) {
                        if (e == null || e.deps == null) continue;
                        for (Map.Entry<String, String> x : e.deps.entrySet()) {
                            String bare = before(x.getKey(), '#');
                            if (bare.trim().isEmpty()) continue;
                            String target = canonical.getOrDefault(PackageService.key(bare), bare);
                            if (target.equalsIgnoreCase(PackageService.trim(d.id))) continue;
                            List<String> list = ranges.computeIfAbsent(target, k -> new ArrayList<>());
                            if (!list.contains(x.getValue())) list.add(x.getValue());
                        }
                    }
                }
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : ranges.entrySet()) out.put(e.getKey(), String.join(" || ", e.getValue()));
            return out;
        }

        /** A detail as a package for side "client" on rid; null when no variant has a client entry for rid. */
        public static CatalogModels.Pkg parsePackage(CatalogModels.Detail d, String rid) {
            String id = d == null ? "" : PackageService.trim(d.id);
            if (id.isEmpty()) {
                return null;
            }
            List<CatalogModels.Variant> variants = variantsOf(d, id, PackageService.trim(rid));
            if (variants.isEmpty()) {
                return null;
            }
            String name = PackageService.trim(d.name);
            String owner = PackageService.trim(d.owner);
            // The index's (cut) description when the catalogue listed one: what the list shows and the search reads.
            String description = PackageService.trim(d.catalogDescription != null ? d.catalogDescription : d.description);
            return new CatalogModels.Pkg(d, id, name.isEmpty() ? id : name, description,
                    owner.isEmpty() ? inferAuthor(id) : owner, CatalogFacets.licenseOf(id, d.license),
                    latestDeps(d), variants.get(0), variants);
        }

        /** The packages of details, in their (the index's) order. */
        public static Index build(List<CatalogModels.Detail> details, String rid) {
            Index idx = new Index();
            for (CatalogModels.Detail d : CatalogModels.Detail.list(details)) {
                CatalogModels.Pkg p = parsePackage(d, rid);
                if (p != null && !idx.byId.containsKey(p.identifier)) {
                    idx.byId.put(p.identifier, p);
                    idx.packages.add(p);
                }
            }
            return idx;
        }

        public synchronized CatalogModels.Pkg find(String identifier) {
            String target = PackageService.trim(identifier);
            if (target.isEmpty()) {
                return null;
            }
            CatalogModels.Pkg p = byId.get(target);
            if (p != null) {
                return p;
            }
            String base = before(target, '#');
            p = byId.get(base);
            if (p != null) {
                return p;
            }
            for (CatalogModels.Pkg q : packages) {
                if (q.identifier.equalsIgnoreCase(target) || q.identifier.equalsIgnoreCase(base)) {
                    return q;
                }
            }
            String key = lookupKey(target);
            if (key.isEmpty()) {
                return null;
            }
            for (CatalogModels.Pkg q : packages) {
                if (lookupKey(q.identifier).equals(key)) {
                    return q;
                }
            }
            return null;
        }

        public synchronized void add(CatalogModels.Pkg p) {
            if (p == null) {
                return;
            }
            CatalogModels.Pkg old = byId.put(p.identifier, p);
            if (old != null) {
                int i = packages.indexOf(old);
                if (i >= 0) {
                    packages.set(i, p);
                    return;
                }
            }
            packages.add(p);
        }
    }

    public final static class Client {
        public static final String DEFAULT_BASE_URL = "https://levimc.org/api/v4/mods/";
        public static final int WORKERS = 8;
        public static final long TIMEOUT_MS = 30_000;
        public static final long REQUEST_TIMEOUT_MS = 20_000;

        private final String baseUrl = DEFAULT_BASE_URL;
        private final Executor executor = PackageService.EXECUTOR;

        public String detailUrl(String identifier) {
            return Catalog.Index.detailUrl(baseUrl, identifier);
        }

        private <T> T getJson(Cancel ctx, String url, Class<T> type) throws Exception {
            // max-age=0 revalidates a cached copy: an unchanged document is a 304.
            return httpGet(ctx, url, "max-age=0", REQUEST_TIMEOUT_MS, resp -> {
                if (resp.code() == 404) {
                    throw notFound(url);
                }
                ResponseBody body = resp.body();
                if (resp.code() != 200 || body == null) {
                    throw new IOException("fetch " + url + ": status " + resp.code());
                }
                try {
                    return GSON.fromJson(body.charStream(), type);
                } catch (RuntimeException e) {
                    throw new IOException("decode " + url + ": " + e.getMessage(), e);
                }
            });
        }

        /** The catalogue index (levimc.org/api/v4/mods/index.json): the listed mods, in relevance order. */
        static final class IndexDoc {
            /** JSON values as they come: a value of another type is skipped, as lip's index reader does. */
            static final class Mod {
                JsonElement id;
                JsonElement description;
                JsonElement side;
                JsonElement platform;
                /** The id of the index before lip v0.40. */
                JsonElement tooth;
            }

            List<Mod> mods;
        }

        /** A JSON string's value; null for anything else. */
        private static String str(JsonElement v) {
            return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
        }

        /** Whether a JSON list has want among its strings (trimmed, ignoring case). */
        private static boolean containsIgnoreCase(JsonElement values, String want) {
            if (values == null || !values.isJsonArray()) return false;
            for (JsonElement v : values.getAsJsonArray()) if (PackageService.trim(str(v)).equalsIgnoreCase(want)) return true;
            return false;
        }

        /**
         * The packages of the index the launcher can install (their latest version runs on side "client" and on
         * platform, both ignoring case), each once, with the index's description (null when it is not a string).
         *
         * @throws IllegalArgumentException for an index in the format before lip v0.40 (tooth, env)
         */
        static Map<String, String> clientPackages(IndexDoc doc, String platform) {
            Map<String, String> out = new LinkedHashMap<>();
            for (IndexDoc.Mod m : CatalogModels.Detail.list(doc == null ? null : doc.mods)) {
                if (m == null) continue;
                if (m.id == null && m.tooth != null) {
                    throw new IllegalArgumentException("invalid catalogue index: the format before lip v0.40 (tooth, env)");
                }
                String id = PackageService.trim(str(m.id));
                if (!id.isEmpty() && containsIgnoreCase(m.side, PackageEngine.SIDE) && containsIgnoreCase(m.platform, platform)
                        && !out.containsKey(id)) {
                    out.put(id, str(m.description));
                }
            }
            return out;
        }

        public List<CatalogModels.Detail> fetchDetails(Cancel parent, Map<String, CatalogModels.Detail> previous) throws PackageService.Failure {
            Cancel ctx2 = PackageService.child(parent, TIMEOUT_MS);
            Map<String, String> descriptions;
            try {
                descriptions = clientPackages(getJson(ctx2, baseUrl.replaceAll("/+$", "") + "/index.json", IndexDoc.class), PackageService.rid());
            } catch (Exception e) {
                // A missing index is an unavailable catalogue, not a missing package.
                throw wrap(parent, ctx2, e instanceof PackageService.Failure ? new IOException(PackageService.message(e), e) : e);
            }
            List<String> ids = new ArrayList<>(descriptions.keySet());
            int n = Math.min(WORKERS, ids.size());
            CatalogModels.Detail[] results = new CatalogModels.Detail[ids.size()];
            AtomicInteger next = new AtomicInteger();
            AtomicInteger fetched = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(n);
            for (int w = 0; w < n; w++) {
                executor.execute(() -> {
                    try {
                        for (int i = next.getAndIncrement(); i < ids.size() && !ctx2.isCancelled(); i = next.getAndIncrement()) {
                            String id = ids.get(i);
                            CatalogModels.Detail d = null;
                            try {
                                d = fetchDetailRaw(ctx2, id);
                            } catch (Exception ignored) {
                                // skipped, or the previous copy is reused below
                            }
                            if (d != null) {
                                d.readmes = null; // a package page fetches its README itself
                                fetched.incrementAndGet();
                            } else if (previous != null) {
                                d = previous.get(id);
                            }
                            if (d != null) {
                                d.catalogDescription = descriptions.get(id);
                                results[i] = d;
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            try {
                done.await();
            } catch (InterruptedException e) {
                ctx2.cancel();
                Thread.currentThread().interrupt();
            }
            if (ctx2.isCancelled()) {
                throw wrap(parent, ctx2, new IOException("cancelled"));
            }
            if (!ids.isEmpty() && fetched.get() == 0) {
                throw wrap(parent, ctx2, new IOException("failed to fetch the package details"));
            }
            List<CatalogModels.Detail> out = new ArrayList<>();
            for (CatalogModels.Detail d : results) {
                if (d != null) {
                    out.add(d);
                }
            }
            return out;
        }

        private static PackageService.Failure wrap(Cancel parent, Cancel ctx2, Throwable err) {
            if (err instanceof PackageService.Failure && ((PackageService.Failure) err).code.equals(PackageService.ERR_PACKAGE_NOT_FOUND)) {
                return (PackageService.Failure) err;
            }
            if (parent != null && parent.isCancelled()) {
                return PackageService.cancelled();
            }
            if (ctx2.isCancelled()) {
                return new PackageService.Failure(PackageService.ERR_PKG_INDEX_TIMEOUT, "the mod catalogue did not respond in time", err);
            }
            return unavailable(PackageService.message(err), err);
        }

        private CatalogModels.Detail fetchDetailRaw(Cancel ctx, String identifier) throws Exception {
            String u = detailUrl(identifier);
            if (u.isEmpty()) {
                throw notFound("");
            }
            CatalogModels.Detail d = getJson(ctx, u, CatalogModels.Detail.class);
            if (d == null || PackageService.trim(d.id).isEmpty()) {
                throw notFound(u + " has no package id");
            }
            return d;
        }

        public CatalogModels.Detail fetchDetail(Cancel parent, String identifier) throws PackageService.Failure {
            Cancel ctx2 = PackageService.child(parent, REQUEST_TIMEOUT_MS);
            try {
                return fetchDetailRaw(ctx2, identifier);
            } catch (Exception e) {
                throw wrap(parent, ctx2, e);
            }
        }
    }

    public final static class Search {
        /** The search score with a search, else the index's order. */
        public static final String SORT_RELEVANCE = "relevance";
        public static final String SORT_STARS = "stars";
        public static final String SORT_UPDATED = "updated";
        /** By repository creation. */
        public static final String SORT_NEWEST = "newest";
        /** The sorts, in the order the screen lists them. */
        public static final List<String> SORTS = Collections.unmodifiableList(
                Arrays.asList(SORT_RELEVANCE, SORT_STARS, SORT_UPDATED, SORT_NEWEST));

        private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList("levilamina", "levilamina-loc", "crashlogger"));
        private static final Pattern LL_FILTER = Pattern.compile(
                "(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?)");

        private Search() {
        }

        /** Tag keys a filter includes, and those it excludes. */
        public static final class Facet {
            public static final Facet EMPTY = new Facet(null, null);

            public final List<String> inc;
            public final List<String> exc;

            public Facet(List<String> inc, List<String> exc) {
                this.inc = Collections.unmodifiableList(new ArrayList<>(inc == null ? Collections.emptyList() : inc));
                this.exc = Collections.unmodifiableList(new ArrayList<>(exc == null ? Collections.emptyList() : exc));
            }
        }

        /** The search, the filters (the site's catalog-query.ts, plus the LeviLamina version) and the sort. */
        public static final class Query {
            public String text = "";
            /** Catalogue game versions, any of them. */
            public List<String> gv = new ArrayList<>();
            /** Tag keys ({@link CatalogFacets#tagKey}): every included one, none of the excluded. */
            public Facet tags = Facet.EMPTY;
            /** License families or SPDX ids, any of them. */
            public List<String> licenses = new ArrayList<>();
            /** Bare ids every result depends on (any case). */
            public List<String> deps = new ArrayList<>();
            /** Owners, any of them (any case). */
            public List<String> authors = new ArrayList<>();
            public String sort = SORT_RELEVANCE;
            /** A LeviLamina version results support ("" = any); off without {@link #db}. */
            public String loaderVersion = "";
            public LeviLamina.Db db;

            public Query copy() {
                Query q = new Query();
                q.text = text;
                q.gv = new ArrayList<>(gv);
                q.tags = tags;
                q.licenses = new ArrayList<>(licenses);
                q.deps = new ArrayList<>(deps);
                q.authors = new ArrayList<>(authors);
                q.sort = sort;
                q.loaderVersion = loaderVersion;
                q.db = db;
                return q;
            }
        }

        /** How many filters are on (the search, sort and LeviLamina version do not count). */
        public static int filterCount(Query q) {
            return q.gv.size() + q.tags.inc.size() + q.tags.exc.size() + q.licenses.size() + q.deps.size() + q.authors.size();
        }

        /** q without the facet filters: the search, sort and LeviLamina version stay. */
        public static Query clearFilters(Query q) {
            Query out = q.copy();
            out.gv = new ArrayList<>();
            out.tags = Facet.EMPTY;
            out.licenses = new ArrayList<>();
            out.deps = new ArrayList<>();
            out.authors = new ArrayList<>();
            return out;
        }

        /** A tag through a chip's states: off, included, excluded, off. A hidden tag is ignored; an alias is its featured tag. */
        public static Facet cycleTag(Facet f, String tag) {
            String key = CatalogFacets.tagKey(tag);
            if (key == null) {
                return f;
            }
            List<String> inc = new ArrayList<>(f.inc);
            List<String> exc = new ArrayList<>(f.exc);
            if (inc.remove(key)) {
                exc.add(key);
            } else if (!exc.remove(key)) {
                inc.add(key);
            }
            return new Facet(inc, exc);
        }

        /** values with value added, or removed when present (ignoring case). */
        public static List<String> toggle(List<String> values, String value) {
            List<String> out = new ArrayList<>(values);
            if (!out.removeIf(v -> v.equalsIgnoreCase(value))) out.add(value);
            return out;
        }

        public static boolean hidden(CatalogModels.Pkg p) {
            String name = PackageService.key(p.name);
            String id = PackageService.key(p.identifier);
            String last = id.substring(id.lastIndexOf('/') + 1);
            return HIDDEN.contains(name) || HIDDEN.contains(id) || HIDDEN.contains(last);
        }

        public static String normalizeLoaderFilter(String v) {
            String n = CatalogModels.Versions.normalizeSemverInput(v);
            Matcher m = LL_FILTER.matcher(n);
            if (m.find()) {
                return m.group(1);
            }
            return n;
        }

        // ---- text search (the site's search.ts) --------------------------------

        private static boolean wordChar(char c) {
            return Character.isLetterOrDigit(c);
        }

        /** 3 when token is a whole word of text, 2 the start of a word, 1 anywhere, 0 not at all. */
        static int matchQuality(String text, String token) {
            int best = 0;
            for (int at = text.indexOf(token); at >= 0 && best < 3; at = text.indexOf(token, at + 1)) {
                boolean start = at == 0 || !wordChar(text.charAt(at - 1));
                int end = at + token.length();
                boolean last = end >= text.length() || !wordChar(text.charAt(end));
                best = Math.max(best, start ? (last ? 3 : 2) : 1);
            }
            return best;
        }

        /** Name x3; repository path and owner x2; featured tag names in every language and visible tags x2; description x1. */
        private static String[] fields(CatalogModels.Pkg p) {
            Set<String> words = new LinkedHashSet<>();
            for (CatalogFacets.Featured f : CatalogFacets.featuredTagsOf(p.tags)) words.addAll(Arrays.asList(f.labels));
            words.addAll(CatalogFacets.visibleTags(p.tags));
            String id = p.identifier;
            String repo = id.contains("/") ? id.substring(id.indexOf('/') + 1).replace('/', ' ') : "";
            return new String[]{PackageService.key(p.name), PackageService.key(repo + " " + p.author),
                    PackageService.key(String.join(" ", words)), PackageService.key(p.description)};
        }

        private static final int[] WEIGHTS = {3, 2, 2, 1};

        /** The relevance of each package matching every word of text, by identifier; null without a search. */
        static Map<String, Integer> scores(List<CatalogModels.Pkg> pkgs, String text) {
            String q = PackageService.key(text);
            if (q.isEmpty()) {
                return null;
            }
            Set<String> tokens = new LinkedHashSet<>(Arrays.asList(q.split("[\\p{IsWhite_Space},，、]+")));
            tokens.remove("");
            Map<String, Integer> out = new HashMap<>();
            for (CatalogModels.Pkg p : pkgs) {
                String[] f = fields(p);
                int total = 0;
                boolean all = true;
                for (String token : tokens) {
                    int score = 0;
                    for (int i = 0; i < f.length; i++) score += WEIGHTS[i] * matchQuality(f[i], token);
                    if (score == 0) {
                        all = false;
                        break;
                    }
                    total += score;
                }
                if (all) {
                    out.put(p.identifier, total + (f[0].equals(q) ? 100 : 0));
                }
            }
            return out;
        }

        // ---- filters (the site's catalog-query.ts) ------------------------------

        private static boolean anyEqualIgnoreCase(Collection<String> values, String v) {
            for (String x : values) if (x.equalsIgnoreCase(v)) return true;
            return false;
        }

        /** Whether p passes q's filters (the search apart). */
        static boolean matchesFilters(CatalogModels.Pkg p, Query q) {
            if (!q.gv.isEmpty() && Collections.disjoint(q.gv, p.gameVersions)) {
                return false;
            }
            List<String> keys = CatalogFacets.tagKeys(p.tags);
            if (!keys.containsAll(q.tags.inc) || !Collections.disjoint(keys, q.tags.exc)) {
                return false;
            }
            if (!q.licenses.isEmpty() && !q.licenses.contains(p.licenseFamily) && !anyEqualIgnoreCase(q.licenses, p.license)) {
                return false;
            }
            for (String d : q.deps) {
                if (!anyEqualIgnoreCase(p.deps.keySet(), d)) return false;
            }
            if (!q.authors.isEmpty() && !anyEqualIgnoreCase(q.authors, p.author)) {
                return false;
            }
            if (q.db != null && !q.db.isEmpty() && !PackageService.trim(q.loaderVersion).isEmpty()) {
                return !p.llRanges.isEmpty() && CatalogModels.Versions.compatible(normalizeLoaderFilter(q.loaderVersion), p.llRanges);
            }
            return true;
        }

        /** What a query gives: the matches in order, what each filter offers, and how many results each option would give. */
        public static final class Result {
            public final List<CatalogModels.Pkg> matched;
            public final Options options;
            /**
             * By option: an option of an OR group (game version, license, author) counted as the group's only
             * value, one of an AND group (tags, dependencies) as added to it (an excluded tag as included instead);
             * the other groups as they are. The options and the query's own values are counted.
             */
            public final Map<String, Integer> gvCounts = new HashMap<>(), tagCounts = new HashMap<>(),
                    licenseCounts = new HashMap<>(), depCounts = new HashMap<>(), authorCounts = new HashMap<>();

            Result(List<CatalogModels.Pkg> matched, Options options) {
                this.matched = Collections.unmodifiableList(matched);
                this.options = options;
            }
        }

        /** The visible packages matching q, sorted, with the filter options and counts. */
        public static Result run(List<CatalogModels.Pkg> pkgs, Query q) {
            List<CatalogModels.Pkg> listed = new ArrayList<>();
            for (CatalogModels.Pkg p : pkgs == null ? Collections.<CatalogModels.Pkg>emptyList() : pkgs) {
                if (!hidden(p)) listed.add(p);
            }
            Map<String, Integer> scores = scores(listed, q.text);
            List<CatalogModels.Pkg> base = new ArrayList<>();
            for (CatalogModels.Pkg p : listed) {
                if (scores == null || scores.containsKey(p.identifier)) base.add(p);
            }
            List<CatalogModels.Pkg> out = new ArrayList<>();
            for (CatalogModels.Pkg p : base) {
                if (matchesFilters(p, q)) out.add(p);
            }
            sortPackages(out, SORTS.contains(q.sort) ? q.sort : SORT_RELEVANCE, scores);
            Result r = new Result(out, options(listed));
            for (String v : withExtra(r.options.gv, q.gv)) {
                Query f = q.copy();
                f.gv = Collections.singletonList(v);
                r.gvCounts.put(v, count(base, f));
            }
            List<String> stateTags = new ArrayList<>(q.tags.inc);
            stateTags.addAll(q.tags.exc);
            for (String key : withExtra(r.options.tags, stateTags)) {
                List<String> inc = new ArrayList<>(q.tags.inc);
                if (!inc.contains(key)) inc.add(key);
                List<String> exc = new ArrayList<>(q.tags.exc);
                exc.remove(key);
                Query f = q.copy();
                f.tags = new Facet(inc, exc);
                r.tagCounts.put(key, count(base, f));
            }
            List<String> licenses = new ArrayList<>(CatalogFacets.FAMILIES);
            for (LicenseOption o : r.options.licenses) licenses.addAll(o.ids);
            for (String v : withExtra(licenses, q.licenses)) {
                Query f = q.copy();
                f.licenses = Collections.singletonList(v);
                r.licenseCounts.put(v, count(base, f));
            }
            for (String d : withExtra(r.options.deps, q.deps)) {
                Query f = q.copy();
                if (!anyEqualIgnoreCase(f.deps, d)) f.deps.add(d);
                r.depCounts.put(d, count(base, f));
            }
            for (String a : withExtra(r.options.authors, q.authors)) {
                Query f = q.copy();
                f.authors = Collections.singletonList(a);
                r.authorCounts.put(a, count(base, f));
            }
            return r;
        }

        private static List<String> withExtra(List<String> options, List<String> selected) {
            List<String> out = new ArrayList<>(options);
            for (String v : selected) if (!anyEqualIgnoreCase(options, v)) out.add(v);
            return out;
        }

        private static int count(List<CatalogModels.Pkg> base, Query q) {
            int n = 0;
            for (CatalogModels.Pkg p : base) if (matchesFilters(p, q)) n++;
            return n;
        }

        public static long updatedTime(String s) {
            String t = PackageService.trim(s);
            if (t.isEmpty()) {
                return 0;
            }
            try {
                return OffsetDateTime.parse(t).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
            }
            try {
                return LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
            }
            return 0;
        }

        /** Most or latest first, ties by identifier; relevance is the score (with a search), else the catalogue's order. */
        static void sortPackages(List<CatalogModels.Pkg> list, String key, Map<String, Integer> scores) {
            Comparator<CatalogModels.Pkg> stars = (a, b) -> Double.compare(b.stars, a.stars);
            Comparator<CatalogModels.Pkg> updated = (a, b) -> Long.compare(b.updatedMs, a.updatedMs);
            Comparator<CatalogModels.Pkg> c;
            if (SORT_STARS.equals(key)) {
                c = stars.thenComparing(updated);
            } else if (SORT_UPDATED.equals(key)) {
                c = updated.thenComparing(stars);
            } else if (SORT_NEWEST.equals(key)) {
                c = Comparator.<CatalogModels.Pkg>comparingLong(p -> -p.createdMs).thenComparing(stars);
            } else {
                // The sort is stable: without a search (or on a tie) the list keeps the catalogue's order.
                list.sort(Comparator.comparingInt(p -> scores == null ? 0 : -scores.getOrDefault(p.identifier, 0)));
                return;
            }
            list.sort(c.thenComparing(p -> PackageService.key(p.identifier)));
        }

        /** A license family and its SPDX ids (by frequency; none for "none"). */
        public static final class LicenseOption {
            public final String family;
            public final List<String> ids;

            LicenseOption(String family, List<String> ids) {
                this.family = family;
                this.ids = Collections.unmodifiableList(ids);
            }
        }

        /** What each filter offers for pkgs. */
        public static final class Options {
            /** The catalogue's game versions, newest first. */
            public final List<String> gv;
            /** Featured tags some package has (in their order), then the other tag keys by frequency. */
            public final List<String> tags = new ArrayList<>();
            /** The families some package is under, in their order. */
            public final List<LicenseOption> licenses = new ArrayList<>();
            /** Bare dependency ids by frequency. */
            public final List<String> deps;
            /** Owners by frequency. */
            public final List<String> authors;

            Options(List<String> gv, List<String> tags, List<String> licenses, List<String> deps, List<String> authors) {
                this.gv = gv;
                List<String> counted = byFrequency(tags);
                for (CatalogFacets.Featured f : CatalogFacets.FEATURED) if (counted.contains(f.id)) this.tags.add(f.id);
                for (String t : counted) if (CatalogFacets.featured(t) == null) this.tags.add(t);
                for (String fam : CatalogFacets.FAMILIES) {
                    List<String> ids = new ArrayList<>();
                    boolean found = false;
                    for (String l : licenses) {
                        if (!CatalogFacets.licenseFamily(l).equals(fam)) continue;
                        found = true;
                        if (!l.isEmpty()) ids.add(l);
                    }
                    if (found) this.licenses.add(new LicenseOption(fam, byFrequency(ids)));
                }
                this.deps = byFrequency(deps);
                this.authors = byFrequency(authors);
            }
        }

        public static Options options(List<CatalogModels.Pkg> pkgs) {
            Set<String> gv = new LinkedHashSet<>();
            List<String> tags = new ArrayList<>(), licenses = new ArrayList<>(), deps = new ArrayList<>(), authors = new ArrayList<>();
            for (CatalogModels.Pkg p : pkgs) {
                if (hidden(p)) continue;
                gv.addAll(p.gameVersions);
                tags.addAll(CatalogFacets.tagKeys(p.tags));
                licenses.add(p.license);
                deps.addAll(p.deps.keySet());
                authors.add(p.author);
            }
            List<String> games = new ArrayList<>(gv);
            games.sort((a, b) -> CatalogModels.Versions.compareVersions(b, a));
            return new Options(games, tags, licenses, deps, authors);
        }

        /** Values by how often they occur, then by name. */
        static List<String> byFrequency(List<String> values) {
            Map<String, Integer> n = new LinkedHashMap<>();
            for (String v : values) n.merge(v, 1, Integer::sum);
            List<String> keys = new ArrayList<>(n.keySet());
            keys.sort(Comparator.<String>comparingInt(k -> -n.get(k)).thenComparing(TEXT_ORDER));
            return keys;
        }
    }

    static final String USER_AGENT = "LeviLaunchroid";

    /** Reads a response; it is closed afterwards. */
    interface BodyReader<T> {
        T read(okhttp3.Response response) throws Exception;
    }

    /**
     * GETs url through the app's client with its HTTP cache (cacheControl: the request's Cache-Control, or null),
     * failing after timeoutMs or once cancel fires.
     */
    static <T> T httpGet(Cancel cancel, String url, String cacheControl, long timeoutMs, BodyReader<T> reader) throws Exception {
        Request.Builder rb = new Request.Builder().url(url).header("User-Agent", USER_AGENT);
        if (cacheControl != null) rb.header("Cache-Control", cacheControl);
        Call call = PackageService.get().cachedHttp().newCall(rb.build());
        if (timeoutMs > 0) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS);
        try (Cancel.Handle ignored = cancel == null ? null : cancel.onCancel(call::cancel);
             okhttp3.Response resp = call.execute()) {
            return reader.read(resp);
        }
    }

    /** The body of a 200 response as text; null for another status. */
    static String text(okhttp3.Response r) throws IOException {
        ResponseBody b = r.body();
        return r.code() == 200 && b != null ? b.string() : null;
    }

    static PackageService.Failure unavailable(String detail, Throwable cause) {
        return new PackageService.Failure(PackageService.ERR_PKG_INDEX_UNAVAILABLE, detail, cause);
    }

    static PackageService.Failure notFound(String detail) {
        return new PackageService.Failure(PackageService.ERR_PACKAGE_NOT_FOUND, detail, null);
    }
}
