package org.levimc.launcher.core.packages.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;

/**
 * Renders the subset of the Scriban language [SCRIBAN] that package files use (package 3):
 * text with code blocks {@code {{ expression }}}, where an expression is empty, a literal
 * ({@code "s"}, {@code 's'}, an integer, {@code true}, {@code false}, {@code null}) or a variable
 * with member accesses ({@code version}, {@code info.name}), optionally followed by a {@code #}
 * comment. Whitespace control {@code {{-}}, {@code -}}}, {@code {{~}} and {@code ~}}} works as in
 * Scriban. Any other construct is rejected with {@link IllegalArgumentException}, which makes the
 * archive not a package.
 */
final class Template {
    private Template() {
    }

    /** Renders {@code template} with the members of {@code vars} as variables. */
    static String render(String template, JsonObject vars) {
        if (template.contains("{%{")) throw unsupported("{%{");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                out.append(template, i, template.length());
                return out.toString();
            }
            out.append(template, i, open);
            int p = open + 2;
            char trimLeft = p < template.length() ? template.charAt(p) : 0;
            if (trimLeft == '-' || trimLeft == '~') {
                trim(out, trimLeft == '-');
                p++;
            } else if (trimLeft == '{') {
                throw unsupported("{{{");
            }
            int close = closing(template, p);
            if (close < 0) throw new IllegalArgumentException("unterminated code block");
            char trimRight = template.charAt(close - 1);
            boolean trims = close - 1 >= p && (trimRight == '-' || trimRight == '~');
            out.append(value(template.substring(p, trims ? close - 1 : close), vars));
            i = close + 2;
            if (trims) i = skip(template, i, trimRight == '-');
        }
    }

    /** Index of the "}}" ending the code that starts at {@code p}, skipping string literals. */
    private static int closing(String s, int p) {
        for (char quote = 0; p + 1 < s.length() || quote != 0 && p < s.length(); p++) {
            char c = s.charAt(p);
            if (quote != 0) {
                if (c == '\\' && quote != '`') p++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'' || c == '`') {
                quote = c;
            } else if (c == '}' && s.charAt(p + 1) == '}') {
                return p;
            }
        }
        return -1;
    }

    /** Removes white space before a block: all of it ('-'), or spaces and tabs on its line ('~'). */
    private static void trim(StringBuilder out, boolean all) {
        int n = out.length();
        while (n > 0 && (all ? Character.isWhitespace(out.charAt(n - 1)) : out.charAt(n - 1) == ' ' || out.charAt(n - 1) == '\t')) {
            n--;
        }
        out.setLength(n);
    }

    /** Skips white space after a block: all of it ('-'), or spaces and tabs and one line break ('~'). */
    private static int skip(String s, int i, boolean all) {
        if (all) {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            return i;
        }
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        if (s.startsWith("\r\n", i)) return i + 2;
        return i < s.length() && s.charAt(i) == '\n' ? i + 1 : i;
    }

    /** Evaluates one code block and renders its value. */
    private static String value(String code, JsonObject vars) {
        String expr = code;
        int hash = expr.indexOf('#');
        if (hash >= 0 && expr.indexOf('"') < 0 && expr.indexOf('\'') < 0) expr = expr.substring(0, hash);
        expr = expr.trim();
        if (expr.isEmpty() || expr.equals("null")) return "";
        if (expr.equals("true") || expr.equals("false")) return expr;
        char c = expr.charAt(0);
        if ((c == '"' || c == '\'') && expr.length() >= 2 && expr.charAt(expr.length() - 1) == c) {
            return unquote(expr.substring(1, expr.length() - 1), c);
        }
        if (expr.matches("[0-9]+")) return expr;
        if (!expr.matches("[A-Za-z_][A-Za-z0-9_]*(\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)*")
                || expr.matches("(if|else|end|for|in|while|func|ret|break|continue|case|when|with|wrap|import|capture|readonly|tablerow|include)\\b.*")) {
            throw unsupported(expr);
        }
        String[] path = expr.split("\\s*\\.\\s*");
        JsonElement v = vars.get(path[0]);
        for (int k = 1; k < path.length; k++) {
            if (v == null || v.isJsonNull()) throw new IllegalArgumentException("'" + expr + "': member of null");
            if (!v.isJsonObject()) throw unsupported(expr);
            v = v.getAsJsonObject().get(path[k]);
        }
        if (v == null || v.isJsonNull()) return "";
        if (!v.isJsonPrimitive()) throw unsupported(expr + " (an object or array)");
        JsonPrimitive prim = v.getAsJsonPrimitive();
        if (!prim.isNumber()) return prim.getAsString();
        Number n = prim.getAsNumber();
        return n instanceof BigDecimal ? ((BigDecimal) n).toPlainString() : n.toString();
    }

    private static String unquote(String s, char quote) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == quote) throw unsupported("several expressions");
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (++i == s.length()) throw unsupported("escape at end");
            c = s.charAt(i);
            switch (c) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case '\\': case '"': case '\'': b.append(c); break;
                default: throw unsupported("escape \\" + c);
            }
        }
        return b.toString();
    }

    private static IllegalArgumentException unsupported(String what) {
        return new IllegalArgumentException("unsupported template construct: " + what);
    }
}
