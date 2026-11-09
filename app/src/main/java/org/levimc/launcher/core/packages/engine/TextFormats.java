package org.levimc.launcher.core.packages.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The file formats of the README conventions: TOML files (TOML 1.0 read as the JSON value it
 * represents; date-times, dates, times, inf and nan are invalid), JSON files (UTF-8, no duplicate
 * member names), a small TOML writer and atomic writes. Syntax errors are {@link IllegalArgumentException}.
 */
public final class TextFormats {
    private TextFormats() {
    }

    /** Decodes strict UTF-8 (a leading byte order mark is dropped). */
    static String utf8(byte[] data) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
            return s.startsWith("\uFEFF") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("not valid UTF-8");
        }
    }

    /** Reads a JSON file. */
    static JsonElement parseJson(byte[] data) {
        try (JsonReader r = new JsonReader(new StringReader(utf8(data)))) {
            r.setStrictness(Strictness.STRICT);
            JsonElement v = readJson(r);
            if (r.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("data after the JSON value");
            return v;
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getMessage());
        }
    }

    private static JsonElement readJson(JsonReader r) throws IOException {
        switch (r.peek()) {
            case BEGIN_OBJECT: {
                JsonObject o = new JsonObject();
                r.beginObject();
                while (r.hasNext()) {
                    String name = r.nextName();
                    if (o.has(name)) throw new IllegalArgumentException("duplicate member '" + name + "'");
                    o.add(name, readJson(r));
                }
                r.endObject();
                return o;
            }
            case BEGIN_ARRAY: {
                JsonArray a = new JsonArray();
                r.beginArray();
                while (r.hasNext()) a.add(readJson(r));
                r.endArray();
                return a;
            }
            case STRING: return new JsonPrimitive(r.nextString());
            case NUMBER: return new JsonPrimitive(new BigDecimal(r.nextString()));
            case BOOLEAN: return new JsonPrimitive(r.nextBoolean());
            case NULL: r.nextNull(); return JsonNull.INSTANCE;
            default: throw new IllegalArgumentException("unexpected " + r.peek());
        }
    }

    /** Writes a file atomically: {@code <name>.tmp} in the same directory, then a rename. */
    /** Writes data to file through a synced temporary file and an atomic rename, creating the parent directory. */
    public static void writeAtomically(File file, byte[] data) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Writes a flat object of strings, numbers and booleans as TOML. */
    static byte[] writeToml(JsonObject o) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            b.append(k.matches("[A-Za-z0-9_-]+") ? k : quote(k)).append(" = ");
            JsonPrimitive v = e.getValue().getAsJsonPrimitive();
            b.append(v.isString() ? quote(v.getAsString()) : v.toString()).append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20 || c == 0x7f) b.append(String.format("\\u%04X", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** Reads a TOML file. */
    static JsonObject parseToml(byte[] data) {
        return new Toml(utf8(data)).document();
    }

    /** A TOML 1.0 reader producing the JSON value of the document. */
    private static final class Toml {
        /** How a table or array came to be, for the rules on defining tables twice. */
        private enum Origin { IMPLICIT, HEADER, DOTTED, FROZEN, ARRAY_OF_TABLES }

        private static final Pattern INTEGER = Pattern.compile("[+-]?(0|[1-9](_?[0-9])*)");
        private static final Pattern RADIX = Pattern.compile("0(x[0-9A-Fa-f](_?[0-9A-Fa-f])*|o[0-7](_?[0-7])*|b[01](_?[01])*)");
        private static final Pattern FLOAT = Pattern.compile(
                "[+-]?(0|[1-9](_?[0-9])*)(\\.[0-9](_?[0-9])*)?([eE][+-]?[0-9](_?[0-9])*)?");

        private static final int MAX_DEPTH = 100;

        private final String s;
        /** Nesting depth of the value being read (tables, dotted keys, arrays), bounded so no reader recurses deeply. */
        private int pos, depth;
        private final Map<JsonElement, Origin> origin = new IdentityHashMap<>();

        Toml(String s) {
            this.s = s;
        }

        JsonObject document() {
            JsonObject root = new JsonObject();
            JsonObject table = root;
            while (true) {
                skipBlank();
                if (pos >= s.length()) return root;
                char c = s.charAt(pos);
                if (c == '\n' || c == '\r') {
                    newline();
                } else if (c == '#') {
                    comment();
                } else if (c == '[') {
                    table = header(root);
                    endOfLine();
                } else {
                    keyValue(table);
                    endOfLine();
                }
            }
        }

        private JsonObject header(JsonObject root) {
            boolean array = s.startsWith("[[", pos);
            pos += array ? 2 : 1;
            List<String> key = key();
            expect(array ? "]]" : "]");
            if ((depth = key.size()) > MAX_DEPTH) throw error("nested too deeply");
            JsonObject t = root;
            for (int i = 0; i < key.size() - 1; i++) t = descend(t, key.get(i), false);
            String last = key.get(key.size() - 1);
            JsonElement v = t.get(last);
            if (array) {
                if (v == null) {
                    t.add(last, v = new JsonArray());
                    origin.put(v, Origin.ARRAY_OF_TABLES);
                } else if (origin.get(v) != Origin.ARRAY_OF_TABLES) {
                    throw error("'" + last + "' is not an array of tables");
                }
                JsonObject n = new JsonObject();
                origin.put(n, Origin.HEADER);
                v.getAsJsonArray().add(n);
                return n;
            }
            if (v == null) {
                t.add(last, v = new JsonObject());
            } else if (origin.get(v) != Origin.IMPLICIT) {
                throw error("table '" + last + "' is defined twice");
            }
            origin.put(v, Origin.HEADER);
            return v.getAsJsonObject();
        }

        /** The table {@code key} of {@code t}, created when missing; {@code dotted} for dotted keys. */
        private JsonObject descend(JsonObject t, String key, boolean dotted) {
            JsonElement v = t.get(key);
            if (v == null) {
                t.add(key, v = new JsonObject());
                origin.put(v, dotted ? Origin.DOTTED : Origin.IMPLICIT);
                return v.getAsJsonObject();
            }
            Origin o = origin.get(v);
            if (o == Origin.ARRAY_OF_TABLES && !dotted) {
                JsonArray a = v.getAsJsonArray();
                return a.get(a.size() - 1).getAsJsonObject();
            }
            if (v.isJsonObject() && (dotted ? o == Origin.DOTTED : o != Origin.FROZEN)) return v.getAsJsonObject();
            throw error("'" + key + "' cannot be extended");
        }

        private void keyValue(JsonObject table) {
            List<String> key = key();
            expect("=");
            skipBlank();
            depth += key.size() - 1;
            JsonElement value = value();
            depth -= key.size() - 1;
            JsonObject t = table;
            for (int i = 0; i < key.size() - 1; i++) t = descend(t, key.get(i), true);
            String last = key.get(key.size() - 1);
            if (t.has(last)) throw error("key '" + last + "' is defined twice");
            t.add(last, value);
        }

        private List<String> key() {
            List<String> parts = new java.util.ArrayList<>();
            while (true) {
                skipBlank();
                char c = peek();
                if (c == '"' || c == '\'') {
                    if (s.startsWith(c == '"' ? "\"\"\"" : "'''", pos)) throw error("multi-line string as key");
                    parts.add(string());
                } else {
                    int start = pos;
                    while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) && s.charAt(pos) < 128
                            || s.charAt(pos) == '_' || s.charAt(pos) == '-')) pos++;
                    if (start == pos) throw error("expected a key");
                    parts.add(s.substring(start, pos));
                }
                skipBlank();
                if (peek() != '.') return parts;
                pos++;
            }
        }

        private JsonElement value() {
            if (++depth > MAX_DEPTH) throw error("nested too deeply");
            try {
                return scalarOrNested();
            } finally {
                depth--;
            }
        }

        private JsonElement scalarOrNested() {
            char c = peek();
            if (c == '"' || c == '\'') return new JsonPrimitive(string());
            if (c == '[') return array();
            if (c == '{') return inlineTable();
            int start = pos;
            while (pos < s.length() && "+-_.:0123456789".indexOf(s.charAt(pos)) >= 0
                    || pos < s.length() && Character.isLetter(s.charAt(pos)) && s.charAt(pos) < 128) pos++;
            String t = s.substring(start, pos);
            if (t.equals("true") || t.equals("false")) return new JsonPrimitive(t.equals("true"));
            if (t.matches("[0-9]{4}-.*|[0-9]{2}:.*")) throw error("dates and times are not allowed");
            if (t.matches("[+-]?(inf|nan)")) throw error("inf and nan are not allowed");
            String digits = t.replace("_", "");
            if (INTEGER.matcher(t).matches()) {
                BigInteger n = new BigInteger(digits.startsWith("+") ? digits.substring(1) : digits);
                if (n.bitLength() > 63) throw error("integer out of range");
                return new JsonPrimitive(n.longValue());
            }
            Matcher m = RADIX.matcher(t);
            if (m.matches()) {
                char base = t.charAt(1);
                BigInteger n = new BigInteger(digits.substring(2), base == 'x' ? 16 : base == 'o' ? 8 : 2);
                if (n.bitLength() > 63) throw error("integer out of range");
                return new JsonPrimitive(n.longValue());
            }
            if (FLOAT.matcher(t).matches()) {
                if (Double.isInfinite(Double.parseDouble(digits))) throw error("float out of range");
                return new JsonPrimitive(new BigDecimal(digits));
            }
            throw error("invalid value '" + t + "'");
        }

        private JsonArray array() {
            pos++;
            JsonArray a = new JsonArray();
            while (true) {
                skipBlankLines();
                if (peek() == ']') break;
                a.add(value());
                skipBlankLines();
                if (peek() != ',') break;
                pos++;
            }
            expect("]");
            origin.put(a, Origin.FROZEN);
            return a;
        }

        private JsonObject inlineTable() {
            pos++;
            JsonObject t = new JsonObject();
            skipBlank();
            if (peek() != '}') {
                while (true) {
                    keyValue(t);
                    skipBlank();
                    if (peek() != ',') break;
                    pos++;
                }
            }
            expect("}");
            freeze(t);
            return t;
        }

        private void freeze(JsonElement v) {
            if (v.isJsonObject() || v.isJsonArray()) origin.put(v, Origin.FROZEN);
            if (v.isJsonObject()) for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) freeze(e.getValue());
        }

        private String string() {
            char q = s.charAt(pos);
            boolean multi = s.startsWith(q == '"' ? "\"\"\"" : "'''", pos);
            pos += multi ? 3 : 1;
            if (multi && s.startsWith("\r\n", pos)) pos += 2;
            else if (multi && s.startsWith("\n", pos)) pos++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw error("unterminated string");
                char c = s.charAt(pos);
                if (c == q && (!multi || s.startsWith(q == '"' ? "\"\"\"" : "'''", pos))) {
                    pos += multi ? 3 : 1;
                    for (int extra = 0; multi && extra < 2 && pos < s.length() && s.charAt(pos) == q; extra++, pos++) {
                        b.append(q);
                    }
                    return b.toString();
                }
                if (c == '\\' && q == '"') {
                    escape(b, multi);
                    continue;
                }
                if (c == '\r' && multi && s.startsWith("\r\n", pos)) {
                    b.append("\r\n");
                    pos += 2;
                    continue;
                }
                if (c < 0x20 && c != '\t' && !(c == '\n' && multi) || c == 0x7f) throw error("control character in string");
                b.append(c);
                pos++;
            }
        }

        private void escape(StringBuilder b, boolean multi) {
            pos++;
            char e = peek();
            pos++;
            switch (e) {
                case 'b': b.append('\b'); return;
                case 't': b.append('\t'); return;
                case 'n': b.append('\n'); return;
                case 'f': b.append('\f'); return;
                case 'r': b.append('\r'); return;
                case '"': b.append('"'); return;
                case '\\': b.append('\\'); return;
                case 'u': case 'U': {
                    int n = e == 'u' ? 4 : 8;
                    if (pos + n > s.length() || !s.substring(pos, pos + n).matches("[0-9A-Fa-f]+")) throw error("bad escape");
                    int cp = (int) Long.parseLong(s.substring(pos, pos + n), 16);
                    if (cp > 0x10FFFF || cp >= 0xD800 && cp <= 0xDFFF || cp < 0) throw error("bad unicode escape");
                    b.appendCodePoint(cp);
                    pos += n;
                    return;
                }
                default:
                    if (!multi) throw error("bad escape \\" + e);
                    pos--;
                    int p = pos;
                    while (p < s.length() && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
                    if (p >= s.length() || s.charAt(p) != '\n' && !s.startsWith("\r\n", p)) throw error("bad escape");
                    pos = p;
                    while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) {
                        if (s.charAt(pos) == '\r' && !s.startsWith("\r\n", pos)) throw error("lone carriage return");
                        pos++;
                    }
            }
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void expect(String t) {
            skipBlank();
            if (!s.startsWith(t, pos)) throw error("expected '" + t + "'");
            pos += t.length();
        }

        private void skipBlank() {
            while (pos < s.length() && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) pos++;
        }

        /** Skips white space, comments and newlines (inside arrays). */
        private void skipBlankLines() {
            while (true) {
                skipBlank();
                char c = peek();
                if (c == '#') comment();
                else if (c == '\n' || c == '\r') newline();
                else return;
            }
        }

        private void comment() {
            for (pos++; pos < s.length() && s.charAt(pos) != '\n' && s.charAt(pos) != '\r'; pos++) {
                char c = s.charAt(pos);
                if (c < 0x20 && c != '\t' || c == 0x7f) throw error("control character in comment");
            }
        }

        private void newline() {
            if (s.startsWith("\r\n", pos)) pos += 2;
            else if (s.charAt(pos) == '\n') pos++;
            else throw error("expected the end of the line");
        }

        private void endOfLine() {
            skipBlank();
            if (peek() == '#') comment();
            if (pos < s.length()) newline();
        }

        private IllegalArgumentException error(String what) {
            int line = 1;
            for (int i = 0; i < Math.min(pos, s.length()); i++) if (s.charAt(i) == '\n') line++;
            return new IllegalArgumentException("not valid TOML (line " + line + "): " + what);
        }
    }
}
