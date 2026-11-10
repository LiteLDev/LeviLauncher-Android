package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** config.toml with liprc.json migration (userspace 2, compatibility 4), the cache, and state.json (workspace 7.1). */
public class ConfigAndStateTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, String s) throws Exception {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void readsAndWritesGithubProxy() throws Exception {
        File dir = tmp.newFolder("config");
        Config c = new Config(dir, tmp.newFolder("cache"));
        assertNull(c.githubProxy());
        c.setGithubProxy("https://ghproxy.example/");
        assertEquals("https://ghproxy.example/", c.githubProxy());
        assertEquals("format = 4\ngithub_proxy = \"https://ghproxy.example/\"\n", read(new File(dir, "config.toml")));
        c.deleteGithubProxy();
        assertNull(c.githubProxy());
        assertEquals("format = 4\n", read(new File(dir, "config.toml")));
        for (String bad : new String[] {"http://x/", "ghproxy", "https://a b/"}) {
            try {
                c.setGithubProxy(bad);
                fail(bad);
            } catch (PackageException e) {
                assertEquals(PackageException.Kind.INVALID_ARGUMENT, e.kind);
            }
        }
        for (String invalid : new String[] {"format = 3\ngithub_proxy = \"https://x/\"", "github_proxy = \"https://x/\"",
                "format = 4\ngithub_proxy = 1", "format = 4\ngithub_proxy = \"not a uri\"", "not toml"}) {
            write(new File(dir, "config.toml"), invalid);
            assertNull(invalid, c.githubProxy());
        }
        write(new File(dir, "config.toml"), "format = 4\ngithub_proxy = \"https://x/\"\nother = 1\n");
        assertEquals("https://x/", c.githubProxy());
    }

    @Test
    public void migratesLegacyConfig() throws Exception {
        File dir = tmp.newFolder("config");
        Config c = new Config(dir, tmp.newFolder("cache"));
        write(new File(dir, "liprc.json"), "{\"format_version\":3,\"github_proxy\":\"https://p.example\",\"cache_dir\":\"x\"}");
        assertEquals("https://p.example", c.githubProxy());
        assertFalse(new File(dir, "liprc.json").exists());
        assertTrue(new File(dir, "config.toml").isFile());
        new File(dir, "config.toml").delete();
        write(new File(dir, "liprc.json"), "{\"format_version\":2,\"github_proxy\":\"https://p.example\"}");
        assertNull(c.githubProxy());
        assertTrue(new File(dir, "liprc.json").exists());
    }

    @Test
    public void cleansCache() throws Exception {
        File cache = tmp.newFolder("cache");
        write(new File(cache, "http-abc"), "12345");
        new File(cache, "sub").mkdir();
        write(new File(cache, "sub/x.tmp"), "12");
        Config c = new Config(tmp.newFolder("config"), cache);
        assertEquals(7, c.cacheSize());
        c.cacheClean();
        assertFalse(cache.exists());
        assertEquals(0, c.cacheSize());
    }

    @Test
    public void roundTripsStateFile() throws Exception {
        Manifest m = Manifest.parseToml(("format = 4\nid = \"github.com/a/b\"\nversion = \"1.0.0\"\n"
                + "[[variants.client]]\nside = \"client\"\nplatform = \"android-arm64\"\n").getBytes(StandardCharsets.UTF_8));
        StateFile s = new StateFile("client", "android-arm64", Arrays.asList(
                new StateFile.Pkg(m, "client", true, "github.com/a/b#client", "0123456789abcdef0123456789abcdef01234567",
                        Arrays.asList("mods/b/a.so", "b.txt")),
                new StateFile.Pkg(m, "", false, "file:///data/b.zip", null, Collections.<String>emptyList())));
        StateFile back = StateFile.parse(s.toJson());
        assertEquals("client", back.side);
        assertEquals("android-arm64", back.platform);
        assertTrue(new String(s.toJson(), StandardCharsets.UTF_8).startsWith("{\n  \"format\": 4,\n  \"side\": \"client\",\n  \"platform\": \"android-arm64\","));
        assertEquals("github.com/a/b#client", back.packages.get(0).identity());
        assertEquals(Arrays.asList("mods/b/a.so", "b.txt"), back.packages.get(0).files);
        assertNull(back.packages.get(1).commit);
        assertEquals(new String(s.toJson(), StandardCharsets.UTF_8), new String(back.toJson(), StandardCharsets.UTF_8));
        String ok = "{\"format\":4,\"side\":\"client\",\"platform\":\"r\",\"packages\":[]}";
        assertTrue(StateFile.parse(ok.getBytes(StandardCharsets.UTF_8)).packages.isEmpty());
        String pkg = "{\"manifest\":" + m.json + ",\"variant\":\"\",\"explicit\":true,\"ref\":\"r\",\"files\":[]";
        for (String bad : new String[] {ok.replace("client", "desktop"), ok.replace("\"r\"", "\"\""),
                ok.replace(",\"packages\":[]", ""), ok.replace("[]", "[" + pkg + ",\"commit\":\"ABC\"}]"),
                ok.replace("[]", "[" + pkg.replace("\"explicit\":true,", "") + "}]"),
                ok.replace("[]", "[" + pkg.replace("1.0.0", "x") + "}]"), "{\"format\":4,\"format\":4}",
                ok.replace("\"r\"", "5"), ok.replace("4", "4.5"), ok.replace("4", "\"4\"")}) {
            try {
                StateFile.parse(bad.getBytes(StandardCharsets.UTF_8));
                fail(bad);
            } catch (PackageException e) {
                assertEquals(PackageException.Kind.INVALID_WORKSPACE, e.kind);
                assertTrue(e.getMessage(), e.getMessage().startsWith("The state file '.lip/state.json' is invalid, so lip"));
            }
        }
        // lip before v0.40 wrote "platform" (the side) and "runtime" (the platform); no migration.
        for (String old : new String[] {"{\"format\":4,\"platform\":\"client\",\"runtime\":\"r\",\"packages\":[]}",
                "{\"format\":4,\"side\":\"client\",\"platform\":\"r\",\"runtime\":\"r\",\"packages\":[]}",
                "{\"format\":4,\"platform\":\"server\",\"packages\":[]}", ok.replace("4", "3")}) {
            try {
                StateFile.parse(old.getBytes(StandardCharsets.UTF_8));
                fail(old);
            } catch (PackageException e) {
                assertEquals(PackageException.Kind.INVALID_WORKSPACE, e.kind);
                assertEquals("The state file '.lip/state.json' was written by a different version of lip, so this lip cannot"
                        + " tell which packages are installed.", e.getMessage());
                if (old.contains("runtime")) {
                    assertEquals("The file uses the keys \"platform\" and \"runtime\" of lip before v0.40; rename \"platform\""
                            + " to \"side\" and \"runtime\" to \"platform\".", e.getCause().getMessage());
                }
            }
        }
    }
}
