package org.levimc.launcher.core.packages.engine;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A source (package 2): a remote repository {@code repo[#variant][@ref]}, a URL
 * {@code https://...[#variant]} or a local path {@code path[#variant]}. Local paths are absolute paths
 * or {@code file:} URIs (the app passes nothing else); a local source is written as the {@code file:}
 * URI of its absolute path (cli 3).
 */
public final class Source {
    enum Kind { REPO, URL, LOCAL }

    private static final Pattern VARIANT = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)?");
    private static final Pattern COMMIT = Pattern.compile("[0-9A-Fa-f]{40}");

    final Kind kind;
    /** The module path, the https URL, or the absolute local path. */
    final String location;
    /** The variant of the source, "" without '#'. */
    final String variant;
    /** For a repository with a ref, exactly one of these is set; {@link #rev} is lowercase. */
    final SemVer version;
    final String branch, tag, rev;
    private final String text;

    private Source(Kind kind, String location, String variant, String ref) throws IllegalArgumentException {
        this.kind = kind;
        this.location = location;
        this.variant = variant;
        String v = null, b = null, t = null, r = null;
        if (ref != null) {
            if (ref.startsWith("branch:") || ref.startsWith("tag:")) {
                String name = ref.substring(ref.indexOf(':') + 1);
                if (!isRefName(name)) throw new IllegalArgumentException("'" + name + "' is not a valid git ref name");
                if (ref.startsWith("tag:")) t = name;
                else b = name;
            } else if (ref.startsWith("rev:")) {
                r = ref.substring(4);
                if (!COMMIT.matcher(r).matches()) throw new IllegalArgumentException("'" + r + "' is not a commit id");
                r = r.toLowerCase(Locale.ROOT);
            } else if (SemVer.parse(ref) == null) {
                throw new IllegalArgumentException("'" + ref + "' is not a version, branch:, tag: or rev: ref");
            } else {
                v = ref;
            }
        }
        version = v == null ? null : SemVer.parse(v);
        branch = b;
        tag = t;
        rev = r;
        String loc = kind == Kind.LOCAL ? fileUri(location) : location;
        text = loc + (variant.isEmpty() ? "" : "#" + variant) + (ref == null ? "" : "@" + ref);
    }

    /** Parses a source; failures are {@code INVALID_ARGUMENT} naming {@code s}. */
    static Source parse(String s) throws PackageException {
        try {
            return of(s);
        } catch (IllegalArgumentException e) {
            throw new PackageException(PackageException.Kind.INVALID_ARGUMENT, s,
                    "'" + s + "' is not a valid source: " + e.getMessage());
        }
    }

    /** Null when {@code s} is a valid source, else an English reason (for input dialogs). */
    public static String check(String s) {
        try {
            of(s);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static Source of(String s) {
        if (s.startsWith("https://")) {
            int hash = s.indexOf('#');
            String url = hash < 0 ? s : s.substring(0, hash);
            checkUrl(url);
            return new Source(Kind.URL, url, variantAfter(s, hash), null);
        }
        if (s.startsWith("/") || s.regionMatches(true, 0, "file:", 0, 5)) {
            int hash = s.startsWith("/") && new java.io.File(s).exists() ? -1 : s.lastIndexOf('#'); // cli 3 rule 2
            String path = hash < 0 ? s : s.substring(0, hash);
            if (!path.startsWith("/")) path = pathOf(path);
            return new Source(Kind.LOCAL, path, variantAfter(s, hash), null);
        }
        if (s.matches("[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("only https:// URLs, local paths and repositories are sources");
        }
        int at = s.indexOf('@');
        String head = at < 0 ? s : s.substring(0, at);
        int hash = head.indexOf('#');
        String repo = hash < 0 ? head : head.substring(0, hash);
        String reason = checkModulePath(repo);
        if (reason != null) throw new IllegalArgumentException("'" + repo + "' is not a valid module path: " + reason);
        return new Source(Kind.REPO, repo, variantAfter(head, hash), at < 0 ? null : s.substring(at + 1));
    }

    private static String variantAfter(String s, int hash) {
        if (hash < 0) return "";
        String v = s.substring(hash + 1);
        if (!VARIANT.matcher(v).matches()) throw new IllegalArgumentException("'" + v + "' is not a valid variant");
        return v;
    }

    private static final Pattern URL = Pattern.compile("https://(?:([A-Za-z0-9._~!$&'()*+,;=:-]|%[0-9A-Fa-f]{2})*@)?"
            + "(\\[[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*(%25[A-Za-z0-9._~-]+)?]|[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*\\.?)(:[0-9]*)?"
            + "([/?]([A-Za-z0-9._~!$&'()*+,;=:@/?-]|%[0-9A-Fa-f]{2})*)?");

    /** An absolute https URI with a non-empty host (RFC 3986, ASCII, port up to 65535). */
    private static void checkUrl(String url) {
        Matcher m = URL.matcher(url);
        String port = m.matches() && m.group(5) != null ? m.group(5).replaceFirst(":0*", "") : "";
        if (!m.matches() || port.length() > 5 || !port.isEmpty() && Integer.parseInt(port) > 65535
                || m.group(2).matches("\\[[0-9.]*]")) {
            throw new IllegalArgumentException("'" + url + "' is not a valid absolute https:// URL");
        }
    }

    /** The {@code file:} URI of an absolute path (RFC 8089), ASCII only. */
    static String fileUri(String absolutePath) {
        try {
            return new URI("file", "", absolutePath, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("'" + absolutePath + "' is not an absolute path");
        }
    }

    /** The absolute path of a {@code file:} URI. */
    static String pathOf(String fileUri) {
        try {
            URI u = new URI(fileUri);
            String host = u.getRawAuthority();
            if ("file".equalsIgnoreCase(u.getScheme()) && u.getRawQuery() == null && u.getPath() != null
                    && u.getPath().startsWith("/") && (host == null || host.isEmpty() || host.equalsIgnoreCase("localhost"))) {
                return u.getPath();
            }
        } catch (URISyntaxException ignored) {
            // invalid below
        }
        throw new IllegalArgumentException("'" + fileUri + "' is not a valid file: URI of an absolute path");
    }

    /** The identity of a package named by this source with id {@code id} (package 4). */
    String identity(String id) {
        return variant.isEmpty() ? id : id + "#" + variant;
    }

    /** Whether this source has a ref. */
    boolean hasRef() {
        return version != null || branch != null || tag != null || rev != null;
    }

    /** The source as written; local sources as {@code file:} URIs. */
    @Override
    public String toString() {
        return text;
    }

    /** Whether {@code s} is a variant other than "" (package 1). */
    static boolean isVariant(String s) {
        return VARIANT.matcher(s).matches();
    }

    /** Null when {@code path} is a valid Go module path (golang.org/x/mod/module.CheckPath), else a reason. */
    static String checkModulePath(String path) {
        if (path.isEmpty()) return "empty string";
        if (path.startsWith("-")) return "leading dash";
        if (path.contains("//")) return "double slash";
        if (path.endsWith("/")) return "trailing slash";
        int slash = path.indexOf('/');
        String first = slash < 0 ? path : path.substring(0, slash);
        if (first.isEmpty()) return "leading slash";
        if (!first.contains(".")) return "missing dot in first path element";
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            if (!(c == '-' || c == '.' || c >= '0' && c <= '9' || c >= 'a' && c <= 'z')) {
                return "invalid char '" + c + "' in first path element";
            }
        }
        for (String elem : path.split("/")) {
            String reason = checkElement(elem);
            if (reason != null) return reason;
        }
        return majorVersionOk(path) ? null : "invalid version suffix";
    }

    private static String checkElement(String elem) {
        if (elem.startsWith(".")) return "leading dot in path element";
        if (elem.endsWith(".")) return "trailing dot in path element";
        for (int i = 0; i < elem.length(); i++) {
            char c = elem.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || "-._~".indexOf(c) >= 0)) {
                return "invalid char '" + c + "'";
            }
        }
        int dot = elem.indexOf('.');
        String shortName = (dot < 0 ? elem : elem.substring(0, dot)).toUpperCase(Locale.ROOT);
        if (shortName.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) return "'" + elem + "' disallowed as path element";
        return shortName.matches(".*~[0-9]+") ? "trailing tilde and digits in path element" : null;
    }

    /** SplitPathVersion's validity of a trailing /vN (or gopkg.in .vN) major version suffix. */
    private static boolean majorVersionOk(String path) {
        if (path.startsWith("gopkg.in/")) {
            return path.matches("gopkg\\.in/.+\\.v(0|[1-9][0-9]*)(-unstable)?");
        }
        int i = path.length();
        boolean dot = false;
        while (i > 0 && (path.charAt(i - 1) >= '0' && path.charAt(i - 1) <= '9' || path.charAt(i - 1) == '.')) {
            dot |= path.charAt(i - 1) == '.';
            i--;
        }
        if (i <= 1 || i == path.length() || path.charAt(i - 1) != 'v' || path.charAt(i - 2) != '/') return true;
        String major = path.substring(i - 2);
        return !dot && major.length() > 2 && major.charAt(2) != '0' && !major.equals("/v1");
    }

    /** Whether {@code name} is a valid ref name below refs/heads/ or refs/tags/ (git-check-ref-format). */
    static boolean isRefName(String name) {
        if (name.isEmpty() || name.equals("@") || name.startsWith("/") || name.endsWith("/") || name.endsWith(".")
                || name.contains("//") || name.contains("..") || name.contains("@{")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || " ~^:?*[\\".indexOf(c) >= 0) return false;
        }
        for (String part : name.split("/")) {
            if (part.startsWith(".") || part.endsWith(".lock")) return false;
        }
        return true;
    }
}
