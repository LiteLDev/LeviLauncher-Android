package org.levimc.launcher.core.packages;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** Writes a workspace's state file (.lip/state.json) for tests. */
public final class WorkspaceFixture {
    /** A package of a state file. */
    public static final class Pkg {
        final String id, variant, version, ref, depsJson;
        final boolean explicit;
        final List<String> files;

        public Pkg(String id, String variant, String version, boolean explicit, String depsJson, String... files) {
            this(id, variant, version, explicit, (variant.isEmpty() ? id : id + "#" + variant) + "@" + version, depsJson,
                    Arrays.asList(files));
        }

        private Pkg(String id, String variant, String version, boolean explicit, String ref, String depsJson, List<String> files) {
            this.id = id;
            this.variant = variant;
            this.version = version;
            this.explicit = explicit;
            this.ref = ref;
            this.depsJson = depsJson;
            this.files = files;
        }

        /** The package recorded with another source (e.g. a local file's file: URI). */
        public Pkg withRef(String newRef) {
            return new Pkg(id, variant, version, explicit, newRef, depsJson, files);
        }
    }

    private WorkspaceFixture() {
    }

    /** A workspace with LeviLamina, packages depending on it and on each other, and loose and folder mods. */
    public static void writeSample(File dir) throws IOException {
        write(dir, "android-arm64",
                new Pkg("github.com/LiteLDev/LeviLamina", "client", "1.2.0", false, null,
                        "mods/LeviLamina/manifest.json", "mods/LeviLamina/LeviLamina.so"),
                new Pkg("github.com/a/foo", "", "1.0.0", true,
                        "{ \"github.com/LiteLDev/LeviLamina#client\": \">=1.0.0 <2.0.0\" }",
                        "mods/foo/manifest.json", "mods/foo/libfoo.so", "mods/foo-extra/manifest.json"),
                new Pkg("example.com/bar", "", "0.1.0", true, "{ \"github.com/a/foo\": \"1.0.0\" }",
                        "mods/bar.so"),
                new Pkg("github.com/a/baz", "", "3.0.0", true, null, "mods/Baz/libbaz.so"),
                new Pkg("github.com/a/qux", "", "1.0.0", true, "{ \"github.com/a/foo\": \">=2.0.0\" }",
                        "mods/qux/libqux.so"));
    }

    /** Writes dir/.lip/state.json for (client, platform) with the packages in installation order. */
    public static void write(File dir, String platform, Pkg... packages) throws IOException {
        JsonArray list = new JsonArray();
        for (Pkg p : packages) {
            JsonObject entry = new JsonObject();
            entry.addProperty("side", "client");
            entry.addProperty("platform", platform);
            if (p.depsJson != null) entry.add("deps", JsonParser.parseString(p.depsJson));
            JsonArray entries = new JsonArray();
            entries.add(entry);
            JsonObject variants = new JsonObject();
            variants.add(p.variant, entries);
            JsonObject manifest = new JsonObject();
            manifest.addProperty("format", 4);
            manifest.addProperty("id", p.id);
            manifest.addProperty("version", p.version);
            manifest.add("variants", variants);
            JsonObject pkg = new JsonObject();
            pkg.add("manifest", manifest);
            pkg.addProperty("variant", p.variant);
            pkg.addProperty("explicit", p.explicit);
            pkg.addProperty("ref", p.ref);
            JsonArray files = new JsonArray();
            for (String f : p.files) files.add(f);
            pkg.add("files", files);
            list.add(pkg);
        }
        JsonObject state = new JsonObject();
        state.addProperty("format", 4);
        state.addProperty("side", "client");
        state.addProperty("platform", platform);
        state.add("packages", list);
        File meta = new File(dir, ".lip");
        if (!meta.isDirectory() && !meta.mkdirs()) throw new IOException("cannot create " + meta);
        Files.write(new File(meta, "state.json").toPath(), state.toString().getBytes(StandardCharsets.UTF_8));
    }
}
