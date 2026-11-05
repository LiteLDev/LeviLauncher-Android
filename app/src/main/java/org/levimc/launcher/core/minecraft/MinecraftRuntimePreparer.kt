package org.levimc.launcher.core.minecraft

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import org.levimc.launcher.R
import org.levimc.launcher.core.auth.MsftAuthManager
import org.levimc.launcher.core.mods.Mod
import org.levimc.launcher.core.mods.ModManager
import org.levimc.launcher.core.mods.ModNativeLoader
import org.levimc.launcher.core.versions.GameVersion
import org.levimc.launcher.preloader.PreloaderInput
import org.levimc.launcher.preloader.PreloaderSignatureRulesManager
import org.levimc.launcher.settings.FeatureSettings
import org.levimc.launcher.util.LauncherStorage
import org.levimc.launcher.util.LauncherBackgroundController
import org.levimc.launcher.util.NativeBridgeHelper
import java.io.File

object MinecraftRuntimePreparer {
    data class PreparedRuntime(
        val version: GameVersion?,
        val gameManager: GamePackageManager,
        val skippedIncompatibleMods: List<String> = emptyList()
    )

    private data class NativeModLoadResult(
        val skippedIncompatibleMods: List<String>,
        val loadedModIds: Set<String>
    )

    interface ProgressListener {
        fun onProgress(progress: Int, status: String, detail: String? = null)
        fun onLog(message: String)
    }

    @JvmStatic
    @JvmName("nativeSetupRuntime")
    private external fun nativeSetupRuntime(modsPath: String)

    private val noopListener = object : ProgressListener {
        override fun onProgress(progress: Int, status: String, detail: String?) = Unit
        override fun onLog(message: String) = Unit
    }

    fun prepare(
        context: Context,
        launchIntent: Intent,
        listener: ProgressListener = noopListener
    ): PreparedRuntime {
        val trace = LaunchTrace.ensure(launchIntent)
        trace.milestone("Runtime preparation started")
        listener.onProgress(4, "Checking selected version")
        val version = resolveGameVersion(launchIntent)
            ?: throw IllegalArgumentException("No Minecraft version specified")
        listener.onLog("Using ${version.directoryName} (${version.versionCode})")
        trace.mark("Minecraft version resolved", "${version.directoryName} ${version.versionCode}")

        if (FeatureSettings.getInstance().isLauncherManagedMcLoginEnabled) {
            listener.onProgress(8, context.getString(R.string.ms_login_auth_xbox_device))
            listener.onLog("Preparing Microsoft account")
            try {
                MsftAuthManager.prepareActiveAccount(context.applicationContext)
                trace.mark("Microsoft credentials prepared")
            } catch (error: Exception) {
                throw IllegalStateException(
                    context.getString(R.string.ms_login_failed_detail, MsftAuthManager.describeError(error)),
                    error
                )
            }
        }

        listener.onProgress(12, "Preparing game files")
        val gameManager = GamePackageManager.getInstance(context.applicationContext, version, trace, null)
        trace.milestone("Game files ready")
        LauncherBackgroundController.awaitShutdown()

        listener.onProgress(26, "Preparing launch")
        prepareMinecraftIntent(context, launchIntent, gameManager, version)
        trace.mark("Launch intent prepared")

        listener.onProgress(34, "Checking mods")
        val modManager = ModManager.getInstance()
        modManager.setCurrentVersion(version)
        trace.mark("ModManager state prepared")

        if (gameManager.shouldLoadMallocRedirect()) {
            listener.onProgress(38, "Loading native libraries", "libminecraftpe.so")
            listener.onLog("Linking Minecraft and its native dependencies")
            val libraryPath = gameManager.resolveLibraryPath("minecraftpe")
                ?: throw RuntimeException("libminecraftpe.so not found in the Minecraft library cache")
            trace.mark("Minecraft dependency graph loading started", libraryPath)
            try {
                NativeBridgeHelper.loadMinecraftRuntime(libraryPath)
            } catch (error: UnsatisfiedLinkError) {
                trace.error("Minecraft dependency graph load failed", error.message ?: "unknown error")
                throw RuntimeException("Failed to load the Minecraft runtime: ${error.message}", error)
            }
            trace.mark("Minecraft dependency graph loaded", "MallocRedirect enabled")
        }

        listener.onProgress(40, "Preparing game loader")
        listener.onLog("Loading game loader")
        trace.mark("Game loader load started")
        if (ModManager.ensurePreloaderLoaded()) {
            trace.mark("Game loader load finished")
        } else {
            trace.mark("Game loader load skipped", "preloader unavailable")
        }
        val signatureRulesFile = PreloaderSignatureRulesManager.getRulesFile(context.applicationContext)
        PreloaderInput.configureSignatureRules(signatureRulesFile, version.versionCode)
        trace.mark("Preloader signature rules configured", signatureRulesFile?.absolutePath ?: "<none>")

        listener.onLog("Loading native libraries")
        loadMinecraftLibraries(gameManager, version, listener, trace)
        PreloaderInput.prepareRuntime()
        trace.milestone("Native runtime ready")

        listener.onProgress(78, "Loading enabled mods")
        listener.onLog("Loading native mods")

        try {
            org.levimc.launcher.core.mods.inbuilt.nativemod.InbuiltModsNative.loadLibrary()
            org.levimc.launcher.core.mods.inbuilt.nativemod.GyroMod.nativePreResolve()
        } catch (_: Throwable) {}

        try {
            org.levimc.launcher.core.mods.inbuilt.nativemod.HotbarSlotMod.initialize()
            org.levimc.launcher.core.mods.inbuilt.overlay.HotbarSlotOverlay.preloadArtwork(context)
        } catch (error: Throwable) {
            trace.warning("Hotbar Slot preparation unavailable", error.message ?: error.javaClass.simpleName)
        }

        //nativeSetupRuntime(modManager.currentVersion?.modsDir?.absolutePath.toString())
        val nativeModResult = loadNativeMods(context, launchIntent, modManager, listener, trace)

        try {
            val profileId = MinecraftLauncher.getStorageProfileId(version)
            val internalGameData = LauncherStorage.getStorageGameDataDir(context, profileId, version.versionIsolation, false)
            val externalGameData = LauncherStorage.getStorageGameDataDir(context, profileId, version.versionIsolation, true)
            val bundledPackCount = BundledResourcePackInstaller.sync(
                modManager.mods,
                nativeModResult.loadedModIds,
                internalGameData,
                externalGameData
            )
            if (bundledPackCount > 0) {
                listener.onLog("Prepared $bundledPackCount bundled native-mod pack(s)")
            }
            trace.mark("Bundled native-mod packs synchronized", bundledPackCount.toString())
        } catch (error: Exception) {
            trace.error("Bundled native-mod pack synchronization failed", error.message ?: error.javaClass.simpleName)
            throw RuntimeException("Failed to prepare bundled native-mod packs", error)
        }

        listener.onProgress(100, "Runtime ready", "Entering Minecraft")
        trace.milestone("Runtime preparation finished")
        return PreparedRuntime(version, gameManager, nativeModResult.skippedIncompatibleMods)
    }

    @JvmStatic
    fun resolveGameVersion(intent: Intent): GameVersion? {
        val parcelableVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(MinecraftLauncher.EXTRA_GAME_VERSION, GameVersion::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<GameVersion>(MinecraftLauncher.EXTRA_GAME_VERSION)
        }
        if (parcelableVersion != null) {
            return parcelableVersion
        }

        val versionDir = intent.getStringExtra("MC_PATH")
        val versionCode = intent.getStringExtra("MINECRAFT_VERSION") ?: ""
        val versionDirName = intent.getStringExtra("MINECRAFT_VERSION_DIR") ?: ""
        val isInstalled = intent.getBooleanExtra("IS_INSTALLED", false)

        return if (!versionDir.isNullOrEmpty()) {
            GameVersion(
                versionDirName,
                versionCode,
                versionCode,
                File(versionDir),
                isInstalled,
                MinecraftLauncher.MC_PACKAGE_NAME,
                ""
            )
        } else if (versionCode.isNotEmpty()) {
            GameVersion(
                versionDirName,
                versionCode,
                versionCode,
                null,
                isInstalled,
                MinecraftLauncher.MC_PACKAGE_NAME,
                ""
            )
        } else {
            null
        }
    }

    private fun prepareMinecraftIntent(
        context: Context,
        launchIntent: Intent,
        gameManager: GamePackageManager,
        version: GameVersion
    ) {
        val profileId = MinecraftLauncher.getStorageProfileId(version)
        val versionIsolation = version.versionIsolation
        val filesDir = LauncherStorage.getStorageFilesRoot(context, profileId, versionIsolation, false)
        val externalFilesDir = LauncherStorage.getStorageFilesRoot(context, profileId, versionIsolation, true)
        val dataDir = LauncherStorage.getStorageDataRoot(context, profileId, versionIsolation)
        val cacheDir = LauncherStorage.getStorageCacheRoot(context, profileId, versionIsolation)

        version.versionDir?.let { launchIntent.putExtra("MC_PATH", it.absolutePath) }
        launchIntent.putExtra("IS_INSTALLED", version.isInstalled)
        launchIntent.putExtra("VERSION_ISOLATION", versionIsolation)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_PROFILE_ID, profileId)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_FILES_DIR, filesDir.absolutePath)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_EXTERNAL_FILES_DIR, externalFilesDir.absolutePath)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_DATA_DIR, dataDir.absolutePath)
        launchIntent.putExtra(MinecraftLauncher.EXTRA_STORAGE_CACHE_DIR, cacheDir.absolutePath)

        val mcInfo: ApplicationInfo = if (version.isInstalled) {
            gameManager.getPackageContext().applicationInfo
        } else {
            MinecraftLauncher(context).createFakeApplicationInfo(version, MinecraftLauncher.MC_PACKAGE_NAME)
        }
        launchIntent.putExtra("MC_SRC", mcInfo.sourceDir)
        val splitSourceDirs = mcInfo.splitSourceDirs
        if (splitSourceDirs != null) {
            launchIntent.putExtra("MC_SPLIT_SRC", arrayListOf(*splitSourceDirs))
        }
        launchIntent.putExtra("MINECRAFT_VERSION", version.versionCode)
        launchIntent.putExtra("MINECRAFT_VERSION_DIR", version.directoryName)
        launchIntent.putExtra("LAUNCH_VERTICALLY", version.launchVertically)
        launchIntent.putExtra("VERSION_ISOLATION", version.versionIsolation)
    }

    private fun loadMinecraftLibraries(
        gameManager: GamePackageManager,
        version: GameVersion,
        listener: ProgressListener,
        trace: LaunchTrace
    ) {
        listener.onProgress(46, "Loading native libraries")
        trace.mark("Minecraft library loading started")

        val initializeBundledSdk = gameManager.shouldLoadMallocRedirect()
        if (shouldLoadHttpClient(version)) {
            if (initializeBundledSdk) {
                trace.mark("Native dependency reused", "libc++_shared.so linked with Minecraft")
            } else {
                loadLibrary(gameManager, "c++_shared", 48, true, listener, trace)
            }
            loadLibrary(gameManager, "HttpClient.Android", 52, true, listener, trace)
        }

        if (initializeBundledSdk) {
            loadLibrary(gameManager, "PlayFabMultiplayer", 53, true, listener, trace)
            loadLibrary(gameManager, "maesdk", 54, true, listener, trace)
        }

        if (shouldLoadMaesdk(version)) {
            val excludeLibs = HashSet<String>()
            val excludeReasons = HashMap<String, String>()
            if (shouldLoadHttpClient(version)) {
                excludeLibs.add("c++_shared")
                excludeReasons["c++_shared"] = if (initializeBundledSdk) {
                    "linked with Minecraft dependency graph"
                } else {
                    "already loaded before the bundle"
                }
            }
            if (shouldLoadHttpClient(version)) {
                excludeLibs.add("HttpClient.Android")
                excludeReasons["HttpClient.Android"] = "already loaded before the bundle"
            }
            if (initializeBundledSdk) {
                excludeLibs.add("PlayFabMultiplayer")
                excludeLibs.add("maesdk")
                excludeReasons["PlayFabMultiplayer"] = "JNI initialized before Minecraft"
                excludeReasons["maesdk"] = "JNI initialized before Minecraft"
            }
            if (!shouldLoadPlayFab(version)) {
                excludeLibs.add("PlayFabMultiplayer")
                excludeReasons["PlayFabMultiplayer"] = "not required by this Minecraft version"
            }
            listener.onProgress(56, "Loading native libraries")
            trace.mark("Minecraft native library bundle loading started", "1.21.110+ layout")
            val failedLibraries = gameManager
                .loadAllLibraries(excludeLibs, trace, listener, 56, 74, excludeReasons)
                .filterNot { it.loaded }
            if (failedLibraries.isNotEmpty()) {
                val details = failedLibraries.joinToString(separator = "\n") { result ->
                    "${result.fileName}: ${result.detail ?: "unknown error"}"
                }
                trace.error("Native library bundle load failed", details)
                throw RuntimeException("Failed to load native libraries:\n$details")
            }
            trace.mark("Minecraft native library bundle loading finished")
        } else {
            if (!shouldLoadHttpClient(version)) {
                loadLibrary(gameManager, "c++_shared", 50, true, listener, trace)
            }
            loadLibrary(gameManager, "fmod", 56, true, listener, trace)
            loadLibrary(gameManager, "MediaDecoders_Android", 62, true, listener, trace)
            if (gameManager.shouldLoadMallocRedirect()) {
                loadLibrary(gameManager, "MallocRedirect", 66, true, listener, trace)
            }
            loadLibrary(gameManager, "minecraftpe", 70, true, listener, trace)
            loadLibrary(gameManager, "gxcore", 74, true, listener, trace)
        }
        trace.mark("Minecraft library loading finished")
    }

    private fun loadLibrary(
        gameManager: GamePackageManager,
        name: String,
        progress: Int,
        required: Boolean,
        listener: ProgressListener,
        trace: LaunchTrace
    ) {
        val fileName = toLibraryFileName(name)
        listener.onProgress(progress, "Loading native libraries", fileName)
        listener.onLog("Loading native library: $fileName")
        trace.mark("Native library load started", fileName)
        val result = gameManager.loadLibraryDetailed(name)
        if (!result.loaded && required) {
            listener.onLog("Failed to load native library: ${result.fileName}")
            trace.error(
                "Required library load failed",
                "${result.fileName} in ${result.durationMs}ms from ${result.source}" +
                    (result.detail?.let { " - $it" } ?: "")
            )
            throw RuntimeException("Failed to load ${result.fileName}: ${result.detail ?: "unknown error"}")
        }
        if (result.loaded) {
            listener.onLog("Loaded native library: ${result.fileName}")
            trace.mark(
                "Native library load finished",
                "${result.fileName} in ${result.durationMs}ms from ${result.source}" +
                    (result.detail?.let { " - $it" } ?: "")
            )
        } else {
            listener.onLog("Skipped native library: ${result.fileName}")
            trace.mark(
                "Native library load skipped",
                "${result.fileName} in ${result.durationMs}ms from ${result.source}" +
                    (result.detail?.let { " - $it" } ?: "")
            )
        }
    }

    private fun loadNativeMods(
        context: Context,
        launchIntent: Intent,
        modManager: ModManager,
        listener: ProgressListener,
        trace: LaunchTrace
    ): NativeModLoadResult {
        val cacheDir = resolveNativeModCacheDir(context, launchIntent)
        trace.mark(
            "Native mod loading started",
            "mods=${modManager.currentVersion?.modsDir?.absolutePath ?: "<unknown>"}"
        )
        val modLoadLabels = java.util.IdentityHashMap<Mod, String>()
        val skippedIncompatibleMods = mutableListOf<String>()
        val loadedModIds = linkedSetOf<String>()
        ModNativeLoader.loadEnabledSoMods(
            modManager,
            cacheDir,
            object : ModNativeLoader.LoadListener {
                override fun onScanStarted(totalEnabled: Int) {
                    if (totalEnabled > 0) {
                        listener.onLog("Loading $totalEnabled enabled mod(s)")
                    } else {
                        listener.onLog("No enabled native mods")
                    }
                }

                override fun onModLoadStarted(mod: Mod, index: Int, total: Int) {
                    val progress = 80 + ((index - 1) * 15 / total.coerceAtLeast(1))
                    val label = "$index/$total"
                    modLoadLabels[mod] = label
                    listener.onProgress(progress, "Loading native mods", "$label ${mod.displayName}")
                    trace.mark("Native mod load started", "$label ${mod.displayName}")
                }

                override fun onModLoadFinished(mod: Mod) {
                    val label = modLoadLabels.remove(mod)?.let { "$it " }.orEmpty()
                    loadedModIds.add(mod.id)
                    listener.onLog("Loaded mod: $label${mod.displayName}")
                    trace.mark("Native mod load finished", mod.displayName)
                }

                override fun onModLoadSkipped(mod: Mod, minecraftVersion: String) {
                    val label = modLoadLabels.remove(mod)?.let { "$it " }.orEmpty()
                    skippedIncompatibleMods.add(mod.displayName)
                    listener.onLog("Skipped incompatible mod ${label}${mod.displayName} for Minecraft $minecraftVersion")
                    trace.warning("Native mod skipped as incompatible", "${mod.displayName}: $minecraftVersion")
                }

                override fun onModLoadFailed(mod: Mod, error: Throwable) {
                    trace.warning("Native mod load failed", "${mod.displayName}: ${error.message ?: error.javaClass.simpleName}")
                    listener.onLog("Failed to load mod ${mod.displayName}: ${error.message ?: error.javaClass.simpleName}")
                }

                override fun onMessage(message: String) {
                    listener.onLog(message)
                    trace.warning("Native mod loader message", message)
                }
            }
        )
        listener.onProgress(96, "Native mods ready")
        listener.onLog("Native mods ready")
        trace.mark("Native mod loading finished")
        return NativeModLoadResult(skippedIncompatibleMods, loadedModIds)
    }

    private fun resolveNativeModCacheDir(context: Context, launchIntent: Intent): File {
        val versionDirName = launchIntent.getStringExtra("MINECRAFT_VERSION_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: "default"
        return File(context.cacheDir, "native_mods/$versionDirName").also { it.mkdirs() }
    }

    private fun shouldLoadMaesdk(version: GameVersion): Boolean {
        val versionCode = version.versionCode
        val targetVersion = if (versionCode.contains("beta")) "1.21.110.22" else "1.21.110"
        return isVersionAtLeast(versionCode, targetVersion)
    }

    private fun shouldLoadHttpClient(version: GameVersion): Boolean {
        val versionCode = version.versionCode
        val targetVersion = if (versionCode.contains("beta")) "1.21.130.20" else "1.21.130"
        return isVersionAtLeast(versionCode, targetVersion)
    }

    private fun shouldLoadPlayFab(version: GameVersion): Boolean {
        val versionCode = version.versionCode
        val targetVersion = if (versionCode.contains("beta")) "1.21.130.20" else "1.21.130"
        return isVersionAtLeast(versionCode, targetVersion)
    }

    private fun toLibraryFileName(name: String): String {
        return if (name.startsWith("lib") && name.endsWith(".so")) name else "lib${name.removePrefix("lib").removeSuffix(".so")}.so"
    }

    private fun isVersionAtLeast(currentVersion: String, targetVersion: String): Boolean {
        return MinecraftVersion.isAtLeast(currentVersion, targetVersion)
    }
}
