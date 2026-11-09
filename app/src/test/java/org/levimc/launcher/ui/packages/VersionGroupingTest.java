// Tests of the Versions tab model (port of the grouping of upstream
// components/VersionList.tsx and filesWithGameVersionState of PackagePage).
package org.levimc.launcher.ui.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.LeviLamina;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VersionGroupingTest {
    static final String RID = "android-arm64";
    static final String LL = "github.com/LiteLDev/LeviLamina#client";

    static LeviLamina.Db db() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("1.26.51.01", Arrays.asList("26.51.0", "26.51.1"));
        raw.put("1.26.60.05", Arrays.asList("26.60.0", "26.60.4", "26.60.5-rc.1"));
        return LeviLamina.Db.normalize(raw);
    }

    static Map<String, String> deps(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    static CatalogModels.Variant variant(CatalogModels.PackageFile... files) {
        return new CatalogModels.Variant("client", "github.com/example/Mod#client", Arrays.asList(files));
    }

    private static List<String> versions(PackageActivity.VersionGrouping.Group g) {
        List<String> out = new ArrayList<>();
        for (PackageActivity.VersionGrouping.FileState s : g.files) {
            out.add(s.file.version);
        }
        return out;
    }

    @Test
    public void testFileStates() {
        CatalogModels.Variant v = variant(
                CatalogModels.PackageFile.of("2.0.0", deps(LL, "26.60.*"), RID),
                CatalogModels.PackageFile.of("1.5.0", deps(LL, ">=26.51.0 <26.61.0"), RID),
                CatalogModels.PackageFile.of("1.0.0", deps("github.com/example/Other", "1.x"), RID));
        List<PackageActivity.VersionGrouping.FileState> states = PackageActivity.VersionGrouping.fileStates(v, db());
        assertEquals(3, states.size());
        assertTrue(states.get(0).hasLLRequirement);
        assertEquals(Collections.singletonList("1.26.60.05"), states.get(0).supportedGameVersions);
        assertEquals(Arrays.asList("1.26.60.05", "1.26.51.01"), states.get(1).supportedGameVersions);
        assertFalse(states.get(2).hasLLRequirement);
        assertTrue(states.get(2).supportedGameVersions.isEmpty());

        // Without a database no game is known.
        List<PackageActivity.VersionGrouping.FileState> noDb = PackageActivity.VersionGrouping.fileStates(v, null);
        assertTrue(noDb.get(0).hasLLRequirement);
        assertTrue(noDb.get(0).supportedGameVersions.isEmpty());
        assertTrue(PackageActivity.VersionGrouping.fileStates(null, db()).isEmpty());
    }

    @Test
    public void testFileStatesUsePlatformView() {
        // android-platforms.json 2.0.0 has a win-x64 and an android-arm64 entry with other dependencies:
        // the Android entry's dependencies are what the engine installs.
        CatalogModels.Variant v = CatalogFixtures.androidVariant();
        List<PackageActivity.VersionGrouping.FileState> states = PackageActivity.VersionGrouping.fileStates(v, db());
        PackageActivity.VersionGrouping.FileState s200 = null;
        for (PackageActivity.VersionGrouping.FileState s : states) {
            if (s.file.version.equals("2.0.0")) {
                s200 = s;
            }
        }
        assertTrue(s200 != null);
        assertTrue(s200.file.otherDependencies.containsKey("github.com/example/AndroidDep"));
        assertFalse(s200.file.otherDependencies.containsKey("github.com/example/WinOnlyDep"));
    }

    @Test
    public void testGamesAndActiveGame() {
        CatalogModels.Variant v = variant(
                CatalogModels.PackageFile.of("2.0.0", deps(LL, "26.60.*"), RID),
                CatalogModels.PackageFile.of("1.5.0", deps(LL, ">=26.51.0 <26.61.0"), RID));
        List<PackageActivity.VersionGrouping.FileState> states = PackageActivity.VersionGrouping.fileStates(v, db());
        List<String> games = PackageActivity.VersionGrouping.games(states);
        assertEquals(Arrays.asList("1.26.60.05", "1.26.51.01"), games);
        assertEquals("1.26.51.01", PackageActivity.VersionGrouping.activeGame(games, "1.26.51.01"));
        assertEquals(PackageActivity.VersionGrouping.ALL, PackageActivity.VersionGrouping.activeGame(games, "1.20.0.1"));
        assertEquals(PackageActivity.VersionGrouping.ALL, PackageActivity.VersionGrouping.activeGame(games, null));
    }

    @Test
    public void testGroupsAll() {
        CatalogModels.Variant v = variant(
                CatalogModels.PackageFile.of("2.0.0", deps(LL, "26.60.*"), RID),
                CatalogModels.PackageFile.of("1.5.0", deps(LL, ">=26.51.0 <26.61.0"), RID),
                CatalogModels.PackageFile.of("1.4.0", deps(LL, ">=26.51.0 <26.61.0"), RID),
                CatalogModels.PackageFile.of("1.0.0", deps(), RID),
                CatalogModels.PackageFile.of("0.9.0", deps(LL, "1.0.*"), RID));
        List<PackageActivity.VersionGrouping.Group> groups = PackageActivity.VersionGrouping.groups(
                PackageActivity.VersionGrouping.fileStates(v, db()), PackageActivity.VersionGrouping.ALL);
        // Newest game first, then by newest file; files without a game last.
        assertEquals(5, groups.size());
        assertEquals("1.26.60.05", groups.get(0).game);
        assertEquals(Collections.singletonList("26.60.*"), groups.get(0).llRanges);
        assertEquals(Collections.singletonList("2.0.0"), versions(groups.get(0)));
        assertEquals("1.26.60.05", groups.get(1).game);
        assertEquals(Arrays.asList("1.5.0", "1.4.0"), versions(groups.get(1)));
        assertEquals("1.26.51.01", groups.get(2).game);
        assertEquals(Arrays.asList("1.5.0", "1.4.0"), versions(groups.get(2)));
        assertEquals("", groups.get(3).game);
        assertEquals(Collections.singletonList("1.0.0"), versions(groups.get(3)));
        assertFalse(groups.get(3).hasLLRequirement);
        assertEquals("", groups.get(4).game);
        assertEquals(Collections.singletonList("0.9.0"), versions(groups.get(4)));
        assertTrue(groups.get(4).hasLLRequirement);

        assertEquals(PackageActivity.VersionGrouping.NoGameLabel.UNRESTRICTED,
                PackageActivity.VersionGrouping.noGameLabel(groups.get(3), true, true));
        assertEquals(PackageActivity.VersionGrouping.NoGameLabel.UNKNOWN,
                PackageActivity.VersionGrouping.noGameLabel(groups.get(4), true, true));
        assertEquals(PackageActivity.VersionGrouping.NoGameLabel.UNAVAILABLE,
                PackageActivity.VersionGrouping.noGameLabel(groups.get(4), true, false));
        assertEquals(PackageActivity.VersionGrouping.NoGameLabel.LOADING,
                PackageActivity.VersionGrouping.noGameLabel(groups.get(4), false, false));
    }

    @Test
    public void testGroupsForOneGame() {
        CatalogModels.Variant v = variant(
                CatalogModels.PackageFile.of("2.0.0", deps(LL, "26.60.*"), RID),
                CatalogModels.PackageFile.of("1.5.0", deps(LL, ">=26.51.0 <26.61.0"), RID),
                CatalogModels.PackageFile.of("1.0.0", deps(), RID),
                CatalogModels.PackageFile.of("0.9.0", deps(LL, "1.0.*"), RID));
        List<PackageActivity.VersionGrouping.Group> groups = PackageActivity.VersionGrouping.groups(
                PackageActivity.VersionGrouping.fileStates(v, db()), "1.26.51.01");
        // Files needing LeviLamina only under the chosen game; dependency-free ones stay visible.
        assertEquals(2, groups.size());
        assertEquals("1.26.51.01", groups.get(0).game);
        assertEquals(Collections.singletonList("1.5.0"), versions(groups.get(0)));
        assertEquals("", groups.get(1).game);
        assertEquals(Collections.singletonList("1.0.0"), versions(groups.get(1)));
    }

    @Test
    public void testRangesSortedAndDeduplicatedInKey() {
        CatalogModels.PackageFile a = CatalogModels.PackageFile.of("1.1.0", deps(LL, "26.60.*"), RID);
        CatalogModels.PackageFile b = CatalogModels.PackageFile.of("1.0.0", deps(LL, "26.60.*"), RID);
        List<PackageActivity.VersionGrouping.Group> groups = PackageActivity.VersionGrouping.groups(
                PackageActivity.VersionGrouping.fileStates(variant(a, b), db()), PackageActivity.VersionGrouping.ALL);
        assertEquals(1, groups.size());
        assertEquals(Arrays.asList("1.1.0", "1.0.0"), versions(groups.get(0)));
    }
}
