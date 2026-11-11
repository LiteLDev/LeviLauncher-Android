package org.levimc.launcher.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.ui.activities.MainActivity;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

public final class InstanceShortcutManager {
    public static final String ACTION_LAUNCH_INSTANCE = "org.levimc.launcher.action.LAUNCH_INSTANCE_SHORTCUT";
    public static final String EXTRA_INSTANCE_TYPE = "org.levimc.launcher.extra.SHORTCUT_INSTANCE_TYPE";
    public static final String EXTRA_INSTANCE_KEY = "org.levimc.launcher.extra.SHORTCUT_INSTANCE_KEY";
    public static final String TYPE_INSTALLED = "installed";
    public static final String TYPE_CUSTOM = "custom";

    private static final String PREFS_NAME = "instance_shortcuts";
    private static final String KEY_NAME_SUFFIX = ".name";
    private static final String KEY_ICON_SUFFIX = ".icon";
    private static final int MAX_ICON_SIZE = 512;

    private InstanceShortcutManager() {
    }

    public static String getShortcutId(GameVersion version) {
        return "instance_" + digest(getInstanceIdentity(version));
    }

    public static boolean isPinned(Context context, GameVersion version) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return false;
        String id = getShortcutId(version);
        List<ShortcutInfo> pinned = manager.getPinnedShortcuts();
        for (ShortcutInfo info : pinned) {
            if (id.equals(info.getId())) return true;
        }
        return false;
    }

    public static String getSavedName(Context context, GameVersion version) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(getShortcutId(version) + KEY_NAME_SUFFIX, defaultShortcutName(version));
    }

    public static String getSavedIconUri(Context context, GameVersion version) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(getShortcutId(version) + KEY_ICON_SUFFIX, null);
    }

    public static void saveSettings(Context context, GameVersion version, String name, String iconUri) {
        String id = getShortcutId(version);
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(id + KEY_NAME_SUFFIX, sanitizeLabel(name, version));
        if (iconUri == null || iconUri.isEmpty()) {
            editor.remove(id + KEY_ICON_SUFFIX);
        } else {
            editor.putString(id + KEY_ICON_SUFFIX, iconUri);
        }
        editor.apply();
    }

    public static boolean createOrUpdate(Activity activity, GameVersion version, String name, String iconUri) {
        ShortcutManager manager = activity.getSystemService(ShortcutManager.class);
        if (manager == null || version == null) return false;
        try {
            ShortcutInfo info = buildShortcut(activity, version, name, iconUri);
            String id = info.getId();
            for (ShortcutInfo pinned : manager.getPinnedShortcuts()) {
                if (id.equals(pinned.getId())) {
                    return manager.updateShortcuts(Collections.singletonList(info));
                }
            }

            if (!manager.isRequestPinShortcutSupported()) return false;
            return manager.requestPinShortcut(info, null);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static ShortcutInfo buildShortcut(Context context, GameVersion version, String name, String iconUri) {
        String label = sanitizeLabel(name, version);
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(ACTION_LAUNCH_INSTANCE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        if (version.isInstalled) {
            intent.putExtra(EXTRA_INSTANCE_TYPE, TYPE_INSTALLED);
            intent.putExtra(EXTRA_INSTANCE_KEY, version.packageName == null ? "" : version.packageName);
        } else {
            intent.putExtra(EXTRA_INSTANCE_TYPE, TYPE_CUSTOM);
            intent.putExtra(EXTRA_INSTANCE_KEY, version.directoryName == null ? "" : version.directoryName);
        }

        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(context, getShortcutId(version))
                .setShortLabel(label)
                .setLongLabel(label)
                .setIntent(intent);

        Bitmap customIcon = loadIconBitmap(context, iconUri);
        if (customIcon != null) {
            builder.setIcon(Icon.createWithAdaptiveBitmap(customIcon));
        } else {
            builder.setIcon(Icon.createWithResource(context, R.drawable.ic_minecraft_cube));
        }
        return builder.build();
    }

    public static Bitmap loadIconBitmap(Context context, String iconUri) {
        if (iconUri == null || iconUri.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(iconUri);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / sample > MAX_ICON_SIZE * 2) sample *= 2;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap decoded;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                decoded = BitmapFactory.decodeStream(in, null, options);
            }
            if (decoded == null) return null;

            int size = Math.min(decoded.getWidth(), decoded.getHeight());
            int x = (decoded.getWidth() - size) / 2;
            int y = (decoded.getHeight() - size) / 2;
            Bitmap square = Bitmap.createBitmap(decoded, x, y, size, size);
            Bitmap scaled = Bitmap.createScaledBitmap(square, MAX_ICON_SIZE, MAX_ICON_SIZE, true);
            if (square != decoded) decoded.recycle();
            if (scaled != square) square.recycle();
            return scaled;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sanitizeLabel(String name, GameVersion version) {
        String label = name == null ? "" : name.trim();
        return label.isEmpty() ? defaultShortcutName(version) : label;
    }

    private static String defaultShortcutName(GameVersion version) {
        if (version == null) return "Minecraft";
        String name = version.displayName == null ? "" : version.displayName.trim();
        if (!name.isEmpty()) {
            int paren = name.lastIndexOf(" (");
            if (paren > 0) name = name.substring(0, paren).trim();
            if (!name.isEmpty()) return name;
        }
        if (version.versionCode != null && !version.versionCode.trim().isEmpty()) return version.versionCode.trim();
        if (version.directoryName != null && !version.directoryName.trim().isEmpty()) return version.directoryName.trim();
        return "Minecraft";
    }

    private static String getInstanceIdentity(GameVersion version) {
        if (version == null) return "missing";
        if (version.isInstalled) return TYPE_INSTALLED + ":" + (version.packageName == null ? "" : version.packageName);
        return TYPE_CUSTOM + ":" + (version.directoryName == null ? "" : version.directoryName);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(24);
            for (int i = 0; i < 12; i++) out.append(String.format("%02x", bytes[i]));
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
