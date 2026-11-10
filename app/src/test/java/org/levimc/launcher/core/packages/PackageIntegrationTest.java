package org.levimc.launcher.core.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.packages.engine.Listener;
import org.levimc.launcher.core.packages.engine.PackageEngine;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.settings.FeatureSettings;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/** The launcher policy over the real engine with local packages (no network). */
public class PackageIntegrationTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private PackageService service;
    private GameVersion instance;
    private File dir;

    @Before
    public void setUp() throws Exception {
        service = new PackageService(null, new FeatureSettings(), tmp.newFolder("files"), tmp.newFolder("cache"));
        PackageService.set(service);
        dir = tmp.newFolder("instance");
        instance = new GameVersion("instance", "Instance", "1.21.50.07", dir, false, "", "arm64-v8a");
    }

    /** A local directory package: lip.toml placing mods/&lt;mod&gt;/ from its own files. */
    private String pkg(String name, String id, String version, String mod) throws Exception {
        File d = new File(tmp.getRoot(), name);
        write(new File(d, "lip.toml"), "format = 4\nid = \"" + id + "\"\nversion = \"" + version + "\"\n"
                + "[[variants.\"\"]]\nside = \"client\"\nplatform = \"" + PackageService.rid() + "\"\n"
                + "assets = [{ type = \"self\", place = [{ src = \"mod/\", dest = \"mods/" + mod + "/\" }] }]\n");
        write(new File(d, "mod/lib" + mod + ".so"), version);
        return d.getPath();
    }

    private static void write(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void installUpdateAndUninstallALocalPackage() throws Exception {
        String src = pkg("p1", "example.com/a/cool", "1.0.0", "CoolMod");
        assertEquals("", service.installSource(new Cancel(), Listener.NONE, instance, src));
        assertTrue(new File(dir, "mods/CoolMod/libCoolMod.so").isFile());

        InstalledPackages ws = InstalledPackages.read(dir);
        InstalledPackages.Pkg owner = ws.ownerOf("coolmod");
        assertNotNull(owner);
        assertEquals("example.com/a/cool", owner.id);
        assertTrue(owner.explicit);
        assertTrue(owner.isLocal());
        assertTrue(ws.ownsFile("mods/CoolMod/libCoolMod.so"));
        assertEquals(Arrays.asList("CoolMod", "loose"), InstalledPackages.order(Arrays.asList("CoolMod", "loose"), ws));

        // Installing it again (a newer copy) is an update of the explicitly installed package.
        pkg("p1", "example.com/a/cool", "1.1.0", "CoolMod");
        assertEquals("", service.installSource(new Cancel(), Listener.NONE, instance, src));
        assertEquals("1.1.0", InstalledPackages.read(dir).byId("example.com/a/cool").version);

        assertEquals("", service.uninstallById(new Cancel(), Listener.NONE, instance, owner.id, owner.identity));
        assertFalse(new File(dir, "mods/CoolMod").exists());
        assertTrue(InstalledPackages.read(dir).isEmpty());
    }

    @Test
    public void failuresBecomeCodes() throws Exception {
        Cancel cancelled = new Cancel();
        cancelled.cancel();
        String src = pkg("p2", "example.com/a/two", "1.0.0", "Two");
        assertEquals(PackageService.ERR_PKG_TASK_CANCELLED, service.installSource(cancelled, Listener.NONE, instance, src));
        assertEquals(PackageService.ERR_PACKAGE_INSTALL_FAILED,
                service.installSource(new Cancel(), Listener.NONE, instance, new File(tmp.getRoot(), "missing").getPath()));
        assertEquals(PackageService.ERR_PACKAGE_INVALID_IDENTIFIER, service.installPackage(new Cancel(), Listener.NONE, instance, "not an id", "1.0.0"));
        assertEquals(PackageService.ERR_PACKAGE_VERSION_REQUIRED, service.installPackage(new Cancel(), Listener.NONE, instance, "a/b", " "));
        assertEquals(PackageService.ERR_TARGET_NOT_FOUND, service.installSource(new Cancel(), Listener.NONE,
                new GameVersion("gone", "Gone", "1.0", new File(tmp.getRoot(), "gone"), false, "", ""), src));

        // A state file of another device is refused before any change.
        WorkspaceFixture.write(dir, "win-x64");
        assertEquals(PackageService.ERR_PKG_WORKSPACE_ENV_MISMATCH, service.installSource(new Cancel(), Listener.NONE, instance, src));
        assertEquals(PackageService.ERR_PKG_WORKSPACE_ENV_MISMATCH, service.installState(instance, "a/b").error);
    }

    @Test
    public void workspaceLockGuardsOperations() throws Exception {
        PackageService.Held held = service.tryLock(dir);
        assertNotNull(held);
        assertTrue(service.isBusy(dir));
        assertNull(service.tryLock(dir));
        // Waiting for the lock ends with the task's timeout.
        Cancel c = new Cancel().withTimeout(200);
        assertEquals(PackageService.ERR_PACKAGE_INSTALL_FAILED, service.installSource(c, Listener.NONE, instance, pkg("p3", "example.com/a/x", "1.0.0", "X")));
        held.close();
        assertFalse(service.isBusy(dir));
    }

    @Test
    public void installStatesOfCatalogueIdentifiers() throws Exception {
        WorkspaceFixture.writeSample(dir);
        CatalogModels.InstallState ll = service.installState(instance, "LiteLDev/LeviLamina");
        assertTrue(ll.installed);
        assertFalse(ll.explicit);
        assertEquals("1.2.0", ll.installedVersion);
        assertEquals(PackageService.ERR_PACKAGE_INVALID_IDENTIFIER, service.installState(instance, "x").error);
        assertFalse(service.installState(instance, "a/none").installed);
    }

    @Test
    public void catalogueIdentifiers() {
        PackageService.Request r = PackageService.Request.parse("https://github.com/LiteLDev/LHolo/");
        assertEquals("LiteLDev/LHolo", r.identifier);
        assertEquals("github.com/LiteLDev/LHolo#client", r.requestRef);
        assertEquals("github.com/LiteLDev/LHolo", r.fallbackRef);
        r = PackageService.Request.parse("github.com/a/b#");
        assertEquals("github.com/a/b", r.requestRef);
        assertEquals("", r.fallbackRef);
        r = PackageService.Request.parse("a/b#lua@1.0.0");
        assertEquals("a/b#lua", r.identifier);
        assertEquals("github.com/a/b#lua", r.requestRef);
        assertNull(PackageService.Request.parse("gitee.com/a/b/c"));
        assertEquals("LiteLDev/LHolo", PackageService.frontendIdentifier("github.com/LiteLDev/LHolo", "client"));
        assertNull(PackageService.frontendIdentifier("example.com/a/b", ""));
    }

    @Test
    public void mirrorAndProxySettings() {
        assertTrue(PackageService.mirrorEnabled(PackageService.MirrorMode.AUTO, Locale.US, "Asia/Shanghai"));
        assertTrue(PackageService.mirrorEnabled(PackageService.MirrorMode.AUTO, Locale.SIMPLIFIED_CHINESE, "UTC"));
        assertFalse(PackageService.mirrorEnabled(PackageService.MirrorMode.AUTO, Locale.US, "UTC"));
        assertTrue(PackageService.mirrorEnabled(PackageService.MirrorMode.ON, Locale.US, "UTC"));
        assertEquals(PackageService.ERR_PKG_INVALID_GITHUB_PROXY, service.setGithubProxy("ftp://x"));
        assertEquals("", service.setGithubProxy("https://proxy.example/"));
        assertEquals("https://proxy.example", service.config().githubProxy());
        assertEquals("https://proxy.example/LiteLDev/x", PackageEngine.githubProxied(service.effectiveGithubProxy(), "https://github.com/LiteLDev/x"));
        assertEquals("", service.setGithubProxy(""));
        assertNull(service.config().githubProxy());
    }

    @Test
    public void fullUrlGithubProxiesAreCompleted() throws Exception {
        assertEquals("https://gh-proxy.org/https://github.com", PackageService.DEFAULT_GITHUB_PROXY);
        assertEquals(PackageService.DEFAULT_GITHUB_PROXY, PackageService.completeGithubProxy("https://gh-proxy.org"));
        assertEquals("https://cdn.gh-proxy.org/https://github.com", PackageService.completeGithubProxy("https://cdn.gh-proxy.org/"));
        assertEquals("https://GHPROXY.vip/https://github.com", PackageService.completeGithubProxy("https://GHPROXY.vip"));
        assertEquals(PackageService.DEFAULT_GITHUB_PROXY, PackageService.completeGithubProxy(PackageService.DEFAULT_GITHUB_PROXY));
        assertEquals("https://proxy.example", PackageService.completeGithubProxy("https://proxy.example"));
        assertEquals("https://gh-proxy.org/x", PackageService.completeGithubProxy("https://gh-proxy.org/x"));
        assertEquals("https://gh-proxy.org?k=v", PackageService.completeGithubProxy("https://gh-proxy.org?k=v"));
        assertEquals("https://notgh-proxy.org", PackageService.completeGithubProxy("https://notgh-proxy.org"));
        assertNull(PackageService.completeGithubProxy(null));

        // Saving completes a bare full-URL proxy, and the engine's rewrite then reaches GitHub through it.
        assertEquals("", service.setGithubProxy("https://gh-proxy.org/"));
        assertEquals(PackageService.DEFAULT_GITHUB_PROXY, service.config().githubProxy());
        assertEquals("https://gh-proxy.org/https://github.com/LiteLDev/x",
                PackageEngine.githubProxied(service.effectiveGithubProxy(), "https://github.com/LiteLDev/x"));

        // A bare proxy saved by an earlier version is completed and saved back.
        service.config().setGithubProxy("https://ghproxy.vip");
        assertEquals("https://ghproxy.vip/https://github.com", service.configuredGithubProxy());
        assertEquals("https://ghproxy.vip/https://github.com", service.config().githubProxy());
    }
}
