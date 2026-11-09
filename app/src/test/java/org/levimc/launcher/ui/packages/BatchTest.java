// The pure parts of the Installed tab's package operations (the operations themselves run in the
// integration test and on the device).
package org.levimc.launcher.ui.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.levimc.launcher.core.packages.InstalledPackages;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.packages.engine.Listener;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.settings.FeatureSettings;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BatchTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    static InstalledFragment.Model.Row row(String id, String variant, String version, boolean explicit, String name,
                                     InstalledFragment.Model.UpdateState update, String target) {
        String identity = variant.isEmpty() ? id : id + "#" + variant;
        InstalledPackages.Pkg p = new InstalledPackages.Pkg(identity, id, variant, version, explicit,
                identity + "@" + version, null, Collections.singletonList(name.toLowerCase()), null);
        String frontend = PackageService.frontendIdentifier(id, variant);
        boolean ll = PackageService.isLeviLamina(id);
        return new InstalledFragment.Model.Row(p, name, frontend, null, ll, p.folders, true, true, update, target,
                Collections.emptyList(), Collections.emptyList());
    }

    static final InstalledFragment.Model.Row FOO = row("github.com/a/foo", "client", "1.0.0", true, "Foo",
            InstalledFragment.Model.UpdateState.AVAILABLE, "1.1.0");
    static final InstalledFragment.Model.Row BAR = row("github.com/a/bar", "", "2.0.0", true, "Bar",
            InstalledFragment.Model.UpdateState.AVAILABLE, "2.1.0");
    static final InstalledFragment.Model.Row BAZ = row("github.com/a/baz", "client", "3.0.0", true, "Baz",
            InstalledFragment.Model.UpdateState.LATEST, "3.0.0");
    static final InstalledFragment.Model.Row DEP = row("github.com/a/dep", "client", "0.1.0", false, "Dep",
            InstalledFragment.Model.UpdateState.NO_SOURCE, "");
    static final InstalledFragment.Model.Row LL = row("github.com/LiteLDev/LeviLamina", "client", "1.2.0", false,
            "LeviLamina", InstalledFragment.Model.UpdateState.NO_SOURCE, "");

    @Test
    public void testCheckUpdate() {
        assertEquals("", PackageActions.Batch.checkUpdate(FOO));
        assertEquals(PackageService.ERR_ALREADY_LATEST, PackageActions.Batch.checkUpdate(BAZ));
        assertEquals(PackageService.ERR_NO_UPDATE_SOURCE, PackageActions.Batch.checkUpdate(DEP));
        assertEquals(PackageService.ERR_LL_MANAGED_IN_VERSION_SETTINGS, PackageActions.Batch.checkUpdate(LL));
        assertEquals(Arrays.asList(FOO, BAR), PackageActions.Batch.updatable(Arrays.asList(FOO, BAZ, BAR, DEP, LL)));
    }

    @Test
    public void testPromoteSource() {
        assertEquals("file:///data/pkgs/my%20mod.zip", PackageActions.Batch.promoteSource("example.com/x",
                "file:///data/pkgs/my%20mod.zip", "1.0.0"));
        assertEquals("file:///data/pkgs/dir#client", PackageActions.Batch.promoteSource("example.com/x",
                "file:///data/pkgs/dir#client", "1.0.0"));
        assertEquals("https://example.com/p.zip", PackageActions.Batch.promoteSource("example.com/x", "https://example.com/p.zip",
                "1.0.0"));
        assertEquals("gitee.com/a/b@1.0.0", PackageActions.Batch.promoteSource("gitee.com/a/b", "gitee.com/a/b@1.0.0", "1.0.0"));
        assertNull(PackageActions.Batch.promoteSource("x", "file:relative", "1.0.0"));
    }

    @Test
    public void testOutcomeAndDistinct() {
        PackageActions.Batch.Outcome o = new PackageActions.Batch.Outcome();
        assertTrue(o.isEmpty());
        assertEquals("", o.code());
        assertFalse(o.changed);
        assertEquals(Arrays.asList(FOO, BAR), PackageActions.Batch.distinct(Arrays.asList(FOO, BAR, FOO)));
    }

    /** A failed item keeps the engine's message, so the result dialog can say why (not only "Failed to uninstall package"). */
    @Test
    public void testFailureKeepsTheCause() throws Exception {
        PackageService service = new PackageService(null, new FeatureSettings(), tmp.newFolder("files"), tmp.newFolder("cache"));
        PackageService.set(service);
        File dir = tmp.newFolder("instance"), src = tmp.newFolder("pkg");
        Files.write(new File(src, "lip.toml").toPath(), ("format = 4\nid = \"example.com/a/data\"\nversion = \"1.0.0\"\n"
                + "[[variants.\"\"]]\nside = \"client\"\nplatform = \"" + PackageService.rid() + "\"\n"
                + "assets = [{ type = \"self\", place = [{ src = \"d.txt\", dest = \"d.txt\" }] }]\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(src, "d.txt").toPath(), "d".getBytes(StandardCharsets.UTF_8));
        GameVersion instance = new GameVersion("instance", "Instance", "1.21.50.07", dir, false, "", "arm64-v8a");
        assertEquals("", service.installSource(new Cancel(), Listener.NONE, instance, src.getPath()));
        File mods = new File(dir, "mods");
        assertTrue(mods.delete() && mods.createNewFile()); // 'mods' is a file now: the uninstall fails before any change

        List<String> forwarded = new ArrayList<>();
        PackageActions.Batch.Cause cause = new PackageActions.Batch.Cause(new Listener() {
            @Override
            public void log(Level level, String message) {
                forwarded.add(level + " " + message);
            }

            @Override
            public void progress(String id, Stage stage, String pkg, long current, long total, boolean bytes) {
            }
        });
        assertEquals(PackageService.ERR_PACKAGE_UNINSTALL_FAILED,
                service.uninstallById(new Cancel(), cause, instance, "example.com/a/data", "example.com/a/data"));
        assertTrue(cause.last(), cause.last().contains("'mods' in the workspace is not a directory"));
        assertTrue(forwarded.contains("ERROR " + cause.last()));
        assertTrue(new File(dir, "d.txt").isFile());
    }
}
