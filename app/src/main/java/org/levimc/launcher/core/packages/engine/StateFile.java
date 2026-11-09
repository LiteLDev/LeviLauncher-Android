package org.levimc.launcher.core.packages.engine;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** The state file {@code .lip/state.json} (workspace 7.1, state.schema.json). Immutable. */
final class StateFile {
    static final String PATH = ".lip/state.json";
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    final String side, platform;
    /** Installed packages in installation order. */
    final List<Pkg> packages;

    /** One installed package. */
    static final class Pkg {
        final Manifest manifest;
        final String variant, ref, commit;
        final boolean explicit;
        /** Owned files, workspace-relative and '/'-separated. */
        final List<String> files;

        Pkg(Manifest manifest, String variant, boolean explicit, String ref, String commit, List<String> files) {
            this.manifest = manifest;
            this.variant = variant;
            this.explicit = explicit;
            this.ref = ref;
            this.commit = commit;
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
        }

        /** The identity: id, then '#' and the variant when not "". */
        String identity() {
            return variant.isEmpty() ? manifest.id : manifest.id + "#" + variant;
        }
    }

    StateFile(String side, String platform, List<Pkg> packages) {
        this.side = side;
        this.platform = platform;
        this.packages = Collections.unmodifiableList(new ArrayList<>(packages));
    }

    /** Reads a state file; an invalid one is {@code INVALID_WORKSPACE}, worded as lip words it. */
    static StateFile parse(byte[] data) throws PackageException {
        boolean otherFormat = false;
        try {
            JsonElement v = TextFormats.parseJson(data);
            if (!v.isJsonObject()) throw new IllegalArgumentException("not an object");
            JsonObject o = v.getAsJsonObject();
            JsonElement format = o.get("format");
            if (!Manifest.isNumber(format, 4)) {
                // lip: another integer is another format; a missing or non-integer one is an invalid file.
                otherFormat = format != null && format.isJsonPrimitive() && format.getAsJsonPrimitive().isNumber()
                        && format.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
                throw new IllegalArgumentException("'format' is not 4");
            }
            if (Manifest.usesOldKeys(o)) {
                otherFormat = true;
                throw new IllegalArgumentException("The file " + Manifest.OLD_KEYS_REASON + ".");
            }
            String side = Manifest.string(o, "side", false), platform = Manifest.string(o, "platform", false);
            if (!Manifest.SIDE_CLIENT.equals(side) && !Manifest.SIDE_SERVER.equals(side)) {
                throw new IllegalArgumentException("\"side\" is \"" + (side == null ? "" : side) + "\", but it must be \"server\" or \"client\".");
            }
            if (platform == null || platform.isEmpty()) {
                throw new IllegalArgumentException("\"platform\" is missing or empty, but it must be a runtime identifier such as \"win-x64\".");
            }
            JsonArray packages = Manifest.array(o, "packages");
            if (packages == null) throw new IllegalArgumentException("'packages' is missing");
            List<Pkg> pkgs = new ArrayList<>();
            for (JsonElement e : packages) {
                if (!e.isJsonObject()) throw new IllegalArgumentException("a package is not an object");
                JsonObject p = e.getAsJsonObject();
                JsonObject manifest = Manifest.object(p, "manifest");
                JsonElement explicit = p.get("explicit");
                if (manifest == null || explicit == null || !explicit.isJsonPrimitive() || !explicit.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("a package lacks manifest or explicit");
                }
                String commit = Manifest.string(p, "commit", false);
                if (commit != null && !COMMIT.matcher(commit).matches()) throw new IllegalArgumentException("commit '" + commit + "'");
                List<String> files = Manifest.strings(p, "files");
                if (files == null) throw new IllegalArgumentException("a package lacks files");
                pkgs.add(new Pkg(Manifest.of(manifest), Manifest.string(p, "variant", true), explicit.getAsBoolean(),
                        Manifest.string(p, "ref", true), commit, files));
            }
            return new StateFile(side, platform, pkgs);
        } catch (IllegalArgumentException | PackageException e) {
            throw new PackageException(PackageException.Kind.INVALID_WORKSPACE, null, otherFormat
                    ? "The state file '" + PATH + "' was written by a different version of lip, so this lip cannot tell which"
                            + " packages are installed."
                    : "The state file '" + PATH + "' is invalid, so lip cannot tell which packages are installed. If you edited"
                            + " it by hand, undo the change; if you have a copy of the file, restore it.", e);
        }
    }

    /** The state file as JSON (UTF-8, indented). */
    byte[] toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("format", 4);
        o.addProperty("side", side);
        o.addProperty("platform", platform);
        JsonArray packages = new JsonArray();
        for (Pkg p : this.packages) {
            JsonObject j = new JsonObject();
            j.add("manifest", p.manifest.json);
            j.addProperty("variant", p.variant);
            j.addProperty("explicit", p.explicit);
            j.addProperty("ref", p.ref);
            if (p.commit != null) j.addProperty("commit", p.commit);
            JsonArray files = new JsonArray();
            for (String f : p.files) files.add(f);
            j.add("files", files);
            packages.add(j);
        }
        o.add("packages", packages);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o).getBytes(StandardCharsets.UTF_8);
    }
}
