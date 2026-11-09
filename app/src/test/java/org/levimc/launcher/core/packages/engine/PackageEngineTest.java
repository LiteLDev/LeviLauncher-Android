package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Operations on a workspace (workspace 2-7, cli 5.1-5.3, compatibility 3) over local, URL and git sources. */
public class PackageEngineTest {
    private static final String A = "example.com/r/a", B = "example.com/r/b";
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private TestHttps server;
    private File ws;
    private PackageEngine engine;
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private final Listener listener = new Listener() {
        @Override
        public void log(Level level, String message) {
            log.add(message);
        }

        @Override
        public void progress(String id, Stage stage, String pkg, long current, long total, boolean bytes) {
        }
    };

    @Before
    public void setUp() throws Exception {
        assumeTrue(TestHttps.hasGit());
        server = new TestHttps(tmp.newFolder("server"));
        ws = tmp.newFolder("ws");
        engine = new PackageEngine(ws, new Config(tmp.newFolder("config"), new File(tmp.getRoot(), "cache")), server.client,
                "android-arm64", null);
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.close();
    }

    /** A local directory package with lip.toml and files (path, content, ...); returns its path. */
    private String local(String dir, String id, String version, String entry, String... files) throws Exception {
        File d = new File(tmp.getRoot(), dir);
        write(d, "lip.toml", TestHttps.toml(id, version, entry));
        for (int i = 0; i < files.length; i += 2) write(d, files[i], files[i + 1]);
        return d.getPath();
    }

    private static void write(File dir, String path, String content) throws Exception {
        File f = new File(dir, path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(ws, path).toPath()), StandardCharsets.UTF_8);
    }

    private boolean exists(String path) {
        return new File(ws, path).exists();
    }

    private static String self(String... srcDest) {
        StringBuilder b = new StringBuilder("assets = [{ type = \"self\", place = [");
        for (int i = 0; i < srcDest.length; i += 2) b.append(i == 0 ? "" : ", ").append("{ src = \"").append(srcDest[i])
                .append("\", dest = \"").append(srcDest[i + 1]).append("\" }");
        return b.append("] }]\n").toString();
    }

    private PackageEngine.Plan install(String... s) throws PackageException {
        return engine.install(Arrays.asList(s), false, new Cancel(), listener);
    }

    private PackageEngine.Plan update(String... s) throws PackageException {
        return engine.update(Arrays.asList(s), false, new Cancel(), listener);
    }

    private PackageEngine.Plan uninstall(String... s) throws PackageException {
        return engine.uninstall(Arrays.asList(s), false, new Cancel(), listener);
    }

    private interface Call {
        void run() throws Exception;
    }

    private static PackageException failure(PackageException.Kind kind, Call call) throws Exception {
        try {
            call.run();
        } catch (PackageException e) {
            assertEquals(e.getMessage(), kind, e.kind);
            return e;
        }
        fail("expected " + kind);
        return null;
    }

    private static List<String> ids(List<PackageEngine.Installed> list) {
        List<String> out = new ArrayList<>();
        for (PackageEngine.Installed p : list) out.add(p.identity + "@" + p.version);
        return out;
    }

    private String pub(String repo, String version, String entry, String... files) throws Exception {
        String[] all = new String[files.length + 2];
        all[0] = "lip.toml";
        all[1] = TestHttps.toml(repo, version, entry);
        System.arraycopy(files, 0, all, 2, files.length);
        return server.commit(repo, "v" + version, all);
    }

    @Test
    public void placesFilesAndUninstallsWithPreserveAndPurge() throws Exception {
        server.files.put("example.com/mod.zip", TestHttps.zip("manifest.json", "{\"name\": \"CoolMod\"}", "libcool.so", "c"));
        String entry = "preserve = [\"config/*.json\"]\npurge = [\"logs/\", \"cache/*.tmp\"]\n" + self("bin/a.so", "lib/a.so",
                "data/", "res/", "cfg/*.json", "config/").replace("] }]", "] }, { type = \"mcmod\", urls = [\"https://example.com/mod.zip\"] }]");
        String p = local("p1", "example.com/p/one", "1.0.0", entry, "bin/a.so", "a", "data/x.txt", "x", "data/sub/y.txt", "y",
                "cfg/main.json", "new", "cfg/other.json", "o", "cfg/readme.md", "r");
        write(ws, "config/main.json", "user");
        PackageEngine.Plan plan = install(p);
        assertEquals(Collections.singletonList("example.com/p/one@1.0.0"), ids(plan.install));
        assertEquals("a", read("lib/a.so"));
        assertEquals("y", read("res/sub/y.txt"));
        assertEquals("user", read("config/main.json"));
        assertEquals("o", read("config/other.json"));
        assertEquals("c", read("mods/CoolMod/libcool.so"));
        assertFalse(exists("lip.toml") || exists("res/lip.toml") || exists("config/readme.md"));
        PackageEngine.State s = engine.state();
        PackageEngine.Installed one = s.packages.get(0);
        assertTrue(one.explicit);
        assertEquals(Source.fileUri(p), one.source);
        assertEquals("CoolMod", one.modName());
        assertFalse(one.files.contains("config/main.json"));
        assertTrue(one.files.contains("mods/CoolMod/libcool.so"));
        assertFalse(PackageEngine.hasJournal(ws));

        write(ws, "logs/a/b.log", "l");
        write(ws, "cache/b.tmp", "t");
        write(ws, "cache/keep.txt", "k");
        plan = uninstall("EXAMPLE.com/p/one");
        assertEquals(Collections.singletonList("example.com/p/one@1.0.0"), ids(plan.uninstall));
        assertFalse(exists("lib") || exists("res") || exists("mods/CoolMod") || exists("logs") || exists("cache/b.tmp"));
        assertTrue(exists("mods") && exists("cache/keep.txt") && exists("config/main.json"));
        assertTrue(engine.state().packages.isEmpty());
        assertArrayEquals(new String[] {"lock", "state.json"}, sorted(new File(ws, ".lip").list()));
    }

    private static String[] sorted(String[] a) {
        Arrays.sort(a);
        return a;
    }

    @Test
    public void dependenciesExplicitPackagesAndOrphans() throws Exception {
        pub(B, "1.0.0", self("b.txt", "b.txt"), "b.txt", "b");
        pub(A, "1.0.0", "deps = { \"" + B + "\" = \"1.*\" }\n" + self("a.txt", "a.txt"), "a.txt", "a");
        assertEquals(Arrays.asList(B + "@1.0.0", A + "@1.0.0"), ids(install(A).install));
        assertFalse(engine.state().packages.get(0).explicit);
        assertTrue(engine.state().packages.get(1).explicit);

        PackageEngine.Plan plan = install(B);
        assertEquals(Collections.singletonList(B), plan.markExplicit);
        assertTrue(plan.install.isEmpty() && plan.uninstall.isEmpty());
        assertEquals(B, failure(PackageException.Kind.ALREADY_EXPLICITLY_INSTALLED, () -> install(B)).argument);
        assertEquals(Collections.singletonList(A + "@1.0.0"), ids(uninstall(A).uninstall));
        assertTrue(exists("b.txt") && !exists("a.txt"));

        install(A);
        plan = uninstall(B); // still needed by A: only no longer explicit
        assertEquals(Collections.singletonList(B), plan.unmarkExplicit);
        assertTrue(plan.uninstall.isEmpty());
        failure(PackageException.Kind.NOT_EXPLICIT, () -> uninstall(B));
        failure(PackageException.Kind.NOT_INSTALLED, () -> uninstall("example.com/r/zzz"));
        assertEquals(Arrays.asList(A + "@1.0.0", B + "@1.0.0"), ids(uninstall(A).uninstall));
        assertFalse(exists("a.txt") || exists("b.txt"));
    }

    @Test
    public void aPackageOfAVersionRefSatisfiesItsDependentsWithoutTheNetwork() throws Exception {
        // As the app installs LeviLamina ("<id>#client@<version>") and then mods that need "<id>#client <range>".
        String ll = B + "#client";
        server.commit(B, "v1.1.0", "lip.toml", TestHttps.toml(B, "1.1.0", "").replace("variants.\"\"", "variants.\"client\""));
        install(ll + "@1.1.0");
        String mod = local("m", "example.com/p/mod", "1.0.0", "deps = { \"" + ll + "\" = \"1.*\" }\n" + self("m.txt", "m.txt"), "m.txt", "m");
        String other = local("o", "example.com/p/other", "1.0.0", self("o.txt", "o.txt"), "o.txt", "o");
        install(mod, other);
        server.close(); // offline: B cannot be resolved again
        PackageEngine.Plan plan = uninstall("example.com/p/other");
        assertEquals(Collections.singletonList("example.com/p/other@1.0.0"), ids(plan.uninstall));
        assertTrue(exists("m.txt") && !exists("o.txt"));
    }

    @Test
    public void updateReplacesOnlyChangedPackages() throws Exception {
        pub(A, "1.0.0", self("a.txt", "a.txt"), "a.txt", "v1");
        install(A);
        assertTrue(update().isEmpty());
        pub(A, "1.1.0", self("a.txt", "a.txt"), "a.txt", "v2");
        PackageEngine.Plan plan = update();
        assertEquals(Collections.singletonList(A + "@1.1.0"), ids(plan.install));
        assertEquals(Collections.singletonList(A + "@1.0.0"), ids(plan.uninstall));
        assertEquals("v2", read("a.txt"));
        update(A + "@1.0.0");
        assertEquals("v1", read("a.txt"));
        assertEquals(A + "@1.0.0", engine.state().packages.get(0).source);
        assertTrue(update().isEmpty()); // a ref keeps its commit
        update(A); // without the ref: back to the version tags
        assertEquals("v2", read("a.txt"));
        failure(PackageException.Kind.NOT_INSTALLED, () -> update("example.com/r/zzz"));
    }

    @Test
    public void failingHookUndoesTheWholeOperation() throws Exception {
        String p = local("h", "example.com/p/h", "1.0.0", self("h.txt", "h.txt"), "h.txt", "v1");
        install(p);
        byte[] state = Files.readAllBytes(new File(ws, StateFile.PATH).toPath());
        local("h", "example.com/p/h", "2.0.0", "hooks = { pre_install = [\"echo ran > hook-ran\"], post_install = [\"exit 3\"] }\n"
                + self("h.txt", "h.txt", "n.txt", "deep/n.txt"), "h.txt", "v2", "n.txt", "n");
        assertEquals("example.com/p/h", failure(PackageException.Kind.HOOK_FAILED, () -> update()).argument);
        assertEquals("v1", read("h.txt"));
        assertFalse(exists("deep"));
        assertTrue(exists("hook-ran")); // hook commands are not undone
        assertArrayEquals(state, Files.readAllBytes(new File(ws, StateFile.PATH).toPath()));
        assertFalse(PackageEngine.hasJournal(ws));
        assertArrayEquals(new String[] {".lip", "h.txt", "hook-ran", "mods"}, sorted(ws.list()));

        engine.update(Collections.<String>emptyList(), true, new Cancel(), listener); // scripts off
        assertEquals("v2", read("h.txt"));
        assertEquals("n", read("deep/n.txt"));
    }

    @Test
    public void cancellingKillsTheHookProcessGroupAndUndoes() throws Exception {
        String p = local("c", "example.com/p/c", "1.0.0",
                "hooks = { post_install = [\"sleep 60 & echo $! > child.pid; sleep 61\"] }\n" + self("c.txt", "c.txt"), "c.txt", "c");
        Cancel cancel = new Cancel();
        Thread t = new Thread(() -> {
            for (int i = 0; i < 200 && !exists("child.pid"); i++) sleep(50);
            sleep(200);
            cancel.cancel();
        });
        t.start();
        long start = System.nanoTime();
        try {
            engine.install(Collections.singletonList(p), false, cancel, listener);
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.CANCELLED, e.kind);
        }
        assertTrue(System.nanoTime() - start < 20_000_000_000L);
        assertFalse(exists("c.txt"));
        assertTrue(engine.state().packages.isEmpty());
        File proc = new File("/proc/" + read("child.pid").trim() + "/stat");
        for (int i = 0; i < 40 && proc.exists() && !new String(Files.readAllBytes(proc.toPath())).contains(") Z"); i++) sleep(50);
        assertTrue(!proc.exists() || new String(Files.readAllBytes(proc.toPath())).contains(") Z"));
        failure(PackageException.Kind.TIMEOUT, () -> engine.install(Collections.singletonList(p), false,
                new Cancel().withTimeout(500), listener));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void earlyFailuresChangeNothingAndRunNoHook() throws Exception {
        install(local("x", "example.com/p/x", "1.0.0", self("s.txt", "shared.txt"), "s.txt", "x"));
        byte[] state = Files.readAllBytes(new File(ws, StateFile.PATH).toPath());
        String hook = "hooks = { pre_install = [\"touch marker\"] }\n";
        failure(PackageException.Kind.CONFLICT, () -> install(local("y", "example.com/p/y", "1.0.0",
                hook + self("y.txt", "y.txt", "s.txt", "shared.txt"), "s.txt", "y", "y.txt", "y")));
        failure(PackageException.Kind.CONFLICT, () -> install(local("z", "example.com/p/z", "1.0.0",
                hook + self("z.txt", ".lip/z.txt"), "z.txt", "z")));
        failure(PackageException.Kind.CONFLICT, () -> install(local("d", "example.com/p/d", "1.0.0",
                hook + self("d.txt", "shared.txt/d.txt"), "d.txt", "d")));
        failure(PackageException.Kind.INVALID_PACKAGE, () -> install(local("m", "example.com/p/m", "1.0.0",
                hook + self("missing.txt", "m.txt"))));
        assertFalse(exists("marker") || exists("y.txt"));
        assertEquals("x", read("shared.txt"));
        assertArrayEquals(state, Files.readAllBytes(new File(ws, StateFile.PATH).toPath()));

        File other = tmp.newFolder("linked");
        Files.createSymbolicLink(new File(other, "mods").toPath(), tmp.newFolder("elsewhere").toPath());
        PackageEngine e = new PackageEngine(other, new Config(tmp.newFolder("c2"), tmp.newFolder("k2")), server.client, "android-arm64", null);
        failure(PackageException.Kind.INVALID_WORKSPACE, () -> e.install(Collections.singletonList(
                local("w", "example.com/p/w", "1.0.0", self("w.txt", "w.txt"), "w.txt", "w")), false, new Cancel(), listener));
        assertFalse(new File(other, "w.txt").exists());
        assertTrue(e.update(Collections.emptyList(), false, new Cancel(), listener).isEmpty()); // no change: mods is not checked
    }

    @Test
    public void lockedWorkspaceFailsAtOnce() throws Exception {
        try (Journal j = Journal.lock(ws)) {
            failure(PackageException.Kind.WORKSPACE_LOCKED, () -> install(local("l", "example.com/p/l", "1.0.0", "")));
            // the refused attempt must not release the process's fcntl lock: another process still cannot take it
            Process p = new ProcessBuilder("python3", "-c", "import fcntl,sys; fcntl.lockf(open(sys.argv[1], 'w'), "
                    + "fcntl.LOCK_EX | fcntl.LOCK_NB)", new File(ws, ".lip/lock").getPath()).start();
            assumeTrue(p.waitFor() != 127);
            assertTrue(p.exitValue() != 0);
        }
        install(local("l", "example.com/p/l", "1.0.0", ""));
    }

    @Test
    public void nothingIsDeletedThroughALink() throws Exception {
        install(local("k", "example.com/p/k", "1.0.0", "purge = [\"data/sub/\", \"data/*.log\"]\n" + self("a.txt", "data/a.txt"),
                "a.txt", "a"));
        File outside = tmp.newFolder("outside");
        write(outside, "a.txt", "mine");
        write(outside, "x.log", "mine");
        write(outside, "sub/s.txt", "mine");
        File data = new File(ws, "data");
        new File(data, "a.txt").delete();
        data.delete();
        Files.createSymbolicLink(data.toPath(), outside.toPath());
        uninstall("example.com/p/k");
        assertArrayEquals(new String[] {"a.txt", "sub", "x.log"}, sorted(outside.list()));
        assertEquals("mine", new String(Files.readAllBytes(new File(outside, "sub/s.txt").toPath()), StandardCharsets.UTF_8));
        failure(PackageException.Kind.CONFLICT, () -> install(local("u", "example.com/p/u", "1.0.0", self("u.txt", ".LIP/u.txt"), "u.txt", "u")));
    }

    @Test
    public void aNewFileOfAnInstalledPackageIsAnUpdate() throws Exception {
        install(local("f1", "example.com/p/f", "1.0.0", self("f.txt", "f.txt"), "f.txt", "1"));
        String f2 = local("f2", "example.com/p/f", "2.0.0", self("f.txt", "f.txt"), "f.txt", "2");
        assertEquals(f2, failure(PackageException.Kind.ALREADY_EXPLICITLY_INSTALLED, () -> install(f2)).argument);
        PackageEngine.Plan plan = update(f2);
        assertEquals(Collections.singletonList("example.com/p/f@2.0.0"), ids(plan.install));
        assertEquals("2", read("f.txt"));
        assertEquals(Source.fileUri(f2), engine.state().packages.get(0).source);
    }

    @Test
    public void aFailedStateRenameIsUndone() throws Exception {
        String p = local("r", "example.com/p/r", "1.0.0",
                "hooks = { post_install = [\"rm -f .lip/state.json && mkdir -p .lip/state.json/x\"] }\n" + self("r.txt", "r.txt"), "r.txt", "r");
        failure(PackageException.Kind.IO, () -> install(p));
        assertFalse(exists("r.txt") || exists("mods") || PackageEngine.hasJournal(ws) || exists(".lip/state.json.tmp"));
    }

    @Test
    public void toothLockBecomesTheStateFile() throws Exception {
        String t = "example.com/r/t", commit = server.commit(t, "v1.0.0", "tooth.json", "{}");
        String tooth = "{\"format_version\": 3, \"format_uuid\": \"289f771f-2c9a-4d73-9f3f-8492495a924d\", \"tooth\": \"" + t
                + "\", \"version\": \"1.0.0\", \"info\": {}, \"variants\": [{\"platform\": \"android-*\"}]}";
        write(ws, "tooth_lock.json", "{\"format_version\": 3, \"packages\": [{\"manifest\": " + tooth + ", \"variant\": \"\","
                + " \"locked\": true, \"files\": [\"plugins/t.txt\", \"" + new File(ws, "plugins/u.txt").getAbsolutePath()
                + "\", \"/elsewhere/x\"]}, {\"manifest\": " + tooth.replace("android-*", "win-*") + ", \"variant\": \"\","
                + " \"locked\": true, \"files\": []}]}");
        write(ws, "plugins/t.txt", "t");
        PackageEngine.Installed p = engine.state().packages.get(0);
        assertEquals(1, engine.state().packages.size());
        assertEquals(Arrays.asList("plugins/t.txt", "plugins/u.txt"), p.files);
        assertNull(p.commit);
        assertTrue(p.explicit);
        assertEquals("{\"side\":\"client\",\"platform\":\"android-arm64\"}",
                p.manifest.getAsJsonObject("variants").getAsJsonArray("").get(0).toString());

        install(local("o", "example.com/p/o", "1.0.0", ""));
        assertFalse(exists("tooth_lock.json"));
        assertTrue(read(".lip/state.json").startsWith("{\n  \"format\": 4,\n  \"side\": \"client\",\n  \"platform\": \"android-arm64\","));
        p = engine.state().packages.get(1); // installation order: example.com/p/o first
        assertEquals(t, p.identity);
        assertEquals(commit, p.commit);
        uninstall(t);
        assertFalse(exists("plugins/t.txt"));
    }
}
