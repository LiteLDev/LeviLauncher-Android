package org.levimc.launcher.core.packages;

import org.levimc.launcher.core.packages.engine.SemVer;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CatalogModels {
    private CatalogModels() {
    }

    public final static class Pkg {
        public final String identifier;
        public final String name;
        /** The listing's description (what the list shows and the search reads): the catalogue index's, else the detail's. */
        public final String description;
        /** The detail's whole description (the package page), else the listing's. */
        public final String fullDescription;
        /** The GitHub owner. */
        public final String author;
        public final List<String> tags;
        public final String avatarUrl;
        public final String projectUrl;
        public final String license;
        public final String licenseFamily;
        public final double stars;
        public final String updated;
        /** {@link #updated} in epoch milliseconds; 0 when unknown. */
        public final long updatedMs;
        /** When the repository was created, in epoch milliseconds; 0 when unknown. */
        public final long createdMs;
        /** The catalogue's game versions (e.g. "1.26.60"), newest first. */
        public final List<String> gameVersions;
        /** What the latest version depends on, by bare id as the catalogue names it (ranges joined by " || "). */
        public final Map<String, String> deps;
        public final List<String> versions;
        public final List<String> llRanges;
        public final String preferredVariant;
        /** The client variants for the device's platform, the preferred one first. */
        public final List<CatalogModels.Variant> variants;
        public final List<CatalogModels.PackageFile> files;

        Pkg(Detail d, String identifier, String name, String description, String author, String license,
            Map<String, String> deps, CatalogModels.Variant preferred, List<CatalogModels.Variant> variants) {
            this.identifier = identifier;
            this.name = name;
            this.description = description;
            String full = PackageService.trim(d.description);
            this.fullDescription = full.isEmpty() ? description : full;
            this.author = author;
            this.tags = Collections.unmodifiableList(new ArrayList<>(Detail.list(d.tags)));
            this.avatarUrl = Catalog.Index.normalizeHttpUrl(d.logo);
            String project = Catalog.Index.normalizeHttpUrl(d.repository);
            this.projectUrl = project.isEmpty() ? Catalog.Index.inferProjectUrl(identifier) : project;
            this.license = license;
            this.licenseFamily = CatalogFacets.licenseFamily(license);
            this.stars = d.stars;
            this.updated = PackageService.trim(d.updated);
            this.updatedMs = Catalog.Search.updatedTime(updated);
            this.createdMs = Catalog.Search.updatedTime(d.created);
            this.gameVersions = Collections.unmodifiableList(new ArrayList<>(Detail.list(d.gameVersions)));
            this.deps = Collections.unmodifiableMap(deps);
            this.versions = preferred.versions;
            this.llRanges = preferred.llRanges;
            this.preferredVariant = preferred.key;
            this.variants = Collections.unmodifiableList(new ArrayList<>(variants));
            this.files = preferred.files;
        }

        public CatalogModels.Variant defaultVariant() {
            CatalogModels.Variant pref = findVariant(preferredVariant);
            return pref != null ? pref : variants.isEmpty() ? null : variants.get(0);
        }

        public CatalogModels.Variant findVariant(String key) {
            for (CatalogModels.Variant v : variants) {
                if (v.key.equals(PackageService.trim(key))) {
                    return v;
                }
            }
            return null;
        }

        public CatalogModels.Availability availability(String rid) {
            CatalogModels.Availability best = null;
            for (CatalogModels.Variant v : variants) {
                best = CatalogModels.Availability.best(best, v.availability(rid));
            }
            return best == null ? CatalogModels.Availability.UNKNOWN : best;
        }

        /** The owner's GitHub page. */
        public String ownerUrl() {
            return "https://github.com/" + author.trim();
        }

        @Override
        public String toString() {
            return "Pkg{" + identifier + "}";
        }
    }

    /** A catalogue document (levimc.org/api/v4/mods/&lt;id&gt;/index.json), bound by Gson. */
    public final static class Detail {
        /** An entry of a variant: its side ("server" or "client"), platform (a RID) and dependencies. */
        public static final class Entry {
            public String side;
            public String platform;
            public Map<String, String> deps;
        }

        public static final class Version {
            public String version;
            /** Variant name ("" is the default variant) to its entries, in the document's order. */
            public Map<String, List<Entry>> variants;
        }

        public static final class Latest {
            public String version;
        }

        /** A dependency as the catalogue names it; url is set when it has a page. */
        public static final class Ref {
            public String id;
            public String url;
        }

        public static final class Readme {
            public String file;
            public String lang;
            public String text;
        }

        public String id;
        public String name;
        public String description;
        public String owner;
        public String logo;
        public String repository;
        public String license;
        public String updated;
        public String created;
        public double stars;
        public List<String> tags;
        public List<String> gameVersions;
        public Latest latest;
        /** Lower-case bare id to the dependency. */
        public Map<String, Ref> dependencies;
        /** Newest first. */
        public List<Version> versions;
        public List<Readme> readmes;
        /** Not in the document: the description the catalogue index lists. */
        public String catalogDescription;

        public static Detail parse(String json) {
            try {
                Detail d = Catalog.GSON.fromJson(json, Detail.class);
                if (d == null) {
                    d = new Detail();
                }
                if (d.id == null) {
                    d.id = "";
                }
                return d;
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid catalogue document: " + e.getMessage(), e);
            }
        }

        static <T> List<T> list(List<T> l) {
            return l == null ? Collections.emptyList() : l;
        }
    }

    public final static class PackageFile {
        public final String version;
        public final Map<String, String> dependencies;
        public final List<String> llRanges;
        public final Map<String, String> otherDependencies;
        /** The platforms of its client entries (none for a file built by hand: availability unknown). */
        private final Set<String> platforms;

        /** deps: what lip derives for the device's entry (blank keys and ranges dropped). */
        PackageFile(String version, Map<String, String> deps, Collection<String> platforms) {
            this.version = version == null ? "" : version;
            Map<String, String> all = new LinkedHashMap<>();
            List<String> ll = new ArrayList<>();
            Map<String, String> other = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : deps.entrySet()) {
                String nk = PackageService.trim(e.getKey());
                String rng = PackageService.trim(e.getValue());
                if (nk.isEmpty() || rng.isEmpty()) {
                    continue;
                }
                all.put(nk, rng);
                if (PackageService.isLeviLamina(nk)) {
                    if (!ll.contains(rng)) {
                        ll.add(rng);
                    }
                    continue;
                }
                other.put(nk, rng);
            }
            this.dependencies = Collections.unmodifiableMap(all);
            this.llRanges = Collections.unmodifiableList(ll);
            this.otherDependencies = Collections.unmodifiableMap(other);
            this.platforms = Collections.unmodifiableSet(new LinkedHashSet<>(platforms));
        }

        public static PackageFile of(String version, Map<String, String> dependencies, String... platforms) {
            return new PackageFile(version, dependencies == null ? Collections.emptyMap() : dependencies,
                    Arrays.asList(platforms));
        }

        public Set<String> platforms() {
            return platforms;
        }

        public CatalogModels.Availability availability(String rid) {
            return CatalogModels.Availability.of(platforms(), rid);
        }

        @Override
        public String toString() {
            return "PackageFile{" + version + ", ll=" + llRanges + ", deps=" + otherDependencies + ", platforms=" + platforms() + "}";
        }
    }

    public final static class Variant {
        public final String key;
        public final String packageIdentifier;
        public final List<String> versions;
        public final List<String> llRanges;
        public final List<CatalogModels.PackageFile> files;

        public Variant(String key, String packageIdentifier, List<CatalogModels.PackageFile> files) {
            this.key = key == null ? "" : key;
            this.packageIdentifier = packageIdentifier == null ? "" : packageIdentifier;
            List<CatalogModels.PackageFile> fs = files == null ? Collections.emptyList() : new ArrayList<>(files);
            List<String> vs = new ArrayList<>();
            List<String> ll = new ArrayList<>();
            for (CatalogModels.PackageFile f : fs) {
                vs.add(f.version);
                for (String r : f.llRanges) {
                    if (!ll.contains(r)) {
                        ll.add(r);
                    }
                }
            }
            this.files = Collections.unmodifiableList(fs);
            this.versions = Collections.unmodifiableList(vs);
            this.llRanges = Collections.unmodifiableList(ll);
        }

        public CatalogModels.PackageFile findFile(String version) {
            String want = CatalogModels.Versions.normalizeSemverInput(version);
            for (CatalogModels.PackageFile f : files) {
                if (CatalogModels.Versions.normalizeSemverInput(f.version).equalsIgnoreCase(want)) {
                    return f;
                }
            }
            return null;
        }

        public CatalogModels.Availability availability(String rid) {
            CatalogModels.Availability best = null;
            for (CatalogModels.PackageFile f : files) {
                best = CatalogModels.Availability.best(best, f.availability(rid));
                if (best == CatalogModels.Availability.AVAILABLE) {
                    break;
                }
            }
            return best == null ? CatalogModels.Availability.UNKNOWN : best;
        }

        public List<CatalogModels.PackageFile> sortedFiles() {
            List<CatalogModels.PackageFile> out = new ArrayList<>(files);
            out.sort((a, b) -> -CatalogModels.Versions.compareVersions(a.version, b.version));
            return out;
        }

        @Override
        public String toString() {
            return "Variant{" + packageIdentifier + " " + versions + "}";
        }
    }

    /** Version helpers of the catalogue: game versions (dotted numbers) and the catalogue's loader ranges. */
    public final static class Versions {
        private Versions() {
        }

        private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
        private static final Pattern COMPARATOR = Pattern.compile("^(>=|<=|>|<|=)\\s*(.+)$", Pattern.DOTALL);

        public static String normalizeSemverInput(String s) {
            s = PackageService.trim(s);
            if (!s.isEmpty() && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) {
                s = s.substring(1);
            }
            return s;
        }

        /** s as a SemVer after an optional leading "v"; null when it is not one. */
        public static SemVer parseSemver(String s) {
            return SemVer.parse(normalizeSemverInput(s));
        }

        /** Like {@link #parseSemver}, with a missing minor or patch read as 0. */
        static SemVer parseLoose(String v) {
            String s = normalizeSemverInput(v);
            String core = s.split("[-+]", 2)[0];
            String rest = s.substring(core.length());
            int dots = core.length() - core.replace(".", "").length();
            for (int i = dots; i < 2 && !core.isEmpty(); i++) core += ".0";
            return SemVer.parse(core + rest);
        }

        /** Versions by SemVer precedence (lenient), above any other text, which compares case-insensitively. */
        public static int compare(String a, String b) {
            SemVer av = parseLoose(a), bv = parseLoose(b);
            if (av != null && bv != null) return Integer.signum(av.compareTo(bv));
            if (av != null || bv != null) return av != null ? 1 : -1;
            return Integer.signum(Catalog.TEXT_ORDER.compare(normalizeSemverInput(a), normalizeSemverInput(b)));
        }

        /** Dotted versions segment by segment, numerically where both segments are numbers (game versions). */
        public static int compareVersions(String a, String b) {
            String[] as = (a == null ? "" : a).split("\\.", -1);
            String[] bs = (b == null ? "" : b).split("\\.", -1);
            int n = Math.max(as.length, bs.length);
            for (int i = 0; i < n; i++) {
                String ai = i < as.length ? as[i] : "0";
                String bi = i < bs.length ? bs[i] : "0";
                int c = DIGITS.matcher(ai).matches() && DIGITS.matcher(bi).matches()
                        ? new BigInteger(ai).compareTo(new BigInteger(bi)) : ai.equals(bi) ? 0 : Catalog.TEXT_ORDER.compare(ai, bi);
                if (c != 0) {
                    return Integer.signum(c);
                }
            }
            return 0;
        }

        public static int comparePlanVersions(String a, String b) {
            SemVer av = parseSemver(a), bv = parseSemver(b);
            return av != null && bv != null ? Integer.signum(av.compareTo(bv)) : compareVersions(a, b);
        }

        /** One space-separated token of a catalogue range: a comparator, a wildcard ("1.x", "1.2.*") or a version. */
        private static boolean tokenMatches(SemVer target, String token) {
            Matcher m = COMPARATOR.matcher(token);
            if (m.matches()) {
                SemVer v = parseSemver(m.group(2));
                if (v == null) {
                    return false;
                }
                int c = target.compareTo(v);
                switch (m.group(1)) {
                    case ">=":
                        return c >= 0;
                    case "<=":
                        return c <= 0;
                    case ">":
                        return c > 0;
                    case "<":
                        return c < 0;
                    default:
                        return c == 0;
                }
            }
            String[] parts = normalizeSemverInput(token).split("\\.", -1);
            int at = -1;
            for (int i = 0; i < parts.length && at < 0; i++) {
                if (parts[i].equals("*") || parts[i].equalsIgnoreCase("x")) at = i;
            }
            if (at >= 0 && parts.length <= 3) {
                // "*", "M.*", "M.m.*": the core's leading numbers equal, the rest wildcards.
                BigInteger[] core = {target.major, target.minor};
                for (int i = 0; i < parts.length; i++) {
                    boolean wild = parts[i].equals("*") || parts[i].equalsIgnoreCase("x");
                    if (i < at ? !DIGITS.matcher(parts[i]).matches() || !core[i].equals(new BigInteger(parts[i])) : !wild) {
                        return false;
                    }
                }
                return true;
            }
            SemVer v = parseSemver(token);
            return v != null && target.compareTo(v) == 0;
        }

        /** Whether version matches every space-separated token of expr (the catalogue's ranges). */
        public static boolean inRange(String version, String expr) {
            SemVer target = parseSemver(version);
            String e = PackageService.trim(expr);
            if (target == null || e.isEmpty()) {
                return false;
            }
            for (String t : e.split("\\s+")) {
                if (!tokenMatches(target, t)) {
                    return false;
                }
            }
            return true;
        }

        public static boolean compatible(String version, List<String> ranges) {
            if (ranges == null || PackageService.trim(version).isEmpty()) {
                return false;
            }
            for (String r : ranges) {
                if (inRange(version.trim(), r)) {
                    return true;
                }
            }
            return false;
        }

        public static boolean compatibleWithAny(List<String> ranges, List<String> versions) {
            if (versions == null) {
                return false;
            }
            for (String v : versions) {
                if (compatible(v, ranges)) {
                    return true;
                }
            }
            return false;
        }

        public static List<String> supportedGameVersions(List<String> ranges, LeviLamina.Db db) {
            List<String> out = new ArrayList<>();
            if (ranges == null || ranges.isEmpty() || db == null) {
                return out;
            }
            for (Map.Entry<String, List<String>> e : db.asMap().entrySet()) {
                if (!e.getKey().trim().isEmpty() && compatibleWithAny(ranges, e.getValue())) {
                    out.add(e.getKey().trim());
                }
            }
            out.sort((x, y) -> -compareVersions(x, y));
            return out;
        }

        public static String pickTargetVersion(List<String> versions) {
            String first = "";
            if (versions == null) {
                return first;
            }
            for (String v : versions) {
                v = PackageService.trim(v);
                if (v.isEmpty()) {
                    continue;
                }
                if (first.isEmpty()) {
                    first = v;
                }
                if (isStable(v)) {
                    return v;
                }
            }
            return first;
        }

        /** Whether target names another version than modVersion (an update is possible). */
        public static boolean canUpdate(String modVersion, String target) {
            return !target.isEmpty() && !normalizeSemverInput(modVersion).equalsIgnoreCase(normalizeSemverInput(target));
        }

        public static boolean isPrerelease(String v) {
            SemVer s = parseSemver(v);
            return s != null && s.isPrerelease() || isReleaseCandidate(v);
        }

        public static boolean isStable(String v) {
            return !PackageService.trim(v).contains("-");
        }

        public static boolean isReleaseCandidate(String v) {
            return PackageService.key(v).contains("rc");
        }
    }

    public enum Availability {
        AVAILABLE,
        UNKNOWN,
        UNAVAILABLE;

        public static CatalogModels.Availability best(CatalogModels.Availability a, CatalogModels.Availability b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return a.ordinal() <= b.ordinal() ? a : b;
        }

        public static CatalogModels.Availability of(Collection<String> platforms, String rid) {
            if (platforms == null || platforms.isEmpty()) {
                return UNKNOWN;
            }
            for (String p : platforms) {
                if (Catalog.Index.selects(p, rid)) {
                    return AVAILABLE;
                }
            }
            return UNAVAILABLE;
        }
    }

    public final static class InstallState {
        public static final InstallState NOT_INSTALLED = new InstallState(false, false, "", "");

        public final boolean installed;
        public final boolean explicit;
        public final String installedVersion;
        public final String error;

        public InstallState(boolean installed, boolean explicit, String installedVersion, String error) {
            this.installed = installed;
            this.explicit = explicit;
            this.installedVersion = installedVersion == null ? "" : installedVersion;
            this.error = error == null ? "" : error;
        }

        public static InstallState installed(boolean explicit, String version) {
            return new InstallState(true, explicit, version, "");
        }

        public static InstallState failed(String code) {
            return new InstallState(false, false, "", code);
        }

        @Override
        public String toString() {
            return "InstallState{installed=" + installed + ", explicit=" + explicit + ", version=" + installedVersion
                    + (error.isEmpty() ? "" : ", error=" + error) + "}";
        }
    }
}
