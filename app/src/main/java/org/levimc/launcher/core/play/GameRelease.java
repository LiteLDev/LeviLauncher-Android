package org.levimc.launcher.core.play;

import java.util.Locale;

/** One Minecraft for Android build the download page can list. */
public final class GameRelease {
    public static final String CHANNEL_RELEASE = "Release";
    public static final String CHANNEL_PREVIEW = "Preview";

    public long versionCode;
    public String versionName;
    public String channel;
    public long size;
    /** When Google Play last reported this build as the account's current one (epoch ms), 0 if never. */
    public long seenAt;

    public GameRelease() {
    }

    public GameRelease(long versionCode, String versionName, long size, long seenAt) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.channel = channelOf(versionName);
        this.size = size;
        this.seenAt = seenAt;
    }

    public String displayName() {
        return versionName == null || versionName.isEmpty()
                ? String.valueOf(versionCode)
                : versionName;
    }

    public boolean isPreview() {
        return CHANNEL_PREVIEW.equals(channel);
    }

    /**
     * Bedrock numbers its builds major.minor.patch.build, and gives preview (beta) builds a
     * build number of 20 or more, e.g. 1.21.60.21; release builds stay below it, e.g. 1.21.51.02.
     */
    public static String channelOf(String versionName) {
        if (versionName == null) return CHANNEL_RELEASE;
        String[] parts = versionName.trim().split("\\.");
        if (parts.length >= 4) {
            try {
                if (Integer.parseInt(parts[3]) >= 20) return CHANNEL_PREVIEW;
            } catch (NumberFormatException ignored) {
            }
        }
        return CHANNEL_RELEASE;
    }

    /** Orders by version name numerically, falling back to the version code. */
    public static int compareNewestFirst(GameRelease a, GameRelease b) {
        int byName = compareVersionNames(b.versionName, a.versionName);
        return byName != 0 ? byName : Long.compare(b.versionCode, a.versionCode);
    }

    static int compareVersionNames(String a, String b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            long va = i < pa.length ? parse(pa[i]) : 0;
            long vb = i < pb.length ? parse(pb[i]) : 0;
            if (va != vb) return Long.compare(va, vb);
        }
        return 0;
    }

    private static long parse(String part) {
        try {
            return Long.parseLong(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s (%d)", displayName(), versionCode);
    }
}
