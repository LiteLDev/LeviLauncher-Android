package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/** ZIP and directory archives (package 1), HTTPS downloads, the cache (userspace 3) and github_proxy. */
public class HttpArchiveTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private TestHttps server;
    private File cache;

    @Before
    public void setUp() throws Exception {
        server = new TestHttps(tmp.newFolder("server"));
        cache = new File(tmp.getRoot(), "cache");
    }

    @After
    public void tearDown() throws Exception {
        server.close();
    }

    private File file(String name, byte[] data) throws Exception {
        File f = new File(tmp.getRoot(), name);
        Files.write(f.toPath(), data);
        return f;
    }

    private static void assertInvalid(File f) {
        try {
            Archive.local(f).close();
            fail("valid: " + f);
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.INVALID_PACKAGE, e.kind);
        }
    }

    @Test
    public void zipArchivesByContent() throws Exception {
        try (Archive a = Archive.local(file("pkg.bin", TestHttps.zip("a.txt", "A", "d/", "", "d/b.txt", "B")))) {
            assertEquals(Arrays.asList("a.txt", "d/b.txt"), a.files());
            assertArrayEquals("B".getBytes(StandardCharsets.UTF_8), a.read("d/b.txt"));
            assertNull(a.read("d/"));
        }
        assertInvalid(file("text.zip", "not a zip".getBytes(StandardCharsets.UTF_8)));
        for (String bad : new String[] {"../x", "a/../x", "/abs", "a\\b"}) assertInvalid(file("bad.zip", TestHttps.zip(bad, "x")));
        // A symbolic link entry (Unix mode 0120777 in the external attributes) is neither a file nor a directory.
        byte[] link = TestHttps.zip("link", "target");
        for (int i = link.length - 22; i >= 0; i--) {
            if (link[i] == 'P' && link[i + 1] == 'K' && link[i + 2] == 1 && link[i + 3] == 2) {
                link[i + 5] = 3;
                link[i + 40] = (byte) 0xff;
                link[i + 41] = (byte) 0xa1;
                break;
            }
        }
        assertInvalid(file("link.zip", link));
    }

    @Test
    public void directoryArchives() throws Exception {
        File dir = tmp.newFolder("dir");
        Files.createDirectories(new File(dir, "sub").toPath());
        Files.write(new File(dir, "sub/f.txt").toPath(), new byte[] {1});
        Files.write(new File(dir, "lip.toml").toPath(), new byte[] {2});
        Files.createSymbolicLink(new File(dir, "link").toPath(), new File(dir, "lip.toml").toPath());
        try (Archive a = Archive.local(dir)) {
            assertEquals(Arrays.asList("lip.toml", "sub/f.txt"), a.files());
            assertArrayEquals(new byte[] {1}, a.read("sub/f.txt"));
        }
        Files.write(new File(dir, "a\\b").toPath(), new byte[0]);
        assertInvalid(dir);
    }

    @Test
    public void downloadsThroughTheCache() throws Exception {
        server.files.put("example.com/a.zip", TestHttps.zip("x", "1"));
        Http http = server.http(cache, null, new Cancel());
        try (Archive a = http.zip("https://example.com/a.zip#frag", false, "p")) {
            assertEquals(Arrays.asList("x"), a.files());
        }
        StringBuilder h = new StringBuilder("http-");
        for (byte b : MessageDigest.getInstance("SHA-256").digest("https://example.com/a.zip".getBytes(StandardCharsets.UTF_8))) {
            h.append(String.format("%02x", b));
        }
        assertEquals(Arrays.asList(h.toString()), Arrays.asList(cache.list()));
        http.zip("https://example.com/a.zip", false, "p").close();
        assertEquals(1, server.requests.size());
        server.files.put("example.com/a.zip", TestHttps.zip("y", "2"));
        try (Archive a = http.zip("https://example.com/a.zip", true, "p")) {
            assertEquals(Arrays.asList("y"), a.files());
        }
        assertEquals(2, server.requests.size());
        // A damaged entry is deleted and downloaded again.
        Files.write(new File(cache, h.toString()).toPath(), "damaged".getBytes(StandardCharsets.UTF_8));
        http.zip("https://example.com/a.zip", false, "p").close();
        assertEquals(3, server.requests.size());
    }

    @Test
    public void failuresAreNotCached() throws Exception {
        server.files.put("example.com/text", "hello".getBytes(StandardCharsets.UTF_8));
        Http http = server.http(cache, null, new Cancel());
        expect(PackageException.Kind.INVALID_PACKAGE, http, "https://example.com/text");
        expect(PackageException.Kind.NETWORK, http, "https://example.com/missing.zip");
        server.redirects.put("example.com/plain", "http://example.com/a.zip");
        expect(PackageException.Kind.NETWORK, http, "https://example.com/plain");
        expect(PackageException.Kind.NETWORK, http, "http://example.com/a.zip");
        assertTrue(cache.list() == null || cache.list().length == 0);
        Cancel cancel = new Cancel();
        cancel.cancel();
        expect(PackageException.Kind.CANCELLED, server.http(cache, null, cancel), "https://example.com/text");
    }

    private static void expect(PackageException.Kind kind, Http http, String url) {
        try {
            http.zip(url, false, "p").close();
            fail(url);
        } catch (PackageException e) {
            assertEquals(e.getMessage(), kind, e.kind);
        }
    }

    @Test
    public void followsHttpsRedirects() throws Exception {
        server.redirects.put("example.com/r", "https://other.test/b.zip");
        server.files.put("other.test/b.zip", TestHttps.zip("b", ""));
        server.http(cache, null, new Cancel()).zip("https://example.com/r", false, "p").close();
        assertEquals(Arrays.asList("GET example.com/r", "GET other.test/b.zip"), server.requests);
    }

    @Test
    public void githubProxyWithDirectFallback() throws Exception {
        assertEquals("https://proxy.test/gh/o/r/a.zip?x=1", Http.proxied("https://proxy.test/gh/?k=v", "https://github.com/o/r/a.zip?x=1"));
        assertEquals("https://proxy.test/o/r.git?k=v", Http.proxied("https://proxy.test?k=v", "https://github.com/o/r.git"));
        assertNull(Http.proxied("https://proxy.test", "https://example.com/o/r"));
        assertNull(Http.proxied(null, "https://github.com/o/r"));
        server.files.put("github.com/o/r/a.zip", TestHttps.zip("a", ""));
        Http http = server.http(cache, "https://proxy.test/gh", new Cancel());
        http.zip("https://github.com/o/r/a.zip", false, "p").close();
        assertEquals(Arrays.asList("GET proxy.test/gh/o/r/a.zip", "GET github.com/o/r/a.zip"), server.requests);
        server.files.put("proxy.test/gh/o/r/b.zip", TestHttps.zip("b", ""));
        http.zip("https://github.com/o/r/b.zip", false, "p").close();
        assertEquals("GET proxy.test/gh/o/r/b.zip", server.requests.get(2));
        assertEquals(3, server.requests.size());
        assertFalse(new File(cache, Http.entryName("https://proxy.test/gh/o/r/b.zip")).exists());
        assertTrue(new File(cache, Http.entryName("https://github.com/o/r/b.zip")).exists());
    }
}
