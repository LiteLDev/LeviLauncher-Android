// Tests of the catalogue screen's filter state: the game version (the
// catalogue's gameVersions) and LeviLamina filters with their defaults from
// the target instance, the facets and sorts of the levimc.org listing, and
// paging.
package org.levimc.launcher.ui.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.levimc.launcher.core.packages.Catalog;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.LeviLamina;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BrowseFiltersTest {
    /** The catalogue's game versions (as levimc.org lists them: BDS versions, newest first). */
    private static final List<String> GAMES = Arrays.asList("1.26.60", "1.26.51", "1.20.61.01");

    private static LeviLamina.Db db() {
        return VersionGroupingTest.db();
    }

    private static BrowseFragment.Filters filters(LeviLamina.Db db, String game, String ll) {
        BrowseFragment.Filters f = new BrowseFragment.Filters();
        f.setGameOptions(GAMES);
        f.setContext(db, game, ll);
        return f;
    }

    @Test
    public void testMatchGame() {
        assertEquals("1.26.60", BrowseFragment.Filters.matchGame("1.26.60.5", GAMES));
        assertEquals("1.20.61.01", BrowseFragment.Filters.matchGame("1.20.61.1", GAMES));
        assertEquals("1.26.51", BrowseFragment.Filters.matchGame("1.26.51", GAMES));
        assertEquals("", BrowseFragment.Filters.matchGame("1.26.6", GAMES));
        assertEquals("", BrowseFragment.Filters.matchGame("1.21.0.3", GAMES));
        assertEquals("", BrowseFragment.Filters.matchGame("", GAMES));
        assertEquals("the longest prefix", "1.26.60.05",
                BrowseFragment.Filters.matchGame("1.26.60.05", Arrays.asList("1.26", "1.26.60.05", "1.26.60")));
    }

    @Test
    public void testNoDatabase() {
        BrowseFragment.Filters f = new BrowseFragment.Filters();
        assertFalse(f.contextReady());
        f.setContext(null, "1.26.60.5", "26.60.4");
        assertTrue(f.contextReady());
        assertFalse(f.mappingAvailable());
        assertTrue("no catalogue yet", f.gameOptions().isEmpty());
        assertEquals(BrowseFragment.Filters.ALL, f.selectedGame());
        // The game filter needs only the catalogue; the LeviLamina filter needs the database.
        f.setGameOptions(GAMES);
        assertEquals(GAMES, f.gameOptions());
        assertEquals("1.26.60", f.selectedGame());
        assertTrue(f.loaderOptions().isEmpty());
        assertEquals(BrowseFragment.Filters.ALL, f.selectedLoader());
        f.setContext(LeviLamina.Db.empty(), "1.26.60.5", "26.60.4");
        assertFalse(f.mappingAvailable());

        Catalog.Search.Query q = f.query();
        assertEquals(Collections.singletonList("1.26.60"), q.gv);
        assertEquals("", q.loaderVersion);
    }

    @Test
    public void testDefaultsFromInstance() {
        // The Android versionName is the catalogue's "1.26.60"; the installed LeviLamina "v26.60.4" is offered.
        BrowseFragment.Filters f = filters(db(), "1.26.60.5", "v26.60.4");
        assertTrue(f.mappingAvailable());
        assertEquals("1.26.60", f.currentGame());
        assertEquals("1.26.60", f.selectedGame());
        assertEquals(Arrays.asList("26.60.5-rc.1", "26.60.4", "26.60.0"), f.loaderOptions());
        assertEquals("26.60.4", f.selectedLoader());

        Catalog.Search.Query q = f.query();
        assertEquals(Collections.singletonList("1.26.60"), q.gv);
        assertEquals("26.60.4", q.loaderVersion);
        assertSame(f.db(), q.db);
        assertEquals(Catalog.Search.SORT_RELEVANCE, q.sort);
    }

    @Test
    public void testUnknownInstanceGame() {
        // A game the catalogue does not list is not selected.
        BrowseFragment.Filters f = filters(db(), "1.20.0.1", "");
        assertEquals(GAMES, f.gameOptions());
        assertEquals("", f.currentGame());
        assertEquals(BrowseFragment.Filters.ALL, f.selectedGame());
        // All games: every LeviLamina version, newest first.
        assertEquals(Arrays.asList("26.60.5-rc.1", "26.60.4", "26.60.0", "26.51.1", "26.51.0"), f.loaderOptions());
        assertEquals(BrowseFragment.Filters.ALL, f.selectedLoader());
        assertTrue(f.query().gv.isEmpty());
    }

    @Test
    public void testCatalogueAfterContext() {
        BrowseFragment.Filters f = new BrowseFragment.Filters();
        f.setContext(db(), "1.26.51.1", "26.51.1");
        assertEquals(BrowseFragment.Filters.ALL, f.selectedGame());
        f.setGameOptions(GAMES);
        assertEquals("1.26.51", f.selectedGame());
        assertEquals(Arrays.asList("26.51.1", "26.51.0"), f.loaderOptions());
        assertEquals("26.51.1", f.selectedLoader());
        // A refreshed catalogue without the selected game falls back to all.
        f.setGameOptions(Collections.singletonList("1.26.60"));
        assertEquals(BrowseFragment.Filters.ALL, f.selectedGame());
    }

    @Test
    public void testInstalledLoaderNotInDatabase() {
        BrowseFragment.Filters f = filters(db(), "", "1.9.0");
        assertEquals(BrowseFragment.Filters.ALL, f.selectedGame());
        // With all games, the installed version is offered first and selected.
        assertEquals("1.9.0", f.loaderOptions().get(0));
        assertEquals("1.9.0", f.selectedLoader());
        // For one game it is not offered, so the choice falls back to all.
        f.selectGame("1.26.51");
        assertEquals(Arrays.asList("26.51.1", "26.51.0"), f.loaderOptions());
        assertEquals(BrowseFragment.Filters.ALL, f.selectedLoader());
    }

    @Test
    public void testSelectionsFollowGame() {
        BrowseFragment.Filters f = filters(db(), "", "");
        f.selectGame("1.26.60");
        f.selectLoader("26.60.0");
        f.selectGame(BrowseFragment.Filters.ALL);
        // still offered for all games
        assertEquals("26.60.0", f.selectedLoader());
        f.selectGame("1.26.51");
        assertEquals(BrowseFragment.Filters.ALL, f.selectedLoader());
    }

    @Test
    public void testLaterContextKeepsChoices() {
        BrowseFragment.Filters f = filters(db(), "1.26.60.05", "26.60.4");
        f.selectGame("1.26.51");
        f.selectLoader("26.51.0");
        // The same instance again (e.g. after a refresh): the user's choices stay.
        f.setContext(db(), "1.26.60.05", "26.60.4");
        assertEquals("1.26.51", f.selectedGame());
        assertEquals("26.51.0", f.selectedLoader());
        // "All" stays for the same instance too.
        f.selectGame(BrowseFragment.Filters.ALL);
        f.setContext(db(), "1.26.60.05", "26.60.4");
        assertEquals(BrowseFragment.Filters.ALL, f.selectedGame());
        // Another instance while "All" is chosen: its game becomes the filter.
        f.setContext(db(), "1.26.51.1", "");
        assertEquals("1.26.51", f.selectedGame());
        // The database is gone: no LeviLamina filter, the game filter stays.
        f.setContext(null, "1.26.51.1", "");
        assertEquals("1.26.51", f.selectedGame());
        assertTrue(f.loaderOptions().isEmpty());
        assertNull(f.db());
        assertEquals("", f.query().loaderVersion);
    }

    @Test
    public void testFacetsAndSort() {
        BrowseFragment.Filters f = filters(null, "", "");
        assertEquals(0, f.facetCount());
        f.setQuery("  land ");
        f.setSort(Catalog.Search.SORT_STARS);
        f.cycleTag("money");
        f.cycleTag("lse");
        f.cycleTag("lse");
        f.toggleLicense("permissive");
        f.toggleDep("github.com/LiteLDev/LeviLamina");
        f.toggleAuthor("LiteLDev");
        f.toggleAuthor("MiracleForest");
        f.toggleAuthor("miracleforest");
        assertEquals(5, f.facetCount());
        Catalog.Search.Query s = f.query();
        assertEquals("land", s.text);
        assertEquals(Catalog.Search.SORT_STARS, s.sort);
        assertEquals(Collections.singletonList("economy"), s.tags.inc);
        assertEquals(Collections.singletonList("lse"), s.tags.exc);
        assertEquals(Collections.singletonList("permissive"), s.licenses);
        assertEquals(Collections.singletonList("github.com/LiteLDev/LeviLamina"), s.deps);
        assertEquals(Collections.singletonList("LiteLDev"), s.authors);
        f.removeTag("economy");
        assertTrue(f.tags().inc.isEmpty());
        f.setSort("name");
        assertEquals("no Name sort: relevance", Catalog.Search.SORT_RELEVANCE, f.sort());
        f.clearFacets();
        assertEquals(0, f.facetCount());
        assertEquals("land", f.searchText());
    }

    @Test
    public void testShowAuthor() {
        BrowseFragment.Filters f = filters(null, "", "");
        f.setQuery("land");
        f.setSort(Catalog.Search.SORT_STARS);
        f.cycleTag("money");
        f.toggleLicense("permissive");
        f.toggleAuthor("MiracleForest");
        f.showAuthor("LiteLDev");
        assertEquals(1, f.facetCount());
        assertEquals(Collections.singletonList("LiteLDev"), f.author());
        assertEquals("", f.searchText());
        assertEquals(Catalog.Search.SORT_STARS, f.sort());
        assertTrue(f.tags().inc.isEmpty());
        assertTrue(f.license().isEmpty());
    }

    @Test
    public void testQueryOnFixtures() {
        CatalogModels.Pkg android = CatalogFixtures.pkg("android-platforms.json");
        CatalogModels.Pkg holo = CatalogFixtures.pkg("holo.json");
        List<CatalogModels.Pkg> all = Arrays.asList(android, holo);
        BrowseFragment.Filters f = filters(null, "", "");
        assertEquals(Arrays.asList(android, holo), Catalog.Search.run(all, f.query()).matched);
        f.cycleTag("tp");
        assertEquals(Collections.singletonList(android), Catalog.Search.run(all, f.query()).matched);
        f.clearFacets();
        f.selectGame("1.26.51");
        assertEquals(Collections.singletonList(holo), Catalog.Search.run(all, f.query()).matched);
    }

    @Test
    public void testPaging() {
        assertEquals(1, BrowseFragment.Filters.pageCount(0));
        assertEquals(1, BrowseFragment.Filters.pageCount(20));
        assertEquals(2, BrowseFragment.Filters.pageCount(21));
        assertEquals(1, BrowseFragment.Filters.clampPage(0, 5));
        assertEquals(2, BrowseFragment.Filters.clampPage(9, 21));
        List<CatalogModels.Pkg> pkgs = new ArrayList<>();
        CatalogModels.Pkg p = CatalogFixtures.pkg("android-platforms.json");
        for (int i = 0; i < 45; i++) {
            pkgs.add(p);
        }
        assertEquals(20, BrowseFragment.Filters.page(pkgs, 1).size());
        assertEquals(5, BrowseFragment.Filters.page(pkgs, 3).size());
        assertEquals(5, BrowseFragment.Filters.page(pkgs, 7).size());
        assertTrue(BrowseFragment.Filters.page(Collections.emptyList(), 1).isEmpty());
    }
}
