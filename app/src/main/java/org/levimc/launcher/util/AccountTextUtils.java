package org.levimc.launcher.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;

import org.levimc.launcher.R;
import org.levimc.launcher.core.auth.MsftAccountStore;

import java.util.concurrent.ConcurrentHashMap;

public final class AccountTextUtils {
    private static final ConcurrentHashMap<String, Bitmap> AVATAR_CACHE = new ConcurrentHashMap<>();

    private AccountTextUtils() {}

    public static String titleOrUnknown(MsftAccountStore.MsftAccount a) {
        if (a == null) return "Unknown";
        return !TextUtils.isEmpty(a.minecraftUsername) ? a.minecraftUsername : (!TextUtils.isEmpty(a.xboxGamertag) ? a.xboxGamertag : "Unknown");
    }

    public static String subtitle(MsftAccountStore.MsftAccount a) {
        if (a == null) return "";
        if (a.xuid != null && !a.xuid.isEmpty()) return "XUID: " + a.xuid;
        if (a.msUserId != null && !a.msUserId.isEmpty()) return "MS ID: " + a.msUserId;
        return "";
    }

    public static String displayNameOrNotSigned(Context ctx, MsftAccountStore.MsftAccount a) {
        if (a == null) return ctx.getString(R.string.not_signed_in);
        String name = !TextUtils.isEmpty(a.minecraftUsername) ? a.minecraftUsername : a.xboxGamertag;
        return !TextUtils.isEmpty(name) ? name : ctx.getString(R.string.accounts_page_title);
    }

    public static boolean isRecentlyUpdated(MsftAccountStore.MsftAccount a, int days) {
        if (a == null) return false;
        long lastUpdated = a.lastUpdated;
        if (lastUpdated <= 0L) return false;
        long daysMillis = days * 24L * 60L * 60L * 1000L;
        long now = System.currentTimeMillis();
        return (now - lastUpdated) < daysMillis;
    }

    public static String sanitizeUrl(String url) {
        if (url == null) return null;
        String u = url.replace("`", "").trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return null;
        return u;
    }

    public static Bitmap getCachedAvatar(String url) {
        return url != null ? AVATAR_CACHE.get(url) : null;
    }

    public static void cacheAvatar(String url, Bitmap bitmap) {
        if (url != null && bitmap != null) {
            AVATAR_CACHE.put(url, bitmap);
        }
    }
}
