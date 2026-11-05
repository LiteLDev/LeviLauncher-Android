package org.levimc.launcher.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.levimc.launcher.core.minecraft.MinecraftLauncher;
import org.levimc.launcher.core.minecraft.GamePackageManager;
import org.levimc.launcher.core.versions.VersionProfileMetadataStore;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class ApkInstaller {

    public interface InstallCallback {
        void onProgress(int progress);

        void onSuccess(String versionName);

        void onError(String errorMessage);
    }

    private static final String APK_FILE_NAME = "base.apk.levi";
    private static final int BUFFER_SIZE = 131072;
    private static final int PROGRESS_PREPARED = 5;
    private static final int PROGRESS_COPY_DONE = 55;
    private static final int PROGRESS_LIBS_DONE = 92;
    private static final int PROGRESS_METADATA_DONE = 96;
    private static final int PROGRESS_MAX = 100;

    private final Context context;
    private final ExecutorService executor;
    private final InstallCallback callback;
    private int lastPostedProgress = -1;

    public ApkInstaller(Context context, ExecutorService executor, InstallCallback callback) {
        this.context = context.getApplicationContext();
        this.executor = executor;
        this.callback = callback;
    }

    public static class VersionAbi {
        public final String versionName;

        public VersionAbi(String versionName) {
            this.versionName = versionName;
        }
    }

    public void install(final Uri apkOrApksUri, final String dirName) {
        executor.submit(() -> {
            try {
                lastPostedProgress = -1;
                postProgress(0);
                File metadataDir = LauncherStorage.getProfileMetadataDir(context, dirName);
                if (metadataDir.exists() && !deleteDir(metadataDir)) {
                    postError("Clean metadata directory failed");
                    return;
                }
                File externalDir = LauncherStorage.getVersionDir(context, dirName);
                if (externalDir.exists() && !deleteDir(externalDir)) {
                    postError("Clean version directory failed");
                    return;
                }
                postProgress(5);

                File libTargetDir = MinecraftLauncher.getRuntimeLibDir(context, dirName);
                if (libTargetDir.exists()) {
                    deleteDir(libTargetDir);
                }
                File baseDir = externalDir;
                if (!baseDir.exists() && !baseDir.mkdirs()) {
                    postError("Open base dir failed");
                    return;
                }

                String fileName = getFileName(apkOrApksUri);
                long sourceSize = getContentSize(apkOrApksUri);
                List<File> apkFilesToExtract = new ArrayList<>();
                if (fileName != null && fileName.toLowerCase().endsWith(".apks")) {
                    boolean foundBaseApk = false;
                    File splitsDir = new File(baseDir, "splits");

                    try (InputStream rawInput = context.getContentResolver().openInputStream(apkOrApksUri)) {
                        if (rawInput == null) {
                            postError("Open apks failed");
                            return;
                        }
                        CountingInputStream countingInput = new CountingInputStream(rawInput);
                        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(countingInput));
                        ZipEntry entry;
                        try {
                            while ((entry = zis.getNextEntry()) != null) {
                                if (entry.isDirectory()) {
                                    zis.closeEntry();
                                    continue;
                                }

                                String entryName = entry.getName();
                                if (!entryName.endsWith(".apk")) {
                                    zis.closeEntry();
                                    continue;
                                }

                                File outFile;
                                String outputName;

                                if (entryName.equals("base.apk") || entryName.endsWith("/base.apk")) {
                                    outFile = new File(baseDir, APK_FILE_NAME);
                                    outputName = APK_FILE_NAME;
                                } else {
                                    if (!splitsDir.exists()) splitsDir.mkdirs();
                                    String splitName = new File(entryName).getName();
                                    splitName = splitName.replace(".apk", ".apk.levi");
                                    outFile = new File(splitsDir, splitName);
                                    outputName = splitName;
                                }

                                File parent = outFile.getParentFile();
                                if (parent != null && !parent.exists()) parent.mkdirs();

                                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                    copyStream(zis, fos, bytesCopied -> postProgressFromBytes(countingInput.getBytesRead(), sourceSize, PROGRESS_PREPARED, PROGRESS_COPY_DONE));
                                }
                                apkFilesToExtract.add(outFile);

                                if (outputName.equals(APK_FILE_NAME)) {
                                    foundBaseApk = true;
                                }
                                zis.closeEntry();
                            }
                        } finally {
                            zis.close();
                        }
                    }
                    if (!foundBaseApk) {
                        postError("No base.apk found in APKS bundle");
                        return;
                    }
                } else {
                    File dstApkFile = new File(baseDir, APK_FILE_NAME);
                    try (InputStream is = context.getContentResolver().openInputStream(apkOrApksUri);
                         OutputStream os = new FileOutputStream(dstApkFile)) {
                        if (is == null) {
                            postError("Open apk failed");
                            return;
                        }
                        long[] copied = {0};
                        copyStream(is, os, bytesCopied -> {
                            copied[0] += bytesCopied;
                            postProgressFromBytes(copied[0], sourceSize, PROGRESS_PREPARED, PROGRESS_COPY_DONE);
                        });
                    }
                    apkFilesToExtract.add(dstApkFile);
                }

                postProgress(PROGRESS_COPY_DONE);
                extractNativeLibsWithProgress(apkFilesToExtract, libTargetDir);

                String versionName = extractVersionName(apkOrApksUri, baseDir, dirName);
                GamePackageManager.recordImportedLibraries(context, dirName, versionName, apkFilesToExtract);
                postProgress(PROGRESS_METADATA_DONE);
                writeProfileMetadata(metadataDir, dirName, versionName);

                postProgress(PROGRESS_MAX);
                postSuccess(versionName);

            } catch (Exception e) {
                postError("Install error: " + e.getMessage());
            }
        });
    }

    private String extractVersionName(Uri apkOrApksUri, File baseDir, String dirName) {
        File baseApkLevi = new File(baseDir, APK_FILE_NAME);
        if (baseApkLevi.exists()) {
            String v = extractApkVersionName(baseApkLevi);
            if (!"unknown_version".equals(v)) return v;
        }
        String name = dirName;
        if (name.startsWith("Minecraft_")) {
            name = name.substring("Minecraft_".length());
        }
        return name;
    }

    private static void copyStream(InputStream is, OutputStream os) throws IOException {
        copyStream(is, os, null);
    }

    private static void copyStream(InputStream is, OutputStream os, ProgressReporter reporter) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int len;
        while ((len = is.read(buffer)) != -1) {
            os.write(buffer, 0, len);
            if (reporter != null) {
                reporter.onBytesCopied(len);
            }
        }
    }

    private static void writeProfileMetadata(File metadataDir, String dirName, String versionName) throws IOException {
        VersionProfileMetadataStore store = new VersionProfileMetadataStore();
        store.loadOrCreate(metadataDir, VersionProfileMetadataStore.Defaults.custom(dirName, versionName));
    }

    private void postProgress(int progress) {
        int clamped = Math.max(0, Math.min(100, progress));
        if (clamped <= lastPostedProgress && clamped < 100) {
            return;
        }
        lastPostedProgress = clamped;
        new Handler(Looper.getMainLooper()).post(() -> {
            if (callback != null) callback.onProgress(clamped);
        });
    }

    private void postProgressFromBytes(long processedBytes, long totalBytes, int start, int end) {
        if (totalBytes <= 0) {
            return;
        }
        long safeProcessed = Math.max(0, Math.min(processedBytes, totalBytes));
        int progress = start + (int) ((safeProcessed * (end - start)) / totalBytes);
        postProgress(progress);
    }

    private void extractNativeLibsWithProgress(List<File> apkFiles, File libTargetDir) throws IOException {
        long totalLibBytes = calculateNativeLibBytes(apkFiles);
        if (totalLibBytes <= 0) {
            postProgress(PROGRESS_LIBS_DONE);
            return;
        }

        long[] extractedBytes = {0};
        for (File apkFile : apkFiles) {
            try (ZipFile zip = new ZipFile(apkFile)) {
                ApkUtils.unzipLibsToSystemAbi(libTargetDir, zip, bytesExtracted -> {
                    extractedBytes[0] += bytesExtracted;
                    postProgressFromBytes(extractedBytes[0], totalLibBytes, PROGRESS_COPY_DONE, PROGRESS_LIBS_DONE);
                });
            }
        }
        postProgress(PROGRESS_LIBS_DONE);
    }

    private long calculateNativeLibBytes(List<File> apkFiles) {
        long total = 0;
        for (File apkFile : apkFiles) {
            try (ZipFile zipFile = new ZipFile(apkFile)) {
                java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith("lib/")) {
                        continue;
                    }
                    long size = entry.getSize();
                    if (size > 0) {
                        total += size;
                    }
                }
            } catch (IOException ignored) {}
        }
        return total;
    }

    private void postSuccess(String versionName) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (callback != null) callback.onSuccess(versionName);
        });
    }

    private void postError(String error) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (callback != null) callback.onError(error);
        });
    }

    private String extractApkVersionName(File apkFile) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (info != null) {
                String pkgName = info.packageName;
                String vName = info.versionName;
                if ("com.mojang.minecraftpe".equals(pkgName) && vName != null && !vName.isEmpty()) {
                    return vName;
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown_version";
    }

    private String getFileName(Uri uri) {
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        String result = null;
        if (cursor != null) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex != -1 && cursor.moveToFirst()) {
                result = cursor.getString(nameIndex);
            }
            cursor.close();
        }
        return result;
    }

    private long getContentSize(Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).length();
        }

        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    return cursor.getLong(sizeIndex);
                }
            } finally {
                cursor.close();
            }
        }

        try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd != null) {
                return fd.getStatSize();
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    private interface ProgressReporter {
        void onBytesCopied(int bytesCopied);
    }

    private static class CountingInputStream extends FilterInputStream {
        private long bytesRead;

        CountingInputStream(InputStream in) {
            super(in);
        }

        long getBytesRead() {
            return bytesRead;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) bytesRead++;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) bytesRead += read;
            return read;
        }
    }

    public static boolean deleteDir(File dir) {
        if (dir == null || !dir.exists()) return true;
        if (dir.isFile()) return dir.delete();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File child : files) {
                if (!deleteDir(child)) return false;
            }
        }
        return dir.delete();
    }
}
