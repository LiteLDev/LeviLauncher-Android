package org.levimc.launcher.core.packages.engine;

/**
 * Globs of package files (lip.schema.toml {@code definitions.globs}): POSIX fnmatch patterns with
 * FNM_PATHNAME and without FNM_PERIOD, matched segment by segment ({@code *}, {@code ?}, bracket
 * expressions, no escape character); a {@code **} segment matches zero or more segments; a trailing
 * {@code /} matches directories and everything below them. Paths are relative and '/'-separated; a
 * directory path is written with a trailing '/'.
 */
final class Glob {
    private Glob() {
    }

    private static final String[] CLASSES = {"alnum", "alpha", "blank", "cntrl", "digit", "graph", "lower",
            "print", "punct", "space", "upper", "xdigit"};

    /** Whether {@code s} contains '*', '?' or '['. */
    static boolean hasMeta(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0;
    }

    /** Null when {@code glob} is valid, else the reason. */
    static String check(String glob) {
        if (glob.isEmpty()) return "it is empty";
        if (glob.startsWith("/")) return "it starts with '/'";
        if (glob.contains("//")) return "it contains '//'";
        if (glob.indexOf('\\') >= 0) return "it contains '\\'";
        for (String seg : glob.split("/")) {
            for (int i = 0; i < seg.length(); i++) {
                if (seg.charAt(i) != '[') continue;
                int end = bracket(seg, i, '\0', true);
                if (end == -2) return "it has an invalid bracket expression in '" + seg + "'";
                if (end > 0) i = end / 2 - 1;
            }
        }
        return null;
    }

    static boolean isValid(String glob) {
        return check(glob) == null;
    }

    /** Whether {@code path} (a directory when it ends with '/') matches the valid glob {@code glob}. */
    static boolean matches(String glob, String path) {
        boolean dirGlob = glob.endsWith("/"), dir = path.endsWith("/");
        String[] g = (dirGlob ? glob.substring(0, glob.length() - 1) : glob).split("/", -1);
        String[] p = (dir ? path.substring(0, path.length() - 1) : path).split("/", -1);
        if (!dirGlob) return !dir && segments(g, 0, p, 0, p.length);
        for (int k = 0; k <= p.length; k++) {
            if ((k < p.length || dir) && segments(g, 0, p, 0, k)) return true;
        }
        return false;
    }

    /** Whether the glob segments from {@code gi} match the path segments [{@code pi}, {@code pe}). */
    private static boolean segments(String[] g, int gi, String[] p, int pi, int pe) {
        if (gi == g.length) return pi == pe;
        if (g[gi].equals("**")) {
            for (int k = pi; k <= pe; k++) if (segments(g, gi + 1, p, k, pe)) return true;
            return false;
        }
        return pi < pe && segment(g[gi], 0, p[pi], 0) && segments(g, gi + 1, p, pi + 1, pe);
    }

    /** fnmatch of one segment. */
    private static boolean segment(String g, int gi, String s, int si) {
        while (gi < g.length()) {
            char c = g.charAt(gi);
            if (c == '*') {
                for (int k = si; k <= s.length(); k++) if (segment(g, gi + 1, s, k)) return true;
                return false;
            }
            if (si == s.length()) return false;
            char ch = s.charAt(si);
            if (c == '[') {
                int end = bracket(g, gi, ch, false);
                if (end > 0) {
                    if ((end & 1) == 0) return false;
                    gi = end / 2;
                    si++;
                    continue;
                }
            }
            if (c != '?' && c != ch) return false;
            gi++;
            si++;
        }
        return si == s.length();
    }

    /**
     * Parses the bracket expression at {@code g[start] == '['}. Returns 2 * (the index after its ']')
     * + (1 when {@code ch} is in the set), or -1 when it is not closed ('[' is then literal), or -2 (only
     * with {@code check}) when it is invalid.
     */
    private static int bracket(String g, int start, char ch, boolean check) {
        int i = start + 1;
        boolean negate = i < g.length() && g.charAt(i) == '!';
        if (negate) i++;
        boolean bad = i < g.length() && g.charAt(i) == '^' && !negate, in = false, first = true;
        while (i < g.length()) {
            char c = g.charAt(i);
            if (c == ']' && !first) {
                return bad && check ? -2 : 2 * (i + 1) + (in != negate ? 1 : 0);
            }
            first = false;
            char lo;
            if (c == '[' && i + 1 < g.length() && ":.=".indexOf(g.charAt(i + 1)) >= 0) {
                char kind = g.charAt(i + 1);
                int close = g.indexOf(new String(new char[] {kind, ']'}), i + 2);
                if (close < 0) return -1;
                String name = g.substring(i + 2, close);
                i = close + 2;
                if (kind == ':') {
                    int k = java.util.Arrays.asList(CLASSES).indexOf(name);
                    bad |= k < 0;
                    in |= k >= 0 && inClass(k, ch);
                    continue;
                }
                bad |= name.length() != 1;
                lo = name.isEmpty() ? 0 : name.charAt(0);
            } else {
                lo = c;
                i++;
            }
            char hi = lo;
            if (i + 1 < g.length() && g.charAt(i) == '-' && g.charAt(i + 1) != ']') {
                hi = g.charAt(i + 1);
                i += 2;
                if (hi == '[' && i < g.length() && ".=".indexOf(g.charAt(i)) >= 0) {
                    int close = g.indexOf(new String(new char[] {g.charAt(i), ']'}), i + 1);
                    if (close < 0) return -1;
                    bad |= close != i + 2;
                    hi = g.charAt(i + 1);
                    i = close + 2;
                }
                bad |= hi < lo;
            }
            in |= ch >= lo && ch <= hi;
        }
        return -1;
    }

    private static boolean inClass(int k, char c) {
        switch (CLASSES[k]) {
            case "alnum": return c < 128 && Character.isLetterOrDigit(c);
            case "alpha": return c < 128 && Character.isLetter(c);
            case "blank": return c == ' ' || c == '\t';
            case "cntrl": return c < 32 || c == 127;
            case "digit": return c >= '0' && c <= '9';
            case "graph": return c > 32 && c < 127;
            case "lower": return c >= 'a' && c <= 'z';
            case "print": return c >= 32 && c < 127;
            case "punct": return c > 32 && c < 127 && !Character.isLetterOrDigit(c);
            case "space": return c == ' ' || c >= '\t' && c <= '\r';
            case "upper": return c >= 'A' && c <= 'Z';
            default: return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
        }
    }
}
