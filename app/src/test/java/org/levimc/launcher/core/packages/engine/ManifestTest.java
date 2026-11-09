package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** lip.toml reading: TOML, templates, lip.schema.toml and package 3. */
public class ManifestTest {
    private static final String TOML = """
            format = 4
            id = "github.com/Example/Mod"
            version = "1.2.0"
            unknown = "ignored"

            [info]
            name = "Example {{version}}"
            tags = ["a"]
            logo = "https://{{id}}/logo.png"

            [[variants.""]]
            side = "client"
            platform = "android-arm64"
            deps = { "github.com/LiteLDev/LeviLamina#client" = "1.4.*", "github.com/x/y" = ">=1.0.0 <2.0.0" }
            preserve = ["mods/Example/config/"]
            purge = ["mods/Example/logs/"]
            hooks = { post_install = ["echo {{version}}"] }

            [[variants."".assets]]
            type = "mcmod"
            urls = ["https://{{id}}/releases/download/v{{version}}/mod.zip"]

            [[variants."".assets]]
            type = "self"
            place = [{ src = "res/", dest = "mods/Example/res/" }, { src = "bin/*.so", dest = "" }, { src = "a.txt", dest = "b.txt" }]

            [[variants.""]]
            side = "server"
            platform = "win-x64"
            """;

    private static Manifest parse(String toml) throws PackageException {
        return Manifest.parseToml(toml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void readsAndRendersPackageFile() throws Exception {
        Manifest m = parse(TOML);
        assertEquals("github.com/Example/Mod", m.id);
        assertEquals("1.2.0", m.version);
        assertEquals(Manifest.FILE, m.packageFile);
        assertEquals("Example 1.2.0", m.json.getAsJsonObject("info").get("name").getAsString());
        assertEquals("https://github.com/Example/Mod/logo.png", m.json.getAsJsonObject("info").get("logo").getAsString());
        Manifest.Entry e = m.entry("", "client", "android-arm64");
        assertNotNull(e);
        assertNull(m.entry("", "client", "win-x64"));
        assertNull(m.entry("client", "client", "android-arm64"));
        assertEquals("1.4.*", e.deps.get("github.com/LiteLDev/LeviLamina#client"));
        assertEquals("https://github.com/Example/Mod/releases/download/v1.2.0/mod.zip", e.mcmod().urls.get(0));
        assertEquals(Arrays.asList("echo 1.2.0"), e.postInstall);
        Manifest.Asset self = e.assets.get(1);
        assertEquals(Manifest.Place.Form.DIRECTORY, self.place.get(0).form);
        assertEquals(Manifest.Place.Form.GLOB, self.place.get(1).form);
        assertEquals(Manifest.Place.Form.FILE, self.place.get(2).form);
        assertEquals(m.json, Manifest.of(m.json).json);
    }

    @Test
    public void rejectsInvalidPackageFiles() {
        String head = "format = 4\nid = \"github.com/a/b\"\nversion = \"1.0.0\"\n";
        String entry = "[[variants.\"\"]]\nside = \"client\"\nplatform = \"android-arm64\"\n";
        String[] bad = {
                "format = 3\nid = \"github.com/a/b\"\nversion = \"1.0.0\"\nvariants = {}",
                "format = 4\nid = \"github.com/a/b\"\nversion = \"1.0.0\"",
                "format = 4\nid = \"not a path\"\nversion = \"1.0.0\"\nvariants = {}",
                "format = 4\nid = \"github.com/a/b\"\nversion = \"v1.0.0\"\nvariants = {}",
                head + "variants = { \"Bad\" = [{ side = \"client\", platform = \"x\" }] }",
                head + "variants = { \"\" = [] }",
                head + "[info]\nlogo = \"http://x\"\n" + entry,
                head + entry.replace("client", "desktop"),
                head + entry.replace("android-arm64", "") + "",
                head + entry.replace("android-arm64", "android-*"),
                head + entry + entry,
                head + entry + "deps = { \"github.com/x/y#\" = \"1.0.0\" }",
                head + entry + "deps = { \"github.com/x/y\" = \"1.x\" }",
                head + entry + "assets = [{ type = \"tar\", urls = [\"https://a\"] }]",
                head + entry + "assets = [{ type = \"zip\" }]",
                head + entry + "assets = [{ type = \"zip\", urls = [] }]",
                head + entry + "assets = [{ type = \"mcmod\", urls = [\"https://a\"], place = [] }]",
                head + entry + "assets = [{ type = \"mcmod\", urls = [\"https://a\"] }, { type = \"mcmod\", urls = [\"https://b\"] }]",
                head + entry + "assets = [{ type = \"self\", place = [{ src = \"a/\", dest = \"b\" }] }]",
                head + entry + "assets = [{ type = \"self\", place = [{ src = \"a\", dest = \"b/\" }] }]",
                head + entry + "assets = [{ type = \"self\", place = [{ src = \"*.so\", dest = \"lib\" }] }]",
                head + entry + "assets = [{ type = \"self\", place = [{ src = \"a\", dest = \"../b\" }] }]",
                head + entry + "assets = [{ type = \"self\", place = [{ src = \"a\", dest = \"C:/b\" }] }]",
                head + entry + "assets = [{ type = \"self\", place = [{ src = \"/a\", dest = \"b\" }] }]",
                head + entry + "preserve = [\"/abs\"]",
                head + entry + "purge = [\"[^a]\"]",
                head + entry + "hooks = { pre_install = [1] }",
                head + entry + "platform2 = \"{{ end }}\"",
                head + "x = \"{{ version\"\n" + entry,
        };
        for (String s : bad) {
            try {
                parse(s);
                fail("accepted:\n" + s);
            } catch (PackageException e) {
                assertEquals(PackageException.Kind.INVALID_PACKAGE, e.kind);
            }
        }
    }

    @Test
    public void rejectsKeysBeforeV040() throws Exception {
        String head = "format = 4\nid = \"github.com/a/b\"\nversion = \"1.0.0\"\n[[variants.client]]\nside = \"client\"\nplatform = \"win-x64\"\n";
        String[] old = {"platform = \"client\"\nruntime = \"android-arm64\"", "platform = \"server\"",
                "side = \"client\"\nplatform = \"android-arm64\"\nruntime = \"android-arm64\""};
        for (String entry : old) {
            try {
                parse(head + "[[variants.client]]\n" + entry + "\n");
                fail(entry);
            } catch (PackageException e) {
                assertEquals(PackageException.Kind.INVALID_PACKAGE, e.kind);
                assertEquals("Invalid package file at $.variants[\"client\"][1]: uses the keys \"platform\" and \"runtime\" of lip"
                        + " before v0.40; rename \"platform\" to \"side\" and \"runtime\" to \"platform\".", e.getMessage());
            }
        }
        try {
            parse(head + "[[variants.client]]\nside = \"client\"\nplatform = \"win-x64\"\n");
            fail();
        } catch (PackageException e) {
            assertEquals("Invalid package file at $.variants[\"client\"][1]: has the side and the platform of"
                    + " $.variants[\"client\"][0].", e.getMessage());
        }
    }

    @Test
    public void readsArchiveFiles() throws Exception {
        byte[] toml = TOML.getBytes(StandardCharsets.UTF_8);
        byte[] tooth = "{\"format_version\":3}".getBytes(StandardCharsets.UTF_8);
        assertEquals(Manifest.FILE, Manifest.read(toml, tooth, "client", "android-arm64", null).packageFile);
        try {
            Manifest.read(null, null, "client", "android-arm64", null);
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.INVALID_PACKAGE, e.kind);
        }
        try {
            Manifest.read(null, tooth, "client", "android-arm64", null);
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.INVALID_PACKAGE, e.kind);
        }
    }
}
