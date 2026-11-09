package org.levimc.launcher.core.packages;

import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.packages.engine.Listener;
import org.levimc.launcher.core.packages.engine.SemVer;
import org.levimc.launcher.core.versions.GameVersion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class LeviLamina {
    private LeviLamina() {
    }

    /** The versions of the game version mcVersion: its exact key, else the key equal segment by segment; null when none. */
    public static List<String> resolveSupportedVersions(Map<String, List<String>> db, String mcVersion) {
        String v = mcVersion == null ? "" : mcVersion.trim();
        if (v.isEmpty() || db == null) {
            return null;
        }
        List<String> exact = db.get(v);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }
        for (Map.Entry<String, List<String>> e : db.entrySet()) {
            if (LeviLamina.Db.versionEqual(e.getKey(), v) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue();
            }
        }
        return null;
    }

    /** The newest version (pre-releases included); "" for none. */
    public static String pickPreferredVersion(List<String> versions) {
        List<String> sorted = sortVersions(versions);
        return sorted.isEmpty() ? "" : sorted.get(0);
    }

    /** Trimmed, de-duplicated, newest first (stable). */
    public static List<String> sortVersions(List<String> versions) {
        List<String> out = new ArrayList<>();
        if (versions != null) {
            for (String v : versions) {
                String t = v == null ? "" : v.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        out.sort((x, y) -> -CatalogModels.Versions.compare(x, y));
        return out;
    }

    /** requested in supported (case-insensitive); null when absent. */
    public static String findRequestedVersion(String requested, List<String> supported) {
        String want = requested == null ? "" : requested.trim();
        if (want.isEmpty() || supported == null) return null;
        for (String v : supported) if (v.trim().equalsIgnoreCase(want)) return v.trim();
        return null;
    }

    /** The LeviLamina versions an install accepts for the game version, newest first; fails with ERR_FETCH_LL_DB. */
    public static List<String> supportedVersions(Cancel c, String mcVersion) throws PackageService.Failure {
        return sortVersions(resolveSupportedVersions(DbClient.get().load(c, false).db.asMap(), mcVersion));
    }

    /**
     * Installs LeviLamina llVersion ("" for the newest the database lists for the instance's game version), or
     * updates it when it is installed explicitly; an installed LeviLamina is changed in place.
     */
    public static String install(Cancel c, Listener l, GameVersion version, String llVersion) {
        String mcVersion = version == null || version.versionCode == null ? "" : version.versionCode.trim();
        List<String> versions;
        try {
            versions = supportedVersions(c, mcVersion);
        } catch (PackageService.Failure e) {
            return e.code.equals(PackageService.ERR_PKG_TASK_CANCELLED) ? e.code : PackageService.ERR_FETCH_LL_DB;
        }
        if (mcVersion.split("\\.", -1).length < 3) return PackageService.ERR_INVALID_VERSION_FORMAT;
        if (versions.isEmpty()) return PackageService.ERR_LL_NOT_SUPPORTED;
        String requested = llVersion == null ? "" : llVersion.trim();
        String ll = requested.isEmpty() ? pickPreferredVersion(versions) : findRequestedVersion(requested, versions);
        if (ll == null) return PackageService.ERR_LL_VERSION_UNSUPPORTED;
        return PackageService.get().installPackage(c, l, version, PackageService.LEVILAMINA_PACKAGE_ID, ll);
    }

    public static String uninstall(Cancel c, Listener l, GameVersion version) {
        String id = PackageService.LEVILAMINA_PACKAGE_ID;
        return PackageService.get().uninstallById(c, l, version, id, id);
    }

    public final static class Db {
        public static final String ACTION_INSTALL = "install";
        public static final String ACTION_UPGRADE = "upgrade";
        public static final String ACTION_DOWNGRADE = "downgrade";
        public static final String ACTION_REINSTALL = "reinstall";
        public static final String ACTION_CHANGE = "change";

        private static final Db EMPTY = new Db(new TreeMap<>());

        private final Map<String, List<String>> games;

        private Db(Map<String, List<String>> games) {
            this.games = Collections.unmodifiableMap(games);
        }

        public static Db empty() {
            return EMPTY;
        }

        public static Db normalize(Map<String, List<String>> raw) {
            Map<String, List<String>> out = new TreeMap<>();
            if (raw == null) {
                return new Db(out);
            }
            for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                String g = CatalogModels.Versions.normalizeSemverInput(e.getKey());
                if (g.isEmpty()) {
                    continue;
                }
                Set<String> seen = new HashSet<>();
                List<String> list = new ArrayList<>();
                if (e.getValue() != null) {
                    for (String v : e.getValue()) {
                        v = PackageService.trim(v);
                        if (v.isEmpty() || !seen.add(v)) {
                            continue;
                        }
                        list.add(v);
                    }
                }
                if (list.isEmpty()) {
                    continue;
                }
                list.sort((a, b) -> -CatalogModels.Versions.compare(a, b));
                out.put(g, Collections.unmodifiableList(list));
            }
            return new Db(out);
        }

        /** The game versions of a version-db.json document, each with its LeviLamina versions. */
        private static final class Doc {
            Map<String, List<String>> versions;
        }

        public static Map<String, List<String>> parseRaw(String json) {
            try {
                Doc d = Catalog.GSON.fromJson(json, Doc.class);
                Map<String, List<String>> out = new LinkedHashMap<>();
                if (d != null && d.versions != null) {
                    for (Map.Entry<String, List<String>> e : d.versions.entrySet()) {
                        out.put(e.getKey(), e.getValue() == null ? new ArrayList<>() : e.getValue());
                    }
                }
                return out;
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid version database: " + e.getMessage(), e);
            }
        }

        public Map<String, List<String>> asMap() {
            return games;
        }

        public boolean isEmpty() {
            return games.isEmpty();
        }

        public int size() {
            return games.size();
        }

        public List<String> versions(String gameVersion) {
            List<String> v = games.get(CatalogModels.Versions.normalizeSemverInput(gameVersion));
            return v == null ? Collections.emptyList() : v;
        }

        public boolean supports(String gameVersion) {
            return !versions(gameVersion).isEmpty();
        }

        public List<String> gameVersions() {
            List<String> out = new ArrayList<>(games.keySet());
            out.sort((a, b) -> {
                int c = CatalogModels.Versions.compareVersions(a, b);
                return c != 0 ? -c : -a.compareTo(b);
            });
            return out;
        }

        public String resolveGame(String spec) {
            spec = CatalogModels.Versions.normalizeSemverInput(spec);
            List<String> list = gameVersions();
            if (spec.equalsIgnoreCase("latest")) {
                return list.isEmpty() ? null : list.get(0);
            }
            if (games.containsKey(spec)) {
                return spec;
            }
            for (String g : list) {
                if (versionEqual(g, spec)) {
                    return g;
                }
            }
            for (String g : list) {
                if (hasVersionPrefix(g, spec)) {
                    return g;
                }
            }
            return null;
        }

        private static String[] segments(String v) {
            return PackageService.trim(v).split("\\.", -1);
        }

        private static boolean segmentEqual(String a, String b) {
            Long na = atoi(a);
            Long nb = atoi(b);
            if (na != null && nb != null) {
                return na.longValue() == nb.longValue();
            }
            return a.equalsIgnoreCase(b);
        }

        private static Long atoi(String s) {
            if (s.isEmpty()) {
                return null;
            }
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean sign = i == 0 && (c == '+' || c == '-') && s.length() > 1;
                if (!sign && (c < '0' || c > '9')) {
                    return null;
                }
            }
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public static boolean versionEqual(String a, String b) {
            String[] sa = segments(a);
            String[] sb = segments(b);
            if (sa.length != sb.length) {
                return false;
            }
            for (int i = 0; i < sa.length; i++) {
                if (!segmentEqual(sa[i], sb[i])) {
                    return false;
                }
            }
            return true;
        }

        public static boolean hasVersionPrefix(String version, String prefix) {
            String[] sv = segments(version);
            String[] sp = segments(prefix);
            if (PackageService.trim(prefix).isEmpty() || sp.length > sv.length) {
                return false;
            }
            for (int i = 0; i < sp.length; i++) {
                if (!segmentEqual(sv[i], sp[i])) {
                    return false;
                }
            }
            return true;
        }

        public static String loaderLatest(List<String> list) {
            return list == null || list.isEmpty() ? "" : list.get(0);
        }

        public static String loaderLatestStable(List<String> list) {
            if (list != null) {
                for (String v : list) {
                    if (!CatalogModels.Versions.isPrerelease(v)) {
                        return v;
                    }
                }
            }
            return "";
        }

        public static String findLoaderVersion(List<String> list, String want) {
            String w = CatalogModels.Versions.normalizeSemverInput(want);
            if (w.isEmpty() || list == null) {
                return null;
            }
            for (String v : list) {
                String t = PackageService.trim(v);
                if (t.equalsIgnoreCase(w) || CatalogModels.Versions.normalizeSemverInput(t).equalsIgnoreCase(w)) {
                    return t;
                }
            }
            return null;
        }

        public static int compareLoaderVersions(String a, String b) {
            return CatalogModels.Versions.compare(a, b);
        }

        public static String loaderAction(String current, String target) {
            if (PackageService.trim(current).isEmpty()) {
                return ACTION_INSTALL;
            }
            SemVer sc = CatalogModels.Versions.parseSemver(current);
            SemVer st = CatalogModels.Versions.parseSemver(target);
            if (sc == null || st == null) {
                if (CatalogModels.Versions.normalizeSemverInput(current).equalsIgnoreCase(CatalogModels.Versions.normalizeSemverInput(target))) {
                    return ACTION_REINSTALL;
                }
                return ACTION_CHANGE;
            }
            int c = st.compareTo(sc);
            if (c > 0) {
                return ACTION_UPGRADE;
            }
            if (c < 0) {
                return ACTION_DOWNGRADE;
            }
            return ACTION_REINSTALL;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Db && ((Db) o).games.equals(games);
        }

        @Override
        public int hashCode() {
            return games.hashCode();
        }
    }

    public final static class DbClient {
        public static final List<String> URLS = Collections.unmodifiableList(Arrays.asList(
                "https://cdn.jsdelivr.net/gh/LiteLDev/levilamina-client-version-db@main/v2/version-db.json",
                "https://fastly.jsdelivr.net/gh/LiteLDev/levilamina-client-version-db@main/v2/version-db.json",
                "https://raw.githubusercontent.com/LiteLDev/levilamina-client-version-db/refs/heads/main/v2/version-db.json",
                "https://github.bibk.top/LiteLDev/levilamina-client-version-db/raw/refs/heads/main/v2/version-db.json"));
        public static final long REQUEST_TIMEOUT_MS = 10_000;

        public static final class Result {
            public final LeviLamina.Db db;
            public final long fetchedAtMs;
            public final boolean stale;
            public final PackageService.Failure error;

            Result(LeviLamina.Db db, long fetchedAtMs, boolean stale, PackageService.Failure error) {
                this.db = db;
                this.fetchedAtMs = fetchedAtMs;
                this.stale = stale;
                this.error = error;
            }
        }

        /** How long a failed fetch is remembered: loads meanwhile answer without the network. */
        static final long RETRY_AFTER_MS = TimeUnit.MINUTES.toMillis(2);
        private static final DbClient INSTANCE = new DbClient();

        private final ReentrantLock lock = new ReentrantLock();
        private volatile Result memory;
        private PackageService.Failure lastFailure;
        private long lastFailureAt;

        public static DbClient get() {
            return INSTANCE;
        }

        public Result cached() {
            return memory;
        }

        /**
         * The database from the first mirror that answers; through the HTTP cache, which serves a fresh copy without
         * the network (refresh revalidates it), and any copy once every mirror failed.
         */
        Map<String, List<String>> fetchRaw(Cancel c, boolean refresh) throws PackageService.Failure {
            String lastMsg = "no source";
            Throwable last = null;
            for (String cacheControl : new String[]{refresh ? "no-cache" : null, "only-if-cached, max-stale=2147483647"}) {
                for (String url : URLS) {
                    PackageService.check(c);
                    try {
                        String body = Catalog.httpGet(c, url, cacheControl, REQUEST_TIMEOUT_MS, Catalog::text);
                        if (body != null) {
                            return LeviLamina.Db.parseRaw(body);
                        }
                        lastMsg = "no answer from " + url;
                    } catch (Exception e) {
                        last = e;
                        lastMsg = PackageService.message(e);
                    }
                }
            }
            PackageService.check(c);
            throw new PackageService.Failure(PackageService.ERR_FETCH_LL_DB, lastMsg, last);
        }

        public Result load(Cancel c, boolean refresh) throws PackageService.Failure {
            Result m = memory;
            if (!refresh && m != null && !m.stale) {
                return m;
            }
            acquire(c);
            try {
                m = memory;
                if (!refresh && m != null && !m.stale) {
                    return m;
                }
                if (!refresh && lastFailure != null && System.currentTimeMillis() - lastFailureAt < RETRY_AFTER_MS) {
                    if (m != null) return m;
                    throw lastFailure;
                }
                try {
                    Result r = new Result(LeviLamina.Db.normalize(fetchRaw(c, refresh)), System.currentTimeMillis(), false, null);
                    memory = r;
                    lastFailure = null;
                    return r;
                } catch (PackageService.Failure e) {
                    if (e.code.equals(PackageService.ERR_PKG_TASK_CANCELLED)) throw e;
                    lastFailure = e;
                    lastFailureAt = System.currentTimeMillis();
                    if (m == null) throw e;
                    return memory = new Result(m.db, m.fetchedAtMs, true, e);
                }
            } finally {
                lock.unlock();
            }
        }

        private void acquire(Cancel c) throws PackageService.Failure {
            while (true) {
                PackageService.check(c);
                try {
                    if (lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw PackageService.cancelled();
                }
            }
        }
    }
}
