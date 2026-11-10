package org.levimc.launcher.core.packages.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * compatibility 2: reads a {@code tooth.json} of format 1, 2 or 3 (lip v0.x) as the package file it
 * stands for in an environment. Formats 1 and 2 are first upgraded to format 3 as lip v0.37.0 does;
 * a format 3 file has its templates rendered with its own top-level members. Failures are
 * {@link IllegalArgumentException}: the archive is then not a package.
 */
final class ToothCompat {
    static final String FILE = "tooth.json";
    private static final String FORMAT_UUID = "289f771f-2c9a-4d73-9f3f-8492495a924d";
    private static final Pattern TAG = Pattern.compile("[a-z0-9-]+(:[a-z0-9-]+)?");
    private static final Pattern V2_TEMPLATE = Pattern.compile("\\$\\(([^)]+?)\\)");
    private static final String[] SCRIPTS = {"pre_install", "install", "post_install", "pre_uninstall", "uninstall", "post_uninstall"};

    private ToothCompat() {
    }

    /** The package file (format 4) that {@code toothJson} stands for in (side, platform). */
    static JsonObject derive(byte[] toothJson, String side, String platform, String tagVersion) {
        if (!side.equals("server") && !side.equals("client")) {
            throw new IllegalArgumentException("compat: side \"" + side + "\" is neither \"server\" nor \"client\"");
        }
        JsonElement root = TextFormats.parseJson(toothJson);
        if (!root.isJsonObject()) throw new IllegalArgumentException("not a JSON object");
        JsonObject t = format3(root.getAsJsonObject());
        String version = tagVersion != null ? tagVersion : looseVersion(Manifest.string(t, "version", true));
        JsonObject out = new JsonObject();
        out.addProperty("format", 4);
        out.addProperty("id", Manifest.string(t, "tooth", true));
        out.addProperty("version", version);
        JsonObject info = obj(t, "info");
        if (info != null) {
            JsonObject i = new JsonObject();
            copy(info, i, "name");
            copy(info, i, "description");
            if (info.has("tags") && !info.get("tags").isJsonNull()) i.add("tags", stringArray(list(info, "tags")));
            String avatar = opt(info, "avatar_url");
            if (avatar != null && avatar.startsWith("https://")) i.addProperty("logo", avatar);
            out.add("info", i);
        }
        JsonObject variants = new JsonObject();
        List<JsonObject> all = objects(t, "variants");
        for (String label : labels(all)) {
            List<JsonObject> selected = new ArrayList<>();
            for (JsonObject v : all) {
                String p = str(v, "platform");
                if (str(v, "label").equals(label) && (p.isEmpty() || platformMatches(p, platform))) selected.add(v);
            }
            JsonObject entry = selected.isEmpty() ? null : entry(selected, side, platform);
            if (entry != null) {
                JsonArray entries = new JsonArray();
                entries.add(entry);
                variants.add(label, entries);
            }
        }
        out.add("variants", variants);
        return out;
    }

    private static LinkedHashSet<String> labels(List<JsonObject> variants) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (JsonObject v : variants) labels.add(str(v, "label"));
        return labels;
    }

    /** Merges the selected format 3 variants of one label into one entry (null: no variant). */
    private static JsonObject entry(List<JsonObject> selected, String side, String platform) {
        JsonObject e = new JsonObject();
        e.addProperty("side", side);
        e.addProperty("platform", platform);
        JsonObject deps = new JsonObject();
        JsonArray assets = new JsonArray(), preserve = new JsonArray(), purge = new JsonArray();
        Map<String, List<String>> scripts = new LinkedHashMap<>();
        for (JsonObject v : selected) {
            noNull(v, "dependencies", "assets", "preserve_files", "remove_files", "scripts");
            JsonObject d = Manifest.object(v, "dependencies");
            for (String k : d == null ? new LinkedHashSet<String>() : d.keySet()) {
                if (deps.has(k)) throw new IllegalArgumentException("variants labeled the same both depend on " + k);
                deps.add(k, d.get(k));
            }
            for (JsonObject a : objects(v, "assets")) {
                String type = Manifest.string(a, "type", true);
                if (!type.equals("self") && !type.equals("zip")) return null; // tar, tgz, uncompressed
                noNull(a, type.equals("zip") ? "urls" : "type", "placements");
                JsonObject asset = new JsonObject();
                asset.addProperty("type", type);
                if (type.equals("zip")) asset.add("urls", stringArray(list(a, "urls")));
                JsonArray place = new JsonArray();
                for (JsonObject p : objects(a, "placements")) {
                    String[] sd = placement(Manifest.string(p, "type", true), str(p, "src"), str(p, "dest"));
                    if (Manifest.checkPlace(sd[0], sd[1]) != null) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("src", sd[0]);
                    o.addProperty("dest", sd[1]);
                    place.add(o);
                }
                if (place.size() > 0) asset.add("place", place);
                assets.add(asset);
            }
            for (String p : list(v, "preserve_files")) {
                p = p.replace('\\', '/');
                addGlob(preserve, p.contains("/") ? p : "**/" + p);
            }
            for (String p : list(v, "remove_files")) {
                p = p.replace('\\', '/');
                addGlob(purge, p);
                if (!p.endsWith("/")) addGlob(purge, p + "/");
            }
            JsonObject s = Manifest.object(v, "scripts");
            if (s != null) noNull(s, SCRIPTS);
            for (String key : SCRIPTS) {
                List<String> cmds = s == null ? new ArrayList<String>() : list(s, key);
                if (cmds.isEmpty()) continue;
                if (scripts.containsKey(key)) throw new IllegalArgumentException("variants labeled the same both have " + key + " scripts");
                scripts.put(key, cmds);
            }
        }
        if (deps.size() > 0) e.add("deps", deps);
        if (assets.size() > 0) e.add("assets", assets);
        if (preserve.size() > 0) e.add("preserve", preserve);
        if (purge.size() > 0) e.add("purge", purge);
        JsonObject hooks = new JsonObject();
        hook(hooks, "pre_install", scripts, "pre_install");
        hook(hooks, "post_install", scripts, "install", "post_install");
        hook(hooks, "pre_uninstall", scripts, "pre_uninstall", "uninstall");
        hook(hooks, "post_uninstall", scripts, "post_uninstall");
        if (hooks.size() > 0) e.add("hooks", hooks);
        return e;
    }

    private static void hook(JsonObject hooks, String key, Map<String, List<String>> scripts, String... from) {
        List<String> all = new ArrayList<>();
        for (String f : from) if (scripts.containsKey(f)) all.addAll(scripts.get(f));
        if (!all.isEmpty()) hooks.add(key, stringArray(all));
    }

    private static void addGlob(JsonArray globs, String g) {
        if (Glob.isValid(g)) globs.add(g);
    }

    /** compatibility 2: a placement as (src, dest) of a package file. */
    static String[] placement(String type, String src, String dest) {
        src = normalize(src);
        dest = normalize(dest);
        if (type.equals("dir")) return new String[] {dir(src), dir(dest)};
        if (Glob.hasMeta(src)) return new String[] {src, dir(dest)};
        return new String[] {src, dest};
    }

    private static String dir(String path) {
        return path.isEmpty() ? "" : path + "/";
    }

    /** '\' to '/', empty and "." segments removed. */
    private static String normalize(String path) {
        StringBuilder b = new StringBuilder();
        for (String s : path.replace('\\', '/').split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (b.length() > 0) b.append('/');
            b.append(s);
        }
        return b.toString();
    }

    /**
     * Whether a tooth.json platform pattern matches the platform (RID) as DotNet.Glob 3.1 (lip v0.37.0) matches
     * it: '*' and '?' match characters other than '/' and '\\', "**" matches one or more characters at
     * the end and nothing elsewhere (on separator-free RIDs); a class starting with a letter or digit
     * followed by '-' is the range of its first two characters ("[a-zA-Z]" is a-z); any other class is a
     * list of characters, its first one taken literally; "]]" is a literal ']'; '!' negates.
     */
    static boolean platformMatches(String pattern, String platform) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' && pattern.startsWith("*", i + 1)) re.append(++i + 1 == pattern.length() ? "(?s:.+)" : "");
            else if (c == '*') re.append("[^/\\\\]*");
            else if (c == '?') re.append("[^/\\\\]");
            else if (c == '/' || c == '\\') re.append("[/\\\\]");
            else if (c != '[') re.append(Pattern.quote(String.valueOf(c)));
            else {
                boolean negate = i + 1 < pattern.length() && pattern.charAt(i + 1) == '!';
                int k = negate ? i + 2 : i + 1;
                boolean range = k + 1 < pattern.length() && Character.isLetterOrDigit(pattern.charAt(k)) && pattern.charAt(k + 1) == '-';
                StringBuilder set = new StringBuilder();
                if (k < pattern.length()) set.append(pattern.charAt(k++));
                if (range) k++;
                for (; k < pattern.length(); k++) {
                    if (pattern.charAt(k) == ']' && (k + 1 == pattern.length() || pattern.charAt(k + 1) != ']')) break;
                    set.append(pattern.charAt(k));
                }
                i = k;
                if (range && set.length() < 2) return false; // DotNet.Glob rejects the pattern
                if (set.length() == 0 || range && set.charAt(1) < set.charAt(0)) {
                    re.append(negate ? "(?s:.)" : "(?!)");
                    continue;
                }
                re.append(negate ? "[^" : "[");
                for (int n = 0; n < (range ? 2 : set.length()); n++) re.append(n == 1 && range ? "-" : "").append("\\x{").append(Integer.toHexString(set.charAt(n))).append('}');
                re.append(']');
            }
        }
        return platform.matches(re.toString());
    }

    /** Reads a tooth.json object as a validated format 3 one (templates rendered). */
    private static JsonObject format3(JsonObject t) {
        JsonElement fv = t.get("format_version"); // written as 1, 2 or 3 exactly (lip v0.37.0 rejects 3.0)
        int format = fv != null && fv.isJsonPrimitive() && fv.getAsJsonPrimitive().isNumber()
                && fv.getAsString().matches("[123]") ? fv.getAsInt() : -1;
        if (format == 1) t = v2ToV3(v1ToV2(t));
        else if (format == 2) t = v2ToV3(t);
        else if (format == 3) t = Manifest.render(t, t).getAsJsonObject();
        else throw new IllegalArgumentException("format_version is not 1, 2 or 3");
        boolean written3 = format == 3;
        if (!FORMAT_UUID.equals(Manifest.string(t, "format_uuid", true))) throw new IllegalArgumentException("unknown format_uuid");
        String id = Manifest.string(t, "tooth", true);
        String reason = Source.checkModulePath(id);
        if (reason != null) throw new IllegalArgumentException("tooth '" + id + "': " + reason);
        if (!t.has("version") || !t.get("version").isJsonNull()) looseVersion(Manifest.string(t, "version", true));
        JsonObject info = obj(t, "info");
        if (info != null) {
            for (String tag : list(info, "tags")) if (!TAG.matcher(tag).matches()) throw new IllegalArgumentException("tag '" + tag + "'");
        }
        if (t.has("variants") && t.get("variants").isJsonNull()) throw new IllegalArgumentException("variants is null");
        for (JsonObject v : objects(t, "variants")) {
            noNull(v, "label", "platform");
            if (!str(v, "label").isEmpty() && !Source.isVariant(str(v, "label"))) throw new IllegalArgumentException("label '" + str(v, "label") + "'");
            str(v, "platform");
            JsonObject deps = obj(v, "dependencies");
            for (String k : deps == null ? new LinkedHashSet<String>() : deps.keySet()) {
                if (Manifest.checkDependencyKey(k) != null || SemVer.Range.parse(Manifest.string(deps, k, true)) == null) {
                    throw new IllegalArgumentException("dependency " + k);
                }
            }
            for (JsonObject a : objects(v, "assets")) {
                if (!Manifest.string(a, "type", true).matches("self|tar|tgz|uncompressed|zip")) throw new IllegalArgumentException("asset type");
                for (String u : list(a, "urls")) if (written3 && u.isEmpty()) throw new IllegalArgumentException("empty url");
                for (JsonObject p : objects(a, "placements")) {
                    String dest = Manifest.string(p, "dest", true);
                    Manifest.string(p, "src", true);
                    if (!Manifest.string(p, "type", true).matches("file|dir") || dest.contains("..") || dest.startsWith("/")) {
                        throw new IllegalArgumentException("placement to '" + dest + "'");
                    }
                }
            }
            list(v, "preserve_files");
            list(v, "remove_files");
            JsonObject s = obj(v, "scripts");
            for (String key : SCRIPTS) if (s != null) list(s, key);
        }
        return t;
    }

    /** A tooth.json version read leniently (leading 'v', missing minor or patch, leading zeros). */
    static String looseVersion(String s) {
        Matcher m = Pattern.compile("\\s*[vV]?([0-9]+)(?:\\.([0-9]+))?(?:\\.([0-9]+))?([-+].*?)?\\s*").matcher(s);
        SemVer v = m.matches() ? SemVer.parse(num(m.group(1)) + "." + num(m.group(2)) + "." + num(m.group(3))
                + (m.group(4) == null ? "" : m.group(4))) : null;
        if (v == null) throw new IllegalArgumentException("version '" + s + "' is not a version");
        return v.toString();
    }

    private static String num(String digits) {
        return digits == null ? "0" : new java.math.BigInteger(digits).toString();
    }

    /** lip v0.37.0's format 1 to format 2 upgrade. */
    private static JsonObject v1ToV2(JsonObject m) {
        Manifest.string(m, "tooth", true);
        Manifest.string(m, "version", true);
        JsonObject out = new JsonObject();
        out.add("tooth", m.get("tooth"));
        out.add("version", m.get("version"));
        JsonObject information = obj(m, "information");
        JsonObject info = new JsonObject();
        info.addProperty("name", information == null ? "" : orEmpty(opt(information, "name")));
        info.addProperty("description", information == null ? "" : orEmpty(opt(information, "description")));
        info.add("tags", stringArray(information == null ? new ArrayList<String>() : list(information, "tags")));
        out.add("info", info);
        JsonObject root = new JsonObject();
        Map<String, JsonObject> platforms = new LinkedHashMap<>();
        for (JsonObject c : objects(m, "commands")) {
            String type = Manifest.string(c, "type", true), goos = str(c, "GOOS");
            String key = type.equalsIgnoreCase("install") ? "post_install" : type.equalsIgnoreCase("uninstall") ? "pre_uninstall" : null;
            JsonObject target = root;
            if (!goos.isEmpty()) {
                String goarch = opt(c, "GOARCH");
                JsonObject p = platforms.get(goos + "/" + goarch);
                if (p == null) {
                    platforms.put(goos + "/" + goarch, p = new JsonObject());
                    p.addProperty("goos", goos);
                    if (goarch != null) p.addProperty("goarch", goarch);
                    p.add("commands", new JsonObject());
                }
                target = p.getAsJsonObject("commands");
            }
            noNull(c, "commands");
            if (key == null) continue;
            JsonArray cmds = target.has(key) ? target.getAsJsonArray(key) : new JsonArray();
            cmds.addAll(stringArray(list(c, "commands")));
            target.add(key, cmds);
        }
        if (root.size() > 0) out.add("commands", root);
        JsonObject deps = obj(m, "dependencies");
        if (deps != null) {
            JsonObject d = new JsonObject();
            for (String k : deps.keySet()) {
                List<String> groups = new ArrayList<>();
                if (!deps.get(k).isJsonArray()) throw new IllegalArgumentException("dependency " + k + " is not an array");
                for (JsonElement g : deps.getAsJsonArray(k)) {
                    if (!g.isJsonArray()) throw new IllegalArgumentException("dependency " + k + " is not an array of arrays");
                    List<String> parts = new ArrayList<>();
                    for (JsonElement s : g.getAsJsonArray()) parts.add(s.isJsonNull() ? "" : s.getAsString());
                    groups.add(String.join(" && ", parts));
                }
                d.addProperty(k, String.join(" || ", groups));
            }
            out.add("dependencies", d);
        }
        if (m.has("placement")) {
            JsonArray place = new JsonArray();
            for (JsonObject p : objects(m, "placement")) {
                JsonObject o = new JsonObject();
                o.addProperty("src", Manifest.string(p, "source", true));
                o.addProperty("dest", Manifest.string(p, "destination", true).replaceAll("\\*+$", ""));
                place.add(o);
            }
            JsonObject files = new JsonObject();
            files.add("place", place);
            out.add("files", files);
        }
        if (!platforms.isEmpty()) {
            JsonArray p = new JsonArray();
            for (JsonObject o : platforms.values()) p.add(o);
            out.add("platforms", p);
        }
        return out;
    }

    /** lip v0.37.0's format 2 to format 3 upgrade. */
    private static JsonObject v2ToV3(JsonObject m) {
        String tooth = Manifest.string(m, "tooth", true), version = Manifest.string(m, "version", true);
        JsonObject info = obj(m, "info");
        if (info == null) throw new IllegalArgumentException("'info' is missing");
        JsonObject vars = new JsonObject();
        vars.addProperty("version", version);
        vars.addProperty("path", tooth);
        JsonObject files = obj(m, "files");
        List<JsonObject> platforms = new ArrayList<>();
        for (JsonElement e : m.has("platforms") && !m.get("platforms").isJsonNull() ? Manifest.array(m, "platforms") : new JsonArray()) {
            if (!e.isJsonNull() && !e.isJsonObject()) throw new IllegalArgumentException("a platform is not an object");
            platforms.add(e.isJsonNull() ? null : e.getAsJsonObject());
        }
        if (!m.has("platforms") || m.get("platforms").isJsonNull()) platforms.add(null);
        JsonArray variants = new JsonArray();
        JsonObject empty = null;
        for (JsonObject p : platforms) {
            JsonObject pFiles = p == null ? null : obj(p, "files");
            JsonObject depSrc = p != null && p.has("dependencies") ? obj(p, "dependencies") : obj(m, "dependencies");
            JsonObject deps = new JsonObject();
            for (String k : depSrc == null ? new LinkedHashSet<String>() : depSrc.keySet()) {
                String key = k.endsWith("#") ? k.substring(0, k.length() - 1) : k;
                JsonElement r = depSrc.get(k);
                String range = r.isJsonNull() ? null : p == null ? r.getAsString() : npmRange(r.getAsString());
                if (Manifest.checkDependencyKey(key) == null && range != null && SemVer.Range.parse(range) != null) {
                    deps.addProperty(key, range);
                }
            }
            JsonArray places = pFiles != null && pFiles.has("place") ? pFiles.getAsJsonArray("place")
                    : files != null ? Manifest.array(files, "place") : null;
            JsonArray assets = new JsonArray();
            String assetUrl = p != null && p.has("asset_url") ? opt(p, "asset_url") : opt(m, "asset_url");
            if (assetUrl != null || p == null && places != null) {
                JsonObject a = new JsonObject();
                String url = assetUrl == null ? null : v2Render(assetUrl, vars);
                a.addProperty("type", url == null ? "self" : assetType(url));
                JsonArray urls = new JsonArray();
                if (url != null) urls.add(url);
                a.add("urls", urls);
                JsonArray placements = new JsonArray();
                for (JsonElement e : places == null ? new JsonArray() : places) {
                    JsonObject pl = e.getAsJsonObject();
                    String src = Manifest.string(pl, "src", true);
                    JsonObject o = new JsonObject();
                    o.addProperty("type", src.endsWith("*") ? "dir" : "file");
                    o.addProperty("src", v2Render(src, vars).replaceAll("\\*+$", ""));
                    o.addProperty("dest", v2Render(orEmpty(opt(pl, "dest")), vars));
                    placements.add(o);
                }
                a.add("placements", placements);
                assets.add(a);
            }
            JsonObject cmds = p != null && p.has("commands") ? obj(p, "commands") : obj(m, "commands");
            JsonObject scripts = new JsonObject();
            for (String key : SCRIPTS) {
                List<String> l = cmds == null || key.equals("install") || key.equals("uninstall") ? new ArrayList<String>() : list(cmds, key);
                JsonArray out = new JsonArray();
                for (String s : l) out.add(v2Render(s, vars));
                scripts.add(key, out);
            }
            String platform = p == null ? "" : goPlatform(p);
            if (platform.contains("*") && empty == null) {
                empty = new JsonObject(); // lip v0.37.0 then adds an empty variant for every platform
                empty.addProperty("label", "");
                empty.addProperty("platform", "");
            }
            JsonObject v = new JsonObject();
            v.addProperty("label", "");
            v.addProperty("platform", platform);
            v.add("dependencies", deps);
            v.add("assets", assets);
            v.add("preserve_files", v2Globs(pFiles, files, "preserve", vars));
            v.add("remove_files", v2Globs(pFiles, files, "remove", vars));
            v.add("scripts", scripts);
            variants.add(v);
        }
        if (empty != null) variants.add(empty);
        JsonObject out = new JsonObject();
        out.addProperty("format_version", 3);
        out.addProperty("format_uuid", FORMAT_UUID);
        out.addProperty("tooth", tooth);
        out.addProperty("version", version);
        JsonObject i = new JsonObject();
        i.add("name", info.get("name") == null ? JsonNull.INSTANCE : info.get("name"));
        i.add("description", info.get("description") == null ? JsonNull.INSTANCE : info.get("description"));
        i.add("tags", opt(info, "tags") == null && !(info.get("tags") instanceof JsonArray) ? JsonNull.INSTANCE : stringArray(list(info, "tags")));
        i.addProperty("avatar_url", v2Render(orEmpty(opt(info, "avatar_url")), vars));
        out.add("info", i);
        out.add("variants", variants);
        return out;
    }

    /** A format 2 platform dependency range (npm syntax) in the standard syntax; null when unsupported. */
    static String npmRange(String r) {
        if (SemVer.Range.parse(r) != null) return r;
        List<String> alternatives = new ArrayList<>();
        for (String alt : r.trim().split("\\s*\\|\\|\\s*", -1)) {
            List<String> parts = new ArrayList<>();
            for (String c : alt.trim().split("\\s+")) {
                Matcher m = Pattern.compile("(>=|<=|>|<|=|~|\\^)?\\s*[vV]?([0-9xX*]+)(?:\\.([0-9xX*]+))?(?:\\.([0-9xX*]+))?(-.*)?").matcher(c);
                if (c.isEmpty()) continue;
                if (!m.matches()) return null;
                String op = m.group(1) == null || m.group(1).equals("=") ? "" : m.group(1);
                StringBuilder v = new StringBuilder();
                boolean wild = false;
                for (int k = 2; k <= 4; k++) {
                    String n = m.group(k) == null || m.group(k).matches("[xX*]") ? null : m.group(k);
                    wild |= n == null;
                    v.append(k > 2 ? "." : "").append(wild ? (op.isEmpty() ? "*" : "0") : n);
                }
                parts.add(op + v.toString().replaceAll("^\\*(\\.\\*)*$", "*").replaceAll("(\\.\\*)+$", ".*") + (m.group(5) == null ? "" : m.group(5)));
            }
            alternatives.add(parts.isEmpty() ? "*" : String.join(" ", parts));
        }
        String out = String.join(" || ", alternatives);
        return SemVer.Range.parse(out) != null ? out : null;
    }

    private static void stringOrNull(JsonElement e) {
        if (e != null && !e.isJsonNull() && !(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())) {
            throw new IllegalArgumentException("a value is not a string");
        }
    }

    private static JsonArray v2Globs(JsonObject pFiles, JsonObject files, String key, JsonObject vars) {
        List<String> l = pFiles != null && pFiles.has(key) ? list(pFiles, key) : files != null ? list(files, key) : new ArrayList<String>();
        JsonArray out = new JsonArray();
        for (String s : l) {
            String g = v2Render(s, vars);
            if (g.isEmpty()) throw new IllegalArgumentException("empty glob");
            out.add(g);
        }
        return out;
    }

    private static String v2Render(String s, JsonObject vars) {
        return Template.render(V2_TEMPLATE.matcher(s).replaceAll("{{$1}}"), vars);
    }

    private static String assetType(String url) {
        String ext = url.substring(url.lastIndexOf('.') + 1);
        return ext.equals("tar") ? "tar" : ext.equals("tgz") || ext.equals("gz") ? "tgz" : ext.equals("zip") ? "zip" : "uncompressed";
    }

    private static String goPlatform(JsonObject p) {
        String goos = Manifest.string(p, "goos", true), goarch = Manifest.string(p, "goarch", false);
        String os = goos.equals("windows") ? "win" : goos.equals("darwin") ? "osx" : goos.matches("linux|ios|android") ? goos : null;
        String arch = goarch == null ? "*" : goarch.equals("amd64") ? "x64" : goarch.equals("386") ? "x86"
                : goarch.matches("arm64|arm") ? goarch : goarch.equals("loong64") ? "loongarch64" : null;
        if (os == null || arch == null) throw new IllegalArgumentException("unsupported GOOS/GOARCH " + goos + "/" + goarch);
        return os + "-" + arch;
    }

    private static void copy(JsonObject from, JsonObject to, String key) {
        String s = opt(from, key);
        if (s != null) to.addProperty(key, s);
    }

    /** Throws when one of {@code keys} is present with the value null. */
    private static void noNull(JsonObject o, String... keys) {
        for (String k : keys) if (o.has(k) && o.get(k).isJsonNull()) throw new IllegalArgumentException("'" + k + "' is null");
    }

    /** An object member, null when absent or null. */
    private static JsonObject obj(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? Manifest.object(o, key) : null;
    }

    /** A string member, null when absent or null. */
    private static String opt(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive() ? Manifest.string(o, key, false) : null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** A string member, "" when absent or null. */
    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? Manifest.string(o, key, true) : "";
    }

    /** An array of strings, empty when absent or null. */
    private static List<String> list(JsonObject o, String key) {
        List<String> l = new ArrayList<>();
        if (!o.has(key) || o.get(key).isJsonNull()) return l;
        for (JsonElement e : Manifest.array(o, key)) {
            stringOrNull(e);
            l.add(e.isJsonNull() ? "" : e.getAsString());
        }
        return l;
    }

    /** An array of objects, empty when absent or null. */
    private static List<JsonObject> objects(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (!o.has(key) || o.get(key).isJsonNull()) return out;
        for (JsonElement e : Manifest.array(o, key)) {
            if (!e.isJsonObject()) throw new IllegalArgumentException("a member of '" + key + "' is not an object");
            out.add(e.getAsJsonObject());
        }
        return out;
    }

    private static JsonArray stringArray(List<String> l) {
        JsonArray a = new JsonArray();
        for (String s : l) a.add(s);
        return a;
    }
}
