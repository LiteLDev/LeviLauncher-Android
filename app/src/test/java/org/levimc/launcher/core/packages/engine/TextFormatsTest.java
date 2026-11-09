package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** TOML and JSON files (README conventions) and the template subset (package 3). */
public class TextFormatsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static JsonObject toml(String s) {
        return TextFormats.parseToml(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertToml(String toml, String json) {
        assertEquals(toml, JsonParser.parseString(json), JsonParser.parseString(toml(toml).toString()));
    }

    @Test
    public void readsToml() {
        String[][] cases = {
                {"a = 1\nb = \"x\"  # c\n", "{a:1,b:'x'}"},
                {"\"q k\" = true\n'l' = false\na.b.c = 1\n", "{'q k':true,l:false,a:{b:{c:1}}}"},
                {"a = 0x1F\nb = 0o17\nc = 0b11\nd = -1_000\ne = +5", "{a:31,b:15,c:3,d:-1000,e:5}"},
                {"a = 1.5\nb = -2e3\nc = 1_0.0_1", "{a:1.5,b:-2000,c:10.01}"},
                {"s = \"a\\tb\\u00e9\\U0001F600\\\\\"", "{s:'a\\tbé😀\\\\'}"},
                {"s = 'C:\\x\\y'", "{s:'C:\\\\x\\\\y'}"},
                {"s = \"\"\"\nl1\n  l2 \\\n   l3\"\"\"", "{s:'l1\\n  l2 l3'}"},
                {"s = '''\nraw \\n'''", "{s:'raw \\\\n'}"},
                {"s = \"\"\"a\"\"\"\"\"", "{s:'a\"\"'}"},
                {"a = [1, [\"x\"], {b = 2},\n  # c\n]", "{a:[1,['x'],{b:2}]}"},
                {"t = { x = 1, y.z = 2 }", "{t:{x:1,y:{z:2}}}"},
                {"[a.b]\nc = 1\n[a]\nd = 2", "{a:{b:{c:1},d:2}}"},
                {"[[v]]\nx = 1\n[[v]]\nx = 2\n[v.s]\ny = 3", "{v:[{x:1},{x:2,s:{y:3}}]}"},
                {"[f]\napple.color = 'red'\n[f.apple.texture]\nsmooth = true", "{f:{apple:{color:'red',texture:{smooth:true}}}}"},
                {"\uFEFFa = 1\r\nb = 2\r\n", "{a:1,b:2}"},
        };
        for (String[] c : cases) assertToml(c[0], c[1]);
    }

    @Test
    public void rejectsInvalidToml() {
        String[] bad = {"a = 1979-05-27", "a = 07:32:00", "a = 1979-05-27T07:32:00Z", "a = inf", "a = -nan", "a = 1e400",
                "a = 1\na = 2", "[a]\n[a]", "a.b = 1\n[a]", "a = {b = 1}\na.c = 2", "a = {b = 1}\n[a.c]", "a = [1]\n[[a]]",
                "[a.b.c]\n[a]\nb.d = 1", "[f]\napple.color = 1\n[f.apple]", "a = 1 b = 2", "a = \"\\e\"", "a = \"x\ny\"",
                "a = 01", "a = 0x", "a = 1__0", "a = 9223372036854775808", "a = { b = 1, }", "a = {\nb = 1}", "= 1",
                "a = \"unterminated", "a = 1\rb = 2", "a = \"\u0001\"", "\"\"\"k\"\"\" = 1", "a = ", "[a]x",
                "a" + ".a".repeat(5000) + " = 1", "[a" + ".a".repeat(5000) + "]", "[a" + ".a".repeat(60) + "]\nb" + ".b".repeat(30) + " = [[[[[[[[[[1]]]]]]]]]]"};
        for (String s : bad) {
            try {
                toml(s);
                fail("accepted: " + s);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
        try {
            TextFormats.parseToml(new byte[] {'a', '=', '"', (byte) 0xff, '"'});
            fail("accepted invalid UTF-8");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void readsJsonStrictly() {
        assertEquals(JsonParser.parseString("{\"a\":[1,{\"b\":null}]}"), TextFormats.parseJson("{\"a\":[1,{\"b\":null}]}".getBytes()));
        for (String bad : new String[] {"{\"a\":1,\"a\":2}", "{a:1}", "{\"a\":1,}", "[1] 2", "// c\n{}", "{\"a\":NaN}", "'s'"}) {
            try {
                TextFormats.parseJson(bad.getBytes(StandardCharsets.UTF_8));
                fail("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    public void writesTomlAndAtomically() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("format", 4);
        o.addProperty("github_proxy", "https://p.example/\"q\"\\");
        o.addProperty("a key", true);
        byte[] text = TextFormats.writeToml(o);
        assertEquals(o, TextFormats.parseToml(text));
        File f = new File(tmp.getRoot(), "config.toml");
        TextFormats.writeAtomically(f, text);
        TextFormats.writeAtomically(f, "format = 4\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("format = 4\n", new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(tmp.getRoot(), "config.toml.tmp").exists());
    }

    @Test
    public void rendersTemplates() {
        JsonObject vars = JsonParser.parseString("{\"version\":\"1.2.3\",\"id\":\"github.com/a/b\",\"info\":{\"name\":\"N\",\"n\":5},"
                + "\"f\":true,\"z\":null,\"big\":1.50}").getAsJsonObject();
        String[][] cases = {
                {"https://{{id}}/releases/download/v{{version}}/x-v{{ version }}.zip",
                        "https://github.com/a/b/releases/download/v1.2.3/x-v1.2.3.zip"},
                {"no template", "no template"}, {"[{{info.name}}|{{ info . n }}|{{f}}|{{z}}|{{missing}}]", "[N|5|true||]"},
                {"{{ \"}}\" }}{{'a'}}{{ 42 }}{{ true }}{{ null }}", "}}a42true"}, {"[}}]{ {version}}", "[}}]{ {version}}"},
                {"a  {{- version -}}  b", "a1.2.3b"}, {"a \n {{~ version ~}} \n b", "a \n1.2.3 b"},
                {"{{# comment }}x{{ version # c }}", "x1.2.3"}, {"{{ }}{{big}}", "1.50"},
        };
        for (String[] c : cases) assertEquals(c[0], c[1], Template.render(c[0], vars));
        for (String bad : new String[] {"{{ version", "{{ missing.x }}", "{{ z.x }}", "{{ info }}", "{{ tags[0] }}",
                "{{ a; b }}", "{{ end }}", "{%{ raw }%}", "{{ $x }}", "{{ version.size }}", "{{{version}}}"}) {
            try {
                Template.render(bad, vars);
                fail("rendered: " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage() != null);
            }
        }
    }
}
