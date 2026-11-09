package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Globs of preserve, purge and place (lip.schema.toml definitions.globs). Directory paths end with '/'. */
public class GlobTest {
    @Test
    public void validity() {
        for (String ok : new String[] {"config.json", "**/*.json", "a/**", "config/", "**/", "[ab].txt", "[!ab]", "[]a]",
                "[!]a]", "[a-]", "foo[", "[^a", "[[:digit:]]", "[[.-.]]", "[[=x=]]", "[?[:word:]"}) {
            assertTrue(ok, Glob.isValid(ok));
        }
        for (String bad : new String[] {"", "/a", "/", "a//b", "a\\b", "[^a]", "[[:word:]]", "[z-a]", "[[.ab.]]", "a/[b-a]x["}) {
            assertFalse(bad, Glob.isValid(bad));
        }
    }

    @Test
    public void matching() {
        Object[][] cases = {
                {"config.json", "config.json", true}, {"config.json", "a/config.json", false},
                {"*.json", "a.json", true}, {"*.json", "a/b.json", false}, {"*", "", true}, {"*", ".env", true},
                {"**/*.json", "config.json", true}, {"**/*.json", "a/b/c.json", true},
                {"a/**", "a", true}, {"a/**", "a/b/c", true}, {"**", "a/b", true}, {"**", "a/", false},
                {"config/", "config/", true}, {"config/", "config/x/y", true}, {"config/", "config", false},
                {"config", "config/", false}, {"**/data/", "x/data/y.bin", true}, {"**/", "a", true}, {"**/", "", true},
                {"*/", "/", true}, {"a.?", "a.b", true}, {"a.?", "a.bc", false}, {"[ab].txt", "b.txt", true},
                {"[!ab].txt", "a.txt", false}, {"[!ab].txt", "c.txt", true}, {"[]a]", "]", true}, {"[a-]", "-", true},
                {"log[0-9].txt", "log5.txt", true}, {"[[:digit:]][[:alpha:]]", "1a", true}, {"foo[", "foo[", true},
                {"[[:alpha]", "[a", true}, {"a[]", "a[]", true}, {"*.json", "a/", false},
        };
        for (Object[] c : cases) assertEquals(c[0] + " ~ " + c[1], c[2], Glob.matches((String) c[0], (String) c[1]));
    }

    @Test
    public void meta() {
        assertTrue(Glob.hasMeta("bin/*.dll"));
        assertTrue(Glob.hasMeta("a[b"));
        assertFalse(Glob.hasMeta("a/b/"));
    }
}
