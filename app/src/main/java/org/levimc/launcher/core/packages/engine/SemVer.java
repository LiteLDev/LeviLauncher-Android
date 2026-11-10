package org.levimc.launcher.core.packages.engine;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A SemVer 2.0.0 version without a leading {@code v} (package 1), and {@link Range}: the ranges that
 * Semver.NET 3.0 {@code SemVersionRange.Parse} accepts with its default options, with its matching.
 * Numbers have no size limit.
 */
public final class SemVer implements Comparable<SemVer> {
    public final BigInteger major, minor, patch;
    /** Prerelease identifiers; empty for a release. */
    public final List<String> prerelease;
    private final String text;

    private SemVer(BigInteger major, BigInteger minor, BigInteger patch, List<String> prerelease, String text) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
        this.text = text != null ? text : major + "." + minor + "." + patch
                + (prerelease.isEmpty() ? "" : "-" + String.join(".", prerelease));
    }

    /** Parses a version strictly (build metadata allowed), or returns null when {@code s} is not one. */
    public static SemVer parse(String s) {
        if (s == null || s.length() > 1024) return null;
        int plus = s.indexOf('+');
        if (plus >= 0) {
            for (String id : s.substring(plus + 1).split("\\.", -1)) {
                if (id.isEmpty() || !alnumDash(id)) return null;
            }
        }
        String core = plus >= 0 ? s.substring(0, plus) : s;
        int dash = core.indexOf('-');
        List<String> pre = Collections.emptyList();
        if (dash >= 0) {
            pre = prerelease(core.substring(dash + 1), false);
            if (pre == null) return null;
            core = core.substring(0, dash);
        }
        String[] n = core.split("\\.", -1);
        if (n.length != 3) return null;
        BigInteger[] v = new BigInteger[3];
        for (int i = 0; i < 3; i++) {
            if (!numeric(n[i])) return null;
            v[i] = new BigInteger(n[i]);
        }
        return new SemVer(v[0], v[1], v[2], pre, s);
    }

    /** Compares two version strings by precedence; an unparsable one sorts first. */
    public static int compare(String a, String b) {
        SemVer x = parse(a), y = parse(b);
        if (x == null || y == null) return x == null ? (y == null ? 0 : -1) : 1;
        return x.compareTo(y);
    }

    public boolean isPrerelease() {
        return !prerelease.isEmpty();
    }

    /** SemVer precedence (build metadata ignored). */
    @Override
    public int compareTo(SemVer o) {
        int c = major.compareTo(o.major);
        if (c == 0) c = minor.compareTo(o.minor);
        if (c == 0) c = patch.compareTo(o.patch);
        if (c != 0) return c;
        if (prerelease.isEmpty() || o.prerelease.isEmpty()) {
            return Boolean.compare(prerelease.isEmpty(), o.prerelease.isEmpty());
        }
        for (int i = 0; i < Math.min(prerelease.size(), o.prerelease.size()); i++) {
            String a = prerelease.get(i), b = o.prerelease.get(i);
            boolean na = digits(a), nb = digits(b);
            c = na && nb ? new BigInteger(a).compareTo(new BigInteger(b)) : na != nb ? (na ? -1 : 1) : a.compareTo(b);
            if (c != 0) return c;
        }
        return Integer.compare(prerelease.size(), o.prerelease.size());
    }

    private boolean sameCore(SemVer o) {
        return major.equals(o.major) && minor.equals(o.minor) && patch.equals(o.patch);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SemVer && text.equals(((SemVer) o).text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    /** The version as written. */
    @Override
    public String toString() {
        return text;
    }

    private static boolean digits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    /** Digits without a leading zero. */
    private static boolean numeric(String s) {
        return digits(s) && (s.length() == 1 || s.charAt(0) != '0');
    }

    private static boolean alnumDash(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '-')) return false;
        }
        return true;
    }

    /** Prerelease identifiers, or null; with {@code wildcard}, a last identifier may be "*". */
    private static List<String> prerelease(String s, boolean wildcard) {
        List<String> ids = new ArrayList<>(Arrays.asList(s.split("\\.", -1)));
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            boolean ok = id.equals("*") ? wildcard && i == ids.size() - 1
                    : !id.isEmpty() && alnumDash(id) && (!digits(id) || numeric(id));
            if (!ok) return null;
        }
        return ids;
    }

    private static SemVer of(BigInteger major, BigInteger minor, BigInteger patch, List<String> pre) {
        return new SemVer(major, minor, patch, pre, null);
    }

    private static final List<String> ZERO = Collections.singletonList("0");

    /** A version range; immutable. */
    public static final class Range {
        private final List<Unbroken> alternatives;

        private Range(List<Unbroken> alternatives) {
            this.alternatives = alternatives;
        }

        /** Parses {@code s} as {@code SemVersionRange.Parse(s)} does, or returns null when it rejects it. */
        public static Range parse(String s) {
            if (s == null || s.length() > 2048) return null;
            List<Unbroken> out = new ArrayList<>();
            int start = 0;
            for (int i = 0; i <= s.length(); i++) {
                if (i == s.length() || i + 1 < s.length() && s.charAt(i) == '|' && s.charAt(i + 1) == '|') {
                    Unbroken u = Unbroken.parse(s.substring(start, i));
                    if (u == null) return null;
                    out.add(u);
                    start = i + 2;
                    i++;
                }
            }
            return new Range(out);
        }

        public boolean matches(SemVer v) {
            for (Unbroken u : alternatives) if (u.contains(v)) return true;
            return false;
        }

        /** Whether {@code version} is a version this range matches. */
        public boolean matches(String version) {
            SemVer v = SemVer.parse(version);
            return v != null && matches(v);
        }
    }

    /** One comparator set: [start, end] with inclusiveness, and whether all prereleases are in it. */
    private static final class Unbroken {
        SemVer start, end;
        boolean startIn, endIn, allPrerelease;

        boolean contains(SemVer v) {
            if (start != null && (startIn ? v.compareTo(start) < 0 : v.compareTo(start) <= 0)) return false;
            if (end != null && (endIn ? v.compareTo(end) > 0 : v.compareTo(end) >= 0)) return false;
            return allPrerelease || !v.isPrerelease()
                    || start != null && start.isPrerelease() && v.sameCore(start)
                    || end != null && end.isPrerelease() && v.sameCore(end);
        }

        void lower(SemVer v, boolean inclusive) {
            int c = start == null ? 1 : v.compareTo(start);
            if (c > 0 || c == 0 && !inclusive) {
                start = v;
                startIn = inclusive;
            }
        }

        void upper(SemVer v, boolean inclusive) {
            int c = end == null ? -1 : v.compareTo(end);
            if (c < 0 || c == 0 && !inclusive) {
                end = v;
                endIn = inclusive;
            }
        }

        static Unbroken parse(String s) {
            Unbroken u = new Unbroken();
            int i = spaces(s, 0);
            if (i < 0 || i == s.length()) return null;
            while (i < s.length()) {
                int j = i;
                while (j < s.length() && operatorChar(s.charAt(j))) j++;
                String op = s.substring(i, j);
                if (!op.isEmpty() && !Arrays.asList("=", "<", "<=", ">", ">=", "~", "^").contains(op)) return null;
                if ((i = spaces(s, j)) < 0) return null;
                j = i;
                while (j < s.length() && !whitespace(s.charAt(j)) && !operatorChar(s.charAt(j))) j++;
                if (!u.comparison(op, s.substring(i, j)) || (i = spaces(s, j)) < 0) return null;
            }
            return u;
        }

        /** Applies one comparison; false when it is invalid. */
        boolean comparison(String op, String version) {
            int dash = version.indexOf('-');
            String[] n = (dash < 0 ? version : version.substring(0, dash)).split("\\.", -1);
            List<String> pre = Collections.emptyList();
            if (dash >= 0 && (pre = prerelease(version.substring(dash + 1), true)) == null) return false;
            boolean preWild = !pre.isEmpty() && pre.get(pre.size() - 1).equals("*");
            if (preWild) pre = pre.subList(0, pre.size() - 1);
            int wild = 3; // index of the first wildcard number part
            BigInteger[] v = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
            if (n.length > 3) return false;
            for (int k = 0; k < 3; k++) {
                if (k >= n.length) {
                    if (wild > k) return false; // a number is missing and no wildcard precedes it
                } else if (n[k].equals("*")) {
                    wild = Math.min(wild, k);
                } else if (wild < k || !numeric(n[k])) {
                    return false;
                } else {
                    v[k] = new BigInteger(n[k]);
                }
            }
            if (!op.isEmpty() && (wild < 3 || preWild) || wild < 3 && !pre.isEmpty()) return false;
            SemVer ver = of(v[0], v[1], v[2], pre);
            switch (op) {
                case "=": lower(ver, true); upper(ver, true); return true;
                case ">": lower(ver, false); return true;
                case ">=": lower(ver, true); return true;
                case "<": upper(ver, false); return true;
                case "<=": upper(ver, true); return true;
                case "~":
                    lower(ver, true);
                    upper(of(v[0], v[1].add(BigInteger.ONE), BigInteger.ZERO, ZERO), false);
                    return true;
                case "^":
                    lower(ver, true);
                    upper(v[0].signum() != 0 ? of(v[0].add(BigInteger.ONE), BigInteger.ZERO, BigInteger.ZERO, ZERO)
                            : v[1].signum() != 0 ? of(v[0], v[1].add(BigInteger.ONE), BigInteger.ZERO, ZERO)
                            : of(v[0], v[1], v[2].add(BigInteger.ONE), ZERO), false);
                    return true;
                default:
                    break;
            }
            allPrerelease |= preWild;
            if (wild == 0) return true;
            List<String> low = pre;
            if (preWild) {
                low = new ArrayList<>(pre);
                low.add("0");
            }
            lower(of(v[0], v[1], v[2], low), true);
            if (wild == 1) {
                upper(of(v[0].add(BigInteger.ONE), BigInteger.ZERO, BigInteger.ZERO, ZERO), false);
            } else if (wild == 2) {
                upper(of(v[0], v[1].add(BigInteger.ONE), BigInteger.ZERO, ZERO), false);
            } else if (!preWild) {
                upper(ver, true);
            } else if (pre.isEmpty()) {
                upper(of(v[0], v[1], v[2].add(BigInteger.ONE), ZERO), false);
            } else {
                List<String> high = new ArrayList<>(pre);
                high.set(high.size() - 1, nextIdentifier(high.get(high.size() - 1)));
                upper(of(v[0], v[1], v[2], high), false);
            }
            return true;
        }

        /** The smallest identifier greater than {@code id}. */
        private static String nextIdentifier(String id) {
            return digits(id) ? new BigInteger(id).add(BigInteger.ONE).toString() : id + "-";
        }

        /** Skips ASCII spaces; -1 when another white space character follows. */
        private static int spaces(String s, int i) {
            while (i < s.length() && s.charAt(i) == ' ') i++;
            return i < s.length() && whitespace(s.charAt(i)) ? -1 : i;
        }

        private static boolean whitespace(char c) {
            return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085';
        }

        /** Characters that start or continue an operator: punctuation and symbols but '-', '.' and '*'. */
        private static boolean operatorChar(char c) {
            if (c == '-' || c == '.' || c == '*' || Character.isSurrogate(c) || whitespace(c)) return false;
            switch (Character.getType(c)) {
                case Character.CONNECTOR_PUNCTUATION: case Character.DASH_PUNCTUATION:
                case Character.START_PUNCTUATION: case Character.END_PUNCTUATION:
                case Character.INITIAL_QUOTE_PUNCTUATION: case Character.FINAL_QUOTE_PUNCTUATION:
                case Character.OTHER_PUNCTUATION: case Character.MATH_SYMBOL:
                case Character.CURRENCY_SYMBOL: case Character.MODIFIER_SYMBOL: case Character.OTHER_SYMBOL:
                    return true;
                default:
                    return false;
            }
        }
    }
}
