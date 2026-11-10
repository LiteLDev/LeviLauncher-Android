package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import okhttp3.OkHttpClient;

/** The journal (workspace 7.3-7.5): recovery of an operation interrupted before and after its commit. */
public class JournalTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private File ws;
    private PackageEngine engine;

    @Before
    public void setUp() throws Exception {
        ws = tmp.newFolder("ws");
        engine = new PackageEngine(ws, new Config(tmp.newFolder("config"), tmp.newFolder("cache")), new OkHttpClient(), "android-arm64", null);
        write("keep.txt", "old");
        write("gone.txt", "g");
        write("dir/x.txt", "x");
    }

    private void write(String path, String content) throws Exception {
        File f = new File(ws, path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(ws, path).toPath()), StandardCharsets.UTF_8);
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Makes the changes of an operation, then "crashes": the lock is released without completing or undoing. */
    private void crash(boolean committed) throws Exception {
        try (Journal j = Journal.lock(ws)) {
            j.begin();
            j.createDirectory("new");
            j.writeFile("new/a.txt", in("a"));
            j.writeFile("keep.txt", in("new"));
            j.deleteFile("gone.txt");
            j.deleteFile("dir/x.txt");
            j.deleteDirectoryOnCommit("dir");
            if (committed) {
                Files.write(new File(ws, Journal.STATE_TMP).toPath(), "{}".getBytes(StandardCharsets.UTF_8));
                Files.write(new File(ws, Journal.FILE).toPath(), "{\"op\":\"Commit\"}\n{\"op\":\"Crea".getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.APPEND);
            }
        }
    }

    @Test
    public void recoveryUndoesAnUncommittedOperation() throws Exception {
        crash(false);
        assertEquals("new", read("keep.txt"));
        assertTrue(PackageEngine.hasJournal(ws));
        assertTrue(engine.recover(new Cancel(), Listener.NONE));
        assertEquals("old", read("keep.txt"));
        assertEquals("g", read("gone.txt"));
        assertEquals("x", read("dir/x.txt"));
        assertFalse(new File(ws, "new").exists());
        assertFalse(PackageEngine.hasJournal(ws));
        assertArrayEquals(new String[] {".lip", "dir", "gone.txt", "keep.txt"}, sorted(ws.list()));
        assertArrayEquals(new String[] {"x.txt"}, new File(ws, "dir").list());
        assertFalse(engine.recover(new Cancel(), Listener.NONE));
    }

    @Test
    public void recoveryCompletesACommittedOperation() throws Exception {
        crash(true);
        assertTrue(engine.recover(new Cancel(), Listener.NONE));
        assertEquals("new", read("keep.txt"));
        assertEquals("a", read("new/a.txt"));
        assertFalse(new File(ws, "gone.txt").exists() || new File(ws, "dir").exists());
        assertEquals("{}", read(StateFile.PATH));
        assertArrayEquals(new String[] {".lip", "keep.txt", "new"}, sorted(ws.list()));
        assertArrayEquals(new String[] {"lock", "state.json"}, sorted(new File(ws, ".lip").list()));
    }

    @Test
    public void changesThatCannotBeUndoneStayInTheJournal() throws Exception {
        assumeTrue(!"root".equals(System.getProperty("user.name")));
        try (Journal j = Journal.lock(ws)) {
            j.begin();
            j.createDirectory("ro");
            j.createDirectory("ro/sub");
            j.writeFile("ro/sub/f.txt", in("f"));
            j.createDirectory("other");
        }
        new File(ws, "ro/sub").setWritable(false);
        try {
            engine.recover(new Cancel(), Listener.NONE);
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.RECOVERY_FAILED, e.kind);
        } finally {
            new File(ws, "ro/sub").setWritable(true);
        }
        assertFalse(new File(ws, "other").exists());
        String[] lines = read(Journal.FILE).split("\n");
        assertEquals(4, lines.length);
        assertTrue(lines[0].contains("\"Begin\"") && lines[1].contains("\"ro\"") && lines[2].contains("\"ro/sub\"")
                && lines[3].contains("ro/sub/f.txt"));
        assertTrue(engine.recover(new Cancel(), Listener.NONE)); // writable again: the rest is undone
        assertFalse(new File(ws, "ro").exists() || PackageEngine.hasJournal(ws));
    }

    private static String[] sorted(String[] a) {
        java.util.Arrays.sort(a);
        return a;
    }
}
