package org.levimc.launcher.core.minecraft

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import org.levimc.launcher.core.versions.GameVersion
import org.levimc.launcher.util.LauncherStorage
import org.levimc.launcher.util.NativeBridgeHelper
import org.levimc.launcher.util.NativeImageGuard
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

class GamePackageManager private constructor(
    private val context: Context,
    private val version: GameVersion?,
    private val launchTrace: LaunchTrace?,
    private val progressListener: MinecraftRuntimePreparer.ProgressListener?
) {

    private val packageContext: Context
    private val assetManager: AssetManager
    private val nativeLibDir: String
    private val applicationInfo: ApplicationInfo

    private val knownPackages = arrayOf(MinecraftLauncher.MC_PACKAGE_NAME)

    private val gameVersionName = version?.versionCode?.takeIf { it.isNotBlank() } ?: try {
        context.packageManager.getPackageInfo(MinecraftLauncher.MC_PACKAGE_NAME, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private val gameResources by lazy {
        android.content.res.Resources(assetManager, context.resources.displayMetrics, context.resources.configuration)
    }

    private val requiredLibs = requiredLibraries(gameVersionName)

    private val optionalLibs = arrayOf(
        "libHttpClient.Android.so",
    )

    private val extractableLibs = requiredLibs + optionalLibs

    private val systemLoadedLibs = arrayOf(
        "libPlayFabMultiplayer.so",
        "libmaesdk.so",
        "libgxcore.so",
    )

    data class LibraryLoadResult(
        val name: String,
        val fileName: String,
        val source: String,
        val loaded: Boolean,
        val durationMs: Long,
        val detail: String? = null
    )

    init {
        report("GamePackageManager init started")
        val packageName = detectGamePackage() ?: throw IllegalStateException("Minecraft not found")
        report("Detected Minecraft package: $packageName")
        packageContext = context.createPackageContext(
            packageName,
            Context.CONTEXT_IGNORE_SECURITY
        )
        
        if (version != null && !version.isInstalled) {
            applicationInfo = MinecraftLauncher(context).createFakeApplicationInfo(version, MinecraftLauncher.MC_PACKAGE_NAME)
            nativeLibDir = applicationInfo.nativeLibraryDir
        } else {
            applicationInfo = packageContext.applicationInfo
            nativeLibDir = resolveNativeLibDir()
        }
        
        extractLibraries()
        report("Creating AssetManager")
        assetManager = createAssetManager()
        report("AssetManager ready")
        setupSecurityProvider()
        report("GamePackageManager init finished")
    }

    private fun detectGamePackage(): String? {
        return knownPackages.firstOrNull { isPackageInstalled(it) }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun resolveNativeLibDir(): String {
        val profileId = if (version != null) {
            MinecraftLauncher.getStorageProfileId(version)
        } else {
            LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID
        }
        val cacheLibDir = MinecraftLauncher.getRuntimeLibAbiDir(context, profileId, getDeviceAbi())
        cacheLibDir.mkdirs()
        return cacheLibDir.absolutePath
    }

    private fun getDeviceAbi(): String {
        return MinecraftLauncher.getRuntimeAbi()
    }

    private fun extractLibraries() {
        report("Preparing Minecraft library cache")
        val outputDir = File(nativeLibDir)
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val apkFiles = collectApkFiles()
        val manifestString = buildExtractionManifest(apkFiles)
        val manifestFile = File(outputDir, ".extraction_manifest")
        val cachedManifest = try {
            if (manifestFile.isFile) manifestFile.readText() else null
        } catch (_: Exception) {
            null
        }
        val markerMatches = cachedManifest == manifestString + buildCacheState(outputDir)
        val cacheRequiredLibs = getCacheRequiredLibs()
        val legacyMatches = cachedManifest == manifestString.replaceFirst("extractor=$EXTRACTOR_VERSION\n", "extractor=2\n") &&
            cacheRequiredLibs.all { NativeImageGuard.isPrepared(File(outputDir, it)) }
        var allPresent = markerMatches || legacyMatches
        if (allPresent) {
            for (lib in cacheRequiredLibs) {
                val file = File(outputDir, lib)
                if (!file.exists() || file.length() == 0L) {
                    allPresent = false
                    break
                }
            }
        }
        
        if (allPresent) {
            report("Minecraft library cache hit: ${outputDir.absolutePath}")
            if (!markerMatches) {
                try {
                    val staged = File(outputDir, ".extraction_manifest.tmp")
                    staged.writeText(manifestString + buildCacheState(outputDir))
                    Files.move(staged.toPath(), manifestFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (error: IOException) {
                    Log.w(TAG, "Failed to update library cache metadata: ${error.message}")
                }
            }
            for (lib in extractableLibs) {
                try {
                    val file = File(outputDir, lib)
                    if (file.exists()) {
                        ensureReadOnly(file)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed ensureReadOnly: ${e.message}")
                }
            }
            return
        }
        report("Minecraft library cache miss: extracting libraries")

        val remaining = extractableLibs.toMutableSet()
        if (version == null || version.isInstalled) {
            val appInfo = packageContext.applicationInfo
            if (File(appInfo.nativeLibraryDir).isDirectory) {
                copyFromNativeDir(appInfo.nativeLibraryDir, outputDir, remaining)
            }
        }
        for (apk in apkFiles) {
            if (remaining.isEmpty()) break
            extractFromApk(apk.absolutePath, outputDir, getDeviceAbi(), remaining)
        }
        val missing = cacheRequiredLibs.filter { it in remaining || File(outputDir, it).length() == 0L }
        if (missing.isNotEmpty()) {
            throw IOException("Minecraft ${gameVersionName.orEmpty()} is missing ${getDeviceAbi()} libraries: ${missing.joinToString()}")
        }
        for (lib in remaining) {
            File(outputDir, lib).delete()
        }

        if (cacheRequiredLibs.all { File(outputDir, it).let { f -> f.exists() && f.length() > 0 } }) {
            try {
                val temporaryManifest = File(outputDir, ".extraction_manifest.tmp")
                temporaryManifest.writeText(manifestString + buildCacheState(outputDir))
                Files.move(temporaryManifest.toPath(), manifestFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                File(outputDir, ".extraction_marker").delete()
                report("Minecraft library cache manifest written")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write extraction manifest: ${e.message}")
                report("Failed to write extraction manifest: ${e.message}")
            }
        }
    }

    private fun collectApkFiles(): List<File> {
        val paths = mutableListOf<String>()
        if (version != null && !version.isInstalled) {
            applicationInfo.sourceDir?.let { paths.add(it) }
            applicationInfo.splitSourceDirs?.let { paths.addAll(it) }
        } else {
            val appInfo = packageContext.applicationInfo
            appInfo.sourceDir?.let { paths.add(it) }
            appInfo.splitPublicSourceDirs?.let { paths.addAll(it) }
        }

        return paths
            .map { File(it) }
            .filter {
                if (it.exists()) {
                    true
                } else {
                    Log.w(TAG, "APK file not found: ${it.absolutePath}")
                    report("APK file not found: ${it.absolutePath}")
                    false
                }
            }
            .sortedBy { it.absolutePath }
    }

    private fun buildCacheState(directory: File): String = cacheState(directory, extractableLibs)

    private fun buildExtractionManifest(apkFiles: List<File>): String {
        return extractionManifest(apkFiles, getDeviceAbi(), gameVersionName, version != null && !version.isInstalled)
    }

    private fun copyFromNativeDir(sourceDir: String, destDir: File, remaining: MutableSet<String>) {
        val source = File(sourceDir)
        if (!source.exists()) {
            Log.w(TAG, "Source native library directory does not exist: $sourceDir")
            return
        }

        remaining.toList().forEach { lib ->
            val srcFile = File(source, lib)
            val dstFile = File(destDir, lib)
            if (srcFile.exists() && srcFile.length() > 0) {
                srcFile.inputStream().use { input ->
                    copyStreamToReadOnlyFile(input, dstFile, srcFile.length())
                }
                remaining.remove(lib)
                report("Copied $lib from native lib directory")
            }
        }
    }

    private fun extractFromApk(apkPath: String, outputDir: File, abi: String, remaining: MutableSet<String>) {
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            Log.w(TAG, "APK file does not exist: $apkPath")
            return
        }
        try {
            ZipFile(apkPath).use { zip ->
                val abiPath = "lib/$abi"
                remaining.toList().forEach { lib ->
                    val entry = zip.getEntry("$abiPath/$lib")
                    if (entry == null) {
                        return@forEach
                    }
                    val output = File(outputDir, lib)
                    zip.getInputStream(entry).use { input ->
                        copyStreamToReadOnlyFile(input, output, entry.size)
                    }
                    remaining.remove(lib)
                    report("Extracted $lib from ${apkFile.name} for $abi")
                }
            }
        } catch (e: Exception) {
            throw IOException("Failed to prepare libraries from ${apkFile.name} for $abi: ${e.message}", e)
        }
    }

    private fun copyStreamToReadOnlyFile(input: InputStream, output: File, expectedSize: Long) {
        ensureParentDirectory(output)
        val tempFile = File(output.absolutePath + ".tmp")
        if (tempFile.exists()) tempFile.delete()
        try {
            val copied = FileOutputStream(tempFile).use { out -> input.copyTo(out, 256 * 1024) }
            if (expectedSize >= 0 && copied != expectedSize) {
                throw IOException("Incomplete native library: ${output.name} ($copied/$expectedSize bytes)")
            }
            NativeImageGuard.publishStagedImage(tempFile, output)
        } catch (error: Exception) {
            throw IOException("Failed to prepare ${output.name}: ${error.message}", error)
        } finally {
            tempFile.delete()
        }
    }

    private fun ensureParentDirectory(file: File) {
        val parent = file.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create parent directory: ${parent.absolutePath}")
        }
    }

    private fun ensureReadOnly(file: File) {
        if (!file.isFile) {
            throw IOException("Expected regular file: ${file.absolutePath}")
        }
        if (!file.canRead() && !file.setReadable(true, true)) {
            throw IOException("Failed to mark file readable: ${file.absolutePath}")
        }
        if (file.canWrite() && !file.setReadOnly()) {
            throw IOException("Failed to keep file read-only: ${file.absolutePath}")
        }
    }

    private fun getCacheRequiredLibs(): Array<String> {
        return if (shouldLoadHttpClient()) {
            requiredLibs + optionalLibs
        } else {
            requiredLibs
        }
    }

    fun shouldLoadMallocRedirect(): Boolean {
        return MinecraftVersion.isAtLeast(gameVersionName, "1.26.70.00")
    }

    private fun shouldLoadHttpClient(): Boolean {
        return requiresHttpClient(gameVersionName)
    }

    private fun report(message: String) {
        if (progressListener != null) {
            progressListener.onLog(message)
        } else {
            launchTrace?.mark(message)
        }
    }

    private fun createAssetManager(): AssetManager {
        val assets = AssetManager::class.java.newInstance()
        val addAssetPathMethod = AssetManager::class.java.getMethod("addAssetPath", String::class.java)

        val paths = mutableListOf<String>()
        
        if (version != null && !version.isInstalled) {
            val baseApk = File(applicationInfo.sourceDir)
            if (baseApk.exists()) {
                paths.add(applicationInfo.sourceDir)
            } else {
                Log.w(TAG, "Base APK for assets not found: ${applicationInfo.sourceDir}")
            }
            applicationInfo.splitSourceDirs?.forEach {
                if (File(it).exists()) {
                    paths.add(it)
                } else {
                    Log.w(TAG, "Split APK for assets not found: $it")
                }
            }
        } else {
            paths.add(packageContext.packageResourcePath)
            applicationInfo.splitPublicSourceDirs?.forEach { path ->
                if (File(path).isFile) paths.add(path)
            }
        }
        
        paths.add(context.packageResourcePath)

        paths.distinct().forEach { path ->
            try {
                addAssetPathMethod.invoke(assets, path)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add asset path $path: ${e.message}")
            }
        }
        return assets
    }

    private fun setupSecurityProvider() {
        try {
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
        } catch (e: Exception) {
            Log.w(TAG, "Conscrypt init failed: ${e.message}")
        }
    }

    fun resolveLibraryPath(name: String): String? {
        val libFile = File(nativeLibDir, toLibraryFileName(name))
        return if (libFile.exists() && libFile.length() > 0) {
            libFile.absolutePath
        } else {
            null
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun loadLibrary(name: String): Boolean {
        return loadLibraryDetailed(name).loaded
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun loadLibraryDetailed(name: String): LibraryLoadResult {
        val fileName = toLibraryFileName(name)
        val normalizedName = normalizeLibraryName(name)
        val startedAt = SystemClock.elapsedRealtime()

        if (systemLoadedLibs.contains(fileName)) {
            val source = if (normalizedName == "gxcore") {
                "launcher bundled bootstrap"
            } else {
                "launcher bundled library"
            }
            try {
                if (normalizedName == "gxcore") {
                    if (!NativeBridgeHelper.bootstrapGxCore()) {
                        val detail = "gxcore bootstrap failed"
                        Log.e(TAG, "Failed to load $fileName from $source: $detail")
                        return LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
                    }
                } else {
                    System.loadLibrary(normalizedName)
                }
                return LibraryLoadResult(
                    normalizedName,
                    fileName,
                    source,
                    true,
                    elapsedSince(startedAt)
                )
            } catch (e: UnsatisfiedLinkError) {
                val detail = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Failed to load $fileName from $source: $detail")
                return LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Failed to load $fileName from $source: $detail")
                return LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
            }
        }

        val resolvedPath = resolveLibraryPath(name)
        val libFile = resolvedPath?.let(::File) ?: File(nativeLibDir, fileName)
        val source = "Minecraft extracted library cache"
        return if (libFile.exists() && libFile.length() > 0) {
            try {
                ensureReadOnly(libFile)
                System.load(libFile.absolutePath)
                LibraryLoadResult(normalizedName, fileName, source, true, elapsedSince(startedAt), libFile.absolutePath)
            } catch (e: UnsatisfiedLinkError) {
                val detail = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Failed to load $fileName from ${libFile.absolutePath}: $detail")
                LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Failed to load $fileName from ${libFile.absolutePath}: $detail")
                LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
            }
        } else {
            val detail = "$fileName not found in $nativeLibDir"
            Log.w(TAG, detail)
            LibraryLoadResult(normalizedName, fileName, source, false, elapsedSince(startedAt), detail)
        }
    }

    fun loadAllLibraries(
        excludeLibs: Set<String> = emptySet(),
        trace: LaunchTrace? = null,
        listener: MinecraftRuntimePreparer.ProgressListener? = null,
        progressStart: Int = 46,
        progressEnd: Int = 74,
        excludeReasons: Map<String, String> = emptyMap()
    ): List<LibraryLoadResult> {
        val allLibs = requiredLibs + systemLoadedLibs
        val loadableLibs = allLibs.filterNot { lib ->
            val libName = normalizeLibraryName(lib)
            excludeLibs.contains(libName) || excludeLibs.contains(lib)
        }
        val total = loadableLibs.size.coerceAtLeast(1)
        var loadIndex = 0
        val results = mutableListOf<LibraryLoadResult>()

        allLibs.forEach { lib ->
            val libName = normalizeLibraryName(lib)
            if (excludeLibs.contains(libName) || excludeLibs.contains(lib)) {
                val reason = excludeReasons[libName]
                    ?: excludeReasons[lib]
                    ?: "not required in this launch path"
                listener?.onLog("Skipped native library: $lib")
                trace?.mark("System.load skipped", "$lib - $reason")
                return@forEach
            }

            loadIndex += 1
            val progress = progressStart + ((progressEnd - progressStart) * (loadIndex - 1) / total)
            listener?.onProgress(progress, "Loading Minecraft", "$loadIndex/$total")
            listener?.onLog("Loading native library: $lib")
            trace?.mark("System.load started", lib)

            val result = loadLibraryDetailed(libName)
            results.add(result)
            val detail = formatLoadResult(result)
            trace?.mark(
                if (result.loaded) "System.load finished" else "System.load failed",
                detail
            )
            if (!result.loaded) {
                Log.e(TAG, "Failed to load bundle library $libName: ${result.detail ?: "unknown error"}")
                listener?.onLog("Failed to load native library: ${result.fileName}")
            } else {
                listener?.onLog("Loaded native library: ${result.fileName}")
            }
        }

        return results
    }

    private fun toLibraryFileName(name: String): String {
        return if (name.startsWith("lib") && name.endsWith(".so")) name else "lib${normalizeLibraryName(name)}.so"
    }

    private fun normalizeLibraryName(name: String): String {
        return name.removePrefix("lib").removeSuffix(".so")
    }

    private fun elapsedSince(startedAt: Long): Long {
        return SystemClock.elapsedRealtime() - startedAt
    }

    private fun formatLoadResult(result: LibraryLoadResult): String {
        val status = if (result.loaded) "Loaded" else "Failed to load"
        return buildString {
            append(status)
            append(' ')
            append(result.fileName)
            append(" in ")
            append(result.durationMs)
            append("ms from ")
            append(result.source)
            result.detail?.let {
                append(" - ")
                append(it)
            }
        }
    }

    fun getAssets(): AssetManager = assetManager

    fun getGameStringResource(name: String): String? {
        return try {
            val packageName = applicationInfo.packageName ?: MinecraftLauncher.MC_PACKAGE_NAME
            val identifier = gameResources.getIdentifier(name, "string", packageName)
            if (identifier == 0) null else gameResources.getString(identifier).takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            null
        }
    }

    fun getPackageContext(): Context = packageContext

    fun getApplicationInfo(): ApplicationInfo = applicationInfo

    fun getVersionName(): String? {
        return gameVersionName
    }

    companion object {
        private const val TAG = "GamePackageManager"
        private const val EXTRACTOR_VERSION = 3

        private fun requiredLibraries(versionName: String?): Array<String> = buildList {
            add("libc++_shared.so")
            add("libfmod.so")
            add("libMediaDecoders_Android.so")
            if (MinecraftVersion.isAtLeast(versionName, "1.26.70.00")) add("libMallocRedirect.so")
            add("libminecraftpe.so")
        }.toTypedArray()

        private fun requiresHttpClient(versionName: String?): Boolean {
            val minimum = if (versionName?.contains("beta") == true) "1.21.130.20" else "1.21.130"
            return MinecraftVersion.isAtLeast(versionName, minimum)
        }

        private fun extractionManifest(apks: List<File>, abi: String, versionName: String?, isolated: Boolean): String = buildString {
            append("extractor=").append(EXTRACTOR_VERSION).append('\n')
            append("abi=").append(abi).append('\n')
            append("token=").append(NativeImageGuard.TOKEN).append('\n')
            append("mode=").append(if (isolated) "isolated" else "installed").append('\n')
            append("http=").append(requiresHttpClient(versionName)).append('\n')
            append("malloc=").append(MinecraftVersion.isAtLeast(versionName, "1.26.70.00")).append('\n')
            for (file in apks.sortedBy { it.absolutePath }) {
                append("apk=").append(file.absolutePath).append('|').append(file.length())
                    .append('|').append(file.lastModified()).append('\n')
            }
        }

        private fun cacheState(directory: File, names: Array<String>): String = buildString {
            for (name in names) {
                val file = File(directory, name)
                append("file=").append(name).append('|').append(file.length())
                    .append('|').append(file.lastModified()).append('\n')
            }
        }

        @JvmStatic
        fun recordImportedLibraries(context: Context, profileId: String, versionName: String, apks: List<File>) {
            val abi = MinecraftLauncher.getRuntimeAbi()
            val directory = MinecraftLauncher.getRuntimeLibAbiDir(context, profileId, abi)
            val names = requiredLibraries(versionName) + arrayOf("libHttpClient.Android.so")
            val required = if (requiresHttpClient(versionName)) names else requiredLibraries(versionName)
            if (required.any { !NativeImageGuard.isPrepared(File(directory, it)) }) return
            val marker = File(directory, ".extraction_manifest")
            val staged = File(directory, ".extraction_manifest.tmp")
            try {
                staged.writeText(extractionManifest(apks, abi, versionName, true) + cacheState(directory, names))
                Files.move(staged.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (error: IOException) {
                Log.w(TAG, "Failed to record imported library cache: ${error.message}")
            } finally {
                staged.delete()
            }
        }

        @Volatile
        private var instance: GamePackageManager? = null
        private var lastVersionKey: String? = null

        @JvmStatic
        fun getInstance(context: Context, version: GameVersion? = null): GamePackageManager {
            return getInstance(context, version, null, null)
        }

        @JvmStatic
        fun getInstance(
            context: Context,
            version: GameVersion? = null,
            launchTrace: LaunchTrace? = null,
            progressListener: MinecraftRuntimePreparer.ProgressListener? = null
        ): GamePackageManager {
            return synchronized(this) {
                val newVersionKey = buildVersionKey(version)
                if (instance == null || newVersionKey != lastVersionKey) {
                    instance = GamePackageManager(context.applicationContext, version, launchTrace, progressListener)
                    lastVersionKey = newVersionKey
                }
                instance!!
            }
        }

        private fun buildVersionKey(version: GameVersion?): String {
            if (version == null) return "installed-default"
            return listOf(
                version.isInstalled.toString(),
                version.packageName.orEmpty(),
                version.versionCode.orEmpty(),
                version.directoryName.orEmpty(),
                version.versionDir?.absolutePath.orEmpty()
            ).joinToString("|")
        }

        fun isInitialized() = instance != null
    }
}
