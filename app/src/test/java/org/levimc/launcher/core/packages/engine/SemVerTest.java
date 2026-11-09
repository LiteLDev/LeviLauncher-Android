package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Versions and ranges; expectations are Semver.NET 3.0 results. */
public class SemVerTest {
    @Test
    public void parsesStrictVersions() {
        for (String ok : new String[] {"1.2.3", "0.0.0", "1.2.3-rc.1", "1.2.3--", "1.2.3+build.01", "1.2.3-0a",
                "99999999999999999999.0.0"}) {
            assertNotNull(ok, SemVer.parse(ok));
        }
        for (String bad : new String[] {"", " 1.2.3", "1.2.3 ", "v1.2.3", "1.2", "1", "01.2.3", "1.02.3", "1.2.3-01",
                "1.2.3-", "1.2.3-a..b", "1.2.3+", "1.2.3.4", "1.2.3-é", "1.2.x"}) {
            assertNull(bad, SemVer.parse(bad));
        }
    }

    @Test
    public void comparesByPrecedence() {
        String[] ordered = {"1.0.0-0", "1.0.0-2", "1.0.0-10", "1.0.0-a", "1.0.0-a.1", "1.0.0-b", "1.0.0", "1.0.1",
                "1.1.0", "2.0.0", "18446744073709551616.0.0"};
        for (int i = 0; i + 1 < ordered.length; i++) {
            assertEquals(ordered[i] + " < " + ordered[i + 1], -1, Integer.signum(SemVer.compare(ordered[i], ordered[i + 1])));
        }
        assertEquals(0, SemVer.compare("1.0.0+a", "1.0.0+b"));
    }

    @Test
    public void parsesRanges() {
        for (String ok : new String[] {"1.2.3", " 1.2.3", "1.2.3  ||  2.0.0", "*", "*.*.*", "1.*", "1.2.*", "*-*",
                "1.2.3-*", "1.2.3-rc.*", "1.*-*", ">=1.0.0 <2.0.0", "> 1.2.3", "^1.2.3", "~1.2.3", "<0.0.0",
                "1.2.3 2.0.0", "1.2.3>=1.0.0", "26.60.*", "1.26.60"}) {
            assertNotNull(ok, SemVer.Range.parse(ok));
        }
        for (String bad : new String[] {"", " ", "\t", "1.2.3\t", "1.2.3 ||", "|| 1.2.3", "1.2.3 | 2.0.0", "a", "v1.0.0",
                "1.x", "1.0.0 - 2.0.0", "1.2", "1", "01.0.0", "1.2.3+build", "**", "*.1", "1.*.2", "1.*-rc", "*-0",
                ">=1.*", "^1.*", "~*", "=1.2.3-*", "1.2.3-*.a", "1.2.3-a*", ">=1.0.0, <2.0.0", "=>1.0.0", "~>1.0.0",
                "1.2.3-a_b", "1.2.3  ", "1.2.3 "}) {
            assertNull(bad, SemVer.Range.parse(bad));
        }
    }

    @Test
    public void matchesLikeSemverNet() {
        Object[][] cases = {
                {"1.2.3", "1.2.3", true}, {"1.2.3", "1.2.4", false},
                {"^1.2.3", "1.9.0", true}, {"^1.2.3", "2.0.0", false}, {"^1.2.3", "2.0.0-0", false},
                {"^0.1.2", "0.1.9", true}, {"^0.1.2", "0.2.0", false}, {"^0.0.2", "0.0.3", false},
                {"~1.2.3", "1.2.9", true}, {"~1.2.3", "1.3.0", false},
                {"1.*", "1.9.9", true}, {"1.*", "2.0.0", false}, {"1.*", "1.5.0-rc", false},
                {"1.2.*", "1.2.0", true}, {"26.60.*", "26.60.5", true}, {"*", "5.0.0", true}, {"*", "5.0.0-rc", false},
                {"*-*", "5.0.0-rc", true}, {"1.*-*", "1.5.0-rc", true}, {"1.*-*", "2.0.0-rc", false},
                {"1.2.3-*", "1.2.3-alpha", true}, {"1.2.3-*", "1.2.3", true}, {"1.2.3-*", "1.2.4-0", false},
                {"1.2.3-rc.*", "1.2.3-rc.7", true}, {"1.2.3-rc.*", "1.2.3-rc", false}, {"1.2.3-rc.*", "1.2.3-rd", false},
                {">=1.2.3-rc", "1.2.3-rc.1", true}, {">=1.2.3-rc", "1.3.0-rc", false}, {">=1.2.3-rc", "1.3.0", true},
                {"<1.2.3", "1.2.3-rc", false}, {"<1.2.3-0", "1.2.2", true}, {"~1.2.3-rc", "1.2.3-rc.2", true},
                {"^1.0.0 ^2.0.0", "1.5.0", false}, {"1.0.0 || 2.0.0", "2.0.0", true},
                {">1.0.0-99999999999999999999", "1.0.0-100000000000000000000", true},
                {"<18446744073709551616.0.0", "18446744073709551615.99.99", true},
        };
        for (Object[] c : cases) {
            assertEquals(c[0] + " ~ " + c[1], c[2], SemVer.Range.parse((String) c[0]).matches((String) c[1]));
        }
    }
}
