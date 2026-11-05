package org.levimc.launcher.util;

import android.content.Context;
import android.net.Uri;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.levimc.launcher.core.minecraft.MinecraftLauncher;

public class ApkUtils {
    public interface LibExtractProgressCallback {
        void onBytesExtracted(int bytesExtracted);
    }

    public static String extractMinecraftVersionNameFromUri(Context context, Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(uri.getPath(), 0);
            if (info != null) {
                String packageName = info.packageName;
                String versionName = info.versionName;
                if ("com.mojang.minecraftpe".equals(packageName) && versionName != null && !versionName.isEmpty()) {
                    return "Minecraft_" + versionName;
                }
            }
            return "Error Apk";
        }

        File tempFile = null;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return "Error Apk";

            tempFile = new File(context.getCacheDir(), "temp_apk_" + System.currentTimeMillis() + ".apk");
            try (OutputStream os = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[131072];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
            }
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(tempFile.getAbsolutePath(), 0);
            if (info != null) {
                String packageName = info.packageName;
                String versionName = info.versionName;

                if ("com.mojang.minecraftpe".equals(packageName) && versionName != null && !versionName.isEmpty()) {
                    return "Minecraft_" + versionName;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
        return "Error Apk";
    }

    public static String extractMinecraftVersionNameFromApksUri(Context context, Uri uri) {
        File tempApkFile = null;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return "Error Apk";
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.isDirectory() && (entry.getName().equals("base.apk") || entry.getName().endsWith("/base.apk"))) {
                        tempApkFile = new File(context.getCacheDir(), "temp_base_" + System.currentTimeMillis() + ".apk");
                        try (OutputStream os = new FileOutputStream(tempApkFile)) {
                            byte[] buffer = new byte[131072];
                            int len;
                            while ((len = zis.read(buffer)) != -1) {
                                os.write(buffer, 0, len);
                            }
                        }
                        
                        PackageManager pm = context.getPackageManager();
                        PackageInfo info = pm.getPackageArchiveInfo(tempApkFile.getAbsolutePath(), 0);
                        if (info != null) {
                            String packageName = info.packageName;
                            String versionName = info.versionName;
                            if ("com.mojang.minecraftpe".equals(packageName) && versionName != null && !versionName.isEmpty()) {
                                return "Minecraft_" + versionName;
                            }
                        }
                        break;
                    }
                    zis.closeEntry();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (tempApkFile != null && tempApkFile.exists()) {
                tempApkFile.delete();
            }
        }
        return "Error Apk";
    }

    public static String abiToSystemLibDir(String abi) {
        if (abi == null) return "unknown";
        switch (abi) {
            case "armeabi-v7a":
                return "arm";
            case "arm64-v8a":
                return "arm64";
            default:
                return abi;
        }
    }

    public static void unzipLibsToSystemAbi(File libBaseDir, ZipInputStream zis) throws IOException {
        unzipLibsToSystemAbi(libBaseDir, zis, null);
    }

    public static void unzipLibsToSystemAbi(File libBaseDir, ZipInputStream zis, LibExtractProgressCallback callback) throws IOException {
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (isNativeLibrary(entry)) extractNativeLibrary(libBaseDir, entry, zis, callback);
            zis.closeEntry();
        }
    }

    public static void unzipLibsToSystemAbi(File libBaseDir, ZipFile zip, LibExtractProgressCallback callback) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!isNativeLibrary(entry)) continue;
            try (InputStream input = zip.getInputStream(entry)) {
                extractNativeLibrary(libBaseDir, entry, input, callback);
            }
        }
    }

    private static boolean isNativeLibrary(ZipEntry entry) {
        if (entry.isDirectory()) return false;
        String[] parts = entry.getName().split("/", -1);
        return parts.length == 3 && "lib".equals(parts[0])
                && !parts[1].isEmpty() && !"..".equals(parts[1]) && !".".equals(parts[1])
                && parts[2].endsWith(".so");
    }

    private static void extractNativeLibrary(File root, ZipEntry entry, InputStream input,
                                              LibExtractProgressCallback callback) throws IOException {
        String[] parts = entry.getName().split("/");
        File output = new File(root, abiToSystemLibDir(parts[1]) + "/" + parts[2]);
        File parent = output.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Failed to create " + parent);
        File staged = new File(parent, output.getName() + ".tmp");
        if (staged.exists() && !staged.delete()) throw new IOException("Failed to replace " + staged);
        try {
            long copied = 0;
            try (FileOutputStream stream = new FileOutputStream(staged)) {
                byte[] buffer = new byte[256 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    stream.write(buffer, 0, count);
                    copied += count;
                    if (callback != null) callback.onBytesExtracted(count);
                }
            }
            if (copied == 0 || (entry.getSize() >= 0 && copied != entry.getSize())) {
                throw new IOException("Incomplete native library: " + entry.getName());
            }
            if (MinecraftLauncher.getRuntimeAbi().equals(parts[1])) {
                NativeImageGuard.publishStagedImage(staged, output);
            } else {
                if (!staged.setReadOnly()) throw new IOException("Failed to protect " + staged);
                Files.move(staged.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IOException("Failed to prepare " + entry.getName() + ": " + error.getMessage(), error);
        } finally {
            staged.delete();
        }
    }
}
