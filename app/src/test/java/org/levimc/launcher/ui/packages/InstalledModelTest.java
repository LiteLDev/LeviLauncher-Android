// The Installed tab's rows (the package groups of upstream useModsPage /
// modIntelligenceResolver): workspace packages joined with the catalogue
// and the instance's mods, update states, filters.
package org.levimc.launcher.ui.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.levimc.launcher.core.packages.InstalledPackages;
import org.levimc.launcher.core.packages.WorkspaceFixture;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.Catalog;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class InstalledModelTest {
    private static final String RID = "android-arm64";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private InstalledPackages ws;

    /**
     * LeviLamina (dependency of foo), foo (explicit, two folders), lib (a
     * dependency of app, a folder that is no loadable mod), app (explicit),
     * a local package (explicit, installed from a file).
     */
    @Before
    public void setUp() throws Exception {
        File dir = tmp.newFolder("Demo");
        WorkspaceFixture.write(dir, RID,
                new WorkspaceFixture.Pkg("github.com/LiteLDev/LeviLamina", "client", "1.2.0", false, null,
                        "mods/LeviLamina/manifest.json"),
                new WorkspaceFixture.Pkg("github.com/a/foo", "client", "1.0.0", true,
                        "{ \"github.com/LiteLDev/LeviLamina#client\": \">=1.0.0 <2.0.0\" }",
                        "mods/foo/manifest.json", "mods/foo-extra/manifest.json"),
                new WorkspaceFixture.Pkg("github.com/a/lib", "", "0.5.0", false, null, "mods/lib/data.bin"),
                new WorkspaceFixture.Pkg("github.com/a/app", "client", "2.0.0", true,
                        "{ \"github.com/a/lib\": \"0.5.0\" }", "mods/App/manifest.json"),
                new WorkspaceFixture.Pkg("example.com/x/local", "", "1.0.0", true, null,
                        "mods/local/manifest.json").withRef("file:///data/pkgs/local.zip"));
        ws = InstalledPackages.read(dir);
    }

    private static Catalog.Index index() {
        List<CatalogModels.Detail> details = new ArrayList<>();
        // 1.1.0 is published for Windows only: the update offered is 1.0.5.
        details.add(CatalogModels.Detail.parse("{\"id\": \"github.com/a/foo\", \"name\": \"Foo\", \"versions\": ["
                + "{\"version\": \"1.1.0\", \"variants\": {\"client\": [{\"side\": \"client\", \"platform\": \"win-x64\"}]}},"
                + "{\"version\": \"1.0.5\", \"variants\": {\"client\": [{\"side\": \"client\", \"platform\": \"android-arm64\"}]}},"
                + "{\"version\": \"1.0.0\", \"variants\": {\"client\": [{\"side\": \"client\", \"platform\": \"android-arm64\"}]}}"
                + "]}"));
        // App's 2.0.0 has a Windows and an Android build.
        details.add(CatalogModels.Detail.parse("{\"id\": \"github.com/a/app\", \"name\": \"App Pkg\", \"versions\": ["
                + "{\"version\": \"2.0.0\", \"variants\": {\"client\": [{\"side\": \"client\", \"platform\": \"win-x64\"}, "
                + "{\"side\": \"client\", \"platform\": \"android-arm64\"}]}}]}"));
        details.add(CatalogModels.Detail.parse("{\"id\": \"github.com/LiteLDev/LeviLamina\", \"name\": \"LeviLamina\", \"versions\": ["
                + "{\"version\": \"1.3.0\", \"variants\": {\"client\": [{\"side\": \"client\", \"platform\": \"android-arm64\"}]}}]}"));
        return Catalog.Index.build(details, RID);
    }

    private static List<InstalledFragment.Model.ModEntry> mods() {
        return Arrays.asList(
                new InstalledFragment.Model.ModEntry("foo", "Foo Mod", true),
                new InstalledFragment.Model.ModEntry("foo-extra", "Foo Extra", false),
                new InstalledFragment.Model.ModEntry("App", "App", true),
                new InstalledFragment.Model.ModEntry("local", "Local Mod", true),
                new InstalledFragment.Model.ModEntry("LeviLamina", "LeviLamina", true),
                new InstalledFragment.Model.ModEntry("plain", "Not a package", true));
    }

    private static InstalledFragment.Model.Row row(List<InstalledFragment.Model.Row> rows, String identity) {
        for (InstalledFragment.Model.Row r : rows) {
            if (r.pkg.identity.equals(identity)) {
                return r;
            }
        }
        throw new AssertionError("no row " + identity + " in " + rows);
    }

    private static List<String> names(List<InstalledFragment.Model.Row> rows) {
        List<String> out = new ArrayList<>();
        for (InstalledFragment.Model.Row r : rows) {
            out.add(r.name);
        }
        return out;
    }

    @Test
    public void testRowsJoinWorkspaceCatalogueAndMods() {
        List<InstalledFragment.Model.Row> rows = InstalledFragment.Model.build(ws, index(), mods(), RID);
        // Sorted by name: catalogue names, the only mod's name, else the identity.
        assertEquals(Arrays.asList("App Pkg", "Foo", "github.com/a/lib", "LeviLamina", "Local Mod"), names(rows));

        InstalledFragment.Model.Row foo = row(rows, "github.com/a/foo#client");
        assertTrue(foo.explicit());
        assertEquals("a/foo", foo.identifier);
        assertEquals(Arrays.asList("foo", "foo-extra"), foo.folders);
        assertEquals(Arrays.asList("foo", "foo-extra"), foo.loadableFolders);
        assertTrue(foo.anyEnabled);
        assertFalse(foo.allEnabled);
        assertFalse(foo.notLoadable());
        // Newest version with a possible Android build (1.1.0 is win-x64 only).
        assertEquals(InstalledFragment.Model.UpdateState.AVAILABLE, foo.update);
        assertEquals("1.0.5", foo.targetVersion);
        assertTrue(foo.canUpdate());
        assertTrue(foo.requiredBy.isEmpty());

        InstalledFragment.Model.Row app = row(rows, "github.com/a/app#client");
        assertEquals(InstalledFragment.Model.UpdateState.LATEST, app.update);
        assertEquals("2.0.0", app.targetVersion);
        assertTrue(app.allEnabled);
        assertNotNull(app.catalog);
        assertEquals("github.com/a/app", app.catalog.identifier);

        InstalledFragment.Model.Row lib = row(rows, "github.com/a/lib");
        assertFalse(lib.explicit());
        assertTrue(lib.notLoadable());
        assertFalse(lib.anyEnabled);
        assertEquals(InstalledFragment.Model.UpdateState.NO_SOURCE, lib.update);
        assertEquals("", lib.targetVersion);
        assertEquals(Arrays.asList("App Pkg"), lib.requiredBy);
        assertEquals(Arrays.asList("App Pkg"), lib.explicitRequiredBy);
        assertEquals("a/lib", lib.identifier);

        // LeviLamina is managed in the Loader settings: never an update here.
        InstalledFragment.Model.Row ll = row(rows, "github.com/LiteLDev/LeviLamina#client");
        assertTrue(ll.leviLamina);
        assertEquals(InstalledFragment.Model.UpdateState.NO_SOURCE, ll.update);
        assertEquals(Arrays.asList("Foo"), ll.requiredBy);
        assertEquals(Arrays.asList("Foo"), ll.explicitRequiredBy);

        InstalledFragment.Model.Row local = row(rows, "example.com/x/local");
        assertNull(local.identifier);
        assertFalse(local.hasPackagePage());
        assertTrue(local.pkg.isLocal());
        assertEquals(InstalledFragment.Model.UpdateState.NO_SOURCE, local.update);
    }

    @Test
    public void testWithoutCatalogue() {
        List<InstalledFragment.Model.Row> rows = InstalledFragment.Model.build(ws, null, mods(), RID);
        for (InstalledFragment.Model.Row r : rows) {
            assertEquals(r.toString(), InstalledFragment.Model.UpdateState.NO_SOURCE, r.update);
            assertNull(r.catalog);
        }
        // One loadable mod names its package; several (foo) leave the identity.
        assertEquals("App", row(rows, "github.com/a/app#client").name);
        assertEquals("github.com/a/foo#client", row(rows, "github.com/a/foo#client").name);
        // Without mods every package is "not loadable".
        for (InstalledFragment.Model.Row r : InstalledFragment.Model.build(ws, null, null, RID)) {
            assertTrue(r.notLoadable());
        }
    }

    @Test
    public void testFilters() {
        List<InstalledFragment.Model.Row> rows = InstalledFragment.Model.build(ws, index(), mods(), RID);
        assertEquals(Arrays.asList("App Pkg", "Foo", "Local Mod"),
                names(InstalledFragment.Model.filter(rows, InstalledFragment.Model.Filter.EXPLICIT, false, "")));
        assertEquals(Arrays.asList("github.com/a/lib", "LeviLamina"),
                names(InstalledFragment.Model.filter(rows, InstalledFragment.Model.Filter.DEPENDENCY, false, null)));
        // Only enabled: a package with at least one enabled mod.
        assertEquals(Arrays.asList("App Pkg", "Foo", "LeviLamina", "Local Mod"),
                names(InstalledFragment.Model.filter(rows, InstalledFragment.Model.Filter.ALL, true, "")));
        // The search matches names, identities and folders, ignoring case.
        assertEquals(Arrays.asList("Foo"), names(InstalledFragment.Model.filter(rows, InstalledFragment.Model.Filter.ALL, false,
                " EXTRA ")));
        assertEquals(Arrays.asList("Local Mod"), names(InstalledFragment.Model.filter(rows, InstalledFragment.Model.Filter.ALL,
                false, "example.com")));
        assertTrue(InstalledFragment.Model.filter(rows, InstalledFragment.Model.Filter.DEPENDENCY, false, "app").isEmpty());
    }

    @Test
    public void testFolderLookup() {
        List<InstalledFragment.Model.Row> rows = InstalledFragment.Model.build(ws, index(), mods(), RID);
        Map<String, InstalledFragment.Model.Row> byFolder = InstalledFragment.Model.byFolder(rows);
        assertEquals("github.com/a/foo#client", byFolder.get(InstalledFragment.Model.folderKey(" FOO-EXTRA ")).pkg.identity);
        assertNull(byFolder.get(InstalledFragment.Model.folderKey("plain")));
        assertEquals("github.com/a/app#client", InstalledFragment.Model.ownerOf(rows, "app").pkg.identity);
        assertNull(InstalledFragment.Model.ownerOf(rows, "plain"));
        assertNull(InstalledFragment.Model.ownerOf(rows, null));
    }

    @Test
    public void testIncludesPreview() {
        InstalledPackages.Pkg p = new InstalledPackages.Pkg("github.com/a/many", "github.com/a/many", "",
                "1.0.0", true, "", null, Arrays.asList("a", "b", "c", "d", "e"), null);
        InstalledFragment.Model.Row r = new InstalledFragment.Model.Row(p, "Many", "a/many", null, false, Arrays.asList("a"),
                true, true, InstalledFragment.Model.UpdateState.NO_SOURCE, "", Arrays.asList(), Arrays.asList());
        assertEquals(Arrays.asList("a", "b", "c"), r.previewFolders());
        assertEquals(2, r.extraFolders());
        assertEquals("github.com/a/many", r.key());
    }

    @Test
    public void testTargetAvailability() {
        List<InstalledFragment.Model.Row> rows = InstalledFragment.Model.build(ws, index(), mods(), RID);
        // foo updates to 1.0.5 (Android build), skipping the Windows-only 1.1.0.
        InstalledFragment.Model.Row foo = row(rows, "github.com/a/foo#client");
        assertEquals("1.0.5", foo.targetVersion);
        assertEquals(CatalogModels.Availability.AVAILABLE, foo.targetAvailability(RID));
        InstalledFragment.Model.Row app = row(rows, "github.com/a/app#client");
        assertEquals("2.0.0", app.targetVersion);
        assertEquals(CatalogModels.Availability.AVAILABLE, app.targetAvailability(RID));
        assertEquals(CatalogModels.Availability.UNAVAILABLE, app.targetAvailability("osx-arm64"));
        // No catalogue entry or no catalogue: no target, nothing to ask.
        assertNull(row(rows, "github.com/a/lib").targetAvailability(RID));
        List<InstalledFragment.Model.Row> offline = InstalledFragment.Model.build(ws, null, mods(), RID);
        assertNull(row(offline, "github.com/a/foo#client").targetAvailability(RID));
    }

    @Test
    public void testCatalogAvailability() {
        Catalog.Index index = index();
        // Install by identifier follows the install dialog's platform rule.
        // The catalogue keeps every client version of a package it lists: the Windows-only 1.1.0 cannot be installed.
        assertEquals(CatalogModels.Availability.UNAVAILABLE, InstalledFragment.Model.catalogAvailability(index, "a/foo", "1.1.0", RID));
        assertEquals(CatalogModels.Availability.AVAILABLE, InstalledFragment.Model.catalogAvailability(index, "a/foo", "v1.0.5", RID));
        assertEquals(CatalogModels.Availability.AVAILABLE,
                InstalledFragment.Model.catalogAvailability(index, "github.com/a/foo#client", "1.0.0", RID));
        assertEquals(CatalogModels.Availability.AVAILABLE, InstalledFragment.Model.catalogAvailability(index, "a/app", "2.0.0", RID));
        // Not in the catalogue: the engine decides.
        assertNull(InstalledFragment.Model.catalogAvailability(index, "a/foo", "9.9.9", RID));
        assertNull(InstalledFragment.Model.catalogAvailability(index, "a/foo#server", "1.0.5", RID));
        assertNull(InstalledFragment.Model.catalogAvailability(index, "a/unknown", "1.0.0", RID));
        assertNull(InstalledFragment.Model.catalogAvailability(index, "not an id", "1.0.0", RID));
        assertNull(InstalledFragment.Model.catalogAvailability(null, "a/foo", "1.1.0", RID));
    }
}
