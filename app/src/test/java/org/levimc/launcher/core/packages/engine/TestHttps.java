package org.levimc.launcher.core.packages.engine;

import static org.junit.Assert.assertEquals;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * A loopback HTTPS server for engine tests. {@link #client} sends every host (example.com, github.com,
 * proxy.test, ...) on port 443 to it, so tests use real-looking URLs and never reach the network.
 * Paths below {@code <repo>.git/} are answered by {@code git http-backend} over {@link #gitRoot}
 * ({@code gitRoot/<host>/<path>.git}); other paths by {@link #files} and {@link #redirects}.
 */
final class TestHttps implements Closeable {
    private static final String PASS = "changeit";

    final File gitRoot;
    /** Bodies by "host/path". */
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    /** Location headers by "host/path". */
    final Map<String, String> redirects = new ConcurrentHashMap<>();
    /** "METHOD host/path" of each request, in order. */
    final List<String> requests = new CopyOnWriteArrayList<>();
    final OkHttpClient client;
    private final SSLServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    TestHttps(File dir) throws Exception {
        gitRoot = new File(dir, "git");
        File ks = new File(dir, "test.p12");
        Process p = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/keytool").getPath(), "-genkeypair",
                "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,dns:example.com,dns:github.com,dns:proxy.test,dns:other.test,ip:127.0.0.1",
                "-keystore", ks.getPath(), "-storetype", "PKCS12", "-storepass", PASS, "-keypass", PASS, "-noprompt")
                .redirectErrorStream(true).start();
        Archive.readAll(p.getInputStream());
        assertEquals(0, p.waitFor());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(ks)) {
            store.load(in, PASS.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, PASS.toCharArray());
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(kmf.getKeyManagers(), null, null);
        server = (SSLServerSocket) serverTls.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.execute(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    return;
                }
            }
        });
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        X509TrustManager trust = (X509TrustManager) tmf.getTrustManagers()[0];
        SSLContext clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, new javax.net.ssl.TrustManager[] {trust}, null);
        int port = server.getLocalPort();
        client = new OkHttpClient.Builder()
                .sslSocketFactory(clientTls.getSocketFactory(), trust)
                .dns(host -> Collections.singletonList(InetAddress.getLoopbackAddress()))
                .socketFactory(new SocketFactory() {
                    @Override
                    public Socket createSocket() {
                        return new Socket() {
                            @Override
                            public void connect(SocketAddress a, int timeout) throws IOException {
                                super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeout);
                            }
                        };
                    }

                    @Override
                    public Socket createSocket(String h, int p) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Socket createSocket(String h, int p, InetAddress l, int lp) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Socket createSocket(InetAddress h, int p) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) {
                        throw new UnsupportedOperationException();
                    }
                })
                .build();
    }

    private void serve(Socket s) {
        try (Socket conn = s) {
            InputStream in = new BufferedInputStream(conn.getInputStream());
            String[] request = line(in).split(" ");
            Map<String, String> headers = new ConcurrentHashMap<>();
            for (String h; !(h = line(in)).isEmpty(); ) {
                int colon = h.indexOf(':');
                headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
            }
            byte[] body = new byte[Integer.parseInt(headers.getOrDefault("content-length", "0"))];
            for (int n = 0, r; n < body.length; n += r) if ((r = in.read(body, n, body.length - n)) < 0) return;
            String host = headers.getOrDefault("host", "").replaceFirst(":.*", "");
            String target = request[1], path = target.replaceFirst("\\?.*", "");
            requests.add(request[0] + " " + host + path);
            OutputStream out = conn.getOutputStream();
            String location = redirects.get(host + path);
            byte[] file = files.get(host + path);
            if (location != null) {
                out.write(("HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
            } else if (file != null) {
                respond(out, 200, "application/octet-stream", file);
            } else if (path.contains(".git/")) {
                gitBackend(out, request[0], host, target, headers, body);
            } else {
                respond(out, 404, "text/plain", new byte[0]);
            }
            out.flush();
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            // the client went away
        }
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
    }

    private void gitBackend(OutputStream out, String method, String host, String target, Map<String, String> headers, byte[] body)
            throws IOException, InterruptedException {
        int q = target.indexOf('?');
        ProcessBuilder pb = new ProcessBuilder("git", "http-backend");
        Map<String, String> env = pb.environment();
        env.put("GIT_PROJECT_ROOT", gitRoot.getAbsolutePath());
        env.put("GIT_HTTP_EXPORT_ALL", "1");
        env.put("PATH_INFO", "/" + host + (q < 0 ? target : target.substring(0, q)));
        env.put("QUERY_STRING", q < 0 ? "" : target.substring(q + 1));
        env.put("REQUEST_METHOD", method);
        env.put("REMOTE_ADDR", "127.0.0.1");
        env.put("CONTENT_LENGTH", Integer.toString(body.length));
        if (headers.containsKey("content-type")) env.put("CONTENT_TYPE", headers.get("content-type"));
        if (headers.containsKey("git-protocol")) env.put("GIT_PROTOCOL", headers.get("git-protocol"));
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(body);
        }
        byte[] cgi = Archive.readAll(p.getInputStream());
        p.waitFor(60, TimeUnit.SECONDS);
        int end = 0;
        while (end + 1 < cgi.length && !(cgi[end] == '\n' && (cgi[end + 1] == '\n' || cgi[end + 1] == '\r'))) end++;
        int bodyAt = cgi[end + 1] == '\n' ? end + 2 : end + 3;
        int status = 200;
        String type = "application/octet-stream";
        for (String h : new String(cgi, 0, end, StandardCharsets.ISO_8859_1).split("\r?\n")) {
            if (h.toLowerCase(Locale.ROOT).startsWith("status:")) status = Integer.parseInt(h.substring(7).trim().split(" ")[0]);
            if (h.toLowerCase(Locale.ROOT).startsWith("content-type:")) type = h.substring(13).trim();
        }
        respond(out, status, type, Arrays.copyOfRange(cgi, bodyAt, cgi.length));
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) throw new IOException("closed");
            if (c != '\r') b.write(c);
        }
        return b.toString("ISO-8859-1");
    }

    /** Runs git in {@code dir}; returns its trimmed standard output. */
    static String git(File dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 9];
        System.arraycopy(new String[] {"git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false",
                "-c", "tag.gpgsign=false"}, 0, cmd, 0, 9);
        System.arraycopy(args, 0, cmd, 9, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        pb.environment().put("HOME", dir.getAbsolutePath());
        Process p = pb.start();
        String out = new String(Archive.readAll(p.getInputStream()), StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) throw new IOException("git " + String.join(" ", args) + ": " + out);
        return out;
    }

    /** A ZIP of name/content pairs ("d/" alone is a directory entry). */
    static byte[] zip(String... nameContent) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(b)) {
            for (int i = 0; i < nameContent.length; i += 2) {
                z.putNextEntry(new java.util.zip.ZipEntry(nameContent[i]));
                z.write(nameContent[i + 1].getBytes(StandardCharsets.UTF_8));
            }
        }
        return b.toByteArray();
    }

    /** A lip.toml with one entry of the default variant for (client, android-arm64), plus {@code entry} members. */
    static String toml(String id, String version, String entry) {
        return "format = 4\nid = \"" + id + "\"\nversion = \"" + version + "\"\n[[variants.\"\"]]\n"
                + "side = \"client\"\nplatform = \"android-arm64\"\n" + entry + "\n";
    }

    /**
     * Commits files (path, content, ...) to the work tree of {@code repo} (a module path served by this server),
     * tags the commit with {@code tag} (annotated when it starts with "+", none when null) and pushes main and
     * the tags; returns the commit id.
     */
    String commit(String repo, String tag, String... pathContent) throws Exception {
        File bare = new File(gitRoot, repo + ".git"), work = new File(gitRoot.getParentFile(), "work/" + repo);
        if (!bare.exists()) {
            assertEquals(true, bare.mkdirs() && work.mkdirs());
            git(bare, "init", "-q", "--bare");
            git(work, "init", "-q", "-b", "main");
        }
        for (int i = 0; i < pathContent.length; i += 2) {
            File f = new File(work, pathContent[i]);
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), pathContent[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        git(work, "add", "-A");
        git(work, "commit", "-q", "--allow-empty", "-m", "c");
        if (tag != null && tag.startsWith("+")) git(work, "tag", "-a", tag.substring(1), "-m", "t");
        else if (tag != null) git(work, "tag", tag);
        git(work, "push", "-q", "-f", "--tags", bare.getPath(), "HEAD:refs/heads/main");
        return git(work, "rev-parse", "HEAD");
    }

    /** An Http for one test operation. */
    Http http(File cacheDir, String proxy, Cancel cancel) {
        return new Http(client, cacheDir, proxy, cancel, Listener.NONE);
    }

    /** Whether git is installed. */
    static boolean hasGit() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        pool.shutdownNow();
    }
}
