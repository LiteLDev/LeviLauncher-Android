package org.levimc.launcher.core.minecraft;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.util.Locale;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import androidx.core.content.FileProvider;

public final class MinecraftImportIntents {
    private MinecraftImportIntents() {}

    public static boolean isMinecraftResourceIntent(Context context, Intent intent) {
        if (intent == null) return false;

        Uri data = intent.getData();
        if (isMinecraftResourceUri(context, data)) {
            return true;
        }

        ClipData clipData = intent.getClipData();
        if (clipData == null) return false;

        for (int i = 0; i < clipData.getItemCount(); i++) {
            if (isMinecraftResourceUri(context, clipData.getItemAt(i).getUri())) {
                return true;
            }
        }
        return false;
    }

    public static Intent prepareContentImport(Context context, Uri source) throws IOException {
        String name = resolveDisplayName(context, source);
        if (name == null || name.trim().isEmpty()) name = source.getLastPathSegment();
        File staged;
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            staged = MinecraftImportArchive.stage(input, name, context.getCacheDir());
        }
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", staged);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/octet-stream");
        intent.setClipData(ClipData.newRawUri("Minecraft import", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    public static boolean forwardToRunningMinecraft(Context context, Intent originalIntent) {
        if (!MinecraftActivityState.isRunning(context)) {
            return false;
        }

        Intent minecraftIntent = new Intent(originalIntent);
        minecraftIntent.setClass(context, MinecraftActivity.class);
        minecraftIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(context instanceof Activity)) {
            minecraftIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        context.startActivity(minecraftIntent);
        return true;
    }

    private static boolean isMinecraftResourceUri(Context context, Uri uri) {
        if (uri == null) return false;
        return hasMinecraftResourceExtension(uri.getPath())
                || hasMinecraftResourceExtension(uri.getLastPathSegment())
                || hasMinecraftResourceExtension(resolveDisplayName(context, uri));
    }

    private static boolean hasMinecraftResourceExtension(String value) {
        if (value == null) return false;

        String lowerValue = value.toLowerCase(Locale.ROOT);
        return lowerValue.endsWith(".mcworld")
                || lowerValue.endsWith(".mcpack")
                || lowerValue.endsWith(".mcaddon")
                || lowerValue.endsWith(".mctemplate");
    }

    private static String resolveDisplayName(Context context, Uri uri) {
        if (context == null || uri == null) return null;

        try (Cursor cursor = context.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }

            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex < 0) return null;

            return cursor.getString(nameIndex);
        } catch (Exception ignored) {
            return null;
        }
    }
}
