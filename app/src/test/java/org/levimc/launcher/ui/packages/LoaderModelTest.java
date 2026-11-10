// Tests of the Loader card logic (port of the LeviLamina part of upstream
// useInstanceSettings: the version selection effect, llInstallActionKind,
// llUninstallBlocked and the RC warning; upstream has no unit tests for it, so
// these are new), with the design's Android rules (latest stable default,
// notices, Recover).
package org.levimc.launcher.ui.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.levimc.launcher.R;
import org.levimc.launcher.core.packages.LeviLamina;
import org.levimc.launcher.core.packages.LeviLamina;
import org.levimc.launcher.core.packages.PackageService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LoaderModelTest {
    private static final List<String> VERSIONS = Arrays.asList("26.60.5-rc.1", "26.60.4", "26.60.0");

    private static LoaderCard.Model.Input input(List<String> versions) {
        LoaderCard.Model.Input in = new LoaderCard.Model.Input();
        in.versions = versions;
        in.stateKnown = true;
        return in;
    }

    private static LoaderCard.Model.Input installed(List<String> versions, String version, boolean explicit) {
        LoaderCard.Model.Input in = input(versions);
        in.installed = true;
        in.explicit = explicit;
        in.installedVersion = version;
        return in;
    }

    @Test
    public void testDefaultSelection() {
        assertEquals("", LoaderCard.Model.defaultSelection(Collections.emptyList(), false, "", ""));
        assertEquals("", LoaderCard.Model.defaultSelection(null, false, "", ""));
        // Not installed: the newest release, not the newer release candidate.
        assertEquals("26.60.4", LoaderCard.Model.defaultSelection(VERSIONS, false, "", ""));
        // The user's pick wins while it is offered.
        assertEquals("26.60.0", LoaderCard.Model.defaultSelection(VERSIONS, false, "", "26.60.0"));
        assertEquals("26.60.5-rc.1", LoaderCard.Model.defaultSelection(VERSIONS, true, "26.60.4", "26.60.5-rc.1"));
        assertEquals("26.60.4", LoaderCard.Model.defaultSelection(VERSIONS, false, "", "9.9.9"));
        // Installed and older than the latest release: the upgrade.
        assertEquals("26.60.4", LoaderCard.Model.defaultSelection(VERSIONS, true, "26.60.0", ""));
        // Installed newer than the latest release (an RC) and offered: keep it.
        assertEquals("26.60.5-rc.1", LoaderCard.Model.defaultSelection(VERSIONS, true, "26.60.5-rc.1", ""));
        // Installed version with a "v" prefix maps onto the list entry.
        assertEquals("26.60.4", LoaderCard.Model.defaultSelection(VERSIONS, true, "v26.60.4", ""));
        // Installed but not offered (newer than any): the latest release.
        assertEquals("26.60.4", LoaderCard.Model.defaultSelection(VERSIONS, true, "27.0.0", ""));
        // Only pre-releases: the newest.
        assertEquals("1.0.0-rc.2",
                LoaderCard.Model.defaultSelection(Arrays.asList("1.0.0-rc.2", "1.0.0-rc.1"), false, "", ""));
        assertEquals("26.60.4", LoaderCard.Model.latestStable(VERSIONS));
        assertEquals("", LoaderCard.Model.latestStable(Collections.emptyList()));
    }

    @Test
    public void testDefaultSelectionFromTheVersionDatabase() {
        // What the card gets: PkgFacade.supportedLeviLaminaVersions = sorted versions of the DB key.
        LeviLamina.Db db = LeviLamina.Db.normalize(LeviLamina.Db.parseRaw(CatalogFixtures.text("version-db.json")));
        List<String> supported = LeviLamina.sortVersions(
                LeviLamina.resolveSupportedVersions(db.asMap(), "1.26.60.5"));
        assertEquals(VERSIONS, supported);
        LoaderCard.Model.View v = LoaderCard.Model.of(input(supported));
        assertEquals("26.60.4", v.selected);
        assertEquals(1, v.selectedIndex());
        assertEquals(LeviLamina.Db.ACTION_INSTALL, v.action);
        assertTrue(v.actionEnabled);
    }

    @Test
    public void testAction() {
        assertEquals(LeviLamina.Db.ACTION_INSTALL, LoaderCard.Model.action(false, "", "26.60.4"));
        assertEquals(LeviLamina.Db.ACTION_INSTALL, LoaderCard.Model.action(false, "26.60.0", "26.60.4"));
        // Installed with an unknown version: reinstall (upstream).
        assertEquals(LeviLamina.Db.ACTION_REINSTALL, LoaderCard.Model.action(true, "", "26.60.4"));
        assertEquals(LeviLamina.Db.ACTION_UPGRADE, LoaderCard.Model.action(true, "26.60.0", "26.60.4"));
        assertEquals(LeviLamina.Db.ACTION_UPGRADE, LoaderCard.Model.action(true, "26.60.4", "26.60.5-rc.1"));
        assertEquals(LeviLamina.Db.ACTION_DOWNGRADE, LoaderCard.Model.action(true, "26.60.4", "26.60.0"));
        assertEquals(LeviLamina.Db.ACTION_DOWNGRADE, LoaderCard.Model.action(true, "26.60.4", "26.60.4-rc.1"));
        assertEquals(LeviLamina.Db.ACTION_REINSTALL, LoaderCard.Model.action(true, "26.60.4", "26.60.4"));
        assertEquals(LeviLamina.Db.ACTION_REINSTALL, LoaderCard.Model.action(true, "v26.60.4", "26.60.4"));
        assertEquals(LeviLamina.Db.ACTION_CHANGE, LoaderCard.Model.action(true, "nightly", "26.60.4"));

        assertEquals(R.string.pkg_task_title_install, LoaderCard.Model.taskAction(LeviLamina.Db.ACTION_INSTALL));
        assertEquals(R.string.pkg_task_title_upgrade, LoaderCard.Model.taskAction(LeviLamina.Db.ACTION_UPGRADE));
        assertEquals(R.string.pkg_task_title_downgrade, LoaderCard.Model.taskAction(LeviLamina.Db.ACTION_DOWNGRADE));
        assertEquals(R.string.pkg_task_title_reinstall, LoaderCard.Model.taskAction(LeviLamina.Db.ACTION_REINSTALL));
        assertEquals(R.string.pkg_task_title_install, LoaderCard.Model.taskAction(LeviLamina.Db.ACTION_CHANGE));
        assertEquals(R.string.pkg_task_title_install, LoaderCard.Model.taskAction(null));
    }

    @Test
    public void testVersionLabel() {
        // The status line: one "v" for numeric versions, unknown versions left to the caller.
        assertEquals("v26.60.4", LoaderCard.Model.versionLabel("26.60.4"));
        assertEquals("v26.60.4", LoaderCard.Model.versionLabel(" v26.60.4 "));
        assertEquals("v1.0.0-rc.1", LoaderCard.Model.versionLabel("V1.0.0-rc.1"));
        assertEquals("nightly", LoaderCard.Model.versionLabel("nightly"));
        assertEquals("v", LoaderCard.Model.versionLabel("v"));
        assertEquals("", LoaderCard.Model.versionLabel(""));
        assertEquals("", LoaderCard.Model.versionLabel(null));
    }

    @Test
    public void testPrereleaseWarning() {
        assertTrue(LoaderCard.Model.needsPrereleaseWarning("26.60.5-rc.1"));
        assertTrue(LoaderCard.Model.needsPrereleaseWarning("1.0.0-beta.1"));
        assertTrue(LoaderCard.Model.needsPrereleaseWarning("1.0.0rc1"));
        assertFalse(LoaderCard.Model.needsPrereleaseWarning("26.60.4"));
        assertFalse(LoaderCard.Model.needsPrereleaseWarning(""));
        assertTrue(LoaderCard.Model.isNoAndroidBuild(PackageService.ERR_PKG_NO_ANDROID_BUILD));
        assertFalse(LoaderCard.Model.isNoAndroidBuild(PackageService.ERR_PKG_INSTALL_FAILED));
    }

    @Test
    public void testLoadingAndUnsupported() {
        LoaderCard.Model.Input in = new LoaderCard.Model.Input();
        LoaderCard.Model.View v = LoaderCard.Model.of(in);
        assertEquals(LoaderCard.Model.Status.CHECKING, v.status);
        assertEquals(Collections.singletonList(LoaderCard.Model.Notice.CHECKING_VERSIONS), v.notices);
        assertEquals("", v.action);
        assertFalse(v.actionEnabled);
        assertFalse(v.uninstallVisible);
        assertTrue(v.refreshEnabled);

        in.versionsError = PackageService.ERR_FETCH_LL_DB;
        v = LoaderCard.Model.of(in);
        assertEquals(Collections.singletonList(LoaderCard.Model.Notice.VERSIONS_FAILED), v.notices);
        assertEquals(PackageService.ERR_FETCH_LL_DB, v.versionsError);

        // The database lists no LeviLamina for the game version.
        v = LoaderCard.Model.of(input(Collections.emptyList()));
        assertEquals(LoaderCard.Model.Status.NOT_INSTALLED, v.status);
        assertEquals(Collections.singletonList(LoaderCard.Model.Notice.NOT_SUPPORTED), v.notices);
        assertEquals("", v.action);
        assertTrue(v.options.isEmpty());
        assertEquals(-1, v.selectedIndex());

        // An installed LeviLamina can still be removed when its game version is not listed.
        v = LoaderCard.Model.of(installed(Collections.emptyList(), "1.0.0", true));
        assertTrue(v.uninstallVisible);
        assertTrue(v.uninstallEnabled);
        assertEquals("", v.action);
    }

    @Test
    public void testInstalledStates() {
        LoaderCard.Model.View v = LoaderCard.Model.of(installed(VERSIONS, "26.60.0", true));
        assertEquals(LoaderCard.Model.Status.INSTALLED, v.status);
        assertEquals("26.60.0", v.installedVersion);
        assertEquals("26.60.4", v.selected);
        assertEquals(LeviLamina.Db.ACTION_UPGRADE, v.action);
        assertTrue(v.actionEnabled);
        assertTrue(v.uninstallVisible);
        assertTrue(v.uninstallEnabled);
        assertFalse(v.uninstallBlocked);
        assertTrue(v.notices.isEmpty());
        // Option flags: the RC is a pre-release, 26.60.4 the latest release, 26.60.0 installed.
        assertEquals(3, v.options.size());
        assertTrue(v.options.get(0).prerelease);
        assertFalse(v.options.get(0).latestStable);
        assertTrue(v.options.get(1).latestStable);
        assertFalse(v.options.get(1).installed);
        assertTrue(v.options.get(2).installed);

        // The user picks the older release: downgrade.
        LoaderCard.Model.Input in = installed(VERSIONS, "26.60.4", true);
        in.selected = "26.60.0";
        v = LoaderCard.Model.of(in);
        assertEquals("26.60.0", v.selected);
        assertEquals(2, v.selectedIndex());
        assertEquals(LeviLamina.Db.ACTION_DOWNGRADE, v.action);

        // A dependency: removal is refused (upstream llUninstallBlocked) with a notice.
        v = LoaderCard.Model.of(installed(VERSIONS, "26.60.4", false));
        assertEquals(LoaderCard.Model.Status.DEPENDENCY, v.status);
        assertEquals(LeviLamina.Db.ACTION_REINSTALL, v.action);
        assertTrue(v.uninstallVisible);
        assertTrue(v.uninstallBlocked);
        assertEquals(Collections.singletonList(LoaderCard.Model.Notice.REQUIRED_BY_DEPENDENTS), v.notices);
    }

    @Test
    public void testBusyJournalAndFailures() {
        LoaderCard.Model.Input in = installed(VERSIONS, "26.60.4", true);
        in.busy = true;
        in.journal = true;
        LoaderCard.Model.View v = LoaderCard.Model.of(in);
        assertFalse(v.actionEnabled);
        assertFalse(v.uninstallEnabled);
        assertFalse(v.recoverEnabled);
        assertFalse(v.refreshEnabled);
        assertEquals(Arrays.asList(LoaderCard.Model.Notice.TASK_RUNNING, LoaderCard.Model.Notice.JOURNAL), v.notices);

        in.busy = false;
        v = LoaderCard.Model.of(in);
        assertTrue(v.recoverEnabled);
        assertTrue(v.actionEnabled);
        assertEquals(Collections.singletonList(LoaderCard.Model.Notice.JOURNAL), v.notices);

        // The state cannot be read: status unknown, install still offered (the engine changes it in place).
        LoaderCard.Model.Input failed = input(VERSIONS);
        failed.stateKnown = false;
        failed.stateError = PackageService.ERR_PKG_WORKSPACE_ENV_MISMATCH;
        failed.workspaceError = "unsupported state format";
        failed.noAndroidBuild = true;
        v = LoaderCard.Model.of(failed);
        assertEquals(LoaderCard.Model.Status.UNKNOWN, v.status);
        assertEquals(LeviLamina.Db.ACTION_INSTALL, v.action);
        assertFalse(v.uninstallVisible);
        assertEquals(Arrays.asList(LoaderCard.Model.Notice.WORKSPACE_INVALID, LoaderCard.Model.Notice.STATE_FAILED,
                LoaderCard.Model.Notice.NO_ANDROID_BUILD), v.notices);
        assertEquals("unsupported state format", v.workspaceError);
        assertEquals(PackageService.ERR_PKG_WORKSPACE_ENV_MISMATCH, v.stateError);
    }
}
