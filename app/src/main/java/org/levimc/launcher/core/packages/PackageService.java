package org.levimc.launcher.core.packages;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.packages.engine.Config;
import org.levimc.launcher.core.packages.engine.Listener;
import org.levimc.launcher.core.packages.engine.PackageEngine;
import org.levimc.launcher.core.packages.engine.PackageException;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

import okhttp3.Cache;
import okhttp3.OkHttpClient;

/**
 * The launcher side of the package manager: runs {@link PackageEngine} operations on game instances
 * (one writer per instance, the download cache shared with cache clean), adds the launcher policy
 * (catalogue identifiers, the client variant with its fallback, explicit/dependency rules) and turns
 * failures into result codes the UI shows. Every operation blocks; run it on {@link #EXECUTOR}.
 */
public final class PackageService {
    // Result codes ("" = success).
    public static final String ERR_TARGET_NOT_FOUND = "ERR_TARGET_NOT_FOUND";
    public static final String ERR_ACCESS_VERSIONS_DIR = "ERR_ACCESS_VERSIONS_DIR";
    public static final String ERR_PKG_WORKSPACE_ENV_MISMATCH = "ERR_PKG_WORKSPACE_ENV_MISMATCH";
    public static final String ERR_PKG_WORKSPACE_INVALID = "ERR_PKG_WORKSPACE_INVALID";
    public static final String ERR_PKG_WORKSPACE_BUSY = "ERR_PKG_WORKSPACE_BUSY";
    public static final String ERR_PACKAGE_INVALID_IDENTIFIER = "ERR_PACKAGE_INVALID_IDENTIFIER";
    public static final String ERR_PACKAGE_VERSION_REQUIRED = "ERR_PACKAGE_VERSION_REQUIRED";
    public static final String ERR_PACKAGE_INSTALL_FAILED = "ERR_PACKAGE_INSTALL_FAILED";
    public static final String ERR_PACKAGE_UNINSTALL_FAILED = "ERR_PACKAGE_UNINSTALL_FAILED";
    public static final String ERR_PACKAGE_REQUIRED_BY_DEPENDENTS = "ERR_PACKAGE_REQUIRED_BY_DEPENDENTS";
    public static final String ERR_PACKAGE_DEMOTED_TO_DEPENDENCY = "ERR_PACKAGE_DEMOTED_TO_DEPENDENCY";
    public static final String ERR_PACKAGE_QUERY_FAILED = "ERR_PACKAGE_QUERY_FAILED";
    public static final String ERR_PACKAGE_NOT_FOUND = "ERR_PACKAGE_NOT_FOUND";
    public static final String ERR_PKG_NO_ANDROID_BUILD = "ERR_PKG_NO_ANDROID_BUILD";
    public static final String ERR_PKG_TASK_CANCELLED = "ERR_PKG_TASK_CANCELLED";
    public static final String ERR_PKG_HOOK_FAILED = "ERR_PKG_HOOK_FAILED";
    public static final String ERR_PKG_INSTALL_FAILED = "ERR_PKG_INSTALL_FAILED";
    public static final String ERR_PKG_CACHE_CLEAN_FAILED = "ERR_PKG_CACHE_CLEAN_FAILED";
    public static final String ERR_PKG_INVALID_GITHUB_PROXY = "ERR_PKG_INVALID_GITHUB_PROXY";
    public static final String ERR_PKG_LOCAL_SOURCE_FAILED = "ERR_PKG_LOCAL_SOURCE_FAILED";
    public static final String ERR_PKG_INDEX_TIMEOUT = "ERR_PKG_INDEX_TIMEOUT";
    public static final String ERR_PKG_INDEX_UNAVAILABLE = "ERR_PKG_INDEX_UNAVAILABLE";
    public static final String ERR_WRITE_FILE = "ERR_WRITE_FILE";
    public static final String ERR_FETCH_LL_DB = "ERR_FETCH_LL_DB";
    public static final String ERR_INVALID_VERSION_FORMAT = "ERR_INVALID_VERSION_FORMAT";
    public static final String ERR_LL_NOT_SUPPORTED = "ERR_LL_NOT_SUPPORTED";
    public static final String ERR_LL_VERSION_UNSUPPORTED = "ERR_LL_VERSION_UNSUPPORTED";
    public static final String ERR_LL_MANAGED_IN_VERSION_SETTINGS = "ERR_LL_MANAGED_IN_VERSION_SETTINGS";
    public static final String ERR_MOD_DELETE_FAILED = "ERR_MOD_DELETE_FAILED";
    public static final String ERR_NO_UPDATE_SOURCE = "ERR_NO_UPDATE_SOURCE";
    public static final String ERR_ALREADY_LATEST = "ERR_ALREADY_LATEST";

    public static final String LEVILAMINA_PACKAGE_ID = "github.com/LiteLDev/LeviLamina";
    /** The variant holding a package's client build (tooth.json label "client"). */
    public static final String CLIENT_VARIANT = "client";
    public static final String DEFAULT_GITHUB_PROXY = "https://github.bibk.top";
    public static final long TASK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(15);
    public static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(daemon("package-task"));

    private static final String TAG = "packages";
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$");
    private static volatile PackageService instance;

    /** A launcher failure with its result code; the message is "code: detail". */
    public static final class Failure extends Exception {
        public final String code;

        public Failure(String code, String detail, Throwable cause) {
            super(detail == null || detail.isEmpty() ? code : code + ": " + detail, cause);
            this.code = code;
        }
    }

    public enum MirrorMode {
        AUTO, ON, OFF;

        public static MirrorMode of(String value) {
            for (MirrorMode m : values()) if (m.name().equalsIgnoreCase(value == null ? "" : value.trim())) return m;
            return AUTO;
        }
    }

    private final File filesDir, configDir, cacheDir, httpCacheDir;
    private final Context context;
    private final FeatureSettings settings;
    private volatile OkHttpClient http, cachedHttp;
    private final Map<String, Semaphore> locks = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock(true);

    /** A service over the given app directories (tests); {@link #get()} after {@link #init} in the app. */
    public PackageService(Context context, FeatureSettings settings, File filesDir, File cacheDir) {
        this.context = context;
        this.settings = settings;
        this.filesDir = filesDir;
        this.configDir = new File(filesDir, "packages/config");
        this.cacheDir = new File(cacheDir, "packages");
        this.httpCacheDir = new File(cacheDir, "packages-http");
    }

    public static synchronized void init(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            instance = new PackageService(app, FeatureSettings.getInstance(), app.getFilesDir(), app.getCacheDir());
        }
    }

    public static PackageService get() {
        PackageService s = instance;
        if (s == null) throw new IllegalStateException("PackageService.init has not run");
        return s;
    }

    /** For tests: replaces the app's service. */
    public static void set(PackageService service) {
        instance = service;
    }

    // #region Environment

    /** This device's platform, a runtime identifier ("android-arm64" on an arm64 device). */
    public static String rid() {
        String[] abis = Build.SUPPORTED_ABIS;
        return abis == null || abis.length == 0 || abis[0] == null ? "android-arm64" : PackageEngine.platformForAbi(abis[0]);
    }

    public Config config() {
        return new Config(configDir, cacheDir);
    }

    /** The client of package operations (built on first use). */
    public OkHttpClient http() {
        OkHttpClient c = http;
        if (c == null) {
            synchronized (this) {
                if ((c = http) == null) {
                    http = c = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();
                }
            }
        }
        return c;
    }

    /** {@link #http()} with an HTTP cache, for the catalogue and the LeviLamina version database. */
    public OkHttpClient cachedHttp() {
        OkHttpClient c = cachedHttp;
        if (c == null) {
            synchronized (this) {
                if ((c = cachedHttp) == null) {
                    cachedHttp = c = http().newBuilder().cache(new Cache(httpCacheDir, 10L * 1024 * 1024)).build();
                }
            }
        }
        return c;
    }

    /** The app's package data directory (picked local packages are copied below it). */
    public File dataDir() {
        return new File(filesDir, "packages");
    }

    public PackageEngine engine(File workspace) {
        return new PackageEngine(workspace, config(), http(), rid(), mirrorEnabled() ? DEFAULT_GITHUB_PROXY : null);
    }

    /** The GitHub proxy operations use: config.toml's, else the mirror default when mirroring; null for none. */
    public String effectiveGithubProxy() {
        String p = config().githubProxy();
        return p != null ? p : mirrorEnabled() ? DEFAULT_GITHUB_PROXY : null;
    }

    public static MirrorMode mirrorMode() {
        return MirrorMode.of(get().settings.getPkgMirrorMode());
    }

    public static void setMirrorMode(MirrorMode mode) {
        get().settings.setPkgMirrorMode(mode == null || mode == MirrorMode.AUTO ? null : mode.name().toLowerCase(Locale.ROOT));
    }

    public static boolean mirrorEnabled() {
        return mirrorEnabled(mirrorMode(), Locale.getDefault(), TimeZone.getDefault().getID());
    }

    /** Auto mirrors for users in mainland China (time zone or locale). */
    public static boolean mirrorEnabled(MirrorMode mode, Locale locale, String timeZone) {
        if (mode != MirrorMode.AUTO) return mode == MirrorMode.ON;
        if (Arrays.asList("Asia/Shanghai", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin", "Asia/Urumqi", "PRC").contains(timeZone)) {
            return true;
        }
        return locale != null && ("CN".equalsIgnoreCase(locale.getCountry())
                || "zh".equalsIgnoreCase(locale.getLanguage()) && locale.getCountry().isEmpty() && locale.getScript().isEmpty());
    }

    /** "" or ERR_PKG_INVALID_GITHUB_PROXY / ERR_WRITE_FILE; blank resets to the default. */
    public String setGithubProxy(String raw) {
        String p = raw == null ? "" : raw.trim().replaceAll("/+$", "");
        try {
            if (p.isEmpty()) config().deleteGithubProxy();
            else config().setGithubProxy(p);
            return "";
        } catch (PackageException e) {
            return e.kind == PackageException.Kind.INVALID_ARGUMENT ? ERR_PKG_INVALID_GITHUB_PROXY : ERR_WRITE_FILE;
        }
    }

    // #endregion

    // #region Workspaces and locks

    /**
     * The workspace of an instance: the directory holding its mods folder (the instance directory), else
     * the profile root of the instance.
     */
    public File workspaceDir(GameVersion version) {
        if (version.versionDir != null) return version.versionDir.getAbsoluteFile();
        return new File(LauncherStorage.getMinecraftRoot(context), LauncherStorage.sanitizeProfileId(version.getStorageProfileId()));
    }

    /** The workspace of version, checked: an existing directory whose state is for this device. */
    public File resolve(GameVersion version) throws Failure {
        return resolveState(version).dir;
    }

    /** A checked workspace with its state, read once. */
    static final class Resolved {
        final File dir;
        final PackageEngine.State state;

        Resolved(File dir, PackageEngine.State state) {
            this.dir = dir;
            this.state = state;
        }
    }

    Resolved resolveState(GameVersion version) throws Failure {
        if (version == null) throw new Failure(ERR_TARGET_NOT_FOUND, "no instance", null);
        File dir;
        try {
            dir = workspaceDir(version);
        } catch (RuntimeException e) {
            throw new Failure(ERR_ACCESS_VERSIONS_DIR, e.getMessage(), e);
        }
        if (!dir.isDirectory()) throw new Failure(ERR_TARGET_NOT_FOUND, dir.toString(), null);
        PackageEngine.State s;
        try {
            s = engine(dir).state();
        } catch (PackageException e) {
            throw new Failure(codeOf(e, ERR_PKG_WORKSPACE_INVALID), message(e), e);
        }
        // The engine keeps a recorded platform (cli 4); the launcher refuses a workspace made for another device.
        if (!PackageEngine.SIDE.equals(s.side) || !rid().equals(s.platform)) {
            throw new Failure(ERR_PKG_WORKSPACE_ENV_MISMATCH, "the workspace records " + s.side + "/" + s.platform
                    + ", this device is " + PackageEngine.SIDE + "/" + rid(), null);
        }
        return new Resolved(dir, s);
    }

    private Semaphore lockOf(File dir) {
        String key;
        try {
            key = dir.getCanonicalPath();
        } catch (IOException e) {
            key = dir.getAbsolutePath();
        }
        return locks.computeIfAbsent(key, k -> new Semaphore(1));
    }

    /** Whether an operation holds dir (launch, backup and delete guards). */
    public boolean isBusy(File dir) {
        return dir != null && lockOf(dir).availablePermits() == 0;
    }

    /**
     * Why the launcher may not change the instance's mods now: an operation holds it, or an interrupted one
     * left its journal (recovered by the next operation, launch or backup); null otherwise.
     */
    public String busyReason(File dir) {
        if (isBusy(dir)) return "a package operation is changing the instance";
        if (PackageEngine.hasJournal(dir)) return "an interrupted package operation has not been recovered yet";
        return null;
    }

    /** A held workspace lock. */
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    /** Holds dir's lock (no operation runs meanwhile); null when an operation holds it. */
    public Held tryLock(File dir) {
        Semaphore lock = lockOf(dir);
        if (!lock.tryAcquire()) return null;
        AtomicInteger once = new AtomicInteger();
        return () -> {
            if (once.getAndIncrement() == 0) lock.release();
        };
    }

    /**
     * Holds dir's lock after recovering an interrupted operation (backups, restores, launch);
     * IOException when an operation holds it or recovery fails.
     */
    public Held lockWorkspace(File dir) throws IOException {
        Held held = tryLock(dir);
        if (held == null) throw new IOException(context.getString(org.levimc.launcher.R.string.pkg_err_workspace_busy));
        try {
            if (PackageEngine.hasJournal(dir)) engine(dir).recover(new Cancel(), logListener());
        } catch (PackageException e) {
            held.close();
            throw new IOException(context.getString(org.levimc.launcher.R.string.pkg_err_recover_failed, e.getMessage()), e);
        }
        return held;
    }

    private interface TryAcquire {
        boolean within(long millis) throws InterruptedException;
    }

    /** Waits for a lock, failing as soon as cancel fires. */
    private static void acquire(TryAcquire lock, Cancel cancel) throws PackageException {
        while (true) {
            cancel.check();
            try {
                if (lock.within(50)) return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PackageException(PackageException.Kind.CANCELLED, "interrupted");
            }
        }
    }

    /** One engine call under the workspace lock, the shared cache lock and a wake lock. */
    private interface Op<T> {
        T run(PackageEngine engine) throws PackageException;
    }

    /** Runs op; after it succeeded, the mod list of the instance follows (failed operations change nothing). */
    private <T> T locked(File dir, Cancel cancel, Op<T> op) throws PackageException {
        boolean existed = dir.isDirectory();
        PowerManager.WakeLock wake = wakeLock();
        Semaphore lock = lockOf(dir);
        try {
            T result;
            acquire(ms -> lock.tryAcquire(ms, TimeUnit.MILLISECONDS), cancel);
            try {
                acquire(ms -> cacheLock.readLock().tryLock(ms, TimeUnit.MILLISECONDS), cancel);
                try {
                    if (existed && !dir.isDirectory()) {
                        throw new PackageException(PackageException.Kind.IO, "the instance " + dir + " was removed");
                    }
                    result = op.run(engine(dir));
                } finally {
                    cacheLock.readLock().unlock();
                }
            } finally {
                lock.release();
            }
            InstalledPackages.sync(dir);
            return result;
        } catch (RuntimeException e) {
            warn("package operation failed", e);
            throw new PackageException(PackageException.Kind.IO, null, "internal error: " + e, e);
        } finally {
            if (wake != null && wake.isHeld()) wake.release();
        }
    }

    private PowerManager.WakeLock wakeLock() {
        try {
            PowerManager pm = context == null ? null : (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return null;
            PowerManager.WakeLock w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "levi:package-task");
            w.setReferenceCounted(false);
            w.acquire(TimeUnit.MINUTES.toMillis(20));
            return w;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // #endregion

    // #region Identifiers

    /** A catalogue identifier ("owner/repo", "github.com/owner/repo", a GitHub URL; "#variant", "@version" allowed). */
    public static final class Request {
        /** "owner/repo", with "#variant" for a variant other than client. */
        public final String identifier;
        /** "github.com/owner/repo". */
        public final String packageId;
        /** The requested variant other than client, or "". */
        public final String variant;
        /** The source of a new install ("#client" unless another variant or a bare "#" is named). */
        public final String requestRef;
        /** The source tried when requestRef has no package for this device ("" for none). */
        public final String fallbackRef;

        Request(String identifier, String packageId, String variant, String requestRef, String fallbackRef) {
            this.identifier = identifier;
            this.packageId = packageId;
            this.variant = variant;
            this.requestRef = requestRef;
            this.fallbackRef = fallbackRef;
        }

        /** null when s is not a GitHub repository identifier. */
        public static Request parse(String s) {
            String t = s == null ? "" : s.trim();
            String variant = "";
            boolean marker = false;
            int hash = t.indexOf('#');
            if (hash >= 0) {
                marker = true;
                variant = t.substring(hash + 1).trim();
                t = t.substring(0, hash).trim();
                int at = variant.indexOf('@');
                if (at >= 0) variant = variant.substring(0, at).trim();
            } else if (t.indexOf('@') >= 0) {
                t = t.substring(0, t.indexOf('@')).trim();
            }
            t = t.replaceFirst("(?i)^https?://", "").replaceAll("^/+|/+$", "");
            String[] parts = t.split("/+");
            if (parts.length == 3 && parts[0].equalsIgnoreCase("github.com")) parts = new String[]{parts[1], parts[2]};
            String base = parts.length == 2 ? parts[0] + "/" + parts[1] : "";
            if (!REPO.matcher(base).matches()) return null;
            String id = "github.com/" + base;
            if (!variant.isEmpty() && !variant.equalsIgnoreCase(CLIENT_VARIANT)) {
                return new Request(base + "#" + variant, id, variant, id + "#" + variant, "");
            }
            if (marker && variant.isEmpty()) return new Request(base, id, "", id, "");
            return new Request(base, id, "", id + "#" + CLIENT_VARIANT, id);
        }
    }

    /** The catalogue identifier of an installed package ("Owner/Repo[#variant]"); null when it is not on GitHub. */
    public static String frontendIdentifier(String id, String variant) {
        String s = (id == null ? "" : id.trim()).replaceFirst("(?i)^https?://", "");
        String[] parts = s.split("/+");
        if (parts.length == 3 && parts[0].equalsIgnoreCase("github.com")) parts = new String[]{parts[1], parts[2]};
        else if (!(parts.length == 2 && !parts[0].contains("."))) return null;
        String v = variant == null ? "" : variant.trim();
        return parts[0] + "/" + parts[1] + (v.isEmpty() || v.equalsIgnoreCase(CLIENT_VARIANT) ? "" : "#" + v);
    }

    /** ASCII-lowercase key of an id or identity. */
    public static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether an id or identifier ("owner/repo", "#variant" allowed) names LeviLamina. */
    public static boolean isLeviLamina(String packageId) {
        return Catalog.Index.lookupKey(packageId).equals("github.com/liteldev/levilamina");
    }

    // #endregion

    // #region State

    /** The installed package with packageId (any variant); null when none. */
    static PackageEngine.Installed find(PackageEngine.State state, String packageId) {
        if (state != null) for (PackageEngine.Installed p : state.packages) if (key(p.id).equals(key(packageId))) return p;
        return null;
    }

    /** The state of each catalogue identifier in the instance (one read of its state file). */
    public List<CatalogModels.InstallState> installStates(GameVersion version, List<String> identifiers) {
        List<CatalogModels.InstallState> out = new ArrayList<>();
        String failure = "";
        PackageEngine.State state = null;
        try {
            state = resolveState(version).state;
        } catch (Failure e) {
            failure = e.code;
        }
        for (String identifier : identifiers) {
            Request r = Request.parse(identifier);
            PackageEngine.Installed p = r == null || state == null ? null : find(state, r.packageId);
            out.add(r == null ? CatalogModels.InstallState.failed(ERR_PACKAGE_INVALID_IDENTIFIER)
                    : !failure.isEmpty() ? CatalogModels.InstallState.failed(failure)
                    : p == null ? CatalogModels.InstallState.NOT_INSTALLED : CatalogModels.InstallState.installed(p.explicit, p.version));
        }
        return out;
    }

    public CatalogModels.InstallState installState(GameVersion version, String identifier) {
        return installStates(version, Collections.singletonList(identifier)).get(0);
    }

    // #endregion

    // #region Operations

    /** The result code of a failed operation whose usual failure is failedCode. */
    static String codeOf(PackageException e, String failedCode) {
        switch (e.kind) {
            case CANCELLED:
                return ERR_PKG_TASK_CANCELLED;
            case HOOK_FAILED:
                return ERR_PKG_HOOK_FAILED;
            case NO_ENTRY_FOR_ENVIRONMENT:
                return ERR_PKG_NO_ANDROID_BUILD;
            case ENV_MISMATCH:
                return ERR_PKG_WORKSPACE_ENV_MISMATCH;
            case INVALID_WORKSPACE:
                return ERR_PKG_WORKSPACE_INVALID;
            case WORKSPACE_LOCKED:
                return ERR_PKG_WORKSPACE_BUSY;
            case NOT_EXPLICIT:
                return ERR_PACKAGE_REQUIRED_BY_DEPENDENTS;
            default:
                return failedCode;
        }
    }

    private static String fail(Listener l, PackageException e, String failedCode) {
        warn(e.kind + ": " + message(e));
        l.log(Listener.Level.ERROR, message(e));
        return codeOf(e, failedCode);
    }

    private static boolean noPackageFor(PackageException e, String arg) {
        return (e.kind == PackageException.Kind.NO_PACKAGE_FOUND || e.kind == PackageException.Kind.NO_ENTRY_FOR_ENVIRONMENT)
                && arg.equalsIgnoreCase(e.argument);
    }

    /**
     * Installs the catalogue package identifier at packageVersion, or updates it when it is installed
     * explicitly. An installed variant is changed in place; a new install asks for the client variant
     * and falls back to the default variant when that has no package for this device.
     */
    public String installPackage(Cancel c, Listener l, GameVersion version, String identifier, String packageVersion) {
        Request r = Request.parse(identifier);
        if (r == null) return ERR_PACKAGE_INVALID_IDENTIFIER;
        String v = packageVersion == null ? "" : packageVersion.trim();
        if (v.isEmpty()) return ERR_PACKAGE_VERSION_REQUIRED;
        Resolved w;
        try {
            w = resolveState(version);
        } catch (Failure e) {
            return e.code;
        }
        File dir = w.dir;
        PackageEngine.Installed installed = find(w.state, r.packageId);
        if (installed != null && (r.variant.isEmpty() || installed.variant.equalsIgnoreCase(r.variant))) {
            return installOrUpdate(c, l, dir, installed.identity + "@" + v, installed.explicit, ERR_PACKAGE_INSTALL_FAILED);
        }
        String code = installOrUpdate(c, l, dir, r.requestRef + "@" + v, false,
                r.fallbackRef.isEmpty() ? ERR_PACKAGE_INSTALL_FAILED : null);
        return code != null ? code : installOrUpdate(c, l, dir, r.fallbackRef + "@" + v, false, ERR_PACKAGE_INSTALL_FAILED);
    }

    /**
     * Installs (or updates when explicit) one source; on "already explicitly installed" it updates. With
     * a null failedCode, a failure to find a package for source returns null (the caller falls back).
     */
    String installOrUpdate(Cancel c, Listener l, File dir, String source, boolean update, String failedCode) {
        List<String> args = Collections.singletonList(source);
        try {
            locked(dir, c, e -> update ? e.update(args, ignoreScripts(), c, l) : e.install(args, ignoreScripts(), c, l));
            return "";
        } catch (PackageException e) {
            if (!update && e.kind == PackageException.Kind.ALREADY_EXPLICITLY_INSTALLED) {
                return installOrUpdate(c, l, dir, source, true, failedCode == null ? ERR_PACKAGE_INSTALL_FAILED : failedCode);
            }
            if (failedCode == null && noPackageFor(e, source)) {
                l.log(Listener.Level.INFO, "No client variant for " + source + "; trying the default variant");
                return null;
            }
            return fail(l, e, failedCode == null ? ERR_PACKAGE_INSTALL_FAILED : failedCode);
        }
    }

    /** Installs a source the engine takes as it is: a local path, an https:// URL or "host/path[#variant]@version". */
    public String installSource(Cancel c, Listener l, GameVersion version, String source) {
        String arg = source == null ? "" : source.trim();
        if (arg.isEmpty()) return ERR_PACKAGE_INVALID_IDENTIFIER;
        try {
            return installOrUpdate(c, l, resolve(version), arg, false, ERR_PACKAGE_INSTALL_FAILED);
        } catch (Failure e) {
            return e.code;
        }
    }

    /**
     * Uninstalls the explicitly installed package with packageId (by its identity, else identity).
     * ERR_PACKAGE_REQUIRED_BY_DEPENDENTS when it is only a dependency (the engine's NOT_EXPLICIT),
     * ERR_PACKAGE_DEMOTED_TO_DEPENDENCY when it stays installed as a dependency of another package.
     */
    public String uninstallById(Cancel c, Listener l, GameVersion version, String packageId, String identity) {
        Resolved w;
        try {
            w = resolveState(version);
        } catch (Failure e) {
            return e.code;
        }
        File dir = w.dir;
        PackageEngine.Installed p = find(w.state, packageId);
        String target = p != null ? p.identity : identity;
        try {
            PackageEngine.Plan plan = locked(dir, c, e -> e.uninstall(Collections.singletonList(target), ignoreScripts(), c, l));
            for (String id : plan.unmarkExplicit) if (key(id).equals(key(target))) return ERR_PACKAGE_DEMOTED_TO_DEPENDENCY;
            return "";
        } catch (PackageException e) {
            // Gone already (removed with the package that needed it): nothing left to do.
            if (e.kind == PackageException.Kind.NOT_INSTALLED) return "";
            return fail(l, e, ERR_PACKAGE_UNINSTALL_FAILED);
        }
    }

    /** Finishes or undoes an interrupted operation of the instance; "" also when there was none. */
    public String recover(Cancel c, Listener l, GameVersion version) {
        try {
            File dir = resolve(version);
            if (!PackageEngine.hasJournal(dir)) return "";
            locked(dir, c, e -> e.recover(c, l));
            return "";
        } catch (Failure e) {
            return e.code;
        } catch (PackageException e) {
            return fail(l, e, ERR_PACKAGE_INSTALL_FAILED);
        }
    }

    /**
     * Clears the download cache once running operations have finished, and the copies of picked local packages
     * no instance was installed from.
     */
    public String cacheClean(Cancel c, Listener l) {
        try {
            acquire(ms -> cacheLock.writeLock().tryLock(ms, TimeUnit.MILLISECONDS), c);
            try {
                config().cacheClean();
                pruneLocalCopies();
                return "";
            } finally {
                cacheLock.writeLock().unlock();
            }
        } catch (PackageException e) {
            return fail(l, e, ERR_PKG_CACHE_CLEAN_FAILED);
        }
    }

    /** Deletes dataDir/local/&lt;hash&gt; copies older than an hour that no instance's state names (all must be readable). */
    private void pruneLocalCopies() {
        VersionManager vm = VersionManager.getIfInitialized();
        File[] copies = LocalSources.localDir(dataDir()).listFiles();
        if (vm == null || copies == null) return;
        List<GameVersion> versions = new ArrayList<>(vm.getInstalledVersions());
        versions.addAll(vm.getCustomVersions());
        StringBuilder refs = new StringBuilder();
        try {
            for (GameVersion v : versions) {
                File dir = workspaceDir(v);
                if (!dir.isDirectory()) continue;
                for (InstalledPackages.Pkg p : InstalledPackages.read(dir).packages()) refs.append(p.ref).append('\n');
            }
        } catch (Failure | RuntimeException e) {
            warn("not pruning local package copies: " + message(e));
            return;
        }
        long before = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        for (File copy : copies) {
            if (copy.lastModified() < before && !refs.toString().contains("/" + LocalSources.DIR_NAME + "/" + copy.getName() + "/")) {
                Config.delete(copy);
            }
        }
    }

    private boolean ignoreScripts() {
        return !settings.isPkgRunScripts();
    }

    // #endregion

    // #region Cancellation

    public static Failure cancelled() {
        return new Failure(ERR_PKG_TASK_CANCELLED, "cancelled", null);
    }

    /** Fails with ERR_PKG_TASK_CANCELLED once c (or null: never) is cancelled. */
    public static void check(Cancel c) throws Failure {
        if (c != null && c.isCancelled()) throw cancelled();
    }

    /** A token cancelled with parent (null: none) or after millis. */
    public static Cancel child(Cancel parent, long millis) {
        Cancel c = new Cancel().withTimeout(millis);
        if (parent != null) parent.onCancel(c::cancel);
        return c;
    }

    // #endregion

    // #region Logging

    /** s trimmed; "" for null. */
    public static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** The detail of a failure for the log and the task console: its message, then each cause that adds one (as lip prints it). */
    public static String message(Throwable t) {
        if (t == null) return "";
        StringBuilder b = new StringBuilder(t.getMessage() != null ? t.getMessage() : t.toString());
        for (Throwable c = t.getCause(); c != null && c != c.getCause(); c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && !m.isEmpty() && b.indexOf(m) < 0) b.append("\n  caused by: ").append(m);
        }
        return b.toString();
    }

    /** The result code of a failure. */
    public static String code(Throwable t) {
        if (t instanceof Failure) return ((Failure) t).code;
        if (t instanceof PackageException) return codeOf((PackageException) t, ERR_PKG_INSTALL_FAILED);
        return t == null ? "" : ERR_PKG_INSTALL_FAILED;
    }

    public static void info(String message) {
        try {
            Log.i(TAG, message);
        } catch (RuntimeException e) {
            System.err.println("I/" + TAG + ": " + message);
        }
    }

    public static void warn(String message) {
        warn(message, null);
    }

    public static void warn(String message, Throwable t) {
        try {
            Log.w(TAG, message, t);
        } catch (RuntimeException e) {
            System.err.println("W/" + TAG + ": " + message + (t == null ? "" : ": " + t));
        }
    }

    /** A listener that only logs (operations without a task). */
    public static Listener logListener() {
        return new Listener() {
            @Override
            public void log(Level level, String message) {
                if (level == Level.INFO) info(message);
                else warn(message);
            }

            @Override
            public void progress(String id, Stage stage, String pkg, long current, long total, boolean bytes) {
            }
        };
    }

    private static java.util.concurrent.ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // #endregion
}
