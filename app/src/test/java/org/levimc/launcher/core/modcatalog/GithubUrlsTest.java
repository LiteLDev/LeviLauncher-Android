package org.levimc.launcher.core.modcatalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class GithubUrlsTest {
    private static final String GH_PROXY = "https://gh-proxy.org/https://github.com";
    private static final String ASSET = "https://github.com/QYCottage/4DSkin-Release/releases/download/v2.0.1/4DSkin.levipack";
    private static final String ICON = "https://raw.githubusercontent.com/QYCottage/4DSkin-Release/v2.0.1/assets/icon.png";

    @Test
    public void githubUrlsGoThroughAFullUrlProxy() {
        assertEquals("https://gh-proxy.org/" + ASSET, GithubUrls.proxied(GH_PROXY, ASSET));
        assertEquals("https://gh-proxy.org/" + ICON, GithubUrls.proxied(GH_PROXY, ICON));
        assertEquals("https://gh-proxy.org/" + ICON, GithubUrls.proxied(GH_PROXY + "/", ICON));
        assertEquals("https://gh-proxy.org/https://objects.githubusercontent.com/x",
                GithubUrls.proxied(GH_PROXY, "https://objects.githubusercontent.com/x"));
    }

    @Test
    public void prefixProxiesOnlyRewriteGithubCom() {
        String proxy = "https://github.bibk.top";
        assertEquals("https://github.bibk.top/QYCottage/4DSkin-Release/releases/download/v2.0.1/4DSkin.levipack",
                GithubUrls.proxied(proxy, ASSET));
        assertNull(GithubUrls.proxied(proxy, ICON));
    }

    @Test
    public void otherUrlsAndNoProxyAreDirect() {
        assertNull(GithubUrls.proxied(null, ASSET));
        assertNull(GithubUrls.proxied("", ASSET));
        assertNull(GithubUrls.proxied(GH_PROXY, "https://www.mediafire.com/file/x/mod.zip"));
        assertNull(GithubUrls.proxied(GH_PROXY, "https://qycottage.github.io/LeviModHub/catalog.json"));
        assertNull(GithubUrls.proxied(GH_PROXY, "http://raw.githubusercontent.com/o/r/x.png"));
        assertNull(GithubUrls.proxied(GH_PROXY, "not a url"));
        assertNull(GithubUrls.proxied(GH_PROXY, null));
    }

    @Test
    public void candidatesTryTheProxyThenDirect() {
        assertEquals(Arrays.asList("https://gh-proxy.org/" + ASSET, ASSET), GithubUrls.candidates(GH_PROXY, ASSET));
        assertEquals(Collections.singletonList(ASSET), GithubUrls.candidates(null, ASSET));
    }
}
