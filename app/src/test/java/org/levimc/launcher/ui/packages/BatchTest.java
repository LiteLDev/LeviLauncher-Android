// The pure parts of the Installed tab's package operations (the operations themselves run in the
// integration test and on the device).
package org.levimc.launcher.ui.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


import org.junit.Test;
import org.levimc.launcher.core.packages.InstalledPackages;
import org.levimc.launcher.core.packages.PackageService;

import java.util.Arrays;
import java.util.Collections;

public class BatchTest {
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
}
