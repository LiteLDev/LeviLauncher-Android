// Local package copies for "Install from file" (design §3.6): a stable place per content.
package org.levimc.launcher.core.packages;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LocalSourcesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A local folder as a picked tree; reverse lists children backwards (another provider order). */
    private static final class FileNode implements LocalSources.Node {
        final File file;
        final boolean reverse;

        FileNode(File file, boolean reverse) {
            this.file = file;
            this.reverse = reverse;
        }

        @Override
        public String name() {
            return file.getName();
        }

        @Override
        public boolean isDirectory() {
            return file.isDirectory();
        }

        @Override
        public List<LocalSources.Node> children() {
            File[] files = file.listFiles();
            List<LocalSources.Node> out = new ArrayList<>();
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    out.add(new FileNode(f, reverse));
                }
            }
            if (reverse) {
                Collections.reverse(out);
            }
            return out;
        }

        @Override
        public InputStream open() throws IOException {
            return new FileInputStream(file);
        }
    }

    private static List<String> entries(File localDir) {
        String[] names = localDir.list();
        List<String> out = names == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(names));
        Collections.sort(out);
        return out;
    }

    @Test
    public void testArchiveCopiesAreStablePerContent() throws Exception {
        File root = tmp.newFolder("data");
        File a = LocalSources.importArchive(root, "My Mod (1).ZIP", () -> new ByteArrayInputStream(bytes("zip-1")));
        assertTrue(a.isFile());
        assertEquals("My_Mod__1_.zip", a.getName());
        assertEquals(LocalSources.HASH_DIGITS, a.getParentFile().getName().length());
        assertEquals(LocalSources.localDir(root), a.getParentFile().getParentFile());
        assertArrayEquals(bytes("zip-1"), Files.readAllBytes(a.toPath()));

        // The same content again: the same copy.
        File again = LocalSources.importArchive(root, "My Mod (1).ZIP", () -> new ByteArrayInputStream(bytes("zip-1")));
        assertEquals(a, again);
        // Other content: another folder.
        File b = LocalSources.importArchive(root, "My Mod (1).ZIP", () -> new ByteArrayInputStream(bytes("zip-2")));
        assertNotEquals(a.getParentFile(), b.getParentFile());
        // No temporary folder is left behind.
        assertEquals(2, entries(LocalSources.localDir(root)).size());
        for (String n : entries(LocalSources.localDir(root))) {
            assertFalse(n, n.startsWith("."));
        }
    }

    @Test
    public void testArchiveReadFailureLeavesNothing() throws Exception {
        File root = tmp.newFolder("data");
        try {
            LocalSources.importArchive(root, "x.zip", () -> {
                throw new IOException("permission denied");
            });
            fail();
        } catch (IOException e) {
            assertEquals("permission denied", e.getMessage());
        }
        assertEquals(Collections.emptyList(), entries(LocalSources.localDir(root)));
    }

    @Test
    public void testDirectoryCopies() throws Exception {
        File src = tmp.newFolder("My Package");
        Files.write(new File(src, "tooth.json").toPath(), bytes("{}"));
        assertTrue(new File(src, "mods/foo").mkdirs());
        Files.write(new File(src, "mods/foo/libfoo.so").toPath(), bytes("ELF"));
        assertTrue(new File(src, "empty").mkdirs());
        File root = tmp.newFolder("data");

        File copy = LocalSources.importDirectory(root, new FileNode(src, false));
        assertTrue(copy.isDirectory());
        assertEquals("My_Package", copy.getName());
        assertArrayEquals(bytes("ELF"), Files.readAllBytes(new File(copy, "mods/foo/libfoo.so").toPath()));
        assertArrayEquals(bytes("{}"), Files.readAllBytes(new File(copy, "tooth.json").toPath()));
        assertTrue(new File(copy, "empty").isDirectory());

        // The hash does not depend on the order a provider lists children in.
        File again = LocalSources.importDirectory(root, new FileNode(src, true));
        assertEquals(copy, again);

        // A changed file, or a renamed one, is another copy.
        Files.write(new File(src, "mods/foo/libfoo.so").toPath(), bytes("ELF2"));
        File changed = LocalSources.importDirectory(root, new FileNode(src, false));
        assertNotEquals(copy.getParentFile(), changed.getParentFile());
        assertEquals(2, entries(LocalSources.localDir(root)).size());
    }

    @Test
    public void testDirectoryRefusesUnsafeNames() throws Exception {
        File root = tmp.newFolder("data");
        LocalSources.Node evil = new LocalSources.Node() {
            @Override
            public String name() {
                return "pkg";
            }

            @Override
            public boolean isDirectory() {
                return true;
            }

            @Override
            public List<LocalSources.Node> children() {
                return Collections.singletonList(new LocalSources.Node() {
                    @Override
                    public String name() {
                        return "../escape";
                    }

                    @Override
                    public boolean isDirectory() {
                        return false;
                    }

                    @Override
                    public List<LocalSources.Node> children() {
                        return Collections.emptyList();
                    }

                    @Override
                    public InputStream open() {
                        return new ByteArrayInputStream(bytes("x"));
                    }
                });
            }

            @Override
            public InputStream open() {
                throw new UnsupportedOperationException();
            }
        };
        try {
            LocalSources.importDirectory(root, evil);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("../escape"));
        }
        assertEquals(Collections.emptyList(), entries(LocalSources.localDir(root)));
        assertFalse(new File(LocalSources.localDir(root), "escape").exists());
        try {
            LocalSources.importDirectory(root, new FileNode(tmp.newFile("plain.zip"), false));
            fail("a file is no folder");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void testNames() {
        assertEquals("package.zip", LocalSources.archiveName(""));
        assertEquals("package.zip", LocalSources.archiveName(".zip"));
        assertEquals("mod.zip", LocalSources.archiveName("..mod"));
        assertEquals("___.zip", LocalSources.archiveName("模组包.zip"));
        assertEquals("package", LocalSources.folderName(null));
        assertEquals(80, LocalSources.sanitize(new String(new char[100]).replace('\0', 'a')).length());
        assertTrue(LocalSources.isSafeEntryName("a.so"));
        assertFalse(LocalSources.isSafeEntryName(".."));
        assertFalse(LocalSources.isSafeEntryName("a/b"));
        assertFalse(LocalSources.isSafeEntryName("a\\b"));
        assertFalse(LocalSources.isSafeEntryName(""));
    }
}
