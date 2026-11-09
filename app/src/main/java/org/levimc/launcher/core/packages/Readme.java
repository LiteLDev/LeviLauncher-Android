package org.levimc.launcher.core.packages;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.packages.engine.PackageEngine;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

public final class Readme {
    public static final long FETCH_TIMEOUT_MS = 20_000;

    public static final class Entry {
        public final String file;
        public final String lang;
        public final String text;

        public Entry(String file, String lang, String text) {
            this.file = file == null ? "" : file;
            this.lang = lang == null ? "" : lang;
            this.text = text == null ? "" : text;
        }
    }

    private Readme() {
    }

    /**
     * The README of a catalogue package for locale: the catalogue's copy for the locale, else the repository's
     * README on GitHub (through the GitHub proxy); "" when there is none.
     */
    public static String forPackage(Cancel c, String identifier, String projectUrl, Locale locale) throws PackageService.Failure {
        List<Entry> entries = Collections.emptyList();
        if (!PackageService.trim(identifier).isEmpty()) {
            try {
                entries = Catalog.get().readmes(c, identifier.trim());
            } catch (PackageService.Failure e) {
                if (e.code.equals(PackageService.ERR_PKG_TASK_CANCELLED)) throw e;
                PackageService.warn("readme: catalogue detail of " + identifier + " unavailable: " + e.getMessage());
            }
        }
        return fetch(c, projectUrl, entries, locale == null ? Locale.getDefault() : locale);
    }

    static String fetch(Cancel c, String projectUrl, List<Entry> readmes, Locale locale) throws PackageService.Failure {
        Entry picked = pick(readmes, locale);
        if (picked != null && !PackageService.trim(picked.text).isEmpty()) {
            return picked.text;
        }
        return fetchFromGithub(c, projectUrl);
    }

    public static Entry pick(List<Entry> readmes, Locale locale) {
        if (readmes == null || readmes.isEmpty()) {
            return null;
        }
        List<Entry> withText = new ArrayList<>();
        for (Entry e : readmes) {
            if (e != null && !PackageService.trim(e.text).isEmpty()) {
                withText.add(e);
            }
        }
        if (withText.isEmpty()) {
            return null;
        }
        Locale l = locale == null ? Locale.ROOT : locale;
        String language = l.getLanguage();
        String country = l.getCountry();
        String script = l.getScript();
        if (script.isEmpty() && language.equals("zh")) {
            script = country.equals("TW") || country.equals("HK") || country.equals("MO") ? "Hant" : "Hans";
        }
        List<String> wanted = new ArrayList<>();
        String tag = l.toLanguageTag();
        if (!tag.isEmpty() && !tag.equals("und")) {
            wanted.add(tag);
        }
        if (!language.isEmpty()) {
            if (!script.isEmpty()) {
                wanted.add(language + "-" + script);
            }
            if (!country.isEmpty()) {
                wanted.add(language + "-" + country);
            }
            wanted.add(language);
        }
        for (String w : wanted) {
            Entry e = findLang(withText, w, false);
            if (e != null) {
                return e;
            }
        }
        if (!language.isEmpty()) {
            Entry e = findLang(withText, language, true);
            if (e != null) {
                return e;
            }
        }
        Entry en = findLang(withText, "en", false);
        if (en == null) {
            en = findLang(withText, "en", true);
        }
        return en != null ? en : withText.get(0);
    }

    private static Entry findLang(List<Entry> entries, String tag, boolean primaryOnly) {
        for (Entry e : entries) {
            String lang = PackageService.trim(e.lang).replace('_', '-');
            if (primaryOnly) {
                int dash = lang.indexOf('-');
                lang = dash >= 0 ? lang.substring(0, dash) : lang;
            }
            if (lang.equalsIgnoreCase(tag)) {
                return e;
            }
        }
        return null;
    }

    /** The README URLs of a GitHub project (default branch), through the GitHub proxy when one is set. */
    static List<String> candidates(String projectUrl) {
        List<String> out = new ArrayList<>();
        Markdown.RepoRef repo = Markdown.repoRef(projectUrl);
        if (repo == null) {
            return out;
        }
        String proxy = PackageService.trim(PackageService.get().effectiveGithubProxy());
        for (String file : new String[]{"README.md", "readme.md"}) {
            String github = "https://github.com/" + repo + "/raw/HEAD/" + file;
            String via = proxy.isEmpty() ? null : PackageEngine.githubProxied(proxy, github);
            out.add(via != null ? via : "https://raw.githubusercontent.com/" + repo + "/HEAD/" + file);
        }
        return out;
    }

    static String fetchFromGithub(Cancel parent, String projectUrl) throws PackageService.Failure {
        Cancel c = PackageService.child(parent, FETCH_TIMEOUT_MS);
        String last = null;
        for (String url : candidates(projectUrl)) {
            PackageService.check(parent);
            try {
                String body = Catalog.httpGet(c, url, null, FETCH_TIMEOUT_MS, r -> {
                    String text = Catalog.text(r);
                    if (text == null) throw new IOException("HTTP " + r.code());
                    return text;
                });
                return PackageService.trim(body).isEmpty() ? "" : body;
            } catch (Exception e) {
                last = PackageService.message(e);
            }
        }
        PackageService.check(parent);
        if (last != null) {
            throw new PackageService.Failure(PackageService.ERR_PKG_INDEX_UNAVAILABLE, last, null);
        }
        return "";
    }

    public final static class Markdown {
        private Markdown() {
        }

        public static final class RepoRef {
            public final String owner;
            public final String repo;

            public RepoRef(String owner, String repo) {
                this.owner = owner;
                this.repo = repo;
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof RepoRef)) {
                    return false;
                }
                RepoRef r = (RepoRef) o;
                return owner.equals(r.owner) && repo.equals(r.repo);
            }

            @Override
            public int hashCode() {
                return owner.hashCode() * 31 + repo.hashCode();
            }

            @Override
            public String toString() {
                return owner + "/" + repo;
            }
        }

        private static final List<Extension> EXTENSIONS = Collections.unmodifiableList(Arrays.asList(
                TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create()));
        private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z\\d+.-]*:");
        private static final Pattern BADGE = Pattern.compile("(?:^|//)img\\.shields\\.io/", Pattern.CASE_INSENSITIVE);
        private static final Set<String> DROPPED_TAGS = new HashSet<>(Arrays.asList(
                "script", "style", "iframe", "frame", "frameset", "object", "embed", "applet", "form", "input",
                "button", "textarea", "select", "option", "meta", "link", "base", "noscript", "template", "svg",
                "math", "audio", "video", "source", "track", "portal", "dialog"));
        private static final Set<String> URL_ATTRS = new HashSet<>(Arrays.asList("href", "src"));
        private static final Pattern BLOCK_WITH_CONTENT = Pattern.compile(
                "<(script|style|noscript|template|svg|math|iframe|object|textarea|select)\\b[^>]*>.*?</\\1\\s*>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\"[^\"]*\"|'[^']*'|[^'\">])*)>");
        private static final Pattern ATTR = Pattern.compile(
                "([^\\s\"'>/=]+)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+))?");
        private static final Pattern ID_ATTR = Pattern.compile("\\b(?:id|name)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
                Pattern.CASE_INSENSITIVE);

        // #region URLs

        /** Owner and repository of a github.com project URL; null for another URL. */
        public static RepoRef repoRef(String projectUrl) {
            HttpUrl u = HttpUrl.parse(projectUrl == null ? "" : projectUrl.trim());
            if (u == null || !(u.host().equals("github.com") || u.host().equals("www.github.com"))) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            for (String p : u.pathSegments()) if (!p.isEmpty()) parts.add(p);
            if (parts.size() < 2) {
                return null;
            }
            String repo = parts.get(1).endsWith(".git") ? parts.get(1).substring(0, parts.get(1).length() - 4) : parts.get(1);
            return repo.isEmpty() ? null : new RepoRef(parts.get(0), repo);
        }

        public static String resolveUrl(String rawUrl, RepoRef repo, boolean image) {
            String raw = rawUrl == null ? "" : rawUrl.trim();
            if (raw.isEmpty()) {
                return "";
            }
            if (raw.startsWith("#")) {
                return raw;
            }
            if (raw.startsWith("//")) {
                return "https:" + raw;
            }
            if (SCHEME.matcher(raw).find()) {
                return raw;
            }
            if (repo == null) {
                return raw;
            }
            String base = image
                    ? "https://raw.githubusercontent.com/" + repo.owner + "/" + repo.repo + "/HEAD/"
                    : "https://github.com/" + repo.owner + "/" + repo.repo + "/blob/HEAD/";
            HttpUrl resolved = HttpUrl.get(base).resolve(raw.startsWith("/") ? raw.substring(1) : raw);
            return resolved == null ? "" : resolved.toString();
        }

        public static boolean isOpenableExternal(String url) {
            return url != null && (HttpUrl.parse(url.trim()) != null || url.trim().regionMatches(true, 0, "mailto:", 0, 7));
        }

        static boolean isBadge(String src) {
            return src != null && BADGE.matcher(src).find();
        }

        static boolean isSafeUrl(String url) {
            String u = url.trim();
            if (u.isEmpty() || u.startsWith("#")) {
                return true;
            }
            Matcher m = SCHEME.matcher(u);
            if (!m.find()) {
                return true;
            }
            String scheme = m.group().toLowerCase(Locale.ROOT);
            return scheme.equals("http:") || scheme.equals("https:") || scheme.equals("mailto:");
        }

        // #endregion

        // #region Slugs

        public static String slug(String heading) {
            String lower = heading == null ? "" : heading.toLowerCase(Locale.ROOT);
            StringBuilder b = new StringBuilder();
            lower.codePoints().forEach(cp -> {
                int type = Character.getType(cp);
                if (Character.isLetterOrDigit(cp) || type == Character.NON_SPACING_MARK
                        || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK
                        || cp == '-' || cp == '_') {
                    b.appendCodePoint(cp);
                } else if (cp == ' ') {
                    b.append('-');
                }
            });
            return b.toString();
        }

        private static final class Slugger {
            private final Map<String, Integer> seen = new HashMap<>();

            String next(String text) {
                String base = slug(text);
                String s = base;
                Integer n = seen.get(base);
                if (n != null) {
                    int i = n;
                    do {
                        i++;
                        s = base + "-" + i;
                    } while (seen.containsKey(s));
                    seen.put(base, i);
                }
                seen.put(s, 0);
                return s;
            }
        }

        // #endregion

        // #region Rendering

        public static String toHtml(String markdown, RepoRef repo) {
            Parser parser = Parser.builder().extensions(EXTENSIONS).build();
            Node doc = parser.parse(markdown == null ? "" : markdown);

            Map<Node, String> headingIds = new IdentityHashMap<>();
            Set<String> ids = new LinkedHashSet<>();
            Slugger slugger = new Slugger();
            doc.accept(new AbstractVisitor() {
                @Override
                public void visit(Heading heading) {
                    String id = slugger.next(textOf(heading));
                    headingIds.put(heading, id);
                    ids.add(id);
                    visitChildren(heading);
                }

                @Override
                public void visit(HtmlBlock block) {
                    collectIds(block.getLiteral(), ids);
                }

                @Override
                public void visit(HtmlInline inline) {
                    collectIds(inline.getLiteral(), ids);
                }
            });

            HtmlRenderer renderer = HtmlRenderer.builder()
                    .extensions(EXTENSIONS)
                    .escapeHtml(false)
                    .sanitizeUrls(true)
                    .attributeProviderFactory(context -> (node, tagName, attributes) -> {
                        if (node instanceof Heading) {
                            String id = headingIds.get(node);
                            if (id != null && !id.isEmpty()) {
                                attributes.put("id", id);
                            }
                        } else if (node instanceof Link && "a".equals(tagName)) {
                            // sanitizeUrls adds rel=nofollow; links never navigate the WebView, so it means nothing.
                            attributes.remove("rel");
                            String href = rewriteUrl(((Link) node).getDestination(), repo, false, ids);
                            if (href.isEmpty()) {
                                attributes.remove("href");
                            } else {
                                attributes.put("href", href);
                            }
                        } else if (node instanceof Image && "img".equals(tagName)) {
                            String src = rewriteUrl(((Image) node).getDestination(), repo, true, ids);
                            if (src.isEmpty()) {
                                attributes.remove("src");
                            } else {
                                attributes.put("src", src);
                            }
                            attributes.put("loading", "lazy");
                            if (isBadge(src)) {
                                attributes.put("class", "badge");
                            }
                        }
                    })
                    .nodeRendererFactory(context -> new RawHtmlRenderer(context, repo, ids))
                    .build();
            return renderer.render(doc);
        }

        static String rewriteUrl(String raw, RepoRef repo, boolean image, Set<String> ids) {
            String resolved = resolveUrl(raw, repo, image);
            if (resolved.isEmpty() || !isSafeUrl(resolved)) {
                return "";
            }
            if (resolved.startsWith("#")) {
                return "#" + fixAnchor(resolved, ids);
            }
            return resolved;
        }

        static String fixAnchor(String href, Set<String> ids) {
            String anchor = decodeHash(href);
            if (anchor.isEmpty()) {
                return "";
            }
            for (String c : new String[]{anchor, anchor.toLowerCase(Locale.ROOT), slug(anchor)}) {
                if (ids.contains(c)) {
                    return c;
                }
                if (c.startsWith("user-content-") && ids.contains(c.substring("user-content-".length()))) {
                    return c.substring("user-content-".length());
                }
            }
            return anchor;
        }

        private static String decodeHash(String href) {
            String raw = href.startsWith("#") ? href.substring(1) : href;
            try {
                return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").trim();
            } catch (Exception e) {
                return raw.trim();
            }
        }

        private static void collectIds(String html, Set<String> ids) {
            if (html == null) {
                return;
            }
            Matcher m = ID_ATTR.matcher(html);
            while (m.find()) {
                String v = m.group(1) != null ? m.group(1) : m.group(2);
                if (v != null && !v.trim().isEmpty()) {
                    ids.add(v.trim());
                }
            }
        }

        private static String textOf(Node node) {
            StringBuilder b = new StringBuilder();
            node.accept(new AbstractVisitor() {
                @Override
                public void visit(Text text) {
                    b.append(text.getLiteral());
                }

                @Override
                public void visit(Code code) {
                    b.append(code.getLiteral());
                }
            });
            return b.toString();
        }

        private static final class RawHtmlRenderer implements NodeRenderer {
            private final HtmlNodeRendererContext context;
            private final RepoRef repo;
            private final Set<String> ids;

            RawHtmlRenderer(HtmlNodeRendererContext context, RepoRef repo, Set<String> ids) {
                this.context = context;
                this.repo = repo;
                this.ids = ids;
            }

            @Override
            public Set<Class<? extends Node>> getNodeTypes() {
                return new HashSet<>(Arrays.asList(HtmlBlock.class, HtmlInline.class));
            }

            @Override
            public void render(Node node) {
                if (node instanceof HtmlBlock) {
                    context.getWriter().line();
                    context.getWriter().raw(sanitizeHtml(((HtmlBlock) node).getLiteral(), repo, ids));
                    context.getWriter().line();
                } else {
                    context.getWriter().raw(sanitizeHtml(((HtmlInline) node).getLiteral(), repo, ids));
                }
            }
        }

        public static String sanitizeHtml(String html, RepoRef repo, Set<String> ids) {
            if (html == null || html.isEmpty()) {
                return "";
            }
            Set<String> known = ids == null ? Collections.emptySet() : ids;
            String s = BLOCK_WITH_CONTENT.matcher(html).replaceAll("");
            Matcher m = TAG.matcher(s);
            StringBuilder out = new StringBuilder();
            int last = 0;
            while (m.find()) {
                out.append(s, last, m.start());
                last = m.end();
                String closing = m.group(1);
                String name = m.group(2).toLowerCase(Locale.ROOT);
                if (DROPPED_TAGS.contains(name)) {
                    continue;
                }
                if (!closing.isEmpty()) {
                    out.append("</").append(name).append('>');
                    continue;
                }
                String attrs = m.group(3);
                boolean selfClosing = attrs.trim().endsWith("/");
                out.append('<').append(name);
                boolean isImg = name.equals("img");
                String src = null;
                Matcher a = ATTR.matcher(selfClosing ? attrs.substring(0, attrs.lastIndexOf('/')) : attrs);
                while (a.find()) {
                    String attr = a.group(1).toLowerCase(Locale.ROOT);
                    String value = a.group(2);
                    if (attr.startsWith("on") || attr.equals("style") && value != null
                            && value.toLowerCase(Locale.ROOT).contains("url(") || attr.equals("srcset")
                            || attr.equals("formaction") || attr.equals("xmlns") || attr.startsWith("xlink")) {
                        continue;
                    }
                    if (value != null && (value.startsWith("\"") || value.startsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (URL_ATTRS.contains(attr)) {
                        String rewritten = rewriteUrl(unescapeAttr(value == null ? "" : value), repo,
                                isImg && attr.equals("src"), known);
                        if (rewritten.isEmpty()) {
                            continue;
                        }
                        value = rewritten;
                        if (isImg) {
                            src = rewritten;
                        }
                    }
                    out.append(' ').append(attr);
                    if (value != null) {
                        out.append("=\"").append(escapeAttr(value)).append('"');
                    }
                }
                if (isImg && isBadge(src)) {
                    out.append(" class=\"badge\"");
                }
                out.append(selfClosing ? " />" : ">");
            }
            out.append(s.substring(last));
            return out.toString();
        }

        private static String unescapeAttr(String v) {
            return v.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&amp;", "&");
        }

        private static String escapeAttr(String v) {
            return v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
        }

        // #endregion

        // #region Document

        public static final class Theme {
            public final String text;
            public final String secondaryText;
            public final String link;
            public final String codeBackground;
            public final String border;

            public Theme(String text, String secondaryText, String link, String codeBackground, String border) {
                this.text = text;
                this.secondaryText = secondaryText;
                this.link = link;
                this.codeBackground = codeBackground;
                this.border = border;
            }
        }

        public static String document(String body, Theme theme) {
            return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<style>"
                    + "html,body{background:transparent;margin:0;padding:0}"
                    + "body{color:" + theme.text + ";font-family:sans-serif;font-size:13px;line-height:1.6;"
                    + "overflow-wrap:anywhere;word-break:break-word;padding:2px 2px 16px}"
                    + "a{color:" + theme.link + ";text-decoration:none}"
                    + "h1,h2,h3,h4,h5,h6{line-height:1.3;margin:18px 0 8px}"
                    + "h1{font-size:1.6em}h2{font-size:1.35em;padding-bottom:4px;border-bottom:1px solid "
                    + theme.border + "}h3{font-size:1.15em}h4,h5,h6{font-size:1em}"
                    + "p{margin:8px 0}ul,ol{padding-left:22px}li{margin:3px 0}"
                    + "img{max-width:100%;height:auto;vertical-align:middle;border-radius:8px}"
                    + "img.badge{border-radius:0;margin:0}"
                    + "code{font-family:monospace;font-size:.92em;background:" + theme.codeBackground
                    + ";padding:1px 4px;border-radius:4px}"
                    + "pre{background:" + theme.codeBackground + ";padding:10px;border-radius:8px;overflow-x:auto;"
                    + "white-space:pre}pre code{background:transparent;padding:0}"
                    + "blockquote{margin:8px 0;padding:2px 12px;border-left:3px solid " + theme.border + ";color:"
                    + theme.secondaryText + "}"
                    + "table{display:block;max-width:100%;overflow-x:auto;border-collapse:collapse;margin:8px 0}"
                    + "th,td{border:1px solid " + theme.border + ";padding:5px 9px}"
                    + "hr{border:0;border-top:1px solid " + theme.border + ";margin:16px 0}"
                    + "del{opacity:.8}"
                    + "</style></head><body>" + body + "</body></html>";
        }

        // #endregion
    }
}
