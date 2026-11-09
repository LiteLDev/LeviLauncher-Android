package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** compatibility 2: tooth.json of formats 1-3 read as package files. */
public class ToothCompatTest {
    /** LeviLamina's tooth.json at 26.60.5, trimmed. */
    private static final String LEVILAMINA = """
            {"format_version": 3, "format_uuid": "289f771f-2c9a-4d73-9f3f-8492495a924d",
             "tooth": "github.com/LiteLDev/LeviLamina", "version": "26.60.5",
             "info": {"name": "LeviLamina", "description": "A mod loader", "tags": [],
                      "avatar_url": "https://docs.levimc.org/LeviLamina/logo.svg"},
             "variants": [
              {"platform": "win-x64",
               "dependencies": {"github.com/LiteLDev/bds": "1.26.60", "github.com/LiteLDev/CrashLogger": "1.4.*"},
               "assets": [{"type": "zip",
                 "urls": ["https://{{tooth}}/releases/download/v{{version}}/levilamina-v{{version}}-server.zip"],
                 "placements": [{"type": "dir", "src": "LeviLamina/", "dest": "mods/LeviLamina/"}]}],
               "remove_files": ["bedrock_server_mod.exe"],
               "scripts": {"post_install": [".\\\\PeEditor.exe -mb"], "post_uninstall": ["REN a b"]}},
              {"label": "client", "platform": "win-x64",
               "dependencies": {"github.com/LiteLDev/PreLoader": "1.16.8"},
               "assets": [{"type": "zip", "urls": ["https://{{tooth}}/v{{version}}/client.zip"],
                 "placements": [{"type": "dir", "src": "LeviLamina/", "dest": "mods/LeviLamina/"}]}]}
             ]}
            """;

    private static JsonObject derive(String tooth, String side, String platform, String tag) {
        return ToothCompat.derive(tooth.getBytes(StandardCharsets.UTF_8), side, platform, tag);
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    public void derivesLeviLamina() throws Exception {
        JsonObject server = derive(LEVILAMINA, "server", "win-x64", null);
        assertEquals(json("""
                {"format":4,"id":"github.com/LiteLDev/LeviLamina","version":"26.60.5",
                 "info":{"name":"LeviLamina","description":"A mod loader","tags":[],"logo":"https://docs.levimc.org/LeviLamina/logo.svg"},
                 "variants":{
                  "":[{"side":"server","platform":"win-x64",
                       "deps":{"github.com/LiteLDev/bds":"1.26.60","github.com/LiteLDev/CrashLogger":"1.4.*"},
                       "assets":[{"type":"zip",
                         "urls":["https://github.com/LiteLDev/LeviLamina/releases/download/v26.60.5/levilamina-v26.60.5-server.zip"],
                         "place":[{"src":"LeviLamina/","dest":"mods/LeviLamina/"}]}],
                       "purge":["bedrock_server_mod.exe","bedrock_server_mod.exe/"],
                       "hooks":{"post_install":[".\\\\PeEditor.exe -mb"],"post_uninstall":["REN a b"]}}],
                  "client":[{"side":"server","platform":"win-x64","deps":{"github.com/LiteLDev/PreLoader":"1.16.8"},
                       "assets":[{"type":"zip","urls":["https://github.com/LiteLDev/LeviLamina/v26.60.5/client.zip"],
                         "place":[{"src":"LeviLamina/","dest":"mods/LeviLamina/"}]}]}]}}
                """), server);
        Manifest m = Manifest.read(null, LEVILAMINA.getBytes(StandardCharsets.UTF_8), "client", "win-x64", "26.60.6");
        assertEquals("26.60.6", m.version);
        assertEquals(ToothCompat.FILE, m.packageFile);
        assertEquals("client", m.entry("client", "client", "win-x64").side);
        assertEquals(json("{}"), derive(LEVILAMINA, "client", "android-arm64", null).getAsJsonObject("variants"));
    }

    @Test
    public void mergesVariantsOfOneLabel() {
        String tooth = """
                {"format_version":3,"format_uuid":"289f771f-2c9a-4d73-9f3f-8492495a924d","tooth":"github.com/a/b","version":"v1.2",
                 "info":{"avatar_url":"http://not-https"},
                 "variants":[
                  {"platform":"","dependencies":{"github.com/x/common":">=1.0.0"},"preserve_files":["config\\\\*.json","a.txt"],
                   "scripts":{"install":["i"],"post_install":["p"]}},
                  {"platform":"android-*","dependencies":{"github.com/x/android":"1.*"},
                   "assets":[{"type":"self","placements":[{"type":"file","src":"./bin//*.so","dest":"lib"},
                     {"type":"file","src":"a.so","dest":"lib/a.so"},{"type":"file","src":"../x","dest":"y"}]}],
                   "scripts":{"uninstall":["u"]}},
                  {"platform":"win-x64","dependencies":{"github.com/x/win":"1.*"}},
                  {"label":"tar","platform":"","assets":[{"type":"tgz","urls":["https://a/b.tgz"]}]}]}
                """;
        JsonObject got = derive(tooth, "client", "android-arm64", null);
        assertEquals("1.2.0", got.get("version").getAsString());
        assertFalse(got.getAsJsonObject("info").has("logo"));
        assertEquals(json("""
                {"":[{"side":"client","platform":"android-arm64",
                  "deps":{"github.com/x/common":">=1.0.0","github.com/x/android":"1.*"},
                  "assets":[{"type":"self","place":[{"src":"bin/*.so","dest":"lib/"},{"src":"a.so","dest":"lib/a.so"}]}],
                  "preserve":["config/*.json","**/a.txt"],
                  "hooks":{"post_install":["i","p"],"pre_uninstall":["u"]}}]}
                """), got.getAsJsonObject("variants"));
        String conflict = tooth.replace("\"github.com/x/android\"", "\"github.com/x/common\"");
        try {
            derive(conflict, "client", "android-arm64", null);
            fail("merged two variants depending on the same package");
        } catch (IllegalArgumentException expected) {
            // lip v0.37.0 cannot merge them either
        }
    }

    @Test
    public void upgradesFormat2() {
        String tooth = """
                {"format_version":2,"tooth":"github.com/GroupMountain/GMSidebar","version":"26.51.0",
                 "info":{"name":"GMSidebar","description":"d","author":"g","tags":["levilamina"]},
                 "asset_url":"https://github.com/GroupMountain/GMSidebar/releases/download/v$(version)/GMSidebar.zip",
                 "dependencies":{"github.com/LiteLDev/LeviLamina":">=26.51.0","bad path":"1.0.0","github.com/x/y":"not a range"},
                 "files":{"place":[{"src":"GMSidebar/*","dest":"plugins/GMSidebar"},{"src":"x.dll","dest":"plugins/x.dll"}],
                          "preserve":["plugins/GMSidebar/config.json"]}}
                """;
        assertEquals(json("""
                {"format":4,"id":"github.com/GroupMountain/GMSidebar","version":"26.51.0",
                 "info":{"name":"GMSidebar","description":"d","tags":["levilamina"]},
                 "variants":{"":[{"side":"server","platform":"win-x64","deps":{"github.com/LiteLDev/LeviLamina":">=26.51.0"},
                   "assets":[{"type":"zip","urls":["https://github.com/GroupMountain/GMSidebar/releases/download/v26.51.0/GMSidebar.zip"],
                     "place":[{"src":"GMSidebar/","dest":"plugins/GMSidebar/"},{"src":"x.dll","dest":"plugins/x.dll"}]}],
                   "preserve":["plugins/GMSidebar/config.json"]}]}}
                """), derive(tooth, "server", "win-x64", null));
        String platforms = tooth.replace("\"files\"", "\"platforms\":[{\"goos\":\"windows\",\"goarch\":\"amd64\","
                + "\"dependencies\":{\"github.com/x/z\":\"1.x\"}}],\"files\"");
        JsonObject win = derive(platforms, "server", "win-x64", null);
        assertEquals("1.*", win.getAsJsonObject("variants").getAsJsonArray("").get(0).getAsJsonObject()
                .getAsJsonObject("deps").get("github.com/x/z").getAsString());
        assertEquals(json("{}"), derive(platforms, "client", "android-arm64", null).getAsJsonObject("variants"));
    }

    @Test
    public void upgradesFormat1() {
        String tooth = """
                {"format_version":1,"tooth":"github.com/quizhizhe/Tooth-LLCheckBag","version":"1.3.14",
                 "dependencies":{"github.com/Tooth-Hub/LiteLoaderBDS":[["2.16.x"]],"github.com/x/y":[[">=1.0.0"]]},
                 "information":{"name":"LLCheckBag","description":"Player Data Management","author":"x"},
                 "placement":[{"source":"LLCheckBag","destination":"plugins/LLCheckBag"},
                              {"source":"LLCheckBag.dll","destination":"plugins/LLCheckBag.dll"}],
                 "commands":[{"type":"install","commands":["echo $(version)"]},{"type":"Uninstall","commands":["u"]}],
                 "possession":["plugins/LLCheckBag/"]}
                """;
        assertEquals(json("""
                {"":[{"side":"client","platform":"win-x64","deps":{"github.com/x/y":">=1.0.0"},
                  "assets":[{"type":"self","place":[{"src":"LLCheckBag","dest":"plugins/LLCheckBag"},
                    {"src":"LLCheckBag.dll","dest":"plugins/LLCheckBag.dll"}]}],
                  "hooks":{"post_install":["echo 1.3.14"],"pre_uninstall":["u"]}}]}
                """), derive(tooth, "client", "win-x64", null).getAsJsonObject("variants"));
    }

    @Test
    public void rejectsWhatV037CannotRead() {
        String[] bad = {"[]", "{\"format_version\":4}", "{\"format_version\":3.0}",
                "{\"format_version\":3,\"format_uuid\":\"x\",\"tooth\":\"github.com/a/b\",\"version\":\"1.0.0\"}",
                "{\"format_version\":3,\"format_uuid\":\"289f771f-2c9a-4d73-9f3f-8492495a924d\",\"tooth\":\"a b\",\"version\":\"1.0.0\"}",
                "{\"format_version\":3,\"format_uuid\":\"289f771f-2c9a-4d73-9f3f-8492495a924d\",\"tooth\":\"github.com/a/b\",\"version\":\"x\"}",
                "{\"format_version\":3,\"format_uuid\":\"289f771f-2c9a-4d73-9f3f-8492495a924d\",\"tooth\":\"github.com/a/b\",\"version\":\"1.0.0\",\"variants\":null}",
                "{\"format_version\":3,\"format_uuid\":\"289f771f-2c9a-4d73-9f3f-8492495a924d\",\"tooth\":\"github.com/a/b\",\"version\":\"1.0.0\",\"variants\":[{\"platform\":\"\",\"dependencies\":null}]}",
                "{\"format_version\":3,\"format_uuid\":\"289f771f-2c9a-4d73-9f3f-8492495a924d\",\"tooth\":\"github.com/a/b\",\"version\":\"1.0.0\",\"info\":{\"tags\":[\"Bad Tag\"]}}"};
        for (String s : bad) {
            try {
                derive(s, "client", "win-x64", null);
                fail("read: " + s);
            } catch (IllegalArgumentException expected) {
                // not a package
            }
        }
        try {
            derive(LEVILAMINA, "Client", "win-x64", null);
            fail("a side other than server or client");
        } catch (IllegalArgumentException e) {
            assertEquals("compat: side \"Client\" is neither \"server\" nor \"client\"", e.getMessage());
        }
        assertTrue(ToothCompat.platformMatches("win-*", "win-x64"));
        assertFalse(ToothCompat.platformMatches("{android,linux}-arm64", "android-arm64")); // DotNet.Glob has no braces
        assertTrue(ToothCompat.platformMatches("linux-[a-z]*", "linux-x64"));
        assertFalse(ToothCompat.platformMatches("linux-[0-9a-z]*", "linux-arm64")); // only the first range counts
        assertTrue(ToothCompat.platformMatches("[!w]*-?64", "android-x64"));
        assertTrue(ToothCompat.platformMatches("win**", "win-x64"));
        assertFalse(ToothCompat.platformMatches("win-*", "android-arm64"));
        assertFalse(ToothCompat.platformMatches("[", "["));
    }
}
