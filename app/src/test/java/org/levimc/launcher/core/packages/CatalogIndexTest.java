// Port of upstream internal/lipindex tests (index, detail, catalog, search) for
// the levimc.org v4 catalogue: the forced side and platform (a package is
// listed only with a client entry for this device's platform), the variant
// rule A2, the exact platform match of lip v0.40, and the shared levimc.org
// fixture.
package org.levimc.launcher.core.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.levimc.launcher.core.packages.Fixtures.RID;
import static org.levimc.launcher.core.packages.Fixtures.WIN;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class CatalogIndexTest {
    private static List<String> ids(List<CatalogModels.Pkg> pkgs) {
        List<String> out = new ArrayList<>();
        for (CatalogModels.Pkg p : pkgs) out.add(p.identifier);
        return out;
    }

    private static List<String> names(List<CatalogModels.Pkg> pkgs) {
        List<String> out = new ArrayList<>();
        for (CatalogModels.Pkg p : pkgs) out.add(p.name);
        return out;
    }

    private static List<String> keys(CatalogModels.Pkg p) {
        List<String> keys = new ArrayList<>();
        for (CatalogModels.Variant x : p.variants) keys.add(x.key);
        return keys;
    }

    @Test
    public void testParsePackage() {
        CatalogModels.Pkg p = Fixtures.pkg(Fixtures.HOLO);
        assertNotNull("LHolo has a client variant for android-arm64", p);
        assertEquals("github.com/MarmieQi/LHolo", p.identifier);
        assertEquals("MarmieQi", p.author);
        assertEquals("https://github.com/MarmieQi", p.ownerUrl());
        assertEquals("https://github.com/MarmieQi/LHolo", p.projectUrl);
        assertEquals(Arrays.asList("26.61.0-beta.1", "26.60.0", "26.51.10"), p.versions);
        assertEquals("the first stable", "26.60.0", CatalogModels.Versions.pickTargetVersion(p.versions));
        assertEquals(Arrays.asList("26.61.*", "26.60.*", "26.51.*"), p.llRanges);
        assertEquals(30, p.stars, 0);
        assertEquals("https://example.com/icon.png", p.avatarUrl);
        assertEquals("GPL-3.0", p.license);
        assertEquals(CatalogFacets.COPYLEFT, p.licenseFamily);
        assertEquals(Arrays.asList("1.26.60", "1.26.51"), p.gameVersions);
        assertEquals(Catalog.Search.updatedTime("2025-03-01T00:00:00Z"), p.createdMs);
        assertEquals(Collections.singletonMap("github.com/LiteLDev/LeviLamina", "26.60.*"), p.deps);
        CatalogModels.Variant v = p.defaultVariant();
        assertNotNull(v);
        assertEquals("github.com/MarmieQi/LHolo#client", v.packageIdentifier);
        assertNull("a package without a client entry is dropped", Fixtures.pkg(Fixtures.SERVER_ONLY));

        CatalogModels.Pkg lse = Fixtures.pkg(Fixtures.LSE);
        assertNotNull(lse);
        assertEquals("stars given as text", 120, lse.stars, 0);
        assertEquals("the server-only default variant is dropped",
                Arrays.asList("client", "client_lua", "client_quickjs"), keys(lse));
        assertEquals(1, lse.files.size());
        assertTrue(lse.files.get(0).llRanges.isEmpty());
        assertEquals(2, lse.files.get(0).otherDependencies.size());

        // LegacyMoney: one default variant with a server and a client entry.
        CatalogModels.Pkg money = Fixtures.pkg(Fixtures.MONEY);
        assertEquals("", money.preferredVariant);
        assertEquals("the default variant installs as the bare id", "github.com/LiteLDev/LegacyMoney",
                money.defaultVariant().packageIdentifier);
        assertEquals(Arrays.asList("0.9.2", "0.9.1"), money.versions);
        CatalogModels.PackageFile latest = money.files.get(0);
        assertEquals("only the client entry is kept", Collections.singleton(RID), latest.platforms());
        assertEquals(Collections.singletonMap("github.com/LiteLDev/LeviLamina#client", "26.60.*"), latest.dependencies);
        Map<String, String> deps = new HashMap<>();
        deps.put("github.com/LiteLDev/LeviLamina", "26.60.*");
        deps.put("github.com/LiteLDev/iListenAttentively", "0.18.*");
        assertEquals("the catalogue's deps: every entry of latest.version, by bare id", deps, money.deps);
        assertEquals(Arrays.asList("github.com/LiteLDev/LeviLamina", "github.com/LiteLDev/iListenAttentively"),
                new ArrayList<>(money.deps.keySet()));
        assertEquals(CatalogFacets.PERMISSIVE, money.licenseFamily);
    }

    @Test
    public void testPreferredVariantRuleA2() {
        // AndroidMod: 3.0.0 is Windows-only; 2.0.0 has the client variant for Android.
        CatalogModels.Detail android = Fixtures.detail(Fixtures.ANDROID);
        CatalogModels.Pkg p = Catalog.Index.parsePackage(android, RID);
        assertEquals("client", p.preferredVariant);
        assertEquals(Arrays.asList("client", ""), keys(p));
        CatalogModels.Pkg win = Catalog.Index.parsePackage(android, WIN);
        assertEquals("client", win.preferredVariant);
        assertEquals("the default variant has no win-x64 entry", Collections.singletonList("client"), keys(win));
        assertEquals(Collections.singletonList("client"), keys(Catalog.Index.parsePackage(android, "linux-x64")));
        assertNull(Catalog.Index.parsePackage(android, "osx-arm64"));
        // In the newest version with one: "client", then "", then the others alphabetically.
        CatalogModels.Detail d = CatalogModels.Detail.parse("{\"id\":\"github.com/a/b\",\"versions\":["
                + "{\"version\":\"1.0.0\",\"variants\":{"
                + "\"client\":[{\"side\":\"client\",\"platform\":\"android-arm64\"}]}},"
                + "{\"version\":\"2.0.0\",\"variants\":{"
                + "\"zeta\":[{\"side\":\"client\",\"platform\":\"android-arm64\"}],"
                + "\"\":[{\"side\":\"client\",\"platform\":\"android-arm64\"}],"
                + "\"client\":[{\"side\":\"server\",\"platform\":\"android-arm64\"}]}}]}");
        assertEquals("the newest version by number", "", Catalog.Index.preferredVariant(d, RID));
        assertEquals(Arrays.asList("", "client", "zeta"), keys(Catalog.Index.parsePackage(d, RID)));
        assertNull(Catalog.Index.preferredVariant(d, WIN));
        List<String> names = new ArrayList<>(Arrays.asList("zeta", "", "client", "alpha", "Client"));
        names.sort(Catalog.Index::compareVariantNames);
        assertEquals(Arrays.asList("client", "", "Client", "alpha", "zeta"), names);
    }

    @Test
    public void testFilePlatformsAndRuntimeView() {
        CatalogModels.Pkg p = Fixtures.pkg(Fixtures.ANDROID);
        assertNotNull(p);
        assertEquals("0.9.0 has no client entry in the client variant; other platforms' versions are listed",
                Arrays.asList("3.0.0", "2.0.0", "1.5.0", "1.0.0"), p.versions);
        CatalogModels.Variant v = p.defaultVariant();
        assertEquals(CatalogModels.Availability.UNAVAILABLE, v.findFile("3.0.0").availability(RID));
        CatalogModels.PackageFile two = v.findFile("v2.0.0");
        assertEquals(new LinkedHashSet<>(Arrays.asList("win-x64", "android-arm64")), two.platforms());
        assertEquals(CatalogModels.Availability.AVAILABLE, two.availability(RID));
        // A file depends on what lip derives for the device: its entry for the platform.
        assertEquals(Collections.singletonMap("github.com/example/AndroidDep", "1.x"), two.otherDependencies);
        assertEquals(Collections.singletonList("26.60.*"), two.llRanges);
        CatalogModels.PackageFile forWin = Catalog.Index.parsePackage(Fixtures.detail(Fixtures.ANDROID), WIN)
                .defaultVariant().findFile("2.0.0");
        assertEquals(Collections.singletonMap("github.com/example/WinOnlyDep", "1.x"), forWin.otherDependencies);
        assertEquals("no entry for the platform: every entry's", 2, Catalog.Index.parsePackage(
                Fixtures.detail(Fixtures.ANDROID), "linux-x64").defaultVariant().findFile("2.0.0").otherDependencies.size());
        assertEquals(CatalogModels.Availability.AVAILABLE, v.findFile("1.5.0").availability(RID));
        assertEquals(CatalogModels.Availability.UNAVAILABLE, v.findFile("1.0.0").availability(RID));
        // Badge: the best version of each variant.
        assertEquals(CatalogModels.Availability.AVAILABLE, v.availability(RID));
        assertEquals(CatalogModels.Availability.AVAILABLE, p.availability(RID));
        assertEquals(CatalogModels.Availability.UNAVAILABLE, p.availability("osx-arm64"));
        CatalogModels.Variant newestOnly = new CatalogModels.Variant("client", "x#client", Arrays.asList(
                CatalogModels.PackageFile.of("2.0.0", null, "win-x64"), CatalogModels.PackageFile.of("1.0.0", null)));
        assertEquals("an older version without entries", CatalogModels.Availability.UNKNOWN, newestOnly.availability(RID));
        assertEquals(CatalogModels.Availability.UNKNOWN, new CatalogModels.Variant("client", "x#client", null).availability(RID));
        CatalogModels.Variant dflt = p.findVariant("");
        assertEquals(Collections.singletonList("0.9.0"), dflt.versions);
        assertEquals(CatalogModels.Availability.AVAILABLE, dflt.availability(RID));
    }

    @Test
    public void testForcedPlatform() {
        // Only packages with a client entry for the platform are listed; AndroidMod's Windows-only newest version
        // does not hide it, an older Android build is enough.
        List<CatalogModels.Detail> details = new ArrayList<>(Arrays.asList(Fixtures.detail(Fixtures.MONEY),
                Fixtures.detail(Fixtures.ANDROID), Fixtures.siteDetail("LiteLDev/Arke")));
        assertEquals(Arrays.asList("LegacyMoney", "AndroidMod"), names(Catalog.Index.build(details, RID).packages()));
        assertEquals(Arrays.asList("AndroidMod", "Arke"), names(Catalog.Index.build(details, WIN).packages()));
    }

    @Test
    public void testDescriptions() {
        CatalogModels.Detail d = Fixtures.detail(Fixtures.HOLO);
        d.catalogDescription = "From the index";
        CatalogModels.Pkg p = Catalog.Index.parsePackage(d, RID);
        assertEquals("From the index", p.description);
        assertEquals("Schematic mod", p.fullDescription);
        // The site cuts mods[].description to 200 code points and searches that text; the detail has the whole
        // description. A word past the cut is not found on the site, so not here either.
        StringBuilder full = new StringBuilder();
        while (full.length() < 199) full.append("plain words ");
        String cut = full.substring(0, 199).trim() + "\u2026";
        String entry = ",\"versions\":[{\"version\":\"1.0.0\",\"variants\":{\"\":[{\"side\":\"client\",\"platform\":\"" + RID + "\"}]}}]}";
        CatalogModels.Detail long1 = CatalogModels.Detail.parse("{\"id\":\"github.com/o/long\",\"name\":\"Long\",\"description\":\""
                + full.substring(0, 199) + " and a zebrafish at the end\"" + entry);
        long1.catalogDescription = cut;
        CatalogModels.Detail plain = CatalogModels.Detail.parse("{\"id\":\"github.com/o/plain\",\"name\":\"Plain\","
                + "\"description\":\"Only in the detail\"" + entry);
        Catalog.Index idx = Catalog.Index.build(Arrays.asList(long1, plain), RID);
        assertEquals(cut, idx.find("o/long").description);
        assertTrue("the package page has the whole text", idx.find("o/long").fullDescription.endsWith("zebrafish at the end"));
        assertEquals("falls back to the detail's, whole", "Only in the detail", idx.find("o/plain").description);
        assertNull("a word past the cut matches nothing", Catalog.Search.scores(idx.packages(), "zebrafish").get("github.com/o/long"));
        assertTrue(Catalog.Search.scores(idx.packages(), "plain words").containsKey("github.com/o/long"));
    }

    @Test
    public void testBuildIndexAndFind() {
        Catalog.Index idx = Fixtures.index();
        assertEquals("server-only dropped", 4, idx.size());
        for (String q : new String[]{"github.com/LiteLDev/LegacyMoney", "github.com/LiteLDev/LegacyMoney#client",
                "GITHUB.COM/liteldev/legacymoney", "LiteLDev/LegacyMoney", "https://github.com/LiteLDev/LegacyMoney"}) {
            CatalogModels.Pkg p = idx.find(q);
            assertNotNull("Find(" + q + ")", p);
            assertEquals("LegacyMoney", p.name);
        }
        assertNull("Find of an unknown package", idx.find("nobody/nothing"));
        // The index's order is kept (the "Relevance" order without a search).
        assertEquals(Arrays.asList("github.com/LiteLDev/LegacyMoney", "github.com/MarmieQi/LHolo",
                "github.com/LiteLDev/LegacyScriptEngine", "github.com/LiteLDev/CrashLogger"), ids(idx.packages()));
    }

    // ---- the index (levimc.org/api/v4/mods/index.json) ------------------------------

    private static Catalog.Client.IndexDoc indexDoc(String json) {
        return Catalog.GSON.fromJson(json, Catalog.Client.IndexDoc.class);
    }

    @Test
    public void testClientPackagesOfIndex() {
        Map<String, String> m = Catalog.Client.clientPackages(indexDoc(Fixtures.text("catalogue-index.json")), RID);
        assertEquals("server-only and Windows-only skipped; the platform any case", Arrays.asList(
                "github.com/MarmieQi/LHolo", "github.com/LiteLDev/LegacyMoney", "github.com/LiteLDev/LegacyScriptEngine",
                "github.com/Broken/Detail"), new ArrayList<>(m.keySet()));
        assertNull("no description in the index", m.get("github.com/MarmieQi/LHolo"));
    }

    @Test
    public void testClientPackagesOnTheSharedFixture() {
        // The forced filter: side has "client" and platform has the launcher's platform.
        Catalog.Client.IndexDoc index = indexDoc(Fixtures.siteText("index.json"));
        assertEquals("an android-arm64 server plugin (CALL) is not listed", Arrays.asList(
                "github.com/MiracleForest/iAutomaticFishing", "github.com/MiracleForest/iInfiniteNightVision"),
                new ArrayList<>(Catalog.Client.clientPackages(index, RID).keySet()));
        Map<String, String> win = Catalog.Client.clientPackages(index, WIN);
        assertEquals("62 of the 111 mods run on the client", 62, win.size());
        assertTrue(win.containsKey("github.com/LiteLDev/Arke"));
        assertFalse("server only", win.containsKey("github.com/IceBlcokMC/PLand"));
        assertEquals("the platform is compared ignoring case", win, Catalog.Client.clientPackages(index, "WIN-X64"));
        Map<String, String> want = new HashMap<>();
        for (JsonObject m : Fixtures.siteMods()) {
            want.put(m.get("id").getAsString(), m.has("description") ? m.get("description").getAsString() : null);
        }
        for (Map.Entry<String, String> e : win.entrySet()) assertEquals(e.getKey(), want.get(e.getKey()), e.getValue());
        // A description that is not a string is ignored (the detail's is used).
        Map<String, String> odd = Catalog.Client.clientPackages(indexDoc("{\"mods\":[{\"id\":\"github.com/a/b\","
                + "\"description\":7,\"side\":[\"client\"],\"platform\":[\"android-arm64\"]}]}"), RID);
        assertEquals(1, odd.size());
        assertNull(odd.get("github.com/a/b"));
        // An entry with values of another type is skipped, not the whole index; values are trimmed.
        Map<String, String> mixed = Catalog.Client.clientPackages(indexDoc("{\"mods\":["
                + "{\"id\":7,\"side\":[\"client\"],\"platform\":[\"android-arm64\"]},"
                + "{\"id\":\"github.com/a/s\",\"side\":\"client\",\"platform\":[\"android-arm64\"]},"
                + "{\"id\":\"github.com/a/b\",\"side\":[\" Client \"],\"platform\":[1,\"android-arm64\"]}]}"), RID);
        assertEquals(Collections.singletonList("github.com/a/b"), new ArrayList<>(mixed.keySet()));
        // The index of the site before lip v0.40 is rejected (the catalogue is then unavailable).
        try {
            Catalog.Client.clientPackages(indexDoc("{\"mods\":[{\"tooth\":\"github.com/a/b\",\"env\":[\"client\"]}]}"), RID);
            fail("old index");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("before lip v0.40"));
        }
    }

    // ---- the shared fixture ----------------------------------------------------------

    @Test
    public void testSharedFixtureDetails() {
        // Every mod of the shared fixture runs on win-x64 only: none is for Android.
        String[] all = {"LiteLDev/Arke", "LiteLDev/LegacyScriptEngine", "IceBlcokMC/PLand", "zimuya4153/LSE_Waila"};
        for (String m : all) {
            assertNull(m, Catalog.Index.parsePackage(Fixtures.siteDetail(m), RID));
        }
        CatalogModels.Pkg arke = Catalog.Index.parsePackage(Fixtures.siteDetail("LiteLDev/Arke"), WIN);
        assertNotNull(arke);
        assertEquals("github.com/LiteLDev/Arke", arke.identifier);
        assertEquals("client", arke.preferredVariant);
        assertEquals("LiteLDev", arke.author);
        assertEquals("0.1.1", CatalogModels.Versions.pickTargetVersion(arke.versions));
        assertEquals("no license reported", "", arke.license);
        assertEquals(CatalogFacets.NONE, arke.licenseFamily);
        assertEquals(Collections.singletonList("1.26.60"), arke.gameVersions);
        assertEquals(Collections.singletonList("shader"), CatalogFacets.visibleTags(arke.tags));
        assertEquals("visual", CatalogFacets.featuredTagsOf(arke.tags).get(0).id);

        // LegacyScriptEngine: "client" in the newest version wins over the default variant's older client entries.
        CatalogModels.Pkg lse = Catalog.Index.parsePackage(Fixtures.siteDetail("LiteLDev/LegacyScriptEngine"), WIN);
        assertEquals("client", lse.preferredVariant);
        assertEquals(Arrays.asList("client", "", "client_lua", "client_nodejs", "client_python", "client_quickjs"), keys(lse));
        assertEquals(74, lse.findVariant("").versions.size());
        assertEquals(CatalogFacets.COPYLEFT, lse.licenseFamily);
        // PLand: only older versions have client entries, in the default variant.
        CatalogModels.Pkg pland = Catalog.Index.parsePackage(Fixtures.siteDetail("IceBlcokMC/PLand"), WIN);
        assertEquals("", pland.preferredVariant);
        assertEquals(Collections.singletonList(""), keys(pland));
        // LSE_Waila: a server plugin; its repository moved to its owner's.
        CatalogModels.Detail waila = Fixtures.siteDetail("zimuya4153/LSE_Waila");
        assertNull(Catalog.Index.parsePackage(waila, WIN));
        assertEquals("MiracleForest", waila.owner);

        List<CatalogModels.Detail> details = new ArrayList<>();
        for (String m : all) details.add(Fixtures.siteDetail(m));
        assertEquals(3, Catalog.Index.build(details, WIN).size());
        assertEquals(0, Catalog.Index.build(details, RID).size());
    }

    @Test
    public void testLatestDepsMatchTheCatalogue() {
        // The deps a detail gives are the index's (levimc.org server/catalog.ts depsOf).
        Map<String, JsonObject> index = new HashMap<>();
        for (JsonObject m : Fixtures.siteMods()) index.put(m.get("id").getAsString(), m);
        for (String m : new String[]{"LiteLDev/Arke", "LiteLDev/LegacyScriptEngine", "IceBlcokMC/PLand", "zimuya4153/LSE_Waila"}) {
            CatalogModels.Detail d = Fixtures.siteDetail(m);
            JsonObject entry = index.get(d.id);
            assertNotNull(m + " is listed", entry);
            List<String> wantKeys = new ArrayList<>();
            Map<String, String> want = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : entry.getAsJsonObject("deps").entrySet()) {
                wantKeys.add(e.getKey());
                want.put(e.getKey(), e.getValue().getAsString());
            }
            Map<String, String> got = Catalog.Index.latestDeps(d);
            assertEquals(m, want, new HashMap<>(got));
            assertEquals(m + ": sorted by id", wantKeys, new ArrayList<>(got.keySet()));
        }
        Map<String, String> lse = Catalog.Index.latestDeps(Fixtures.siteDetail("LiteLDev/LegacyScriptEngine"));
        assertFalse("not the package itself", lse.containsKey("github.com/LiteLDev/LegacyScriptEngine"));
        assertEquals("26.60.*", lse.get("github.com/LiteLDev/LeviLamina"));
        assertEquals("an unlisted dependency keeps the key's spelling", "26.*", lse.get("github.com/LiteLDev/7-zip-tooth"));
    }

    @Test
    public void testLookupKeyAndReference() {
        assertEquals("github.com/liteldev/foo", Catalog.Index.lookupKey("LiteLDev/Foo"));
        assertEquals("github.com/liteldev/foo", Catalog.Index.lookupKey("github.com/LiteLDev/Foo#client"));
        assertEquals("github.com/liteldev/foo", Catalog.Index.lookupKey("https://github.com/liteldev/foo@1.0.0"));
        assertEquals("gitee.com/a/b", Catalog.Index.lookupKey("gitee.com/a/b"));
        Catalog.Index.Reference r = Catalog.Index.parseReference("LiteLDev/LegacyMoney#client@0.9.2");
        assertNotNull(r);
        assertEquals("github.com/LiteLDev/LegacyMoney", r.id);
        assertEquals("client", r.variant);
        assertEquals("0.9.2", r.version);
        for (String bad : new String[]{"", "LegacyMoney", "a/b/c/d", "C:\\mods\\x"}) {
            assertNull("ParseReference(" + bad + ")", Catalog.Index.parseReference(bad));
        }
        assertTrue(PackageService.isLeviLamina("github.com/LiteLDev/LeviLamina#client"));
        assertFalse(PackageService.isLeviLamina("LiteLDev/LegacyMoney"));
        assertEquals("https://levimc.org/api/v4/mods/github.com/a%20b/c/index.json",
                Catalog.Index.detailUrl(Catalog.Client.DEFAULT_BASE_URL, "github.com/a b/c#client"));
        assertEquals("", Catalog.Index.detailUrl(Catalog.Client.DEFAULT_BASE_URL, "#client"));
    }

    @Test
    public void testInferredFields() {
        assertEquals("https://gitee.com/a/b", Catalog.Index.inferProjectUrl("gitee.com/a/b"));
        assertEquals("https://example.com/", Catalog.Index.normalizeHttpUrl("HTTPS://example.com"));
        assertEquals("", Catalog.Index.normalizeHttpUrl("ftp://example.com/icon.png"));
        assertEquals("a", Catalog.Index.inferAuthor("gitee.com/a/b"));
        // Without an owner the identifier's owner is the author; without a repository its project page.
        CatalogModels.Pkg p = Catalog.Index.parsePackage(CatalogModels.Detail.parse("{\"id\":\"github.com/o/r\",\"versions\":"
                + "[{\"version\":\"1.0.0\",\"variants\":{\"\":[{\"side\":\"client\",\"platform\":\"android-arm64\"}]}}]}"), RID);
        assertEquals("o", p.author);
        assertEquals("https://github.com/o/r", p.projectUrl);
        assertEquals("", p.license);
        assertEquals(CatalogFacets.NONE, p.licenseFamily);
    }

    @Test
    public void testIndexAdd() {
        Catalog.Index idx = Fixtures.index();
        CatalogModels.Pkg android = Fixtures.pkg(Fixtures.ANDROID);
        idx.add(android);
        assertSame(android, idx.find("example/AndroidMod"));
        assertEquals(5, idx.size());
        CatalogModels.Pkg again = Fixtures.pkg(Fixtures.ANDROID);
        idx.add(again);
        assertEquals("a package with the same id is replaced", 5, idx.size());
        assertSame(again, idx.find("github.com/example/AndroidMod"));
        assertTrue(idx.packages().contains(again));
        assertFalse(idx.packages().contains(android));
    }

    @Test
    public void testAvailability() {
        assertEquals(CatalogModels.Availability.AVAILABLE, CatalogModels.Availability.of(Collections.singletonList("android-arm64"), RID));
        assertEquals(CatalogModels.Availability.AVAILABLE, CatalogModels.Availability.of(Arrays.asList("win-x64", "android-arm64"), RID));
        assertEquals("lip v0.40 matches the platform exactly", CatalogModels.Availability.UNAVAILABLE,
                CatalogModels.Availability.of(Collections.singletonList("android-*"), RID));
        assertEquals(CatalogModels.Availability.UNAVAILABLE, CatalogModels.Availability.of(Collections.singletonList("Android-Arm64"), RID));
        assertEquals(CatalogModels.Availability.UNAVAILABLE, CatalogModels.Availability.of(Collections.singletonList(""), RID));
        assertEquals(CatalogModels.Availability.UNKNOWN, CatalogModels.Availability.of(Collections.emptyList(), RID));
        assertEquals(CatalogModels.Availability.UNAVAILABLE, CatalogModels.Availability.of(Arrays.asList("win-x64", "linux-arm64"), RID));
        assertEquals("android-arm is not arm64", CatalogModels.Availability.UNAVAILABLE,
                CatalogModels.Availability.of(Collections.singletonList("android-arm"), RID));
        assertEquals(CatalogModels.Availability.AVAILABLE, CatalogModels.Availability.best(CatalogModels.Availability.UNKNOWN, CatalogModels.Availability.AVAILABLE));
        assertEquals(CatalogModels.Availability.UNKNOWN, CatalogModels.Availability.best(CatalogModels.Availability.UNAVAILABLE, CatalogModels.Availability.UNKNOWN));
    }

    @Test
    public void testCacheRoundTrip() throws Exception {
        java.io.File f = java.io.File.createTempFile("catalog", ".json");
        try {
            Catalog.Cache cache = new Catalog.Cache(f);
            CatalogModels.Detail d = Fixtures.siteDetail("LiteLDev/LegacyScriptEngine");
            d.catalogDescription = "Indexed";
            d.readmes = null;
            cache.save(Collections.singletonList(d), 1000);
            assertFalse("v4 keys only", new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").contains("\"tooth\""));
            Catalog.Cache.Doc doc = cache.load();
            assertEquals(1000, doc.fetchedAtMs());
            CatalogModels.Pkg a = Catalog.Index.parsePackage(d, WIN);
            CatalogModels.Pkg b = Catalog.Index.parsePackage(doc.details.get(0), WIN);
            assertEquals(a.versions, b.versions);
            assertEquals(keys(a), keys(b));
            assertEquals("variant and dependency order survive", a.deps, b.deps);
            assertEquals(a.gameVersions, b.gameVersions);
            assertEquals(a.createdMs, b.createdMs);
            assertEquals("Indexed", b.description);
            // A copy of the older shape has no ids: nothing of it is read.
            java.nio.file.Files.write(f.toPath(), "{\"details\":[{\"tooth\":\"github.com/a/b\"}]}".getBytes("UTF-8"));
            assertTrue(cache.load().details.isEmpty());
        } finally {
            f.delete();
        }
    }

    @Test
    public void testDetailDecodeErrors() {
        for (String bad : new String[]{"{\"id\": {}}", "{\"id\":\"a/b\",\"versions\":{}}",
                "{\"id\":\"a/b\",\"versions\":[{\"variants\":\"x\"}]}", "not json",
                // the format before lip v0.40: variants as a list
                "{\"id\":\"github.com/a/b\",\"versions\":[{\"version\":\"1.0.0\",\"variants\":[{\"label\":\"client\"}]}]}"}) {
            try {
                CatalogModels.Detail.parse(bad);
                throw new AssertionError("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // a document of another shape
            }
        }
        CatalogModels.Detail d = CatalogModels.Detail.parse("{\"id\":\"github.com/a/b\",\"versions\":[null,"
                + "{\"version\":\"1.0.0\",\"variants\":{\"client\":[null,{\"side\":\"client\",\"platform\":\"android-arm64\",\"deps\":null}]}}]}");
        CatalogModels.Pkg p = Catalog.Index.parsePackage(d, RID);
        assertNotNull(p);
        assertEquals("no name: the id", "github.com/a/b", p.name);
        assertEquals("no owner: inferred", "a", p.author);
        assertTrue(p.files.get(0).dependencies.isEmpty());
    }

    // ---- search, filters, sorts and counts ---------------------------------------------

    private static List<String> run(Catalog.Index idx, Catalog.Search.Query q) {
        return names(Catalog.Search.run(idx.packages(), q).matched);
    }

    @Test
    public void testQuery() {
        Catalog.Index idx = Fixtures.index();
        LeviLamina.Db db = Fixtures.db();
        // CrashLogger is hidden; relevance without a search is the catalogue's order.
        Catalog.Search.Query q = new Catalog.Search.Query();
        assertEquals(Arrays.asList("LegacyMoney", "LHolo", "LegacyScriptEngine"), run(idx, q));
        q.sort = Catalog.Search.SORT_STARS;
        assertEquals(Arrays.asList("LegacyScriptEngine", "LegacyMoney", "LHolo"), run(idx, q));
        q.sort = Catalog.Search.SORT_UPDATED;
        assertEquals("unknown dates last", Arrays.asList("LHolo", "LegacyMoney", "LegacyScriptEngine"), run(idx, q));
        q.sort = Catalog.Search.SORT_NEWEST;
        assertEquals(Arrays.asList("LHolo", "LegacyMoney", "LegacyScriptEngine"), run(idx, q));
        q.sort = "name";
        assertEquals("an unknown sort is relevance", Arrays.asList("LegacyMoney", "LHolo", "LegacyScriptEngine"), run(idx, q));
        assertEquals(Arrays.asList("relevance", "stars", "updated", "newest"), Catalog.Search.SORTS);
        // Search: featured names in every language, the owner, every word.
        for (String text : new String[]{"ECONOMY", "经济", "經濟", "前置库", "Library / API"}) {
            q = new Catalog.Search.Query();
            q.text = text;
            assertEquals(text, Collections.singletonList("LegacyMoney"), run(idx, q));
        }
        q = new Catalog.Search.Query();
        q.text = "marmieqi schematic";
        assertEquals(Collections.singletonList("LHolo"), run(idx, q));
        q.text = "marmieqi economy";
        assertTrue(run(idx, q).isEmpty());
        q.text = "levilamina";
        assertTrue("the hidden loader tag finds nothing", run(idx, q).isEmpty());
        assertEquals(3, Catalog.Search.matchQuality("visual & hud", "hud"));
        assertEquals(2, Catalog.Search.matchQuality("legacy money", "leg"));
        assertEquals(1, Catalog.Search.matchQuality("levilamina", "lamina"));
        assertEquals(0, Catalog.Search.matchQuality("levilamina", "x"));
        assertTrue("an exact name scores highest",
                Catalog.Search.scores(idx.packages(), "legacymoney").get("github.com/LiteLDev/LegacyMoney") > 100);
        // Game version: any of them, on the catalogue's gameVersions.
        q = new Catalog.Search.Query();
        q.gv = Collections.singletonList("1.26.51");
        assertEquals(Arrays.asList("LegacyMoney", "LHolo"), run(idx, q));
        q.gv = Arrays.asList("1.26.51", "1.26.60");
        assertEquals(3, run(idx, q).size());
        // Tags: every included one, none of the excluded; aliases count as their featured tag.
        q = new Catalog.Search.Query();
        q.tags = Catalog.Search.cycleTag(q.tags, "money");
        assertEquals(Collections.singletonList("economy"), q.tags.inc);
        assertEquals(Collections.singletonList("LegacyMoney"), run(idx, q));
        q.tags = Catalog.Search.cycleTag(q.tags, "economy");
        assertEquals(Collections.singletonList("economy"), q.tags.exc);
        assertEquals(Arrays.asList("LHolo", "LegacyScriptEngine"), run(idx, q));
        q.tags = Catalog.Search.cycleTag(q.tags, "Category:Economy");
        assertTrue(q.tags.inc.isEmpty() && q.tags.exc.isEmpty());
        assertSame("a hidden tag is not a filter", q.tags, Catalog.Search.cycleTag(q.tags, "type:mod"));
        // License: a family or an SPDX id, any case.
        q = new Catalog.Search.Query();
        q.licenses = Collections.singletonList(CatalogFacets.COPYLEFT);
        assertEquals(Arrays.asList("LHolo", "LegacyScriptEngine"), run(idx, q));
        q.licenses = Arrays.asList("mit", "none");
        assertEquals(Collections.singletonList("LegacyMoney"), run(idx, q));
        // Dependencies: every bare id, any case; author: any owner, any case.
        q = new Catalog.Search.Query();
        q.deps = Collections.singletonList("github.com/liteldev/levilamina");
        assertEquals(3, run(idx, q).size());
        q.deps = Catalog.Search.toggle(q.deps, "github.com/LiteLDev/iListenAttentively");
        assertEquals(Collections.singletonList("LegacyMoney"), run(idx, q));
        q.deps = Catalog.Search.toggle(q.deps, "GITHUB.COM/liteldev/ilistenattentively");
        assertEquals(1, q.deps.size());
        q = new Catalog.Search.Query();
        q.authors = Collections.singletonList("marmieqi");
        assertEquals(Collections.singletonList("LHolo"), run(idx, q));
        // LeviLamina (the launcher's extra): a range of the preferred variant the version satisfies; not tied to the game.
        q = new Catalog.Search.Query();
        q.loaderVersion = "v26.60.4";
        assertEquals("off without the database", 3, run(idx, q).size());
        q.db = LeviLamina.Db.empty();
        assertEquals(3, run(idx, q).size());
        q.db = db;
        assertEquals(Arrays.asList("LegacyMoney", "LHolo"), run(idx, q));
        q.loaderVersion = "26.51.1";
        assertEquals(Arrays.asList("LegacyMoney", "LHolo"), run(idx, q));
        q.gv = Collections.singletonList("1.26.60");
        assertEquals(Arrays.asList("LegacyMoney", "LHolo"), run(idx, q));
        q.gv = new ArrayList<>();
        q.loaderVersion = "26.70.0";
        assertTrue(run(idx, q).isEmpty());
        // Options and counts.
        q = new Catalog.Search.Query();
        q.tags = new Catalog.Search.Facet(Collections.singletonList("economy"), null);
        Catalog.Search.Result r = Catalog.Search.run(idx.packages(), q);
        assertEquals(Arrays.asList("library", "visual", "economy", "lse", "quickjs", "schematic"), r.options.tags);
        assertEquals(Arrays.asList("1.26.60", "1.26.51"), r.options.gv);
        assertEquals(Arrays.asList("LiteLDev", "MarmieQi"), r.options.authors);
        assertEquals(2, r.options.licenses.size());
        assertEquals(CatalogFacets.PERMISSIVE, r.options.licenses.get(0).family);
        assertEquals(Collections.singletonList("GPL-3.0"), r.options.licenses.get(1).ids);
        assertEquals(1, (int) r.tagCounts.get("economy"));
        assertEquals(1, (int) r.tagCounts.get("library"));
        assertEquals(0, (int) r.tagCounts.get("visual"));
        assertEquals(1, (int) r.licenseCounts.get(CatalogFacets.PERMISSIVE));
        assertEquals(0, (int) r.licenseCounts.get("GPL-3.0"));
        assertEquals(1, (int) r.authorCounts.get("LiteLDev"));
        assertEquals(1, (int) r.gvCounts.get("1.26.51"));
        assertEquals(1, Catalog.Search.filterCount(q));
        assertEquals("1.4.2", Catalog.Search.normalizeLoaderFilter("v1.4.2"));
        assertEquals("26.60.5-rc.1", Catalog.Search.normalizeLoaderFilter("LeviLamina 26.60.5-rc.1 build"));
        assertEquals(1_767_225_600_000L, Catalog.Search.updatedTime("2026-01-01"));
        assertEquals(0, Catalog.Search.updatedTime("yesterday"));
    }

    private static InstallPlanner.Input input(CatalogModels.Variant v) {
        InstallPlanner.Input in = new InstallPlanner.Input();
        in.variant = v;
        in.rid = RID;
        return in;
    }

    @Test
    public void testDecidePicksTheNewestVersionTheInstalledLoaderAccepts() {
        // The game (1.26.60.05) has LeviLamina 26.60.0 and 26.60.4; 26.60.0 is installed.
        CatalogModels.Variant v = new CatalogModels.Variant("client", "github.com/a/mod#client", Arrays.asList(
                CatalogModels.PackageFile.of("1.3.0", Collections.singletonMap("github.com/LiteLDev/LeviLamina#client", ">=26.60.4"), RID),
                CatalogModels.PackageFile.of("1.2.1", Collections.singletonMap("github.com/LiteLDev/LeviLamina#client", "26.60.*"), RID)));
        InstallPlanner.Input in = input(v);
        in.gameVersion = "1.26.60.05";
        in.db = Fixtures.db();
        in.loader = CatalogModels.InstallState.installed(true, "26.60.0");
        InstallPlanner.Plan p = InstallPlanner.decide(in);
        assertEquals("1.2.1", p.version);
        assertTrue(p.loaderCompatible);
        assertFalse(p.needsLoader);
        assertEquals("", p.blocker);
        // Without LeviLamina the newest version the game supports.
        in.loader = CatalogModels.InstallState.NOT_INSTALLED;
        assertEquals("1.3.0", InstallPlanner.decide(in).version);
        // No version fits the installed LeviLamina: the newest stays, shown as needing another LeviLamina.
        in.loader = CatalogModels.InstallState.installed(true, "26.51.1");
        p = InstallPlanner.decide(in);
        assertEquals("1.3.0", p.version);
        assertTrue(p.replacesLoader);
    }

    @Test
    public void testDecideAndroidAvailability() {
        CatalogModels.Variant v = Fixtures.pkg(Fixtures.ANDROID).defaultVariant();
        LeviLamina.Db db = Fixtures.db();
        // No version asked: the newest build for Android (2.0.0), not the newer Windows-only 3.0.0.
        InstallPlanner.Input in = input(v);
        in.gameVersion = "1.26.60.05";
        in.db = db;
        in.loader = CatalogModels.InstallState.installed(true, "26.60.4");
        InstallPlanner.Plan p = InstallPlanner.decide(in);
        assertEquals("2.0.0", p.version);
        assertEquals(CatalogModels.Availability.AVAILABLE, p.availability);
        assertEquals("", p.blocker);
        assertFalse(p.needsConfirm);
        assertEquals("the Android entry's dependencies", Collections.singletonMap("github.com/example/AndroidDep", "1.x"),
                p.file.otherDependencies);
        // Asking for the Windows-only version is blocked.
        in.version = "3.0.0";
        p = InstallPlanner.decide(in);
        assertEquals(CatalogModels.Availability.UNAVAILABLE, p.availability);
        assertEquals(InstallPlanner.BLOCK_NO_ANDROID_BUILD, p.blocker);
        assertFalse(p.needsConfirm);
        // An android-arm64 entry is a build; another platform (linux-x64) is not.
        in.version = "1.5.0";
        p = InstallPlanner.decide(in);
        assertEquals(CatalogModels.Availability.AVAILABLE, p.availability);
        assertFalse(p.needsConfirm);
        in.version = "1.0.0";
        p = InstallPlanner.decide(in);
        assertEquals(CatalogModels.Availability.UNAVAILABLE, p.availability);
        assertEquals(InstallPlanner.BLOCK_NO_ANDROID_BUILD, p.blocker);
        // A file without entries (built by hand) asks first.
        CatalogModels.Variant bare = new CatalogModels.Variant("client", "x#client",
                Collections.singletonList(CatalogModels.PackageFile.of("1.0.0", null)));
        p = InstallPlanner.decide(input(bare));
        assertEquals(CatalogModels.Availability.UNKNOWN, p.availability);
        assertTrue(p.needsConfirm);
        assertEquals("", p.blocker);
        // LegacyMoney's default variant has an android-arm64 client entry: no confirmation.
        in = input(Fixtures.pkg(Fixtures.MONEY).defaultVariant());
        in.gameVersion = "1.26.60.05";
        in.db = db;
        p = InstallPlanner.decide(in);
        assertEquals("0.9.2", p.version);
        assertEquals(CatalogModels.Availability.AVAILABLE, p.availability);
        assertFalse(p.needsConfirm);
        // A real package (the shared fixture): every version is win-x64 only.
        in = input(Catalog.Index.parsePackage(Fixtures.siteDetail("LiteLDev/Arke"), WIN).defaultVariant());
        in.gameVersion = "1.26.60.05";
        in.db = db;
        p = InstallPlanner.decide(in);
        assertEquals(InstallPlanner.BLOCK_NO_ANDROID_BUILD, p.blocker);
        assertEquals("0.1.1", p.version);
    }

    // ---- tags and licenses -----------------------------------------------------------

    @Test
    public void testFacetsTables() {
        assertEquals("utility", CatalogFacets.tagKey("Category:Tools"));
        assertNull(CatalogFacets.tagKey("platform:levilamina"));
        assertNull(CatalogFacets.tagKey("client"));
        assertEquals(Arrays.asList("visual", "schematic"), CatalogFacets.tagKeys(Arrays.asList("type:mod", "HUD", "shader", "schematic")));
        assertEquals(Arrays.asList("economy", "teleport", "quickjs"),
                CatalogFacets.tagKeys(Arrays.asList("money", "economy", "tp", "type:mod", "quickjs")));
        assertEquals("Visual & HUD", CatalogFacets.featured("hud").label(java.util.Locale.ENGLISH));
        assertEquals("视觉与界面", CatalogFacets.featured("hud").label(java.util.Locale.SIMPLIFIED_CHINESE));
        assertEquals("視覺與介面", CatalogFacets.featured("hud").label(java.util.Locale.TRADITIONAL_CHINESE));
        assertEquals("前置庫 / API", CatalogFacets.featured("library").label(java.util.Locale.forLanguageTag("zh-HK")));
        assertEquals("library", CatalogFacets.featured("pland:library").id);
        // A mod's featured tags, each with the raw tags that put it there, then its other tags.
        List<String> tags = Arrays.asList("tp", "money", "Economy", "waystones", "lse", "platform:levilamina", "custom");
        List<CatalogFacets.Featured> f = CatalogFacets.featuredTagsOf(tags);
        assertEquals(2, f.size());
        assertEquals("economy", f.get(0).id);
        assertEquals(Arrays.asList("money", "economy"), CatalogFacets.rawTagsOf(f.get(0), tags));
        assertEquals("teleport", f.get(1).id);
        assertEquals(Arrays.asList("tp", "waystones"), CatalogFacets.rawTagsOf(f.get(1), tags));
        assertEquals(Arrays.asList("lse", "custom"), CatalogFacets.plainTagsOf(tags));
        assertEquals(CatalogFacets.PERMISSIVE, CatalogFacets.licenseFamily("Apache-2.0"));
        assertEquals(CatalogFacets.WEAK_COPYLEFT, CatalogFacets.licenseFamily("LGPL-3.0"));
        assertEquals(CatalogFacets.COPYLEFT, CatalogFacets.licenseFamily("AGPL-3.0"));
        assertEquals(CatalogFacets.OTHER, CatalogFacets.licenseFamily("NOASSERTION"));
        assertEquals(CatalogFacets.OTHER, CatalogFacets.licenseFamily("WTFPL"));
        assertEquals(CatalogFacets.NONE, CatalogFacets.licenseFamily(""));
        assertEquals(CatalogFacets.NONE, CatalogFacets.licenseFamily(null));
        assertEquals("AGPL-3.0", CatalogFacets.licenseOf("github.com/Copper-Lamp/BSChat", "NOASSERTION"));
        assertEquals("MIT", CatalogFacets.licenseOf("github.com/IECKSEYR/hologramlib", null));
        assertEquals("MIT", CatalogFacets.licenseOf("github.com/a/b", " MIT "));
    }
}
