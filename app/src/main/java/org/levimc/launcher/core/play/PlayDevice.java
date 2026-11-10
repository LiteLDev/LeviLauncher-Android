package org.levimc.launcher.core.play;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * The device identity the launcher presents to Google Play: this phone's own build and
 * hardware, except that it always asks for the arm64-v8a build, the only ABI the launcher
 * can run.
 */
final class PlayDevice {
    /** Used when Google Play services or the Play Store is not installed. */
    private static final long FALLBACK_GMS_VERSION = 251333035L;
    private static final long FALLBACK_VENDING_VERSION = 84582130L;
    private static final String FALLBACK_VENDING_VERSION_NAME = "45.8.21-31 [0] [PR] 747433787";
    private static final String TARGET_ABI = "arm64-v8a";

    private static volatile PlayDevice cached;

    final String fingerprint = Build.FINGERPRINT;
    final String hardware = Build.HARDWARE;
    final String brand = Build.BRAND;
    final String radio = valueOr(Build.getRadioVersion(), "unknown");
    final String bootloader = Build.BOOTLOADER;
    final String device = Build.DEVICE;
    final String model = Build.MODEL;
    final String manufacturer = Build.MANUFACTURER;
    final String product = Build.PRODUCT;
    final String buildId = Build.ID;
    final String release = Build.VERSION.RELEASE;
    final int sdk = Build.VERSION.SDK_INT;

    final long gmsVersion;
    final long vendingVersion;
    final String vendingVersionName;

    final int touchScreen;
    final int keyboard;
    final int navigation;
    final int screenLayout;
    final int density;
    final int screenWidth;
    final int screenHeight;
    final int glEsVersion;
    final long totalMemory;
    final boolean lowRam;
    final List<String> features;
    final List<String> sharedLibraries;
    final List<String> locales;
    final List<String> glExtensions;
    final Locale locale = Locale.getDefault();

    private PlayDevice(Context context) {
        PackageManager pm = context.getPackageManager();
        long[] gms = installedVersion(pm, "com.google.android.gms");
        gmsVersion = gms != null ? gms[0] : FALLBACK_GMS_VERSION;
        PackageInfo vending = packageInfo(pm, "com.android.vending");
        if (vending != null && vending.versionName != null) {
            vendingVersion = vending.getLongVersionCode();
            vendingVersionName = vending.versionName;
        } else {
            vendingVersion = FALLBACK_VENDING_VERSION;
            vendingVersionName = FALLBACK_VENDING_VERSION_NAME;
        }

        Configuration config = context.getResources().getConfiguration();
        touchScreen = config.touchscreen;
        keyboard = config.keyboard;
        navigation = config.navigation;
        screenLayout = config.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = context.getSystemService(WindowManager.class);
        if (wm != null) {
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics = context.getResources().getDisplayMetrics();
        }
        density = metrics.densityDpi;
        screenWidth = Math.min(metrics.widthPixels, metrics.heightPixels);
        screenHeight = Math.max(metrics.widthPixels, metrics.heightPixels);

        ActivityManager am = context.getSystemService(ActivityManager.class);
        glEsVersion = am != null ? am.getDeviceConfigurationInfo().reqGlEsVersion : 0x30002;
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(memoryInfo);
        totalMemory = memoryInfo.totalMem > 0 ? memoryInfo.totalMem : 8L * 1024 * 1024 * 1024;
        lowRam = am != null && am.isLowRamDevice();

        TreeSet<String> featureNames = new TreeSet<>();
        FeatureInfo[] available = pm.getSystemAvailableFeatures();
        if (available != null) {
            for (FeatureInfo info : available) {
                if (info.name != null) featureNames.add(info.name);
            }
        }
        features = new ArrayList<>(featureNames);

        String[] libraries = pm.getSystemSharedLibraryNames();
        sharedLibraries = libraries == null ? Collections.emptyList() : sorted(Arrays.asList(libraries));

        TreeSet<String> localeNames = new TreeSet<>();
        for (String name : Resources.getSystem().getAssets().getLocales()) {
            if (name != null && !name.isEmpty()) localeNames.add(name.replace('-', '_'));
        }
        locales = new ArrayList<>(localeNames);

        List<String> extensions = readGlExtensions();
        glExtensions = extensions.isEmpty()
                ? Collections.singletonList("GL_OES_compressed_ETC1_RGB8_texture")
                : extensions;
    }

    static PlayDevice get(Context context) {
        PlayDevice device = cached;
        if (device == null) {
            synchronized (PlayDevice.class) {
                device = cached;
                if (device == null) {
                    device = new PlayDevice(context.getApplicationContext());
                    cached = device;
                }
            }
        }
        return device;
    }

    String abi() {
        return TARGET_ABI;
    }

    String countryCode() {
        String country = locale.getCountry();
        return country.isEmpty() ? "us" : country.toLowerCase(Locale.ROOT);
    }

    String languageCode() {
        String language = locale.getLanguage();
        return language.isEmpty() ? "en" : language.toLowerCase(Locale.ROOT);
    }

    String authUserAgent() {
        return "GoogleAuth/1.4 (" + device + " " + buildId + ")";
    }

    String storeUserAgent() {
        return "Android-Finsky/" + vendingVersionName + " (api=3"
                + ",versionCode=" + vendingVersion
                + ",sdk=" + sdk
                + ",device=" + device
                + ",hardware=" + hardware
                + ",product=" + product
                + ",platformVersionRelease=" + release
                + ",model=" + model
                + ",buildId=" + buildId
                + ",isWideScreen=0"
                + ",supportedAbis=" + TARGET_ABI + ")";
    }

    Proto.Writer deviceConfiguration() {
        Proto.Writer config = new Proto.Writer()
                .int64(1, touchScreen)
                .int64(2, keyboard)
                .int64(3, navigation)
                .int64(4, screenLayout)
                .bool(5, keyboard == Configuration.KEYBOARD_QWERTY)
                .bool(6, false)
                .int64(7, density)
                .int64(8, glEsVersion)
                .strings(9, sharedLibraries)
                .strings(10, features)
                .string(11, TARGET_ABI)
                .int64(12, screenWidth)
                .int64(13, screenHeight)
                .strings(14, locales)
                .strings(15, glExtensions)
                .int64(16, 0)
                .int64(19, lowRam ? 1 : 0)
                .int64(20, totalMemory)
                .int64(21, Math.max(1, Runtime.getRuntime().availableProcessors()));
        for (String feature : features) {
            config.message(26, new Proto.Writer().string(1, feature).int64(2, 0));
        }
        return config;
    }

    /** An AndroidCheckinRequest for a device that has never checked in before. */
    byte[] checkinRequest() {
        Proto.Writer build = new Proto.Writer()
                .string(1, fingerprint)
                .string(2, hardware)
                .string(3, brand)
                .string(4, radio)
                .string(5, bootloader)
                .string(6, "android-google")
                .int64(7, System.currentTimeMillis() / 1000)
                .int64(8, gmsVersion)
                .string(9, device)
                .int64(10, sdk)
                .string(11, model)
                .string(12, manufacturer)
                .string(13, product)
                .bool(14, false);
        Proto.Writer checkin = new Proto.Writer()
                .message(1, build)
                .int64(2, 0)
                .string(6, "310")
                .string(7, "38")
                .string(8, "mobile-notroaming")
                .int64(9, 0);
        return new Proto.Writer()
                .int64(2, 0)
                .message(4, checkin)
                .string(6, locale.toString())
                .string(12, TimeZone.getDefault().getID())
                .int64(14, 3)
                .message(18, deviceConfiguration())
                .int64(20, 0)
                .toByteArray();
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static PackageInfo packageInfo(PackageManager pm, String packageName) {
        try {
            return pm.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static long[] installedVersion(PackageManager pm, String packageName) {
        PackageInfo info = packageInfo(pm, packageName);
        return info == null ? null : new long[]{info.getLongVersionCode()};
    }

    /** The GLES extensions of an offscreen context; empty when no context can be made. */
    private static List<String> readGlExtensions() {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) return Collections.emptyList();
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return Collections.emptyList();
            int[] attributes = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                return Collections.emptyList();
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT) return Collections.emptyList();
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (surface == EGL14.EGL_NO_SURFACE) return Collections.emptyList();
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return Collections.emptyList();
            String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            if (extensions == null) return Collections.emptyList();
            TreeSet<String> names = new TreeSet<>();
            for (String name : extensions.split(" ")) {
                if (!name.trim().isEmpty()) names.add(name.trim());
            }
            return new ArrayList<>(names);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglTerminate(display);
            }
        }
    }
}
