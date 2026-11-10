// Tests of the README renderer of the package page: the URL rules of upstream
// PackagePage.tsx (resolveMarkdownUrl, isBadgeImage,
// isOpenableExternalUrl) and the remark-gfm / rehype-slug / rehype-sanitize
// behaviour the Android renderer reproduces.
package org.levimc.launcher.core.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public class MarkdownTest {
    private static final Readme.Markdown.RepoRef REPO = new Readme.Markdown.RepoRef("LiteLDev", "LHolo");

    @Test
    public void testRepoRef() {
        assertEquals(REPO, Readme.Markdown.repoRef("https://github.com/LiteLDev/LHolo"));
        assertEquals(REPO, Readme.Markdown.repoRef(" https://github.com/LiteLDev/LHolo/tree/main/docs "));
        assertNull(Readme.Markdown.repoRef("https://gitlab.com/LiteLDev/LHolo"));
        assertEquals(REPO, Readme.Markdown.repoRef("https://www.github.com/LiteLDev/LHolo.git"));
        assertNull(Readme.Markdown.repoRef("https://github.com/LiteLDev"));
        assertNull(Readme.Markdown.repoRef(""));
        assertNull(Readme.Markdown.repoRef(null));
        assertNull(Readme.Markdown.repoRef("not a url"));
    }

    @Test
    public void testResolveUrl() {
        assertEquals("", Readme.Markdown.resolveUrl("  ", REPO, false));
        assertEquals("#usage", Readme.Markdown.resolveUrl("#usage", REPO, false));
        assertEquals("https://cdn.example.com/a.png", Readme.Markdown.resolveUrl("//cdn.example.com/a.png", REPO, true));
        assertEquals("https://example.com/x", Readme.Markdown.resolveUrl("https://example.com/x", REPO, false));
        assertEquals("mailto:dev@example.com", Readme.Markdown.resolveUrl("mailto:dev@example.com", REPO, false));
        assertEquals("docs/a.md", Readme.Markdown.resolveUrl("docs/a.md", null, false));
        assertEquals("https://raw.githubusercontent.com/LiteLDev/LHolo/HEAD/docs/logo.png",
                Readme.Markdown.resolveUrl("docs/logo.png", REPO, true));
        assertEquals("https://github.com/LiteLDev/LHolo/blob/HEAD/docs/a.md",
                Readme.Markdown.resolveUrl("/docs/a.md", REPO, false));
        assertEquals("https://github.com/LiteLDev/LHolo/blob/HEAD/b.md?x=1#part",
                Readme.Markdown.resolveUrl("./a/../b.md?x=1#part", REPO, false));
        // as new URL("../LICENSE", "https://github.com/o/r/blob/HEAD/")
        assertEquals("https://github.com/LiteLDev/LHolo/blob/LICENSE",
                Readme.Markdown.resolveUrl("../LICENSE", REPO, false));
        assertEquals("https://raw.githubusercontent.com/LiteLDev/LHolo/HEAD/img/a%20b.png",
                Readme.Markdown.resolveUrl("img/a b.png", REPO, true));
        assertEquals("https://raw.githubusercontent.com/LiteLDev/LHolo/HEAD/%E5%9B%BE.png",
                Readme.Markdown.resolveUrl("图.png", REPO, true));
    }

    @Test
    public void testExternalAndBadges() {
        assertTrue(Readme.Markdown.isOpenableExternal("https://github.com/LiteLDev/LHolo"));
        assertTrue(Readme.Markdown.isOpenableExternal("http://example.com"));
        assertTrue(Readme.Markdown.isOpenableExternal("mailto:dev@example.com"));
        assertFalse(Readme.Markdown.isOpenableExternal("javascript:alert(1)"));
        assertFalse(Readme.Markdown.isOpenableExternal("file:///sdcard/x"));
        assertFalse(Readme.Markdown.isOpenableExternal("intent://x#Intent;end"));
        assertFalse(Readme.Markdown.isOpenableExternal("docs/a.md"));
        assertFalse(Readme.Markdown.isOpenableExternal(null));
        assertTrue(Readme.Markdown.isBadge("https://img.shields.io/badge/a-b-c"));
        assertFalse(Readme.Markdown.isBadge("https://example.com/img.shields.io.png"));
    }

    @Test
    public void testSlugs() {
        assertEquals("hello-world", Readme.Markdown.slug("Hello World!"));
        assertEquals("api-v20", Readme.Markdown.slug("API v2.0"));
        assertEquals("a--b", Readme.Markdown.slug("A & B"));
        assertEquals("中文-标题", Readme.Markdown.slug("中文 标题"));
        assertEquals("snake_case", Readme.Markdown.slug("snake_case"));
    }

    @Test
    public void testGfmRendering() {
        String html = Readme.Markdown.toHtml("# Title\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n~~old~~ see https://levimc.org\n",
                REPO);
        assertTrue(html, html.contains("<h1 id=\"title\">Title</h1>"));
        assertTrue(html, html.contains("<table>"));
        assertTrue(html, html.contains("<td>1</td>"));
        assertTrue(html, html.contains("<del>old</del>"));
        assertTrue(html, html.contains("<a href=\"https://levimc.org\">https://levimc.org</a>"));
    }

    @Test
    public void testHeadingIdsAndAnchors() {
        String md = "## Usage\n\n## Usage\n\n### Config `file`\n\n[a](#Usage) [b](#usage-1) [c](#config-file)"
                + " [d](#user-content-usage) [e](#missing)\n";
        String html = Readme.Markdown.toHtml(md, REPO);
        assertTrue(html, html.contains("<h2 id=\"usage\">"));
        assertTrue(html, html.contains("<h2 id=\"usage-1\">"));
        assertTrue(html, html.contains("<h3 id=\"config-file\">"));
        assertTrue(html, html.contains("<a href=\"#usage\">a</a>"));
        assertTrue(html, html.contains("<a href=\"#usage-1\">b</a>"));
        assertTrue(html, html.contains("<a href=\"#config-file\">c</a>"));
        assertTrue(html, html.contains("<a href=\"#usage\">d</a>"));
        assertTrue(html, html.contains("<a href=\"#missing\">e</a>"));
    }

    @Test
    public void testRelativeLinksAndImages() {
        String html = Readme.Markdown.toHtml("![logo](assets/logo.png) [docs](docs/README.md)"
                + " ![badge](https://img.shields.io/badge/x-y-z)\n", REPO);
        assertTrue(html, html.contains(
                "src=\"https://raw.githubusercontent.com/LiteLDev/LHolo/HEAD/assets/logo.png\""));
        assertTrue(html, html.contains("href=\"https://github.com/LiteLDev/LHolo/blob/HEAD/docs/README.md\""));
        assertTrue(html, html.contains("class=\"badge\""));
        assertTrue(html, html.contains("loading=\"lazy\""));
    }

    @Test
    public void testUnsafeUrlsDropped() {
        String html = Readme.Markdown.toHtml("[x](javascript:alert(1)) ![y](data:image/png;base64,AAA)\n", REPO);
        assertFalse(html, html.contains("javascript:"));
        assertFalse(html, html.contains("data:"));
        assertTrue(html, html.contains("<a>x</a>"));
    }

    @Test
    public void testRawHtmlSanitized() {
        String md = "<p align=\"center\"><img src=\"logo.png\" width=\"128\" onerror=\"alert(1)\"></p>\n\n"
                + "<script>alert(1)</script>\n\n"
                + "<iframe src=\"https://evil.example\"></iframe>\n\n"
                + "Text <a href=\"docs/a.md\" onclick=\"x()\">link</a> and <a href=\"javascript:x()\">bad</a>.\n\n"
                + "<div style=\"background:url(https://t.example/p)\"><a name=\"anchor\"></a></div>\n\n"
                + "[jump](#anchor)\n";
        String html = Readme.Markdown.toHtml(md, REPO);
        assertTrue(html, html.contains("<p align=\"center\">"));
        assertTrue(html, html.contains(
                "<img src=\"https://raw.githubusercontent.com/LiteLDev/LHolo/HEAD/logo.png\" width=\"128\">"));
        assertFalse(html, html.contains("onerror"));
        assertFalse(html, html.contains("onclick"));
        assertFalse(html, html.contains("script"));
        assertFalse(html, html.contains("alert(1)"));
        assertFalse(html, html.contains("iframe"));
        assertFalse(html, html.contains("evil.example"));
        assertFalse(html, html.contains("javascript"));
        assertFalse(html, html.contains("t.example"));
        assertTrue(html, html.contains("<a href=\"https://github.com/LiteLDev/LHolo/blob/HEAD/docs/a.md\">link</a>"));
        assertTrue(html, html.contains("<a>bad</a>"));
        assertTrue(html, html.contains("<a href=\"#anchor\">jump</a>"));
    }

    @Test
    public void testSanitizeHtmlFragment() {
        assertEquals("<img src=\"https://img.shields.io/x\" class=\"badge\" />",
                Readme.Markdown.sanitizeHtml("<IMG SRC='https://img.shields.io/x' />", REPO, Collections.emptySet()));
        assertEquals("<b>bold</b>", Readme.Markdown.sanitizeHtml("<b>bold</b>", REPO, Collections.emptySet()));
        assertEquals("", Readme.Markdown.sanitizeHtml("<style>body{}</style>", REPO, Collections.emptySet()));
        assertEquals("<a href=\"https://a.example/?a=1&amp;b=2\">x</a>",
                Readme.Markdown.sanitizeHtml("<a href=\"https://a.example/?a=1&amp;b=2\">x</a>", null,
                        Collections.emptySet()));
    }

    @Test
    public void testDocumentTheme() {
        String doc = Readme.Markdown.document("<p>x</p>",
                new Readme.Markdown.Theme("#111111", "#222222", "#333333", "#444444", "#555555"));
        assertTrue(doc.startsWith("<!DOCTYPE html>"));
        assertTrue(doc.contains("color:#111111"));
        assertTrue(doc.contains("a{color:#333333"));
        assertTrue(doc.contains("background:#444444"));
        assertTrue(doc.contains("<body><p>x</p></body>"));
    }
}
