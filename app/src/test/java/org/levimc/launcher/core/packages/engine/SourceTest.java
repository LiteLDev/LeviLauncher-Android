package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Sources (package 2), Go module paths and git ref names. */
public class SourceTest {
    @Test
    public void parsesRepositories() throws Exception {
        Source s = Source.parse("github.com/LiteLDev/LeviLamina#client@1.2.3");
        assertEquals(Source.Kind.REPO, s.kind);
        assertEquals("github.com/LiteLDev/LeviLamina", s.location);
        assertEquals("client", s.variant);
        assertEquals("1.2.3", s.version.toString());
        assertEquals("github.com/LiteLDev/LeviLamina#client", s.identity("github.com/LiteLDev/LeviLamina"));
        assertEquals("github.com/a/b#x/y@1.2.3", Source.parse("github.com/a/b#x/y@1.2.3").toString());
        assertEquals("feat/x#1", Source.parse("github.com/a/b@branch:feat/x#1").branch);
        assertEquals("v1.0.0", Source.parse("github.com/a/b@tag:v1.0.0").tag);
        assertEquals("0123456789abcdef0123456789abcdef01234567",
                Source.parse("github.com/a/b@rev:0123456789ABCDEF0123456789abcdef01234567").rev);
    }

    @Test
    public void parsesUrlsAndLocalPaths() throws Exception {
        Source u = Source.parse("https://example.com/x.zip#lua/sub");
        assertEquals(Source.Kind.URL, u.kind);
        assertEquals("https://example.com/x.zip", u.location);
        assertEquals("lua/sub", u.variant);
        Source l = Source.parse("/data/a b#1.zip#client");
        assertEquals(Source.Kind.LOCAL, l.kind);
        assertEquals("/data/a b#1.zip", l.location);
        assertEquals("file:///data/a%20b%231.zip#client", l.toString());
        assertEquals("/data/a b#1.zip", Source.parse(l.toString()).location);
        assertEquals("/x", Source.pathOf("file:/x"));
        assertEquals("/x", Source.pathOf("file://localhost/x"));
    }

    @Test
    public void rejectsInvalidSources() {
        String[] bad = {"", "github.com/a/b#", "github.com/a/b#Bad", "github.com/a/b@", "github.com/a/b@v1.0.0",
                "github.com/a/b@1.0", "github.com/a/b@branch:a..b", "github.com/a/b@rev:123", "github.com/a/b@head:x",
                "http://example.com/x.zip", "ftp://x/y", "https://", "https:///x", "https://a:65536/x", "https://a b/x",
                "https://a/é", "https://a/x#", "https://a/x#a@b", "noslash", "Github.com/a/b", "-a.com/b",
                "file://server/x"};
        for (String s : bad) assertNotNull(s, Source.check(s));
        for (String s : new String[] {"https://a", "https://u:p@a:443/x?y=%20", "https://[::1]:8080/x", "https://a/x@1.0.0",
                "github.com/a/b", "gopkg.in/yaml.v3", "example.com/a/v2"}) {
            assertNull(s, Source.check(s));
        }
    }

    @Test
    public void checksModulePaths() {
        for (String ok : new String[] {"github.com/a/b", "github.com/LiteLDev/LeviLamina", "a.b/c~d_e-f.g", "example.com/a/v2",
                "gopkg.in/yaml.v3", "gopkg.in/check.v1-unstable", "example.com/x/v2.0.0x"}) {
            assertNull(ok, Source.checkModulePath(ok));
        }
        for (String bad : new String[] {"", "-a.com/b", "a.com//b", "a.com/b/", "/a.com", "abc/d", "A.com/b", "a_b.com/c",
                "a.com/.b", "a.com/b.", "a.com/..", "a.com/b c", "a.com/con", "a.com/LPT1.txt", "a.com/x~1", "a.com/x~1.y", "a.com/b/v1",
                "a.com/b/v0", "a.com/b/v2.1", "gopkg.in/yaml", "a.com/é"}) {
            assertNotNull(bad, Source.checkModulePath(bad));
        }
    }

    @Test
    public void checksRefNames() {
        for (String ok : new String[] {"main", "feat/x#1", "a.LOCK", "a@b", "é", "v1.0.0", "release-2024"}) {
            assertTrue(ok, Source.isRefName(ok));
        }
        for (String bad : new String[] {"", "@", "/a", "a/", "a//b", "a..b", ".a", "a/.b", "a.lock", "a/b.lock/c", "a.",
                "a@{b", "a b", "a~b", "a^b", "a:b", "a?b", "a*b", "a[b", "a\\b", "a\u007fb", "a\tb"}) {
            assertFalse(bad, Source.isRefName(bad));
        }
    }
}
