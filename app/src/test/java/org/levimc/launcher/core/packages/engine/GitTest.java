package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The git smart HTTP v2 client against {@code git http-backend}, packs with deltas, and tree rules. */
public class GitTest {
    private static final String REPO = "example.com/o/r";
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private TestHttps server;

    @Before
    public void setUp() throws Exception {
        assumeTrue(TestHttps.hasGit());
        server = new TestHttps(tmp.newFolder("server"));
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.close();
    }

    private Git git(String proxy) {
        return new Git(server.http(tmp.getRoot(), proxy, new Cancel()));
    }

    private long posts() {
        return server.requests.stream().filter(r -> r.startsWith("POST")).count();
    }

    @Test
    public void appliesDeltas() throws Exception {
        byte[] base = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] delta = {11, 8, (byte) 0x90, 5, 3, '!', '!', '!'};
        assertArrayEquals("hello!!!".getBytes(StandardCharsets.UTF_8), Git.applyDelta(base, delta));
        try {
            Git.applyDelta(base, new byte[] {12, 8, (byte) 0x90, 5});
            fail();
        } catch (java.io.IOException expected) {
            // the base has another size
        }
    }

    /** Sizes declared by a hostile server are not allocated up front (review). */
    @Test
    public void forgedSizesFailWithoutAllocating() throws Exception {
        java.io.ByteArrayOutputStream pack = new java.io.ByteArrayOutputStream();
        pack.write(new byte[] {'P', 'A', 'C', 'K', 0, 0, 0, 2, 0, 0, 0, 1});
        long size = 0x7fffff00L; // a blob that claims almost 2 GiB
        pack.write((int) (0x80 | 3 << 4 | size & 15));
        for (size >>>= 4; size != 0; size >>>= 7) pack.write((int) ((size > 127 ? 0x80 : 0) | size & 127));
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(new byte[] {'x'});
        d.finish();
        byte[] z = new byte[64];
        pack.write(z, 0, d.deflate(z));
        pack.write(java.security.MessageDigest.getInstance("SHA-1").digest(pack.toByteArray()));
        try {
            git(null).readPack(new ByteArrayInputStream(pack.toByteArray()), "bar", "pkg");
            fail();
        } catch (java.io.IOException expected) {
            // the object has the wrong size
        }
        byte[] base = "hello world".getBytes(StandardCharsets.UTF_8);
        for (byte[] delta : new byte[][] {
                {11, (byte) 0xf0, (byte) 0xff, (byte) 0xff, (byte) 0xff, 7, 1, 'x'}, // claims ~2 GiB
                {11, 12, (byte) 0x90, 11, (byte) 0x90, 11, (byte) 0x90, 11}}) { // copies more than it claims
            try {
                Git.applyDelta(base, delta);
                fail();
            } catch (java.io.IOException expected) {
                // the delta gives the wrong size
            }
        }
    }

    @Test
    public void readsPacksWithOffsetAndRefDeltas() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) big.append("line ").append(i).append('\n');
        String commit = server.commit(REPO, null, "a.txt", big + "a", "b.txt", big + "b");
        File work = new File(tmp.getRoot(), "server/work/" + REPO);
        String blobA = TestHttps.git(work, "rev-parse", "HEAD:a.txt");
        for (String option : new String[] {"--delta-base-offset", "--no-reuse-delta"}) {
            Process p = new ProcessBuilder("git", "pack-objects", "--stdout", "--revs", option).directory(work).start();
            try (OutputStream in = p.getOutputStream()) {
                in.write((commit + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            byte[] pack = Archive.readAll(p.getInputStream());
            assertEquals(0, p.waitFor());
            Map<String, Git.Obj> objects = git(null).readPack(new ByteArrayInputStream(pack), "bar", "pkg");
            assertEquals(4, objects.size());
            assertEquals(big + "a", new String(objects.get(blobA).data, StandardCharsets.UTF_8));
            pack[pack.length - 1] ^= 1;
            try {
                git(null).readPack(new ByteArrayInputStream(pack), "bar", "pkg");
                fail("checksum");
            } catch (java.io.IOException expected) {
                // the trailer does not match
            }
        }
    }

    private void lsRefsAndArchive(boolean filter) throws Exception {
        String c1 = server.commit(REPO, "v1.0.0", "lip.toml", "x", "src/a.txt", "A", "bin/run.sh", "#!/bin/sh");
        File work = new File(tmp.getRoot(), "server/work/" + REPO);
        Files.createSymbolicLink(new File(work, "link").toPath(), new File("src/a.txt").toPath());
        assertTrue(new File(work, "bin/run.sh").setExecutable(true));
        String c2 = server.commit(REPO, "+v1.1.0", "src/b.txt", "B");
        TestHttps.git(work, "push", "-q", new File(server.gitRoot, REPO + ".git").getPath(), "HEAD:refs/heads/dev");
        if (filter) TestHttps.git(new File(server.gitRoot, REPO + ".git"), "config", "uploadpack.allowFilter", "true");
        Git git = git(null);
        List<String> refs = new ArrayList<>();
        for (Git.Ref r : git.lsRefs(REPO)) refs.add(r.name + " " + r.commit);
        refs.sort(null);
        assertEquals(Arrays.asList("refs/heads/dev " + c2, "refs/heads/main " + c2, "refs/tags/v1.0.0 " + c1,
                "refs/tags/v1.1.0 " + c2), refs);
        Archive a = git.archive(REPO, c2, "pkg");
        assertEquals(Arrays.asList("bin/run.sh", "lip.toml", "src/a.txt", "src/b.txt"), a.files());
        long before = posts();
        assertArrayEquals("B".getBytes(StandardCharsets.UTF_8), a.read("src/b.txt"));
        assertEquals(filter ? before + 1 : before, posts());
        a.prefetch(Arrays.asList("src/a.txt", "lip.toml", "missing"));
        assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), a.read("src/a.txt"));
        assertArrayEquals("x".getBytes(StandardCharsets.UTF_8), a.read("lip.toml"));
        assertEquals(filter ? before + 2 : before, posts());
        assertEquals(1, git.archive(REPO, c1, "pkg").files().stream().filter(f -> f.equals("src/a.txt")).count());
    }

    @Test
    public void fetchesWithoutBlobsWhenTheServerFilters() throws Exception {
        lsRefsAndArchive(true);
    }

    @Test
    public void fetchesWholeTreesOtherwise() throws Exception {
        lsRefsAndArchive(false);
    }

    @Test
    public void rejectsBackslashNamesAndUnknownCommits() throws Exception {
        String c = server.commit(REPO, null, "a\\b", "x");
        try {
            git(null).archive(REPO, c, "pkg");
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.INVALID_PACKAGE, e.kind);
        }
        try {
            git(null).archive(REPO, "0123456789012345678901234567890123456789", "pkg");
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.NETWORK, e.kind);
        }
    }

    @Test
    public void triesTheGithubProxyFirst() throws Exception {
        String c = server.commit("github.com/o/r", "v1.0.0", "f", "1");
        assertEquals(c, git("https://proxy.test/gh").lsRefs("github.com/o/r").get(0).commit);
        assertTrue(server.requests.toString(), server.requests.get(0).startsWith("GET proxy.test/gh/o/r.git/info/refs"));
        assertTrue(server.requests.get(1).startsWith("GET github.com/o/r.git/info/refs"));
        server.requests.clear();
        TestHttps.git(server.gitRoot, "clone", "-q", "--bare", "github.com/o/r.git", "proxy.test/gh/o/r.git");
        git("https://proxy.test/gh").archive("github.com/o/r", c, "pkg");
        for (String r : server.requests) assertTrue(r, r.contains("proxy.test/gh/o/r.git/"));
    }
}
