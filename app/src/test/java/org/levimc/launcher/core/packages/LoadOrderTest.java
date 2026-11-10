// InstalledPackages.order: mod folders of packages load after their dependencies'
// folders; everything else keeps the user's order.
package org.levimc.launcher.core.packages;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LoadOrderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private InstalledPackages sample() throws Exception {
        File dir = tmp.newFolder("Demo");
        WorkspaceFixture.writeSample(dir);
        return InstalledPackages.read(dir);
    }

    private static List<String> l(String... s) {
        return Arrays.asList(s);
    }

    @Test
    public void testDependenciesFirst() throws Exception {
        InstalledPackages ws = sample();
        // foo and foo-extra need LeviLamina; everything else keeps its place.
        assertEquals(l("Baz", "LeviLamina", "foo", "user1", "foo-extra"),
                InstalledPackages.order(l("Baz", "foo", "LeviLamina", "user1", "foo-extra"), ws));
        // Case of the folder names does not matter.
        assertEquals(l("levilamina", "FOO"), InstalledPackages.order(l("FOO", "levilamina"), ws));
        // Already in order: unchanged.
        List<String> ordered = l("user0", "LeviLamina", "user1", "foo", "foo-extra", "Baz", "qux");
        assertEquals(ordered, InstalledPackages.order(ordered, ws));
        // A dependency that is not among the mods (not loadable, or removed)
        // does not hold its dependents back.
        assertEquals(l("foo-extra", "foo", "user1"), InstalledPackages.order(l("foo-extra", "foo", "user1"), ws));
        // The user's order of independent packages is kept.
        assertEquals(l("qux", "Baz", "LeviLamina", "foo"), InstalledPackages.order(l("qux", "Baz", "foo", "LeviLamina"), ws));
    }

    @Test
    public void testLoaderFolders() throws Exception {
        File dir = tmp.newFolder("Loader");
        WorkspaceFixture.write(dir, "android-arm64",
                new WorkspaceFixture.Pkg("github.com/LiteLDev/LeviLamina", "", "1.0.0", true,
                        "{ \"github.com/a/runtime\": \"1.0.0\", \"github.com/a/shared\": \"1.0.0\" }",
                        "mods/LeviLamina/a.so"),
                new WorkspaceFixture.Pkg("github.com/a/runtime", "", "1.0.0", false, null, "mods/Runtime/a.so"),
                new WorkspaceFixture.Pkg("github.com/a/shared", "", "1.0.0", false, null, "mods/shared/a.so"),
                new WorkspaceFixture.Pkg("github.com/a/mod", "", "1.0.0", true,
                        "{ \"github.com/LiteLDev/LeviLamina\": \"1.0.0\", \"github.com/a/shared\": \"1.0.0\" }",
                        "mods/mod/a.so"));
        InstalledPackages ws = InstalledPackages.read(dir);
        assertEquals("1.0.0", ws.leviLamina().version);
        // LeviLamina and what only it pulled in; not a dependency a mod needs too, nor the mods.
        assertEquals(new java.util.HashSet<>(l("levilamina", "runtime")), ws.loaderFolderKeys());

        File none = tmp.newFolder("NoLoader");
        WorkspaceFixture.write(none, "android-arm64",
                new WorkspaceFixture.Pkg("github.com/a/mod", "", "1.0.0", true, null, "mods/mod/a.so"));
        assertEquals(null, InstalledPackages.read(none).leviLamina());
        assertEquals(Collections.emptySet(), InstalledPackages.read(none).loaderFolderKeys());
    }

    @Test
    public void testTransitiveDependencies() throws Exception {
        File dir = tmp.newFolder("Chain");
        WorkspaceFixture.write(dir, "android-arm64",
                new WorkspaceFixture.Pkg("github.com/a/core", "", "1.0.0", false, null, "mods/core/a.so"),
                new WorkspaceFixture.Pkg("github.com/a/mid", "", "1.0.0", false,
                        "{ \"github.com/a/core\": \"1.0.0\" }", "mods/mid/a.so"),
                new WorkspaceFixture.Pkg("github.com/a/top", "", "1.0.0", true,
                        "{ \"github.com/a/mid\": \"1.0.0\" }", "mods/top/a.so"));
        InstalledPackages ws = InstalledPackages.read(dir);
        assertEquals(l("core", "mid", "top"), InstalledPackages.order(l("top", "mid", "core"), ws));
        // top needs core through mid, even when mid is not loadable.
        assertEquals(l("core", "top"), InstalledPackages.order(l("top", "core"), ws));
    }

    // A dependency cycle keeps the user's order for it instead of looping.
    @Test
    public void testCycle() throws Exception {
        File dir = tmp.newFolder("Cycle");
        WorkspaceFixture.write(dir, "android-arm64",
                new WorkspaceFixture.Pkg("github.com/a/x", "", "1.0.0", true,
                        "{ \"github.com/a/y\": \"1.0.0\" }", "mods/x/a.so"),
                new WorkspaceFixture.Pkg("github.com/a/y", "", "1.0.0", true,
                        "{ \"github.com/a/x\": \"1.0.0\" }", "mods/y/a.so"));
        InstalledPackages ws = InstalledPackages.read(dir);
        assertEquals(l("y", "x", "z"), InstalledPackages.order(l("y", "x", "z"), ws));
        assertEquals(l("z", "x", "y"), InstalledPackages.order(l("z", "x", "y"), ws));
    }

    @Test
    public void testNoWorkspace() throws Exception {
        List<String> mods = l("b", "a");
        assertEquals(mods, InstalledPackages.order(mods, null));
        assertEquals(mods, InstalledPackages.order(mods, InstalledPackages.read(tmp.newFolder("Empty"))));
        assertEquals(Collections.emptyList(), InstalledPackages.order(null, null));
    }
}
