package org.levimc.launcher.core.packages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InstallPlanner {
    public static final String ACTION_INSTALL = "install";
    public static final String ACTION_UPGRADE = "upgrade";
    public static final String ACTION_DOWNGRADE = "downgrade";
    public static final String ACTION_REINSTALL = "reinstall";
    public static final String ACTION_PROMOTE = "promote";

    public static final String BLOCK_LOADER_DB_UNAVAILABLE = "ERR_FETCH_LL_DB";
    public static final String BLOCK_GAME_UNSUPPORTED = "ERR_PKG_GAME_VERSION_UNSUPPORTED";
    public static final String BLOCK_LOADER_CHECK_FAILED = "ERR_PACKAGE_QUERY_FAILED";
    public static final String BLOCK_NO_COMPATIBLE_LOADER = "ERR_LL_NO_COMPATIBLE_VERSION";
    public static final String BLOCK_LOADER_VERSION_INVALID = "ERR_LL_VERSION_UNSUPPORTED";
    public static final String BLOCK_VERSION_NOT_FOUND = "ERR_PACKAGE_VERSION_NOT_FOUND";
    public static final String BLOCK_NO_ANDROID_BUILD = PackageService.ERR_PKG_NO_ANDROID_BUILD;

    private InstallPlanner() {
    }

    public static final class Input {
        public CatalogModels.Variant variant;
        public String version = "";
        public String gameVersion = "";
        public LeviLamina.Db db;
        public CatalogModels.InstallState loader = CatalogModels.InstallState.NOT_INSTALLED;
        public CatalogModels.InstallState pkg = CatalogModels.InstallState.NOT_INSTALLED;
        public String loaderVersion = "";
        public String rid;

        boolean hasDb() {
            return db != null && !db.isEmpty();
        }

        boolean supportsGame(CatalogModels.PackageFile f, String game) {
            if (f.llRanges.isEmpty()) {
                return true;
            }
            if (!hasDb() || game.isEmpty()) {
                return false;
            }
            return CatalogModels.Versions.supportedGameVersions(f.llRanges, db).contains(game);
        }
    }

    public static final class Plan {
        public final String gameVersion;
        public final String version;
        public final CatalogModels.PackageFile file;
        public final boolean requiresLoader;
        public final boolean gameCompatible;
        public final List<String> supportedGames;
        public final boolean loaderCompatible;
        public final boolean needsLoader;
        public final boolean replacesLoader;
        public final boolean loaderDowngrade;
        public final List<String> loaderOptions;
        public final String loaderVersion;
        public final String installedLoader;
        public final String action;
        public final String installedVersion;
        public final String blocker;
        public final CatalogModels.Availability availability;
        public final boolean needsConfirm;

        private Plan(Builder b) {
            gameVersion = b.gameVersion;
            version = b.version;
            file = b.file;
            requiresLoader = b.requiresLoader;
            gameCompatible = b.gameCompatible;
            supportedGames = Collections.unmodifiableList(b.supportedGames);
            loaderCompatible = b.loaderCompatible;
            needsLoader = b.needsLoader;
            replacesLoader = b.replacesLoader;
            loaderDowngrade = b.loaderDowngrade;
            loaderOptions = Collections.unmodifiableList(b.loaderOptions);
            loaderVersion = b.loaderVersion;
            installedLoader = b.installedLoader;
            action = b.action;
            installedVersion = b.installedVersion;
            blocker = b.blocker;
            availability = b.availability;
            needsConfirm = b.availability == CatalogModels.Availability.UNKNOWN && b.blocker.isEmpty();
        }
    }

    private static final class Builder {
        String gameVersion = "";
        String version = "";
        CatalogModels.PackageFile file;
        boolean requiresLoader;
        boolean gameCompatible;
        List<String> supportedGames = new ArrayList<>();
        boolean loaderCompatible;
        boolean needsLoader;
        boolean replacesLoader;
        boolean loaderDowngrade;
        List<String> loaderOptions = new ArrayList<>();
        String loaderVersion = "";
        String installedLoader = "";
        String action = "";
        String installedVersion = "";
        String blocker = "";
        CatalogModels.Availability availability;
    }

    private interface FilePredicate {
        boolean test(CatalogModels.PackageFile f);
    }

    private static CatalogModels.PackageFile first(List<CatalogModels.PackageFile> files, FilePredicate p) {
        for (CatalogModels.PackageFile f : files) {
            if (p.test(f)) {
                return f;
            }
        }
        return null;
    }

    /** The database key of an instance's game version (gameVersion itself when the database has none). */
    static String resolveGame(LeviLamina.Db db, String gameVersion) {
        String requested = CatalogModels.Versions.normalizeSemverInput(gameVersion);
        if (db != null && !db.isEmpty() && !requested.isEmpty() && !requested.equalsIgnoreCase("latest")
                && !db.asMap().containsKey(requested)) {
            // Android: the instance's versionName ("1.21.132.1") names a database key ("1.21.132.01").
            String key = db.resolveGame(requested);
            if (key != null) {
                return key;
            }
        }
        return requested;
    }

    /**
     * Whether a version's LeviLamina ranges fit an instance: the installed LeviLamina when there is one, else a
     * LeviLamina the database lists for the instance's game. True when it needs no LeviLamina, or when neither is known.
     */
    public static boolean fitsLoader(CatalogModels.PackageFile f, String installedLoader, LeviLamina.Db db,
                                     String gameVersion) {
        if (f.llRanges.isEmpty()) {
            return true;
        }
        String ll = PackageService.trim(installedLoader);
        if (!ll.isEmpty()) {
            return CatalogModels.Versions.compatible(ll, f.llRanges);
        }
        String game = resolveGame(db, gameVersion);
        if (db == null || db.isEmpty() || game.isEmpty()) {
            return true;
        }
        return CatalogModels.Versions.compatibleWithAny(f.llRanges, db.versions(game));
    }

    public static Plan decide(Input in) {
        String rid = in.rid == null ? PackageService.rid() : in.rid;
        String game = resolveGame(in.db, in.gameVersion);
        List<CatalogModels.PackageFile> files = new ArrayList<>();
        if (in.variant != null) {
            files.addAll(in.variant.sortedFiles());
        }
        Builder p = new Builder();
        p.gameVersion = game;
        CatalogModels.InstallState loader = in.loader == null ? CatalogModels.InstallState.NOT_INSTALLED : in.loader;
        CatalogModels.InstallState pkg = in.pkg == null ? CatalogModels.InstallState.NOT_INSTALLED : in.pkg;
        String installedLoader = loader.installed ? PackageService.trim(loader.installedVersion) : "";
        CatalogModels.PackageFile file = null;
        String want = PackageService.trim(in.version);
        if (!want.isEmpty()) {
            String w = CatalogModels.Versions.normalizeSemverInput(want);
            file = first(files, f -> CatalogModels.Versions.normalizeSemverInput(f.version).equalsIgnoreCase(w));
            if (file == null) {
                p.version = want;
                p.blocker = BLOCK_VERSION_NOT_FOUND;
                return new Plan(p);
            }
        } else {
            // Prefer a stable release, so a pre-release the dotted order ranks
            // above its own release (1.0.0-rc.1 > 1.0.0) is not picked; and a
            // build for this platform over one with no declared platform. A version the installed
            // LeviLamina accepts comes first, so a newer one needing another LeviLamina is not offered over it.
            for (boolean fitInstalled : new boolean[]{true, false}) {
                for (CatalogModels.Availability tier : new CatalogModels.Availability[]{CatalogModels.Availability.AVAILABLE, CatalogModels.Availability.UNKNOWN}) {
                    FilePredicate fits = f -> f.availability(rid) == tier && in.supportsGame(f, game)
                            && (!fitInstalled || fitsLoader(f, installedLoader, null, ""));
                    file = first(files, f -> fits.test(f) && CatalogModels.Versions.isStable(f.version));
                    if (file == null) {
                        file = first(files, fits);
                    }
                    if (file != null) {
                        break;
                    }
                }
                if (file != null) {
                    break;
                }
            }
            if (file == null) {
                file = first(files, f -> f.availability(rid) != CatalogModels.Availability.UNAVAILABLE);
            }
            if (file == null && !files.isEmpty()) {
                file = files.get(0);
            }
            if (file == null) {
                p.blocker = BLOCK_VERSION_NOT_FOUND;
                return new Plan(p);
            }
        }
        p.file = file;
        p.version = file.version;
        p.availability = file.availability(rid);
        p.requiresLoader = !file.llRanges.isEmpty();
        p.gameCompatible = in.supportsGame(file, game);
        if (p.requiresLoader && in.hasDb()) {
            p.supportedGames = CatalogModels.Versions.supportedGameVersions(file.llRanges, in.db);
        }
        p.installedLoader = PackageService.trim(loader.installedVersion);
        boolean llInstalled = loader.installed;
        p.loaderCompatible = llInstalled && !p.installedLoader.isEmpty()
                && CatalogModels.Versions.compatible(p.installedLoader, file.llRanges);
        boolean checkFailed = p.requiresLoader && !llInstalled && !PackageService.trim(loader.error).isEmpty();
        p.needsLoader = p.requiresLoader && !checkFailed && !p.loaderCompatible;
        p.replacesLoader = p.needsLoader && llInstalled;
        if (p.requiresLoader && !game.isEmpty() && in.hasDb()) {
            for (String v : in.db.versions(game)) {
                if (CatalogModels.Versions.compatible(v, file.llRanges)) {
                    p.loaderOptions.add(v);
                }
            }
        }
        if (p.needsLoader) {
            String wantLoader = PackageService.trim(in.loaderVersion);
            if (!wantLoader.isEmpty()) {
                for (String v : p.loaderOptions) {
                    if (CatalogModels.Versions.normalizeSemverInput(v).equalsIgnoreCase(
                            CatalogModels.Versions.normalizeSemverInput(wantLoader))) {
                        p.loaderVersion = v;
                    }
                }
            } else {
                for (String v : p.loaderOptions) {
                    if (!CatalogModels.Versions.isReleaseCandidate(v)) {
                        p.loaderVersion = v;
                        break;
                    }
                }
                if (p.loaderVersion.isEmpty() && !p.loaderOptions.isEmpty()) {
                    p.loaderVersion = p.loaderOptions.get(0);
                }
            }
            p.loaderDowngrade = p.replacesLoader && !p.installedLoader.isEmpty() && !p.loaderVersion.isEmpty()
                    && CatalogModels.Versions.comparePlanVersions(p.loaderVersion, p.installedLoader) < 0;
        }
        p.installedVersion = PackageService.trim(pkg.installedVersion);
        if (!pkg.installed) {
            p.action = ACTION_INSTALL;
        } else if (p.installedVersion.isEmpty()) {
            p.action = ACTION_REINSTALL;
        } else {
            int d = CatalogModels.Versions.comparePlanVersions(p.version, p.installedVersion);
            if (d > 0) {
                p.action = ACTION_UPGRADE;
            } else if (d < 0) {
                p.action = ACTION_DOWNGRADE;
            } else if (!pkg.explicit) {
                p.action = ACTION_PROMOTE;
            } else {
                p.action = ACTION_REINSTALL;
            }
        }
        if (p.availability == CatalogModels.Availability.UNAVAILABLE) {
            p.blocker = BLOCK_NO_ANDROID_BUILD;
        } else if (p.requiresLoader && !in.hasDb()) {
            p.blocker = BLOCK_LOADER_DB_UNAVAILABLE;
        } else if (p.requiresLoader && !p.gameCompatible) {
            p.blocker = BLOCK_GAME_UNSUPPORTED;
        } else if (checkFailed) {
            p.blocker = BLOCK_LOADER_CHECK_FAILED;
        } else if (p.needsLoader && p.loaderOptions.isEmpty()) {
            p.blocker = BLOCK_NO_COMPATIBLE_LOADER;
        } else if (p.needsLoader && p.loaderVersion.isEmpty()) {
            p.blocker = BLOCK_LOADER_VERSION_INVALID;
        }
        return new Plan(p);
    }
}
