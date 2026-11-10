// Tests of the install dialog decision (upstream PackagePage install dialog,
// driven by the planner per design §5.3 and FINAL DECISIONS 1).
package org.levimc.launcher.ui.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.levimc.launcher.R;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.InstallPlanner;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.CatalogModels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InstallModelTest {
    private static final String RID = VersionGroupingTest.RID;
    private static final String LL = VersionGroupingTest.LL;

    private static CatalogModels.Variant variant() {
        return VersionGroupingTest.variant(
                CatalogModels.PackageFile.of("2.0.0", VersionGroupingTest.deps(LL, "26.60.*"), RID),
                CatalogModels.PackageFile.of("1.5.0", VersionGroupingTest.deps(LL, "26.60.*"), "win-x64"),
                CatalogModels.PackageFile.of("1.2.0", VersionGroupingTest.deps(LL, "26.60.*")),
                CatalogModels.PackageFile.of("1.0.0", VersionGroupingTest.deps(), RID));
    }

    private static InstallDialog.Model.Input input(String version) {
        InstallDialog.Model.Input in = new InstallDialog.Model.Input();
        in.variant = variant();
        in.version = version;
        in.gameVersion = "1.26.60.5";
        in.db = VersionGroupingTest.db();
        in.loader = CatalogModels.InstallState.installed(true, "26.60.4");
        in.pkg = CatalogModels.InstallState.NOT_INSTALLED;
        in.rid = RID;
        return in;
    }

    private static List<InstallDialog.Model.NoticeType> types(InstallDialog.Model m) {
        List<InstallDialog.Model.NoticeType> out = new ArrayList<>();
        for (InstallDialog.Model.Notice n : m.notices) {
            out.add(n.type);
        }
        return out;
    }

    @Test
    public void testLoading() {
        InstallDialog.Model.Input in = input("2.0.0");
        in.loader = null;
        in.pkg = null;
        in.dbLoading = true;
        InstallDialog.Model m = InstallDialog.Model.of(in);
        assertNull(m.plan);
        assertNull(m.availability());
        assertFalse(m.primaryEnabled);
        assertEquals(InstallDialog.Model.Primary.INSTALL, m.primary);
        assertEquals(Arrays.asList(InstallDialog.Model.NoticeType.CHECKING_LOADER,
                InstallDialog.Model.NoticeType.CHECKING_PACKAGE), types(m));

        // A version without a LeviLamina dependency does not wait for LeviLamina.
        in = input("1.0.0");
        in.pkg = null;
        in.loader = null;
        assertEquals(Arrays.asList(InstallDialog.Model.NoticeType.CHECKING_PACKAGE), types(InstallDialog.Model.of(in)));
    }

    @Test
    public void testInstallAvailable() {
        InstallDialog.Model m = InstallDialog.Model.of(input("2.0.0"));
        assertNotNull(m.plan);
        assertEquals(InstallDialog.Model.Primary.INSTALL, m.primary);
        assertTrue(m.primaryEnabled);
        assertTrue(types(m).isEmpty());
        assertEquals(CatalogModels.Availability.AVAILABLE, m.availability());
        assertFalse(m.confirmUnspecified);
        assertFalse(m.confirmDowngrade);
        assertEquals(R.string.pkg_task_title_install, InstallDialog.Model.taskAction(m.primary));
    }

    @Test
    public void testLoaderMissingGoesToLoader() {
        InstallDialog.Model.Input in = input("2.0.0");
        in.loader = CatalogModels.InstallState.NOT_INSTALLED;
        InstallDialog.Model m = InstallDialog.Model.of(in);
        assertEquals(InstallDialog.Model.Primary.GO_TO_LOADER, m.primary);
        assertTrue(m.primaryEnabled);
        assertEquals(Arrays.asList(InstallDialog.Model.NoticeType.LOADER_MISSING), types(m));
    }

    @Test
    public void testLoaderIncompatibleBlocks() {
        InstallDialog.Model.Input in = input("2.0.0");
        in.loader = CatalogModels.InstallState.installed(true, "26.51.1");
        InstallDialog.Model m = InstallDialog.Model.of(in);
        assertEquals(InstallDialog.Model.Primary.INSTALL, m.primary);
        assertFalse(m.primaryEnabled);
        assertEquals(Arrays.asList(InstallDialog.Model.NoticeType.LOADER_INCOMPATIBLE), types(m));
        assertEquals("26.51.1", m.plan.installedLoader);
    }

    @Test
    public void testMappingUnavailableBlocks() {
        InstallDialog.Model.Input in = input("2.0.0");
        in.db = null;
        InstallDialog.Model m = InstallDialog.Model.of(in);
        assertFalse(m.primaryEnabled);
        assertTrue(types(m).contains(InstallDialog.Model.NoticeType.MAPPING_UNAVAILABLE));

        // A version without LeviLamina needs no database.
        in = input("1.0.0");
        in.db = null;
        m = InstallDialog.Model.of(in);
        assertTrue(m.primaryEnabled);
        assertTrue(types(m).isEmpty());
    }

    @Test
    public void testGameIncompatibleBlocks() {
        InstallDialog.Model.Input in = input("2.0.0");
        in.gameVersion = "1.26.51.1";
        InstallDialog.Model m = InstallDialog.Model.of(in);
        assertFalse(m.primaryEnabled);
        assertTrue(types(m).contains(InstallDialog.Model.NoticeType.GAME_INCOMPATIBLE));
        assertEquals(InstallPlanner.BLOCK_GAME_UNSUPPORTED, m.plan.blocker);
    }

    @Test
    public void testNoAndroidBuildBlocks() {
        InstallDialog.Model.Input in = input("1.5.0");
        in.loader = CatalogModels.InstallState.NOT_INSTALLED;
        InstallDialog.Model m = InstallDialog.Model.of(in);
        assertFalse(m.primaryEnabled);
        assertEquals(CatalogModels.Availability.UNAVAILABLE, m.availability());
        // LeviLamina is missing too, but installing it cannot make this version installable:
        // no "Go to Loader" and no LeviLamina notice.
        assertEquals(Arrays.asList(InstallDialog.Model.NoticeType.NO_ANDROID_BUILD), types(m));
        assertEquals(InstallDialog.Model.Primary.INSTALL, m.primary);

        // The same with an incompatible LeviLamina installed.
        in = input("1.5.0");
        in.loader = CatalogModels.InstallState.installed(true, "26.51.1");
        m = InstallDialog.Model.of(in);
        assertFalse(m.primaryEnabled);
        assertEquals(Arrays.asList(InstallDialog.Model.NoticeType.NO_ANDROID_BUILD), types(m));
    }

    @Test
    public void testNoInstance() {
        // The dialog's states when no instance is left to install to.
        InstallDialog.Model.Input in = input("2.0.0");
        in.loader = CatalogModels.InstallState.failed("ERR_TARGET_NOT_FOUND");
        in.pkg = in.loader;
        InstallDialog.Model m = InstallDialog.Model.of(in);
        assertFalse(m.primaryEnabled);
        assertFalse(types(m).contains(InstallDialog.Model.NoticeType.CHECKING_PACKAGE));
        assertTrue(types(m).contains(InstallDialog.Model.NoticeType.PACKAGE_STATE_FAILED));
    }

    @Test
    public void testUnspecifiedPlatformConfirms() {
        InstallDialog.Model m = InstallDialog.Model.of(input("1.2.0"));
        assertTrue(m.primaryEnabled);
        assertTrue(m.confirmUnspecified);
        assertEquals(CatalogModels.Availability.UNKNOWN, m.availability());
        assertEquals(Arrays.asList(InstallDialog.Model.NoticeType.PLATFORM_UNSPECIFIED), types(m));
    }

    @Test
    public void testActions() {
        InstallDialog.Model.Input in = input("2.0.0");
        in.pkg = CatalogModels.InstallState.installed(true, "1.0.0");
        InstallDialog.Model m = InstallDialog.Model.of(in);
        assertEquals(InstallDialog.Model.Primary.UPGRADE, m.primary);
        assertEquals(R.string.pkg_task_title_upgrade, InstallDialog.Model.taskAction(m.primary));

        in = input("1.0.0");
        in.pkg = CatalogModels.InstallState.installed(true, "2.0.0");
        m = InstallDialog.Model.of(in);
        assertEquals(InstallDialog.Model.Primary.DOWNGRADE, m.primary);
        assertTrue(m.confirmDowngrade);
        assertEquals(R.string.pkg_task_title_downgrade, InstallDialog.Model.taskAction(m.primary));

        in = input("2.0.0");
        in.pkg = CatalogModels.InstallState.installed(true, "2.0.0");
        assertEquals(InstallDialog.Model.Primary.REINSTALL, InstallDialog.Model.of(in).primary);

        in = input("2.0.0");
        in.pkg = CatalogModels.InstallState.installed(false, "2.0.0");
        m = InstallDialog.Model.of(in);
        assertEquals(InstallDialog.Model.Primary.PROMOTE, m.primary);
        assertEquals(R.string.pkg_task_title_install, InstallDialog.Model.taskAction(m.primary));

        in = input("2.0.0");
        in.pkg = CatalogModels.InstallState.installed(true, "");
        assertEquals(InstallDialog.Model.Primary.REINSTALL, InstallDialog.Model.of(in).primary);
    }

    @Test
    public void testStateErrorsAndRunningTask() {
        InstallDialog.Model.Input in = input("2.0.0");
        in.pkg = CatalogModels.InstallState.failed("ERR_PACKAGE_QUERY_FAILED");
        InstallDialog.Model m = InstallDialog.Model.of(in);
        assertTrue(m.primaryEnabled);
        assertEquals(1, m.notices.size());
        assertEquals(InstallDialog.Model.NoticeType.PACKAGE_STATE_FAILED, m.notices.get(0).type);
        assertEquals("ERR_PACKAGE_QUERY_FAILED", m.notices.get(0).code);

        // LeviLamina's state could not be read: blocked (planner), not "Go to Loader".
        in = input("2.0.0");
        in.loader = CatalogModels.InstallState.failed("ERR_PACKAGE_QUERY_FAILED");
        m = InstallDialog.Model.of(in);
        assertFalse(m.primaryEnabled);
        assertEquals(InstallDialog.Model.Primary.INSTALL, m.primary);
        assertEquals(InstallDialog.Model.NoticeType.BLOCKED, m.notices.get(0).type);
        assertEquals(InstallPlanner.BLOCK_LOADER_CHECK_FAILED, m.notices.get(0).code);

        in = input("2.0.0");
        in.taskRunning = true;
        m = InstallDialog.Model.of(in);
        assertFalse(m.primaryEnabled);
        assertEquals(Arrays.asList(InstallDialog.Model.NoticeType.TASK_RUNNING), types(m));
    }

    @Test
    public void testUnknownVersionBlocked() {
        InstallDialog.Model m = InstallDialog.Model.of(input("9.9.9"));
        assertFalse(m.primaryEnabled);
        assertEquals(InstallDialog.Model.NoticeType.BLOCKED, m.notices.get(0).type);
        assertEquals(InstallPlanner.BLOCK_VERSION_NOT_FOUND, m.notices.get(0).code);
    }

    @Test
    public void testPrimaryOf() {
        assertEquals(InstallDialog.Model.Primary.INSTALL, InstallDialog.Model.primaryOf(InstallPlanner.ACTION_INSTALL));
        assertEquals(InstallDialog.Model.Primary.UPGRADE, InstallDialog.Model.primaryOf(InstallPlanner.ACTION_UPGRADE));
        assertEquals(InstallDialog.Model.Primary.DOWNGRADE,
                InstallDialog.Model.primaryOf(InstallPlanner.ACTION_DOWNGRADE));
        assertEquals(InstallDialog.Model.Primary.REINSTALL,
                InstallDialog.Model.primaryOf(InstallPlanner.ACTION_REINSTALL));
        assertEquals(InstallDialog.Model.Primary.PROMOTE, InstallDialog.Model.primaryOf(InstallPlanner.ACTION_PROMOTE));
        assertEquals(InstallDialog.Model.Primary.INSTALL, InstallDialog.Model.primaryOf(""));
    }
}
