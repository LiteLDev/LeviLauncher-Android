package org.levimc.launcher.core.modcatalog;

import com.google.gson.annotations.SerializedName;

import org.levimc.launcher.core.mods.ModNativeLoader;

import java.util.ArrayList;
import java.util.List;

public final class ModCatalog {
    @SerializedName("schema_version")
    public int schemaVersion;
    public List<CatalogMod> mods = new ArrayList<>();
    /** The GitHub proxy when the catalogue was loaded (for its GitHub-hosted icons); null for none. */
    public transient String githubProxy;

    public static final class CatalogMod {
        public String id;
        public String name;
        public String author;
        public String description;
        @SerializedName("icon_url")
        public String iconUrl;
        @SerializedName("homepage_url")
        public String homepageUrl;
        public List<String> tags = new ArrayList<>();
        public List<CatalogRelease> releases = new ArrayList<>();

        public CatalogRelease compatibleRelease(String minecraftVersion) {
            if (releases == null || releases.isEmpty()) return null;
            CatalogRelease newest = null;
            for (CatalogRelease release : releases) {
                if (release == null || !release.supports(minecraftVersion)) continue;
                if (newest == null || comparePublishedAt(release, newest) > 0) {
                    newest = release;
                }
            }
            return newest;
        }

        public CatalogRelease latestRelease() {
            if (releases == null || releases.isEmpty()) return null;
            CatalogRelease newest = null;
            for (CatalogRelease release : releases) {
                if (release == null) continue;
                if (newest == null || comparePublishedAt(release, newest) > 0) {
                    newest = release;
                }
            }
            return newest;
        }

        private static int comparePublishedAt(CatalogRelease first, CatalogRelease second) {
            String firstPublishedAt = first == null || first.publishedAt == null
                    ? "" : first.publishedAt;
            String secondPublishedAt = second == null || second.publishedAt == null
                    ? "" : second.publishedAt;
            return firstPublishedAt.compareTo(secondPublishedAt);
        }
    }

    public static final class CatalogRelease {
        public String version;
        @SerializedName("minecraft_versions")
        public List<String> minecraftVersions = new ArrayList<>();
        @SerializedName("download_type")
        public String downloadType;
        @SerializedName("download_url")
        public String downloadUrl;
        public List<CatalogAsset> assets = new ArrayList<>();
        @SerializedName("published_at")
        public String publishedAt;

        public boolean supports(String minecraftVersion) {
            return ModNativeLoader.isCompatibleWithMinecraftVersion(minecraftVersions, minecraftVersion);
        }

        public boolean opensInBrowser() {
            return "browser".equalsIgnoreCase(downloadType)
                    || "ad".equalsIgnoreCase(downloadType);
        }

        public boolean isAdDownload() {
            return "ad".equalsIgnoreCase(downloadType);
        }

        public List<CatalogAsset> directAssets() {
            return assets == null ? new ArrayList<>() : assets;
        }
    }

    public static final class CatalogAsset {
        public String name;
        public String label;
        @SerializedName("download_url")
        public String downloadUrl;
        public long size;
        public String sha256;
    }
}
