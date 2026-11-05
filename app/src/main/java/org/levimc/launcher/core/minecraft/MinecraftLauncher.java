package org.levimc.launcher.core.minecraft;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.PlayStoreValidator;
import org.levimc.launcher.ui.dialogs.PlayStoreValidationDialog;
import org.levimc.launcher.util.LauncherBackgroundController;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;

public class MinecraftLauncher {
    private static final String TAG = "MinecraftLauncher";
    private final Context context;
    public static final String MC_PACKAGE_NAME = "com.mojang.minecraftpe";
    public static final String EXTRA_GAME_VERSION = "org.levimc.launcher.extra.GAME_VERSION";
    public static final String EXTRA_STORAGE_PROFILE_ID = "org.levimc.launcher.extra.STORAGE_PROFILE_ID";
    public static final String EXTRA_STORAGE_FILES_DIR = "org.levimc.launcher.extra.STORAGE_FILES_DIR";
    public static final String EXTRA_STORAGE_EXTERNAL_FILES_DIR = "org.levimc.launcher.extra.STORAGE_EXTERNAL_FILES_DIR";
    public static final String EXTRA_STORAGE_DATA_DIR = "org.levimc.launcher.extra.STORAGE_DATA_DIR";
    public static final String EXTRA_STORAGE_CACHE_DIR = "org.levimc.launcher.extra.STORAGE_CACHE_DIR";

    public interface LaunchCallback {
        void onLaunchStarted();
        void onLaunchFailed(Exception e);
    }

    public MinecraftLauncher(Context context) {
        this.context = context;
    }

    public static String abiToSystemLibDir(String abi) {
        if ("arm64-v8a".equals(abi)) return "arm64";
        if ("armeabi-v7a".equals(abi)) return "arm";
        return abi;
    }

    public static File getRuntimeLibDir(Context context, String profileId) {
        return new File(context.getFilesDir(), "minecraft_libs/" + LauncherStorage.sanitizeProfileId(profileId));
    }

    public static String getRuntimeAbi() {
        String[] supported = android.os.Process.is64Bit()
                ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        if (supported.length == 0) {
            throw new IllegalStateException("No ABI matches the launcher process");
        }
        return supported[0];
    }

    public static File getRuntimeLibAbiDir(Context context, String profileId, String abi) {
        return new File(getRuntimeLibDir(context, profileId), abiToSystemLibDir(abi));
    }

    public ApplicationInfo createFakeApplicationInfo(GameVersion version, String packageName) {
        ApplicationInfo fakeInfo = new ApplicationInfo();
        File apkFile = new File(version.versionDir, "base.apk.levi");
        fakeInfo.sourceDir = apkFile.getAbsolutePath();
        fakeInfo.publicSourceDir = fakeInfo.sourceDir;
        File dstLibDir = getRuntimeLibAbiDir(context, getStorageProfileId(version), getRuntimeAbi());
        fakeInfo.nativeLibraryDir = dstLibDir.getAbsolutePath();
        fakeInfo.packageName = packageName;
        fakeInfo.dataDir = LauncherStorage.getProfileDataRoot(context, getStorageProfileId(version)).getAbsolutePath();

        File splitsFolder = new File(version.versionDir, "splits");
        if (splitsFolder.exists() && splitsFolder.isDirectory()) {
            File[] splits = splitsFolder.listFiles();
            if (splits != null) {
                ArrayList<String> splitPathList = new ArrayList<>();
                for (File f : splits) {
                    if (f.isFile() && f.getName().endsWith(".apk.levi")) {
                        splitPathList.add(f.getAbsolutePath());
                    }
                }
                if (!splitPathList.isEmpty()) {
                    java.util.Collections.sort(splitPathList);
                    fakeInfo.splitSourceDirs = splitPathList.toArray(new String[0]);
                    fakeInfo.splitPublicSourceDirs = fakeInfo.splitSourceDirs;
                }
            }
        }
        return fakeInfo;
    }

    public void launch(Intent sourceIntent, GameVersion version) {
        launch(sourceIntent, version, null);
    }

    public void launch(Intent sourceIntent, GameVersion version, LaunchCallback callback) {
        Activity activity = (Activity) context;

        try {
            if (version == null) {
                Log.e(TAG, "No version selected");
                showLaunchErrorOnUi("No version selected");
                notifyLaunchFailed(callback, new IllegalArgumentException("No version selected"));
                return;
            }

            if (version.versionCode != null) {
                try {
                    String[] parts = version.versionCode.split("\\.");
                    if (parts.length >= 2) {
                        int major = Integer.parseInt(parts[0].replaceAll("\\D", ""));
                        int minor = Integer.parseInt(parts[1].replaceAll("\\D", ""));
                        int patch = 0;
                        if (parts.length > 2) {
                            String patchStr = parts[2].replaceAll("\\D.*", "");
                            if (!patchStr.isEmpty()) {
                                patch = Integer.parseInt(patchStr);
                            }
                        }
                        if (major < 1 || (major == 1 && minor < 21) || (major == 1 && minor == 21 && patch < 80)) {
                            activity.runOnUiThread(() -> {
                                new org.levimc.launcher.ui.dialogs.CustomAlertDialog(activity)
                                        .setTitleText(activity.getString(org.levimc.launcher.R.string.unsupported_version_title))
                                        .setMessage(activity.getString(org.levimc.launcher.R.string.unsupported_version_msg))
                                        .setPositiveButton(activity.getString(org.levimc.launcher.R.string.back), null)
                                        .show();
                            });
                            notifyLaunchFailed(callback, new IllegalStateException("Unsupported version"));
                            return;
                        }
                    }
                } catch (Exception e) {
                    // Ignore parsing errors and let it launch
                }
            }

            if (version.needsRepair) {
                activity.runOnUiThread(() ->
                        org.levimc.launcher.core.versions.VersionManager.attemptRepairLibs(activity, version)
                );
                notifyLaunchFailed(callback, new IllegalStateException("Selected version needs repair"));
                return;
            }

            activity.runOnUiThread(() -> {
                try {
                    if (!PlayStoreValidator.isMinecraftFromPlayStore(activity)) {
                        Log.w(TAG, "Launch denied: Minecraft is not verified as Play Store install");
                        PlayStoreValidationDialog.showNotFromPlayStoreDialog(activity);
                        notifyLaunchFailed(callback, new SecurityException("Minecraft is not verified as Play Store install"));
                        return;
                    }
                    launchMinecraftActivity(sourceIntent, version, false);
                    notifyLaunchStarted(callback);
                } catch (Exception e) {
                    LauncherBackgroundController.launchFailed(activity);
                    Log.e(TAG, "Launch failed: " + e.getMessage(), e);
                    showLaunchErrorOnUi("Launch failed: " + e.getMessage());
                    notifyLaunchFailed(callback, e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Launch failed: " + e.getMessage(), e);
            showLaunchErrorOnUi("Launch failed: " + e.getMessage());
            notifyLaunchFailed(callback, e);
        }
    }

    public static String getStorageProfileId(GameVersion version) {
        if (version == null) return LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID;
        if (version.isInstalled) return LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID;
        return LauncherStorage.sanitizeProfileId(version.directoryName);
    }

    private void fillIntentWithStoragePaths(Intent sourceIntent, GameVersion version) {
        String profileId = getStorageProfileId(version);
        boolean versionIsolation = version.versionIsolation;

        File filesDir = LauncherStorage.getStorageFilesRoot(context, profileId, versionIsolation, false);
        File externalFilesDir = LauncherStorage.getStorageFilesRoot(context, profileId, versionIsolation, true);
        File dataDir = LauncherStorage.getStorageDataRoot(context, profileId, versionIsolation);
        File cacheDir = LauncherStorage.getStorageCacheRoot(context, profileId, versionIsolation);

        sourceIntent.putExtra("MC_PATH", version.versionDir == null ? "" : version.versionDir.getAbsolutePath());
        sourceIntent.putExtra("IS_INSTALLED", version.isInstalled);
        sourceIntent.putExtra("VERSION_ISOLATION", versionIsolation);
        sourceIntent.putExtra(EXTRA_STORAGE_PROFILE_ID, profileId);
        sourceIntent.putExtra(EXTRA_STORAGE_FILES_DIR, filesDir.getAbsolutePath());
        sourceIntent.putExtra(EXTRA_STORAGE_EXTERNAL_FILES_DIR, externalFilesDir.getAbsolutePath());
        sourceIntent.putExtra(EXTRA_STORAGE_DATA_DIR, dataDir.getAbsolutePath());
        sourceIntent.putExtra(EXTRA_STORAGE_CACHE_DIR, cacheDir.getAbsolutePath());
    }

    private void launchMinecraftActivity(Intent sourceIntent, GameVersion version, boolean modsEnabled) {
        Activity activity = (Activity) context;

        Intent launchIntent = sourceIntent == null ? new Intent() : new Intent(sourceIntent);
        LaunchTrace trace = LaunchTrace.ensure(launchIntent);
        trace.mark("Building MinecraftLoadingActivity intent");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            launchIntent.putExtra("DISABLE_SPLASH_SCREEN", true);
        }
        fillIntentWithStoragePaths(launchIntent, version);
        launchIntent.setClass(context, MinecraftLoadingActivity.class);
        launchIntent.putExtra(EXTRA_GAME_VERSION, version);
        launchIntent.putExtra("MODS_ENABLED", modsEnabled);
        launchIntent.putExtra("MINECRAFT_VERSION", version.versionCode);
        launchIntent.putExtra("MINECRAFT_VERSION_DIR", version.directoryName);
        launchIntent.putExtra("LAUNCH_VERTICALLY", version.launchVertically);
        launchIntent.putExtra("VERSION_ISOLATION", version.versionIsolation);
        launchIntent.removeExtra("LAUNCH_WITH_URI");

        LauncherBackgroundController.suspendForMinecraft();
        activity.startActivity(launchIntent);
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        trace.mark("MinecraftLoadingActivity startActivity called");
    }

    private void showLaunchErrorOnUi(String message) {
        Activity activity = (Activity) context;
        activity.runOnUiThread(() -> Toast.makeText(
                activity, activity.getString(R.string.dialog_message_launch_failed, message), Toast.LENGTH_LONG).show()
        );
    }

    private void notifyLaunchStarted(LaunchCallback callback) {
        if (callback != null) {
            callback.onLaunchStarted();
        }
    }

    private void notifyLaunchFailed(LaunchCallback callback, Exception e) {
        if (callback != null) {
            callback.onLaunchFailed(e);
        }
    }
}
