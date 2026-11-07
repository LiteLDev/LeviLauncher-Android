package org.levimc.launcher.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.provider.DocumentsContract;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.levimc.launcher.core.minecraft.MinecraftLauncher;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.core.versions.VersionProfileMetadataStore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class InstanceBackupManager {
    public static final String BACKUP_EXTENSION = ".levibackup";
    public static final String ZIP_EXTENSION = ".zip";

    private static final String TAG = "InstanceBackupManager";
    private static final String FORMAT_ID = "levilauncher_instance_backup";
    private static final int SCHEMA_VERSION = 1;
    private static final int BUFFER_SIZE = 131072;
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String PROFILE_PREFIX = "profile/";
    private static final String SHARED_PREFIX = "shared/";
    private static final String RUNTIME_LIBS_PREFIX = "runtime_libs/";
    private static final String PACKAGE_PREFIX = "package/";
    private static final String DOWNLOAD_RELATIVE_PATH =
            Environment.DIRECTORY_DOWNLOADS + "/LeviLauncher/Backups";
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface BackupCallback {
        void onStarted();
        void onProgress(int progress);
        void onSuccess(String displayPath);
        void onError(String message);
    }

    public interface RestoreCallback {
        void onStarted();
        void onProgress(int progress);
        void onSuccess(String restoredName);
        void onError(String message);
    }

    public static class BackupManifest {
        public String format = FORMAT_ID;
        public int schemaVersion = SCHEMA_VERSION;
        public String instanceName;
        public String directoryName;
        public String profileId;
        public String versionName;
        public boolean installed;
        public String packageName;
        public boolean versionIsolation;
        public boolean launchVertically;
        public long createdAt;
    }

    private static class OutputTarget {
        Uri uri;
        File file;
        OutputStream outputStream;
        String displayPath;
    }

    private static class NamedSource {
        final File file;
        final String entryPath;

        NamedSource(File file, String entryPath) {
            this.file = file;
            this.entryPath = entryPath;
        }
    }

    public InstanceBackupManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void shutdown() {
        if (!executor.isShutdown()) {
            executor.shutdown();
        }
    }

    public boolean needsDownloadsFolderSelection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false;
        Uri tree = getBackupDownloadsTree();
        if (tree == null) return true;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (tree.equals(permission.getUri()) && permission.isReadPermission() && permission.isWritePermission()) return false;
        }
        return true;
    }

    public Intent downloadsFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return intent;
    }

    public void setDownloadsFolder(Intent data) throws IOException {
        Uri tree = data == null ? null : data.getData();
        if (tree == null || !"com.android.externalstorage.documents".equals(tree.getAuthority())
                || !"primary:Download".equals(DocumentsContract.getTreeDocumentId(tree))) {
            throw new IOException(context.getString(org.levimc.launcher.R.string.backup_select_downloads));
        }
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            throw new IOException(context.getString(org.levimc.launcher.R.string.backup_select_downloads));
        }
        context.getContentResolver().takePersistableUriPermission(tree, flags);
        context.getSharedPreferences("instance_backups", Context.MODE_PRIVATE).edit()
                .putString("downloads_tree", tree.toString()).apply();
    }

    private Uri getBackupDownloadsTree() {
        String value = context.getSharedPreferences("instance_backups", Context.MODE_PRIVATE)
                .getString("downloads_tree", null);
        return value == null ? null : Uri.parse(value);
    }

    private Uri getOrCreateBackupDirectory(Uri tree, Uri parent, String name) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = context.getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot read Downloads");
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) {
                    if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                        throw new IOException("Backup path is not a folder");
                    }
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                }
            }
        }
        Uri directory = DocumentsContract.createDocument(context.getContentResolver(), parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (directory == null) throw new IOException("Cannot create backup folder");
        return directory;
    }

    public void backup(GameVersion version, BackupCallback callback) {
        executor.execute(() -> {
            postStarted(callback);
            OutputTarget target = null;
            try {
                if (version == null) {
                    throw new IOException("No instance selected");
                }

                BackupManifest manifest = createManifest(version);
                String fileName = buildBackupFileName(manifest);
                target = createOutputTarget(fileName);

                List<NamedSource> packageSources = collectInstalledPackageSources(version);
                long totalBytes = countBackupBytes(version, packageSources);
                long[] copiedBytes = {0L};
                int[] lastProgress = {-1};
                Set<String> addedEntries = new HashSet<>();

                try (ZipOutputStream zos = new ZipOutputStream(target.outputStream)) {
                    writeManifest(zos, manifest, addedEntries);
                    postProgress(callback, 2, lastProgress);

                    File profileRoot = getProfileRoot(version);
                    zipDirectory(profileRoot, PROFILE_PREFIX, zos, addedEntries,
                            copiedBytes, totalBytes, lastProgress, callback);

                    if (!version.versionIsolation) {
                        zipDirectory(LauncherStorage.getSharedRoot(context), SHARED_PREFIX, zos, addedEntries,
                                copiedBytes, totalBytes, lastProgress, callback);
                    }

                    File runtimeLibDir = MinecraftLauncher.getRuntimeLibDir(context, manifest.profileId);
                    zipDirectory(runtimeLibDir, RUNTIME_LIBS_PREFIX, zos, addedEntries,
                            copiedBytes, totalBytes, lastProgress, callback);

                    for (NamedSource source : packageSources) {
                        zipSingleFile(source.file, source.entryPath, zos, addedEntries,
                                copiedBytes, totalBytes, lastProgress, callback);
                    }
                }

                finishOutputTarget(target, true);
                postProgress(callback, 100, lastProgress);
                postBackupSuccess(callback, target.displayPath);
            } catch (Exception e) {
                Log.e(TAG, "Failed to backup instance", e);
                if (target != null) {
                    finishOutputTarget(target, false);
                }
                postBackupError(callback, e.getMessage());
            }
        });
    }

    public void restore(Uri backupUri, RestoreCallback callback) {
        executor.execute(() -> {
            postStarted(callback);
            File tempFile = new File(context.getCacheDir(), "instance_restore_" + System.currentTimeMillis() + ".zip");
            try {
                copyUriToTempFile(backupUri, tempFile, callback);
                postProgress(callback, 10, new int[]{-1});

                try (ZipFile zipFile = new ZipFile(tempFile)) {
                    validateZipFile(zipFile);
                    BackupManifest manifest = readManifest(zipFile);
                    validateManifest(manifest);

                    boolean restoredInstalledData = manifest.installed;
                    String restoredName = restoredInstalledData
                            ? restoreInstalledData(zipFile, manifest, callback)
                            : restoreCustomInstance(zipFile, manifest, callback);

                    VersionManager.get(context).loadAllVersions();
                    postRestoreSuccess(callback, restoredName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to restore instance backup", e);
                postRestoreError(callback, e.getMessage());
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
        });
    }

    public static boolean isBackupFileName(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(BACKUP_EXTENSION) || lower.endsWith(ZIP_EXTENSION);
    }

    public static boolean isBackupIntent(Context context, Intent intent) {
        if (intent == null) return false;
        Uri data = intent.getData();
        if (hasStrongBackupExtension(context, data)) {
            return true;
        }
        if (intent.getClipData() == null) {
            return false;
        }
        for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
            if (hasStrongBackupExtension(context, intent.getClipData().getItemAt(i).getUri())) {
                return true;
            }
        }
        return false;
    }

    public static String toManifestJson(BackupManifest manifest) {
        return GSON.toJson(manifest);
    }

    public static BackupManifest parseManifestJson(String json) {
        return GSON.fromJson(json, BackupManifest.class);
    }

    public static String generateRestoredDirectoryName(File minecraftRoot, String originalName) {
        String sanitized = LauncherStorage.sanitizeProfileId(originalName);
        File original = new File(minecraftRoot, sanitized);
        if (!original.exists()) {
            return sanitized;
        }

        String restoredBase = sanitized + "_restored";
        File restored = new File(minecraftRoot, restoredBase);
        if (!restored.exists()) {
            return restoredBase;
        }

        int index = 1;
        while (new File(minecraftRoot, restoredBase + "_" + index).exists()) {
            index++;
        }
        return restoredBase + "_" + index;
    }

    public static String normalizeZipEntryName(String name) {
        if (name == null) return "";
        String normalized = name.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    public static boolean isSafeZipEntryName(String name) {
        if (name == null) return false;
        String raw = name.trim();
        if (raw.isEmpty() || raw.indexOf('\0') >= 0) return false;
        if (raw.startsWith("/") || raw.startsWith("\\") || raw.matches("^[A-Za-z]:.*")) {
            return false;
        }

        String normalized = normalizeZipEntryName(raw);
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            return false;
        }

        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private BackupManifest createManifest(GameVersion version) {
        BackupManifest manifest = new BackupManifest();
        manifest.instanceName = firstNonEmpty(stripVersionSuffix(version.displayName, version.versionCode),
                version.directoryName, version.versionCode, "instance");
        manifest.directoryName = firstNonEmpty(version.directoryName, version.getStorageProfileId());
        manifest.profileId = version.getStorageProfileId();
        manifest.versionName = firstNonEmpty(version.versionCode, manifest.directoryName);
        manifest.installed = version.isInstalled;
        manifest.packageName = version.packageName;
        manifest.versionIsolation = version.versionIsolation;
        manifest.launchVertically = version.launchVertically;
        manifest.createdAt = System.currentTimeMillis();
        return manifest;
    }

    private String buildBackupFileName(BackupManifest manifest) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(manifest.createdAt));
        return "levilauncher_instance_"
                + sanitizeFileName(firstNonEmpty(manifest.directoryName, manifest.instanceName, "instance"))
                + "_"
                + timestamp
                + BACKUP_EXTENSION;
    }

    private OutputTarget createOutputTarget(String fileName) throws IOException {
        OutputTarget target = new OutputTarget();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOAD_RELATIVE_PATH);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            ContentResolver resolver = context.getContentResolver();
            target.uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target.uri == null) {
                throw new IOException("Failed to create backup file");
            }
            target.outputStream = resolver.openOutputStream(target.uri);
            if (target.outputStream == null) {
                throw new IOException("Failed to open backup output");
            }
            target.displayPath = DOWNLOAD_RELATIVE_PATH + "/" + fileName;
            return target;
        }

        Uri tree = getBackupDownloadsTree();
        if (tree == null) throw new IOException(context.getString(org.levimc.launcher.R.string.backup_select_downloads));
        Uri downloads = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri launcherFolder = getOrCreateBackupDirectory(tree, downloads, "LeviLauncher");
        Uri backupFolder = getOrCreateBackupDirectory(tree, launcherFolder, "Backups");
        target.uri = DocumentsContract.createDocument(context.getContentResolver(), backupFolder, "application/zip", fileName);
        if (target.uri == null) throw new IOException("Cannot create backup file");
        try {
            target.outputStream = context.getContentResolver().openOutputStream(target.uri);
            if (target.outputStream == null) throw new IOException("Cannot open backup file");
        } catch (IOException | RuntimeException error) {
            DocumentsContract.deleteDocument(context.getContentResolver(), target.uri);
            throw error;
        }
        target.displayPath = DOWNLOAD_RELATIVE_PATH + "/" + fileName;
        return target;
    }

    private void finishOutputTarget(OutputTarget target, boolean success) {
        try {
            if (target.outputStream != null) {
                target.outputStream.close();
            }
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.uri != null) {
            ContentResolver resolver = context.getContentResolver();
            if (success) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(target.uri, values, null, null);
            } else {
                resolver.delete(target.uri, null, null);
            }
        } else if (!success && target.uri != null) {
            try {
                DocumentsContract.deleteDocument(context.getContentResolver(), target.uri);
            } catch (Exception ignored) {
            }
        } else if (!success && target.file != null && target.file.exists()) {
            target.file.delete();
        }
    }

    private File getProfileRoot(GameVersion version) {
        if (version.versionDir != null) {
            return version.versionDir;
        }
        return LauncherStorage.getVersionRoot(context, version.getStorageProfileId());
    }

    private List<NamedSource> collectInstalledPackageSources(GameVersion version) {
        List<NamedSource> result = new ArrayList<>();
        if (version == null || !version.isInstalled) {
            return result;
        }

        String packageName = firstNonEmpty(version.packageName, MinecraftLauncher.MC_PACKAGE_NAME);
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            addPackageSource(result, info.sourceDir, PACKAGE_PREFIX + "base.apk");
            if (info.splitSourceDirs != null) {
                for (String split : info.splitSourceDirs) {
                    addPackageSource(result, split, PACKAGE_PREFIX + "splits/" + new File(split).getName());
                }
            }
            if (info.splitPublicSourceDirs != null) {
                for (String split : info.splitPublicSourceDirs) {
                    addPackageSource(result, split, PACKAGE_PREFIX + "splits/" + new File(split).getName());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to collect installed package sources", e);
        }
        return result;
    }

    private void addPackageSource(List<NamedSource> result, String path, String entryPath) {
        if (TextUtils.isEmpty(path)) return;
        File file = new File(path);
        if (!file.isFile()) return;
        for (NamedSource existing : result) {
            if (existing.file.equals(file)) return;
        }
        result.add(new NamedSource(file, entryPath));
    }

    private long countBackupBytes(GameVersion version, List<NamedSource> packageSources) {
        long total = 0L;
        total += countFileBytes(getProfileRoot(version));
        if (!version.versionIsolation) {
            total += countFileBytes(LauncherStorage.getSharedRoot(context));
        }
        total += countFileBytes(MinecraftLauncher.getRuntimeLibDir(context, version.getStorageProfileId()));
        for (NamedSource source : packageSources) {
            total += Math.max(0L, source.file.length());
        }
        return Math.max(total, 1L);
    }

    private long countFileBytes(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return Math.max(0L, file.length());
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += countFileBytes(child);
            }
        }
        return total;
    }

    private void writeManifest(ZipOutputStream zos, BackupManifest manifest, Set<String> addedEntries) throws IOException {
        String json = toManifestJson(manifest);
        ZipEntry entry = new ZipEntry(MANIFEST_ENTRY);
        zos.putNextEntry(entry);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        zos.write(bytes);
        zos.closeEntry();
        addedEntries.add(MANIFEST_ENTRY);
    }

    private void zipDirectory(
            File root,
            String prefix,
            ZipOutputStream zos,
            Set<String> addedEntries,
            long[] copiedBytes,
            long totalBytes,
            int[] lastProgress,
            BackupCallback callback
    ) throws IOException {
        if (root == null || !root.exists()) return;
        if (root.isFile()) {
            zipSingleFile(root, prefix + root.getName(), zos, addedEntries, copiedBytes, totalBytes, lastProgress, callback);
            return;
        }

        File[] children = root.listFiles();
        if (children == null) return;
        String rootPath = root.getCanonicalPath();
        for (File child : children) {
            zipDirectoryChild(root, rootPath, child, prefix, zos, addedEntries,
                    copiedBytes, totalBytes, lastProgress, callback);
        }
    }

    private void zipDirectoryChild(
            File root,
            String rootPath,
            File file,
            String prefix,
            ZipOutputStream zos,
            Set<String> addedEntries,
            long[] copiedBytes,
            long totalBytes,
            int[] lastProgress,
            BackupCallback callback
    ) throws IOException {
        String filePath = file.getCanonicalPath();
        if (!isWithinRoot(rootPath, filePath)) {
            return;
        }

        String relativePath = root.toURI().relativize(file.toURI()).getPath();
        String entryPath = prefix + normalizeZipEntryName(relativePath);
        if (file.isDirectory()) {
            if (!entryPath.endsWith("/")) {
                entryPath += "/";
            }
            putDirectoryEntry(zos, addedEntries, entryPath);
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    zipDirectoryChild(root, rootPath, child, prefix, zos, addedEntries,
                            copiedBytes, totalBytes, lastProgress, callback);
                }
            }
        } else {
            zipSingleFile(file, entryPath, zos, addedEntries, copiedBytes, totalBytes, lastProgress, callback);
        }
    }

    private void putDirectoryEntry(ZipOutputStream zos, Set<String> addedEntries, String entryPath) throws IOException {
        if (TextUtils.isEmpty(entryPath) || !addedEntries.add(entryPath)) {
            return;
        }
        ZipEntry entry = new ZipEntry(entryPath);
        zos.putNextEntry(entry);
        zos.closeEntry();
    }

    private void zipSingleFile(
            File file,
            String entryPath,
            ZipOutputStream zos,
            Set<String> addedEntries,
            long[] copiedBytes,
            long totalBytes,
            int[] lastProgress,
            BackupCallback callback
    ) throws IOException {
        if (file == null || !file.isFile()) return;
        String normalizedEntryPath = normalizeZipEntryName(entryPath);
        if (!isSafeZipEntryName(normalizedEntryPath) || !addedEntries.add(normalizedEntryPath)) {
            return;
        }

        ZipEntry entry = new ZipEntry(normalizedEntryPath);
        entry.setTime(file.lastModified());
        zos.putNextEntry(entry);
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = input.read(buffer)) != -1) {
                zos.write(buffer, 0, len);
                copiedBytes[0] += len;
                postProgressFromBytes(callback, copiedBytes[0], totalBytes, 2, 99, lastProgress);
            }
        }
        zos.closeEntry();
    }

    private void copyUriToTempFile(Uri uri, File tempFile, RestoreCallback callback) throws IOException {
        if (uri == null) {
            throw new IOException("Backup file is missing");
        }

        long totalBytes = getContentSize(uri);
        int[] lastProgress = {-1};
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(tempFile)) {
            if (input == null) {
                throw new IOException("Cannot open backup file");
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            long copied = 0L;
            int len;
            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
                copied += len;
                if (totalBytes > 0) {
                    postProgressFromBytes(callback, copied, totalBytes, 0, 10, lastProgress);
                }
            }
        }
    }

    private long getContentSize(Uri uri) {
        if (uri == null) return -1L;
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).length();
        }

        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0) {
                    return cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {}

        try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd != null) {
                return fd.getStatSize();
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    private void validateZipFile(ZipFile zipFile) throws IOException {
        boolean hasManifest = false;
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String entryName = normalizeZipEntryName(entry.getName());
            if (!isSafeZipEntryName(entryName)) {
                throw new IOException("Unsafe backup entry: " + entry.getName());
            }
            if (MANIFEST_ENTRY.equals(entryName)) {
                hasManifest = true;
            }
        }
        if (!hasManifest) {
            throw new IOException("Invalid backup: manifest.json not found");
        }
    }

    private BackupManifest readManifest(ZipFile zipFile) throws IOException {
        ZipEntry manifestEntry = findEntry(zipFile, MANIFEST_ENTRY);
        if (manifestEntry == null || manifestEntry.isDirectory()) {
            throw new IOException("Invalid backup manifest");
        }

        try (InputStream input = zipFile.getInputStream(manifestEntry)) {
            byte[] data = readAllBytes(input, Math.max(1L, manifestEntry.getSize()));
            String json = new String(data, StandardCharsets.UTF_8);
            return parseManifestJson(json);
        } catch (Exception e) {
            throw new IOException("Invalid backup manifest", e);
        }
    }

    private void validateManifest(BackupManifest manifest) throws IOException {
        if (manifest == null || !FORMAT_ID.equals(manifest.format)) {
            throw new IOException("Unknown backup format");
        }
        if (manifest.schemaVersion <= 0 || manifest.schemaVersion > SCHEMA_VERSION) {
            throw new IOException("Unsupported backup version");
        }
        if (TextUtils.isEmpty(manifest.profileId) && TextUtils.isEmpty(manifest.directoryName)) {
            throw new IOException("Backup manifest is missing instance identity");
        }
    }

    private String restoreCustomInstance(ZipFile zipFile, BackupManifest manifest, RestoreCallback callback) throws IOException {
        File minecraftRoot = LauncherStorage.getMinecraftRoot(context);
        String originalName = firstNonEmpty(manifest.directoryName, manifest.profileId, "restored_instance");
        String targetName = generateRestoredDirectoryName(minecraftRoot, originalName);
        File targetProfileRoot = new File(minecraftRoot, targetName);
        File targetRuntimeRoot = MinecraftLauncher.getRuntimeLibDir(context, targetName);
        boolean sharedRestoredIntoProfile = containsPrefix(zipFile, SHARED_PREFIX);

        boolean success = false;
        try {
            if (!targetProfileRoot.exists() && !targetProfileRoot.mkdirs()) {
                throw new IOException("Failed to create instance directory");
            }
            extractBackupEntries(zipFile, callback, (entryName, relativePath) -> {
                if (entryName.startsWith(PROFILE_PREFIX)) {
                    return new RestoreTarget(targetProfileRoot, relativePath);
                }
                if (entryName.startsWith(SHARED_PREFIX)) {
                    return new RestoreTarget(targetProfileRoot, relativePath);
                }
                if (entryName.startsWith(RUNTIME_LIBS_PREFIX)) {
                    return new RestoreTarget(targetRuntimeRoot, relativePath);
                }
                return null;
            });
            updateCustomMetadata(targetName, manifest, sharedRestoredIntoProfile);
            selectRestoredCustomVersion(targetName);
            success = true;
            return targetName;
        } finally {
            if (!success) {
                deleteDir(targetProfileRoot);
                deleteDir(targetRuntimeRoot);
            }
        }
    }

    private String restoreInstalledData(ZipFile zipFile, BackupManifest manifest, RestoreCallback callback) throws IOException {
        String packageName = firstNonEmpty(manifest.packageName, MinecraftLauncher.MC_PACKAGE_NAME);
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Minecraft is not installed");
        }

        File profileRoot = LauncherStorage.getVersionRoot(context, LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID);
        File sharedRoot = LauncherStorage.getSharedRoot(context);
        File runtimeRoot = MinecraftLauncher.getRuntimeLibDir(context, LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID);

        extractBackupEntries(zipFile, callback, (entryName, relativePath) -> {
            if (entryName.startsWith(PROFILE_PREFIX)) {
                if (shouldSkipInstalledProfileEntry(relativePath)) {
                    return null;
                }
                return new RestoreTarget(profileRoot, relativePath);
            }
            if (entryName.startsWith(SHARED_PREFIX)) {
                return new RestoreTarget(sharedRoot, relativePath);
            }
            if (entryName.startsWith(RUNTIME_LIBS_PREFIX)) {
                return new RestoreTarget(runtimeRoot, relativePath);
            }
            return null;
        });
        return firstNonEmpty(manifest.instanceName, LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID);
    }

    private boolean shouldSkipInstalledProfileEntry(String relativePath) {
        String normalized = normalizeZipEntryName(relativePath);
        return "base.apk.levi".equals(normalized) || normalized.startsWith("splits/");
    }

    private void extractBackupEntries(ZipFile zipFile, RestoreCallback callback, RestoreTargetResolver resolver) throws IOException {
        long totalBytes = countRestorableBytes(zipFile, resolver);
        long[] copiedBytes = {0L};
        int[] lastProgress = {-1};

        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String entryName = normalizeZipEntryName(entry.getName());
            if (entry.isDirectory() || MANIFEST_ENTRY.equals(entryName) || entryName.startsWith(PACKAGE_PREFIX)) {
                continue;
            }

            String relativePath = relativePathForKnownPrefix(entryName);
            if (relativePath == null || relativePath.isEmpty()) {
                continue;
            }
            RestoreTarget target = resolver.resolve(entryName, relativePath);
            if (target == null) {
                continue;
            }
            copyZipEntry(zipFile, entry, target.baseDir, target.relativePath,
                    copiedBytes, totalBytes, lastProgress, callback);
        }
        postProgress(callback, 100, lastProgress);
    }

    private long countRestorableBytes(ZipFile zipFile, RestoreTargetResolver resolver) {
        long total = 0L;
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String entryName = normalizeZipEntryName(entry.getName());
            if (entry.isDirectory() || MANIFEST_ENTRY.equals(entryName) || entryName.startsWith(PACKAGE_PREFIX)) {
                continue;
            }
            String relativePath = relativePathForKnownPrefix(entryName);
            if (relativePath == null || relativePath.isEmpty()) {
                continue;
            }
            try {
                if (resolver.resolve(entryName, relativePath) == null) {
                    continue;
                }
            } catch (IOException ignored) {
                continue;
            }
            long size = entry.getSize();
            if (size > 0) {
                total += size;
            }
        }
        return Math.max(total, 1L);
    }

    private void copyZipEntry(
            ZipFile zipFile,
            ZipEntry entry,
            File baseDir,
            String relativePath,
            long[] copiedBytes,
            long totalBytes,
            int[] lastProgress,
            RestoreCallback callback
    ) throws IOException {
        if (baseDir == null || TextUtils.isEmpty(relativePath)) return;
        File target = resolveTargetFile(baseDir, relativePath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        try (InputStream input = zipFile.getInputStream(entry);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
                copiedBytes[0] += len;
                postProgressFromBytes(callback, copiedBytes[0], totalBytes, 10, 99, lastProgress);
            }
        }
        if (entry.getTime() > 0) {
            target.setLastModified(entry.getTime());
        }
    }

    private File resolveTargetFile(File baseDir, String relativePath) throws IOException {
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IOException("Failed to create directory: " + baseDir.getAbsolutePath());
        }
        File target = new File(baseDir, relativePath);
        String basePath = baseDir.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!isWithinRoot(basePath, targetPath)) {
            throw new IOException("Unsafe backup entry target: " + relativePath);
        }
        return target;
    }

    private void updateCustomMetadata(String targetName, BackupManifest manifest, boolean sharedRestoredIntoProfile) throws IOException {
        VersionProfileMetadataStore store = new VersionProfileMetadataStore();
        File metadataDir = LauncherStorage.getProfileMetadataDir(context, targetName);
        store.update(metadataDir,
                VersionProfileMetadataStore.Defaults.custom(targetName, manifest.versionName),
                metadata -> {
                    metadata.profileId = LauncherStorage.sanitizeProfileId(targetName);
                    metadata.directoryName = targetName;
                    metadata.versionName = firstNonEmpty(manifest.versionName, targetName);
                    metadata.displayName = firstNonEmpty(manifest.instanceName, null);
                    metadata.versionIsolation = sharedRestoredIntoProfile || manifest.versionIsolation;
                    metadata.launchVertically = manifest.launchVertically;
                    metadata.installed = false;
                    metadata.packageName = null;
                });
    }

    private void selectRestoredCustomVersion(String targetName) {
        VersionManager manager = VersionManager.get(context);
        manager.loadAllVersions();
        for (GameVersion version : manager.getCustomVersions()) {
            if (targetName.equals(version.directoryName)) {
                manager.selectVersion(version);
                return;
            }
        }
    }

    private boolean containsPrefix(ZipFile zipFile, String prefix) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            String name = normalizeZipEntryName(entries.nextElement().getName());
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }

    private ZipEntry findEntry(ZipFile zipFile, String normalizedName) {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (normalizedName.equals(normalizeZipEntryName(entry.getName()))) {
                return entry;
            }
        }
        return null;
    }

    private String relativePathForKnownPrefix(String entryName) {
        if (entryName.startsWith(PROFILE_PREFIX)) {
            return entryName.substring(PROFILE_PREFIX.length());
        }
        if (entryName.startsWith(SHARED_PREFIX)) {
            return entryName.substring(SHARED_PREFIX.length());
        }
        if (entryName.startsWith(RUNTIME_LIBS_PREFIX)) {
            return entryName.substring(RUNTIME_LIBS_PREFIX.length());
        }
        if (entryName.startsWith(PACKAGE_PREFIX)) {
            return entryName.substring(PACKAGE_PREFIX.length());
        }
        return null;
    }

    private byte[] readAllBytes(InputStream input, long expectedSize) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(
                expectedSize > 0 && expectedSize < Integer.MAX_VALUE ? (int) expectedSize : BUFFER_SIZE
        );
        byte[] buffer = new byte[8192];
        int len;
        while ((len = input.read(buffer)) != -1) {
            output.write(buffer, 0, len);
        }
        return output.toByteArray();
    }

    private static boolean hasStrongBackupExtension(Context context, Uri uri) {
        if (uri == null) return false;
        return hasLevibackupExtension(uri.getPath())
                || hasLevibackupExtension(uri.getLastPathSegment())
                || hasLevibackupExtension(resolveDisplayName(context, uri));
    }

    private static boolean hasLevibackupExtension(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).endsWith(BACKUP_EXTENSION);
    }

    private static String resolveDisplayName(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            return nameIndex >= 0 ? cursor.getString(nameIndex) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isWithinRoot(String rootPath, String childPath) {
        return childPath.equals(rootPath) || childPath.startsWith(rootPath + File.separator);
    }

    private void postStarted(BackupCallback callback) {
        if (callback != null) {
            mainHandler.post(callback::onStarted);
        }
    }

    private void postStarted(RestoreCallback callback) {
        if (callback != null) {
            mainHandler.post(callback::onStarted);
        }
    }

    private void postProgress(BackupCallback callback, int progress, int[] lastProgress) {
        if (callback == null) return;
        int clamped = Math.max(0, Math.min(100, progress));
        if (lastProgress != null && clamped <= lastProgress[0] && clamped < 100) return;
        if (lastProgress != null) lastProgress[0] = clamped;
        mainHandler.post(() -> callback.onProgress(clamped));
    }

    private void postProgress(RestoreCallback callback, int progress, int[] lastProgress) {
        if (callback == null) return;
        int clamped = Math.max(0, Math.min(100, progress));
        if (lastProgress != null && clamped <= lastProgress[0] && clamped < 100) return;
        if (lastProgress != null) lastProgress[0] = clamped;
        mainHandler.post(() -> callback.onProgress(clamped));
    }

    private void postProgressFromBytes(
            BackupCallback callback,
            long copied,
            long total,
            int start,
            int end,
            int[] lastProgress
    ) {
        if (total <= 0) return;
        int progress = start + (int) ((Math.min(copied, total) * (end - start)) / total);
        postProgress(callback, progress, lastProgress);
    }

    private void postProgressFromBytes(
            RestoreCallback callback,
            long copied,
            long total,
            int start,
            int end,
            int[] lastProgress
    ) {
        if (total <= 0) return;
        int progress = start + (int) ((Math.min(copied, total) * (end - start)) / total);
        postProgress(callback, progress, lastProgress);
    }

    private void postBackupSuccess(BackupCallback callback, String displayPath) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(displayPath));
        }
    }

    private void postBackupError(BackupCallback callback, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(firstNonEmpty(message, "Backup failed")));
        }
    }

    private void postRestoreSuccess(RestoreCallback callback, String restoredName) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(restoredName));
        }
    }

    private void postRestoreError(RestoreCallback callback, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(firstNonEmpty(message, "Restore failed")));
        }
    }

    private static String sanitizeFileName(String value) {
        String safe = firstNonEmpty(value, "instance").replaceAll("[^A-Za-z0-9._-]+", "_");
        while (safe.startsWith(".")) safe = safe.substring(1);
        while (safe.endsWith(".")) safe = safe.substring(0, safe.length() - 1);
        return safe.isEmpty() ? "instance" : safe;
    }

    private static String stripVersionSuffix(String displayName, String versionCode) {
        if (TextUtils.isEmpty(displayName)) return "";
        String trimmed = displayName.trim();
        if (TextUtils.isEmpty(versionCode)) return trimmed;
        String suffix = " (" + versionCode + ")";
        if (trimmed.endsWith(suffix)) {
            return trimmed.substring(0, trimmed.length() - suffix.length()).trim();
        }
        return trimmed;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean deleteDir(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteDir(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private interface RestoreTargetResolver {
        RestoreTarget resolve(String entryName, String relativePath) throws IOException;
    }

    private static class RestoreTarget {
        final File baseDir;
        final String relativePath;

        RestoreTarget(File baseDir, String relativePath) {
            this.baseDir = baseDir;
            this.relativePath = relativePath;
        }
    }
}
