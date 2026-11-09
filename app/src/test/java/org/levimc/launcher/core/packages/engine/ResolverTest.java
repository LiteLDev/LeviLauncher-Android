package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertEquals;
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

/** Resolve(source, range, environment) (package 4) and solving (workspace 3, 5) over git, URL and local sources. */
public class ResolverTest {
    private static final String A = "example.com/r/a", B = "example.com/r/b", C = "example.com/r/c";
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private TestHttps server;
    private Resolver resolver;

    @Before
    public void setUp() throws Exception {
        assumeTrue(TestHttps.hasGit());
        server = new TestHttps(tmp.newFolder("server"));
        resolver = newResolver();
    }

    private Resolver newResolver() {
        return new Resolver("client", "android-arm64", server.http(new File(tmp.getRoot(), "cache"), null, new Cancel()));
    }

    @After
    public void tearDown() throws Exception {
        if (resolver != null) resolver.close();
        if (server != null) server.close();
    }

    private String pub(String repo, String version, String entry) throws Exception {
        return server.commit(repo, "v" + version, "lip.toml", TestHttps.toml(repo, version, entry));
    }

    private static String dep(String key, String range) {
        return "deps = { \"" + key + "\" = \"" + range + "\" }";
    }

    private static String mcmod(String url) {
        return "assets = [{ type = \"mcmod\", urls = [\"https://example.com/missing.zip\", \"" + url + "\"] }]";
    }

    private Resolver.Candidates resolve(String source, String range) throws PackageException {
        return resolver.resolve(Source.parse(source), range, false);
    }

    private static List<String> versions(Resolver.Candidates cs) throws PackageException {
        List<String> out = new ArrayList<>();
        for (int i = 0; cs.get(i) != null; i++) out.add(cs.get(i).version());
        return out;
    }

    private static void expect(PackageException.Kind kind, Resolver.Candidates cs) throws PackageException {
        assertNull(cs.get(0));
        assertEquals(cs.none().getMessage(), kind, cs.none().kind);
        assertEquals(cs.source.toString(), cs.none().argument);
    }

    private long posts() {
        return server.requests.stream().filter(r -> r.startsWith("POST")).count();
    }

    @Test
    public void ordersVersionTagsAndAppliesRanges() throws Exception {
        pub(A, "1.0.0", "");
        pub(A, "2.0.0-beta", "");
        pub(A, "1.1.0", "");
        pub(A, "1.1.0+b", "");
        server.commit(A, "v3.0.0", "lip.toml", TestHttps.toml(A, "3.0.1", ""));
        server.commit(A, "v4.0.0", "lip.toml", TestHttps.toml("example.com/r/other", "4.0.0", ""));
        server.commit(A, "vx", "lip.toml", TestHttps.toml(A, "5.0.0", ""));
        Resolver.Candidates cs = resolve(A, null);
        assertEquals("2.0.0-beta", cs.get(0).version());
        assertEquals(4, posts()); // ls-refs, then v4.0.0, v3.0.0 (not packages) and v2.0.0-beta
        assertEquals(Arrays.asList("2.0.0-beta", "1.1.0+b", "1.1.0", "1.0.0"), versions(cs));
        assertEquals(Arrays.asList("1.1.0+b", "1.1.0", "1.0.0"), versions(resolve(A, "1.*")));
        assertEquals(A, cs.get(0).identity);
        assertEquals(7, posts());
    }

    @Test
    public void refsSelectCommits() throws Exception {
        String c1 = pub(A, "1.0.0", "");
        String c2 = server.commit(A, null, "lip.toml", TestHttps.toml(A, "9.9.9", ""));
        assertEquals(c1, resolve(A + "@1.0.0", null).get(0).commit);
        assertEquals(c1, resolve(A + "@tag:v1.0.0", null).get(0).commit);
        assertEquals("9.9.9", resolve(A + "@branch:main", null).get(0).version());
        assertEquals(c2, resolve(A + "@rev:" + c2.toUpperCase(), null).get(0).commit);
        expect(PackageException.Kind.NO_PACKAGE_FOUND, resolve(A + "@2.0.0", null));
        expect(PackageException.Kind.NO_PACKAGE_FOUND, resolve(A + "@1.0.0", "2.*"));
    }

    @Test
    public void explainsWhyThereIsNoCandidate() throws Exception {
        pub(A, "1.0.0", "");
        server.commit(B, "v1.0.0", "lip.toml", TestHttps.toml(B, "1.0.0", "").replace("android-arm64", "win-x64"));
        expect(PackageException.Kind.NO_ENTRY_FOR_ENVIRONMENT, resolve(A + "#client", null));
        expect(PackageException.Kind.NO_ENTRY_FOR_ENVIRONMENT, resolve(B, null));
        expect(PackageException.Kind.NO_PACKAGE_FOUND, resolve(A, "5.*"));
        expect(PackageException.Kind.NETWORK, resolve("example.com/r/none", null));
    }

    /** Listing many tags without an entry for this platform holds no git archive (memory stays bounded). */
    @Test
    public void keepsOnlyTheArchivesOfCandidates() throws Exception {
        for (String v : new String[] {"1.0.0", "1.1.0", "1.2.0"}) {
            server.commit(A, "v" + v, "lip.toml", TestHttps.toml(A, v, "").replace("android-arm64", "win-x64")
                    + "[[variants.client]]\nside = \"client\"\nplatform = \"android-arm64\"\n", "src/x.txt", v);
        }
        expect(PackageException.Kind.NO_ENTRY_FOR_ENVIRONMENT, resolve(A, null));
        assertEquals(0, resolver.heldArchives());
        Resolver.Candidate c = resolve(A + "#client", null).get(0); // the same reads, archives released
        assertEquals("1.2.0", c.version());
        assertEquals("1.2.0", new String(c.archive().read("src/x.txt"), StandardCharsets.UTF_8));
        assertEquals(1, resolver.heldArchives());
    }

    @Test
    public void urlAndLocalSourcesAndModNames() throws Exception {
        server.files.put("example.com/mod.zip", TestHttps.zip("manifest.json", "{\"name\": \"CoolMod\"}", "libx.so", "x"));
        server.files.put("example.com/bad.zip", TestHttps.zip("manifest.json", "{\"name\": \"-bad\"}"));
        server.files.put("example.com/p.zip", TestHttps.zip("lip.toml", TestHttps.toml("example.com/p", "1.0.0", mcmod("https://example.com/mod.zip"))));
        Resolver.Candidate c = resolve("https://example.com/p.zip", null).get(0);
        assertEquals("CoolMod", c.modName());
        assertEquals("example.com/p", c.identity);
        int requests = server.requests.size(); // an installed mod's name comes from its mods/<name>/manifest.json
        StateFile.Pkg p = new StateFile.Pkg(c.manifest, "", true, c.source.toString(), null,
                Arrays.asList("mods/aaa/settings.json", "mods/CoolMod/libx.so", "mods/CoolMod/manifest.json"));
        assertEquals("CoolMod", newResolver().installed(p).modName());
        assertEquals(requests, server.requests.size());
        File dir = tmp.newFolder("local");
        Files.write(new File(dir, "lip.toml").toPath(),
                TestHttps.toml("example.com/q", "1.0.0", mcmod("https://example.com/bad.zip")).getBytes(StandardCharsets.UTF_8));
        try {
            resolve(dir.getPath(), null).get(0).modName();
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.INVALID_PACKAGE, e.kind);
        }
        File zip = new File(tmp.getRoot(), "q.zip");
        Files.write(zip.toPath(), TestHttps.zip("lip.toml", TestHttps.toml("example.com/q", "2.0.0", "")));
        expect(PackageException.Kind.NO_ENTRY_FOR_ENVIRONMENT, resolve(zip.getPath() + "#client", null));
        assertEquals("2.0.0", resolve("file://" + zip.getPath(), null).get(0).version());
        try {
            resolve(new File(tmp.getRoot(), "gone.zip").getPath(), null).get(0);
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.INVALID_ARGUMENT, e.kind);
        }
    }

    private static Resolver.Request request(String source) throws PackageException {
        return new Resolver.Request(Source.parse(source), null, true, false, true, null);
    }

    private List<String> solve(List<Resolver.Candidate> installed, String... sources) throws PackageException {
        List<Resolver.Request> requests = new ArrayList<>();
        for (String s : sources) requests.add(request(s));
        List<String> out = new ArrayList<>();
        for (Resolver.Member m : resolver.solve(requests, installed)) {
            out.add(m.candidate.identity + "@" + m.candidate.version() + (m.explicit ? "!" : ""));
        }
        return out;
    }

    @Test
    public void solvesDependenciesInInstallationOrder() throws Exception {
        pub(C, "1.0.0", "");
        pub(C, "2.0.0", "");
        pub(B, "1.0.0", "deps = { \"" + A + "\" = \"*\", \"" + C + "\" = \"1.*\" }");
        pub(A, "1.0.0", dep(B, "1.*"));
        assertEquals(Arrays.asList(C + "@1.0.0", A + "@1.0.0!", B + "@1.0.0"), solve(Collections.emptyList(), A));
    }

    @Test
    public void refsStandInAndInstalledPackagesKeepTheirVersion() throws Exception {
        pub(B, "1.0.0", "");
        pub(B, "1.1.0", "");
        server.commit(B, null, "lip.toml", TestHttps.toml(B, "1.5.0", ""));
        pub(A, "1.0.0", dep(B, "1.*"));
        assertEquals(Arrays.asList(B + "@1.5.0!", A + "@1.0.0!"), solve(Collections.emptyList(), A, B + "@branch:main"));
        assertEquals(Arrays.asList(B + "@1.1.0", A + "@1.0.0!"), solve(Collections.emptyList(), A));
        Resolver.Candidate installed = resolve(B, null).get(1);
        assertEquals(Arrays.asList(B + "@1.0.0", A + "@1.0.0!"), solve(Collections.singletonList(installed), A));
        pub(C, "1.0.0", dep(B, ">=1.2.0")); // B@branch:main is 1.5.0, but no tag of B could be installed for it
        try {
            solve(Collections.emptyList(), C, B + "@branch:main");
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.UNSATISFIABLE, e.kind);
        }
    }

    @Test
    public void backtracksThroughDiamondsAndUpdatesTargets() throws Exception {
        pub(C, "1.0.0", "");
        pub(C, "2.0.0", "");
        pub(B, "1.0.0", dep(C, "1.*"));
        pub(B, "2.0.0", dep(C, "2.*"));
        pub(A, "1.0.0", "deps = { \"" + B + "\" = \"*\", \"" + C + "\" = \"1.*\" }");
        assertEquals(Arrays.asList(C + "@1.0.0", B + "@1.0.0", A + "@1.0.0!"), solve(Collections.emptyList(), A));
        List<Resolver.Candidate> installed = Arrays.asList(resolve(B, "1.*").get(0), resolve(C, "1.*").get(0));
        assertEquals(Arrays.asList(C + "@1.0.0", B + "@1.0.0!"), solve(installed, B));
        Resolver.Request update = new Resolver.Request(Source.parse(B), null, true, true, false, null);
        List<String> out = new ArrayList<>();
        for (Resolver.Member m : resolver.solve(Collections.singletonList(update), installed)) out.add(m.candidate.identity + "@" + m.candidate.version());
        assertEquals(Arrays.asList(C + "@2.0.0", B + "@2.0.0"), out);
    }

    @Test
    public void modNameClashesBacktrack() throws Exception {
        server.files.put("example.com/same.zip", TestHttps.zip("manifest.json", "{\"name\": \"Same\"}"));
        server.files.put("example.com/other.zip", TestHttps.zip("manifest.json", "{\"name\": \"other\"}"));
        pub(A, "1.0.0", mcmod("https://example.com/other.zip"));
        pub(A, "2.0.0", mcmod("https://example.com/same.zip"));
        pub(B, "1.0.0", mcmod("https://example.com/same.zip"));
        assertEquals(Arrays.asList(A + "@1.0.0!", B + "@1.0.0!"), solve(Collections.emptyList(), B, A));
    }

    @Test
    public void reportsUnsatisfiableRequests() throws Exception {
        pub(B, "1.0.0", "");
        pub(A, "1.0.0", dep(B, ">=5.0.0"));
        try {
            solve(Collections.emptyList(), A);
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.UNSATISFIABLE, e.kind);
            assertTrue(e.getMessage(), e.getMessage().contains("needed by " + A));
        }
        try {
            solve(Collections.emptyList(), B + "@9.0.0");
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.NO_PACKAGE_FOUND, e.kind);
            assertEquals(B + "@9.0.0", e.argument);
        }
        server.files.put("example.com/b.zip", TestHttps.zip("lip.toml", TestHttps.toml(B, "7.0.0", "")));
        try {
            solve(Collections.emptyList(), B, "https://example.com/b.zip"); // one identity from two sources
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.UNSATISFIABLE, e.kind);
        }
        try {
            solve(Collections.emptyList(), B + "@1.0.0", B + "@branch:main");
            fail();
        } catch (PackageException e) {
            assertEquals(PackageException.Kind.UNSATISFIABLE, e.kind);
        }
    }
}
