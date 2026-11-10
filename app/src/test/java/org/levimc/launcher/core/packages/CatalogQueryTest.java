// The catalogue query against the shared levimc.org fixture (the shared
// contract's A3): the expected values are what the site's own
// mods/src/lib/search.ts and catalog-query.ts give on the same index.json
// (computed with them; the site's default order spreads authors, so results
// in the relevance order without a search are compared as sets).
package org.levimc.launcher.core.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class CatalogQueryTest {
    private static List<CatalogModels.Pkg> catalog;

    @BeforeClass
    public static void load() {
        catalog = Fixtures.siteCatalog();
    }

    private static String shortId(String id) {
        return id.replace("github.com/", "");
    }

    /** The first n results of a search as "owner/repo=score", by score then the catalogue's order. */
    private static List<String> search(String q, int n) {
        Map<String, Integer> scores = Catalog.Search.scores(catalog, q);
        List<CatalogModels.Pkg> hits = new ArrayList<>();
        for (CatalogModels.Pkg p : catalog) {
            if (scores.containsKey(p.identifier)) {
                hits.add(p);
            }
        }
        hits.sort((a, b) -> Integer.compare(scores.get(b.identifier), scores.get(a.identifier)));
        List<String> out = new ArrayList<>();
        for (CatalogModels.Pkg p : hits.subList(0, Math.min(n, hits.size()))) {
            out.add(shortId(p.identifier) + "=" + scores.get(p.identifier));
        }
        return out;
    }

    private static List<String> ids(List<CatalogModels.Pkg> pkgs, int n) {
        List<String> out = new ArrayList<>();
        for (CatalogModels.Pkg p : pkgs.subList(0, Math.min(n, pkgs.size()))) {
            out.add(shortId(p.identifier));
        }
        return out;
    }

    private static Catalog.Search.Query state() {
        return new Catalog.Search.Query();
    }

    @Test
    public void testSearchMatchesTheSite() {
        assertEquals(111, catalog.size());
        assertEquals(Arrays.asList("IceBlcokMC/PLand=14", "IceBlcokMC/PLand-LegacyRemoteCallApi=8",
                "lwenk/PlandProjectileFix=5"), search("land", 10));
        assertEquals(Collections.singletonList("IceBlcokMC/PLand=1"), search("领地", 10));
        assertEquals("featured names in every language", Arrays.asList("LiteLDev/LegacyMoney=6", "carefly/RLXMoney=6"),
                search("經濟", 10));
        assertEquals(Arrays.asList("LiteLDev/LegacyMoney=6", "carefly/RLXMoney=6"), search("economy", 10));
        assertEquals(Arrays.asList("LiteLDev/LegacyMoney=6", "carefly/RLXMoney=6"), search("经济", 10));
        assertEquals(30, Catalog.Search.scores(catalog, "lamina").size());
        assertEquals(Arrays.asList("amatouhake/LaminaPeek=11", "amatouhake/LaminaSort=11",
                "Maskviva/levilamina-rust-loader=6", "VincentZyuApps/levilamina-plugin-mclistener-ws-server=3",
                "LiteLDev/LegacyScriptEngine=1", "IceBlcokMC/PLand=1"), search("lamina", 6));
        assertEquals(Collections.singletonList("engsr6982/TeleportSystem=22"), search("teleport tp", 10));
        assertEquals(8, Catalog.Search.scores(catalog, "lse").size());
        assertEquals(Arrays.asList("zimuya4153/LSE_Waila=21", "Ieckseyr/HologramLib=6",
                "GroupMountain/GMLIB-LegacyRemoteCallApi=6", "superx101/CALL=6", "LiteLDev/LegacyRemoteCall=6",
                "LL-LSE-Lab/MelonRind=6"), search("lse", 6));
        assertEquals("an exact name scores highest", Arrays.asList("LiteLDev/LegacyScriptEngine=115",
                "zimuya4153/LSE_Waila=6", "LiteLDev/LegacyRemoteCall=3"), search("LegacyScriptEngine", 10));
        assertEquals(8, Catalog.Search.scores(catalog, "Library / API").size());
        assertEquals(Arrays.asList("GroupMountain/GMLIB-LegacyRemoteCallApi=26", "GroupMountain/ModAPI-Release=26",
                "Ieckseyr/HologramLib=24", "IceBlcokMC/PLand-LegacyRemoteCallApi=24", "GroupMountain/GMLIB-Release=21",
                "LiteLDev/iListenAttentively=21"), search("Library / API", 6));
        assertTrue("every word must match", Catalog.Search.scores(catalog, "api, form").isEmpty());
        assertNull("no query", Catalog.Search.scores(catalog, "  "));
    }

    @Test
    public void testOptionsMatchTheSite() {
        Catalog.Search.Options o = Catalog.Search.options(catalog);
        assertEquals(40, o.tags.size());
        assertEquals(Arrays.asList("library", "utility", "management", "game-mechanics", "adventure", "visual",
                "economy", "teleport", "anticheat", "optimization", "technology", "protocol", "gmlib", "lse", "pland",
                "backpack"), o.tags.subList(0, 16));
        assertEquals(4, o.licenses.size());
        assertEquals("permissive", o.licenses.get(0).family);
        assertEquals(Arrays.asList("MIT", "CC0-1.0", "Apache-2.0", "BSD-3-Clause", "Unlicense"), o.licenses.get(0).ids);
        assertEquals("weak-copyleft", o.licenses.get(1).family);
        assertEquals(Collections.singletonList("LGPL-3.0"), o.licenses.get(1).ids);
        assertEquals("copyleft", o.licenses.get(2).family);
        assertEquals(Arrays.asList("GPL-3.0", "AGPL-3.0"), o.licenses.get(2).ids);
        assertEquals("none", o.licenses.get(3).family);
        assertTrue(o.licenses.get(3).ids.isEmpty());
        assertEquals(19, o.deps.size());
        assertEquals(Arrays.asList("github.com/LiteLDev/LeviLamina", "github.com/GroupMountain/GMLIB-Release",
                "github.com/GroupMountain/ModAPI-Release", "github.com/MiracleForest/iListenAttentively-Release"),
                o.deps.subList(0, 4));
        assertEquals(49, o.authors.size());
        assertEquals(Arrays.asList("MiracleForest", "GroupMountain", "LiteLDev"), o.authors.subList(0, 3));
        assertEquals(26, o.gv.size());
        assertEquals(Arrays.asList("1.26.60", "1.26.51", "1.26.40", "1.26.32", "1.26.20"), o.gv.subList(0, 5));
    }

    @Test
    public void testFiltersAndSortsMatchTheSite() {
        Catalog.Search.Query s = state();
        s.licenses = Collections.singletonList("permissive");
        s.sort = Catalog.Search.SORT_STARS;
        Catalog.Search.Result r = Catalog.Search.run(catalog, s);
        assertEquals(38, r.matched.size());
        assertEquals(Arrays.asList("ShrBox/BackupHelper", "copper-lamp/LitematicaBE", "yhzx233/CustomMap"),
                ids(r.matched, 3));

        s = state();
        s.licenses = Collections.singletonList("other");
        assertTrue(Catalog.Search.run(catalog, s).matched.isEmpty());

        s = state();
        s.gv = Collections.singletonList("1.21.50");
        s.sort = Catalog.Search.SORT_STARS;
        assertEquals(Arrays.asList("superx101/CALL", "zimuya4153/LSE_Waila", "GroupMountain/GMSidebar"),
                ids(Catalog.Search.run(catalog, s).matched, 10));

        s = state();
        s.tags = new Catalog.Search.Facet(null, Arrays.asList("library", "utility", "management"));
        s.sort = Catalog.Search.SORT_UPDATED;
        r = Catalog.Search.run(catalog, s);
        assertEquals(91, r.matched.size());
        assertEquals(Arrays.asList("MRUIAW/UtilityRenderDistanceExpansion", "LiteLDev/Arke", "qing85566/ChiyanMap"),
                ids(r.matched, 3));

        s = state();
        s.deps = Collections.singletonList("github.com/groupmountain/gmlib-release");
        s.sort = Catalog.Search.SORT_NEWEST;
        r = Catalog.Search.run(catalog, s);
        assertEquals("dependencies match in any case", 11, r.matched.size());
        assertEquals(Arrays.asList("GroupMountain/GMEssentials-Release", "GroupMountain/GMDebugStick",
                "GroupMountain/ThrowableFireball"), ids(r.matched, 3));

        s = state();
        s.authors = Arrays.asList("MiracleForest", "groupmountain");
        s.sort = Catalog.Search.SORT_STARS;
        r = Catalog.Search.run(catalog, s);
        assertEquals(33, r.matched.size());
        assertEquals(Arrays.asList("GroupMountain/GMLIB-Release", "GroupMountain/GMLIB-LegacyRemoteCallApi",
                "GroupMountain/FreeCamera"), ids(r.matched, 3));

        s = state();
        s.text = "lse";
        s.tags = new Catalog.Search.Facet(null, Collections.singletonList("lse"));
        assertEquals("the search score orders relevance", Arrays.asList("LL-LSE-Lab/MelonRind",
                "copper-lamp/LitematicaBE", "LiteLDev/LegacyScriptEngine"), ids(Catalog.Search.run(catalog, s).matched, 10));
    }

    @Test
    public void testCountsMatchTheSite() {
        Catalog.Search.Query s = state();
        s.tags = new Catalog.Search.Facet(Collections.singletonList("library"), Collections.singletonList("management"));
        s.authors = Collections.singletonList("LiteLDev");
        Catalog.Search.Result r = Catalog.Search.run(catalog, s);
        assertEquals(new HashSet<>(Arrays.asList("LiteLDev/iListenAttentively", "LiteLDev/LegacyMoney",
                "LiteLDev/LegacyRemoteCall")), new HashSet<>(ids(r.matched, 10)));
        assertEquals(3, (int) r.tagCounts.get("library"));
        assertEquals("an excluded tag counts as included instead", 0, (int) r.tagCounts.get("management"));
        assertEquals(1, (int) r.tagCounts.get("economy"));
        assertEquals(0, (int) r.tagCounts.get("utility"));
        assertEquals(0, (int) r.tagCounts.get("gmlib"));
        assertEquals(1, (int) r.licenseCounts.get("permissive"));
        assertEquals(2, (int) r.licenseCounts.get("weak-copyleft"));
        assertEquals(0, (int) r.licenseCounts.get("copyleft"));
        assertEquals(0, (int) r.licenseCounts.get("none"));
        assertEquals(1, (int) r.licenseCounts.get("MIT"));
        assertEquals(2, (int) r.licenseCounts.get("LGPL-3.0"));
        assertEquals("an OR group's option replaces the group", 3, (int) r.authorCounts.get("LiteLDev"));
        assertEquals(3, (int) r.authorCounts.get("GroupMountain"));
        assertEquals(0, (int) r.authorCounts.get("MiracleForest"));
        assertEquals(3, (int) r.depCounts.get("github.com/LiteLDev/LeviLamina"));
        assertEquals(0, (int) r.depCounts.get("github.com/LiteLDev/iListenAttentively"));
        assertEquals(3, (int) r.gvCounts.get("1.26.60"));
        assertEquals(0, (int) r.gvCounts.get("1.21.50"));
        assertEquals("one included tag, one excluded tag and one author", 3, Catalog.Search.filterCount(s));
        assertEquals(0, Catalog.Search.filterCount(Catalog.Search.clearFilters(s)));
    }
}
