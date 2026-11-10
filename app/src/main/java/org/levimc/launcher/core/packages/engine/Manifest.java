package org.levimc.launcher.core.packages.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A package file (package 3, lip.schema.toml): parsed from {@code lip.toml} with its templates
 * rendered, derived from a {@code tooth.json} ({@link ToothCompat}), or read back from the state file.
 * Members the schema does not describe are kept in {@link #json} and otherwise ignored.
 */
final class Manifest {
    static final String FILE = "lip.toml";
    static final String SIDE_CLIENT = "client", SIDE_SERVER = "server";
    /** Why an entry, or the state file, is invalid when it uses the keys of lip before v0.40 (as lip words it). */
    static final String OLD_KEYS_REASON = "uses the keys \"platform\" and \"runtime\" of lip before v0.40; rename"
            + " \"platform\" to \"side\" and \"runtime\" to \"platform\"";

    /** A platform: a .NET runtime identifier without wildcards. */
    private static final Pattern PLATFORM = Pattern.compile("[A-Za-z0-9_]+(?:[.-][A-Za-z0-9_]+)*");

    /** The package file as a JSON value (what the state file records). */
    final JsonObject json;
    final String id, version;
    /** The archive's file that is the package file: {@code lip.toml} or {@code tooth.json}; never written. */
    final String packageFile;
    /** Variant name ("" for the default variant) to its entries, in file order. */
    final Map<String, List<Entry>> variants = new LinkedHashMap<>();

    /** One entry of a variant. */
    static final class Entry {
        String side, platform;
        /** Dependency key ("id" or "id#variant") to range. */
        final Map<String, String> deps = new LinkedHashMap<>();
        final List<Asset> assets = new ArrayList<>();
        final List<String> preserve = new ArrayList<>(), purge = new ArrayList<>();
        final List<String> preInstall = new ArrayList<>(), postInstall = new ArrayList<>();
        final List<String> preUninstall = new ArrayList<>(), postUninstall = new ArrayList<>();

        /** The asset of type "mcmod", or null. */
        Asset mcmod() {
            for (Asset a : assets) if (a.type.equals("mcmod")) return a;
            return null;
        }
    }

    /** An asset: type "self", "zip" or "mcmod". */
    static final class Asset {
        String type;
        final List<String> urls = new ArrayList<>();
        final List<Place> place = new ArrayList<>();
    }

    /** A member of {@code place}. */
    static final class Place {
        enum Form { FILE, DIRECTORY, GLOB }

        final String src, dest;
        final Form form;

        Place(String src, String dest) {
            this.src = src;
            this.dest = dest;
            form = Glob.hasMeta(src) ? Form.GLOB : src.isEmpty() || src.endsWith("/") ? Form.DIRECTORY : Form.FILE;
        }
    }

    private Manifest(JsonObject json, String packageFile) {
        this.json = json;
        this.packageFile = packageFile;
        this.id = string(json, "id", true);
        this.version = string(json, "version", true);
    }

    /**
     * Reads the package file of an archive from its root files {@code lip.toml} and {@code tooth.json}
     * (either may be null) for the environment; {@code tagVersion} (or null) is the tag version of a git
     * archive. An archive that is not a package is {@code INVALID_PACKAGE}.
     */
    static Manifest read(byte[] packageToml, byte[] toothJson, String side, String platform, String tagVersion)
            throws PackageException {
        if (packageToml != null) return parseToml(packageToml);
        if (toothJson == null) throw new PackageException(PackageException.Kind.INVALID_PACKAGE, null, "Not a package: the archive has no " + FILE);
        try {
            return of(ToothCompat.derive(toothJson, side, platform, tagVersion), ToothCompat.FILE);
        } catch (IllegalArgumentException e) {
            throw invalid("its " + ToothCompat.FILE + " cannot be read: ", e);
        }
    }

    /** Parses {@code lip.toml}: TOML, then templates rendered with its top-level members, then validated. */
    static Manifest parseToml(byte[] data) throws PackageException {
        try {
            JsonObject written = TextFormats.parseToml(data);
            return of(render(written, written).getAsJsonObject(), FILE);
        } catch (IllegalArgumentException e) {
            throw invalid("its " + FILE + " is not valid: ", e);
        }
    }

    /** Validates a package file value (from the state file, or derived). */
    static Manifest of(JsonObject json) throws PackageException {
        try {
            return of(json, FILE);
        } catch (IllegalArgumentException e) {
            throw invalid("the package file is not valid: ", e);
        }
    }

    /** {@code e} as INVALID_PACKAGE; a failure lip words ({@link Invalid}) keeps lip's message. */
    private static PackageException invalid(String context, IllegalArgumentException e) {
        String message = e instanceof Invalid ? e.getMessage() : "Not a package: " + context + e.getMessage();
        return new PackageException(PackageException.Kind.INVALID_PACKAGE, null, message, e);
    }

    /** A failure at a JSON path of a package file, worded as lip words it. */
    private static final class Invalid extends IllegalArgumentException {
        Invalid(String path, String reason) {
            super("Invalid package file at " + path + ": " + reason + ".");
        }
    }

    /** Whether an entry or a state file uses the keys of lip before v0.40: "runtime", or a side as "platform". */
    static boolean usesOldKeys(JsonObject o) {
        JsonElement p = o.get("platform");
        return o.has("runtime") || p != null && p.isJsonPrimitive() && p.getAsJsonPrimitive().isString()
                && (p.getAsString().equals(SIDE_SERVER) || p.getAsString().equals(SIDE_CLIENT));
    }

    /** {@code v} with each string rendered as a template with the members of {@code vars} as variables. */
    static JsonElement render(JsonElement v, JsonObject vars) {
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
            return new JsonPrimitive(Template.render(v.getAsString(), vars));
        }
        if (v.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement e : v.getAsJsonArray()) out.add(render(e, vars));
            return out;
        }
        if (v.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) out.add(e.getKey(), render(e.getValue(), vars));
            return out;
        }
        return v;
    }

    /** The entry of variant {@code variant} for the environment, or null. */
    Entry entry(String variant, String side, String platform) {
        List<Entry> entries = variants.get(variant);
        if (entries != null) {
            for (Entry e : entries) if (e.side.equals(side) && e.platform.equals(platform)) return e;
        }
        return null;
    }

    /** Validates {@code json} against lip.schema.toml and package 3; throws IllegalArgumentException. */
    private static Manifest of(JsonObject json, String packageFile) {
        if (!isNumber(json.get("format"), 4)) throw new IllegalArgumentException("'format' is not 4");
        Manifest m = new Manifest(json, packageFile);
        String reason = Source.checkModulePath(m.id);
        if (reason != null) throw new IllegalArgumentException("id '" + m.id + "' is not a module path: " + reason);
        if (SemVer.parse(m.version) == null) throw new IllegalArgumentException("version '" + m.version + "' is not a version");
        JsonObject info = object(json, "info");
        if (info != null) {
            string(info, "name", false);
            string(info, "description", false);
            strings(info, "tags");
            String logo = string(info, "logo", false);
            if (logo != null && !logo.startsWith("https://")) throw new IllegalArgumentException("info.logo is not an https URL");
        }
        JsonObject variants = object(json, "variants");
        if (variants == null) throw new IllegalArgumentException("'variants' is missing");
        for (Map.Entry<String, JsonElement> v : variants.entrySet()) {
            String name = v.getKey();
            if (!name.isEmpty() && !Source.isVariant(name)) throw new IllegalArgumentException("'" + name + "' is not a variant");
            if (!v.getValue().isJsonArray() || v.getValue().getAsJsonArray().size() == 0) {
                throw new IllegalArgumentException("variants['" + name + "'] is not a non-empty array");
            }
            List<Entry> entries = new ArrayList<>();
            List<String> environments = new ArrayList<>();
            String path = "$.variants[\"" + name + "\"]";
            for (JsonElement e : v.getValue().getAsJsonArray()) {
                if (!e.isJsonObject()) throw new IllegalArgumentException("an entry of '" + name + "' is not an object");
                Entry entry = entry(e.getAsJsonObject(), path + "[" + entries.size() + "]");
                int same = environments.indexOf(entry.side + "\n" + entry.platform);
                if (same >= 0) throw new Invalid(path + "[" + entries.size() + "]", "has the side and the platform of " + path + "[" + same + "]");
                environments.add(entry.side + "\n" + entry.platform);
                entries.add(entry);
            }
            m.variants.put(name, Collections.unmodifiableList(entries));
        }
        return m;
    }

    private static Entry entry(JsonObject o, String path) {
        if (usesOldKeys(o)) throw new Invalid(path, OLD_KEYS_REASON);
        Entry e = new Entry();
        e.side = string(o, "side", true);
        e.platform = string(o, "platform", true);
        if (!e.side.equals(SIDE_SERVER) && !e.side.equals(SIDE_CLIENT)) throw new Invalid(path + ".side", "must be \"server\" or \"client\"");
        if (!PLATFORM.matcher(e.platform).matches()) throw new Invalid(path + ".platform", "'" + e.platform + "' is not a runtime identifier");
        JsonObject deps = object(o, "deps");
        if (deps != null) {
            for (String key : deps.keySet()) {
                String range = string(deps, key, true);
                String reason = checkDependencyKey(key);
                if (reason != null) throw new IllegalArgumentException("dependency '" + key + "': " + reason);
                if (SemVer.Range.parse(range) == null) throw new IllegalArgumentException("'" + range + "' is not a range");
                e.deps.put(key, range);
            }
        }
        JsonArray assets = array(o, "assets");
        for (int i = 0; assets != null && i < assets.size(); i++) {
            if (!assets.get(i).isJsonObject()) throw new IllegalArgumentException("an asset is not an object");
            JsonObject a = assets.get(i).getAsJsonObject();
            Asset asset = new Asset();
            asset.type = string(a, "type", true);
            if (!asset.type.matches("self|zip|mcmod")) throw new IllegalArgumentException("asset type '" + asset.type + "'");
            if (!asset.type.equals("self")) {
                List<String> urls = strings(a, "urls");
                if (urls == null || urls.isEmpty()) throw new IllegalArgumentException("a " + asset.type + " asset has no urls");
                asset.urls.addAll(urls);
            }
            JsonArray place = array(a, "place");
            if (place != null && asset.type.equals("mcmod")) throw new IllegalArgumentException("an mcmod asset has place");
            for (int k = 0; place != null && k < place.size(); k++) {
                if (!place.get(k).isJsonObject()) throw new IllegalArgumentException("a member of place is not an object");
                JsonObject p = place.get(k).getAsJsonObject();
                String src = string(p, "src", true), dest = string(p, "dest", true);
                String reason = checkPlace(src, dest);
                if (reason != null) throw new IllegalArgumentException("place " + src + " -> " + dest + ": " + reason);
                asset.place.add(new Place(src, dest));
            }
            e.assets.add(asset);
        }
        int mcmods = 0;
        for (Asset a : e.assets) if (a.type.equals("mcmod")) mcmods++;
        if (mcmods > 1) throw new IllegalArgumentException("an entry has more than one mcmod asset");
        globs(o, "preserve", e.preserve);
        globs(o, "purge", e.purge);
        JsonObject hooks = object(o, "hooks");
        if (hooks != null) {
            addAll(e.preInstall, strings(hooks, "pre_install"));
            addAll(e.postInstall, strings(hooks, "post_install"));
            addAll(e.preUninstall, strings(hooks, "pre_uninstall"));
            addAll(e.postUninstall, strings(hooks, "post_uninstall"));
        }
        return e;
    }

    /** Null when "id" or "id#variant" is a valid dependency key, else the reason. */
    static String checkDependencyKey(String key) {
        int hash = key.indexOf('#');
        if (hash >= 0 && !Source.isVariant(key.substring(hash + 1))) return "'" + key.substring(hash + 1) + "' is not a variant";
        return Source.checkModulePath(hash < 0 ? key : key.substring(0, hash));
    }

    /** Null when (src, dest) is a valid member of place (definitions.path and definitions.place), else why. */
    static String checkPlace(String src, String dest) {
        for (String path : new String[] {src, dest}) {
            if (path.startsWith("/") || path.matches("[A-Za-z]:.*") || path.contains("//") || path.indexOf('\\') >= 0
                    || ("/" + path + "/").contains("/../")) {
                return "'" + path + "' is not a valid path";
            }
        }
        boolean dirDest = dest.isEmpty() || dest.endsWith("/");
        if (Glob.hasMeta(src)) return Glob.check(src) != null ? "'" + src + "' is not a valid glob" : dirDest ? null : "dest must end with '/'";
        if (src.isEmpty() || src.endsWith("/")) return dirDest ? null : "dest must end with '/'";
        return dest.endsWith("/") ? "dest must not end with '/'" : null;
    }

    private static void globs(JsonObject o, String key, List<String> out) {
        List<String> globs = strings(o, key);
        if (globs == null) return;
        for (String g : globs) {
            String reason = Glob.check(g);
            if (reason != null) throw new IllegalArgumentException(key + " glob '" + g + "': " + reason);
        }
        out.addAll(globs);
    }

    private static void addAll(List<String> to, List<String> from) {
        if (from != null) to.addAll(from);
    }

    /** Whether {@code v} is the JSON number {@code n}. */
    static boolean isNumber(JsonElement v, int n) {
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()
                && v.getAsBigDecimal().compareTo(BigDecimal.valueOf(n)) == 0;
    }

    static String string(JsonObject o, String key, boolean required) {
        JsonElement v = o.get(key);
        if (v == null) {
            if (required) throw new IllegalArgumentException("'" + key + "' is missing");
            return null;
        }
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("'" + key + "' is not a string");
        return v.getAsString();
    }

    static JsonObject object(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null) return null;
        if (!v.isJsonObject()) throw new IllegalArgumentException("'" + key + "' is not an object");
        return v.getAsJsonObject();
    }

    static JsonArray array(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null) return null;
        if (!v.isJsonArray()) throw new IllegalArgumentException("'" + key + "' is not an array");
        return v.getAsJsonArray();
    }

    /** An array of strings, or null when absent. */
    static List<String> strings(JsonObject o, String key) {
        JsonArray a = array(o, key);
        if (a == null) return null;
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("'" + key + "' has a non-string");
            out.add(e.getAsString());
        }
        return out;
    }
}
