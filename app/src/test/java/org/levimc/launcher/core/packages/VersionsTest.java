// Port of upstream internal/version_test.go.
package org.levimc.launcher.core.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VersionsTest {
    @Test
    public void testCompare() {
        List<String> list = new ArrayList<>(Arrays.asList("1.0.0-rc.1", "v1.0.0", "0.9.10", "0.9.2", "nightly",
                "1.0.0-beta", "1.0.0-rc.10", "1.0.0-rc.2", "abc"));
        list.sort((a, b) -> -CatalogModels.Versions.compare(a, b));
        assertEquals(Arrays.asList("v1.0.0", "1.0.0-rc.10", "1.0.0-rc.2", "1.0.0-rc.1", "1.0.0-beta", "0.9.10",
                "0.9.2", "nightly", "abc"), list);
    }

    @Test
    public void testCompareVersions() {
        assertEquals(0, Integer.signum(CatalogModels.Versions.compareVersions("1.21.50.07", "1.21.50.7")));
        assertEquals(1, Integer.signum(CatalogModels.Versions.compareVersions("1.21.100", "1.21.99")));
        assertEquals(0, Integer.signum(CatalogModels.Versions.compareVersions("1.21", "1.21.0")));
        assertEquals(-1, Integer.signum(CatalogModels.Versions.compareVersions("1.26.0", "1.26.0.1")));
        assertEquals(-1, Integer.signum(CatalogModels.Versions.compareVersions("0.9.2", "0.9.10")));
        assertEquals("the dotted compare ranks a pre-release above its release", 1,
                Integer.signum(CatalogModels.Versions.compareVersions("1.0.0-rc.1", "1.0.0")));
        assertEquals(-1, Integer.signum(CatalogModels.Versions.comparePlanVersions("1.0.0-rc.1", "1.0.0")));
        assertEquals("dotted fallback", 1, Integer.signum(CatalogModels.Versions.comparePlanVersions("1.0", "0.9.5")));
    }

    @Test
    public void testInRange() {
        Object[][] cases = {
                {"1.4.2", ">=1.4.0 <1.5.0", true},
                {"1.5.0", ">=1.4.0 <1.5.0", false},
                {"1.5.0-rc.1", "<1.5.0", true},
                {"26.60.3", "26.60.*", true},
                {"26.61.0", "26.60.*", false},
                {"2.0.0", "1.x", false},
                {"1.9.9", "1.x", true},
                {"9.9.9", "*", true},
                {"1.2.3", "1.2.3", true},
                {"v1.2.3", "=1.2.3", true},
                {"1.2.4", "1.2.3", false},
                {"1.2.3", "", false},
                {"bad", "*", false},
                {"1.2.3", ">= 1.2.0", false}, // tokens split on spaces, as in the window
                {"1.2.3", "1.2.x.x", false},
                {"1.2.3", "a.*", false},
                {"1.2.3", "1.*.3", false},
                {"1.2.3", "X", true},
        };
        for (Object[] c : cases) {
            assertEquals("InRange(" + c[0] + ", " + c[1] + ")", c[2], CatalogModels.Versions.inRange((String) c[0], (String) c[1]));
        }
        assertTrue(CatalogModels.Versions.compatible("1.4.2", Arrays.asList("2.x", "1.4.*")));
        assertFalse("nothing without ranges", CatalogModels.Versions.compatible("1.4.2", null));
        assertFalse(CatalogModels.Versions.compatible("", Collections.singletonList("*")));
    }

    @Test
    public void testParseSemver() {
        assertEquals("1.2.3-rc.1", CatalogModels.Versions.parseSemver(" v1.2.3-rc.1 ").toString());
        assertNull(CatalogModels.Versions.parseSemver("1.2"));
        assertEquals("lenient sorting reads 1.2 as 1.2.0", 1, CatalogModels.Versions.compare("1.2", "1.1.9"));
        assertTrue(CatalogModels.Versions.isPrerelease("1.8.0-rc.1"));
        assertTrue(CatalogModels.Versions.isPrerelease("1.8.0rc1"));
        assertFalse(CatalogModels.Versions.isPrerelease("1.8.0"));
        assertEquals("1.0.0", CatalogModels.Versions.pickTargetVersion(Arrays.asList(" ", "1.1.0-rc.1", "1.0.0")));
        assertEquals("1.1.0-rc.1", CatalogModels.Versions.pickTargetVersion(Collections.singletonList("1.1.0-rc.1")));
        assertEquals("", CatalogModels.Versions.pickTargetVersion(null));
    }

    @Test
    public void testLoaderDb() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("v1.21.50.07", Arrays.asList("1.0.0", " 1.1.0", "1.1.0", "1.2.0-rc.1"));
        raw.put("1.21.60.10", Collections.singletonList("1.3.0"));
        raw.put("1.21.70.01", Collections.emptyList());
        LeviLamina.Db db = LeviLamina.Db.normalize(raw);
        assertEquals(Arrays.asList("1.2.0-rc.1", "1.1.0", "1.0.0"), db.versions("1.21.50.07"));
        assertFalse("a game without versions must be dropped", db.asMap().containsKey("1.21.70.01"));
        assertEquals(Arrays.asList("1.21.60.10", "1.21.50.07"),
                CatalogModels.Versions.supportedGameVersions(Collections.singletonList(">=1.1.0"), db));
        assertTrue("no ranges supports nothing", CatalogModels.Versions.supportedGameVersions(null, db).isEmpty());
    }
}
