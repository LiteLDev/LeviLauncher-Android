package org.levimc.launcher.core.modcatalog;

import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.packages.engine.PackageEngine;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import okhttp3.HttpUrl;

/**
 * External mods are hosted on GitHub (release assets, raw icons), so their downloads go through the GitHub proxy
 * that LeviMC Mods use (the package settings' GitHub proxy), with a direct fallback.
 */
public final class GithubUrls {
    private static final String GITHUB_ORIGIN = "https://github.com";
    /**
     * Other GitHub hosts: a full-URL proxy ("https://gh-proxy.org/https://github.com") takes any of their URLs
     * after its own base ("https://gh-proxy.org/https://raw.githubusercontent.com/o/r/..."). A prefix proxy only
     * rewrites github.com.
     */
    private static final Set<String> CONTENT_HOSTS = new HashSet<>(Arrays.asList(
            "raw.githubusercontent.com",
            "gist.githubusercontent.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "codeload.github.com"));

    private GithubUrls() {
    }

    /** The GitHub proxy package operations use (the configured one, else the mirror default); null for none. */
    public static String currentProxy() {
        try {
            return PackageService.get().effectiveGithubProxy();
        } catch (RuntimeException e) {
            PackageService.warn("external mods: cannot read the GitHub proxy", e);
            return null;
        }
    }

    /** {@code url} through {@code proxy} when it is a GitHub URL the proxy can reach; else null. */
    public static String proxied(String proxy, String url) {
        if (proxy == null || proxy.trim().isEmpty() || url == null) return null;
        HttpUrl u = HttpUrl.parse(url);
        if (u == null || !u.isHttps()) return null;
        String host = u.host().toLowerCase(Locale.ROOT);
        if (host.equals("github.com")) return PackageEngine.githubProxied(proxy.trim(), url);
        if (!CONTENT_HOSTS.contains(host)) return null;
        String base = proxy.trim().replaceAll("/+$", "");
        if (!base.endsWith("/" + GITHUB_ORIGIN)) return null;
        return base.substring(0, base.length() - GITHUB_ORIGIN.length()) + url;
    }

    /** The URLs to try for {@code url}, in order: through {@code proxy} when it applies, then directly. */
    public static List<String> candidates(String proxy, String url) {
        String via = proxied(proxy, url);
        return via == null ? Collections.singletonList(url) : Arrays.asList(via, url);
    }
}
