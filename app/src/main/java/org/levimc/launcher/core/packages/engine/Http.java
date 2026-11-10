package org.levimc.launcher.core.packages.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.ByteString;

/**
 * HTTPS for one operation: GETs following redirects to https only, the {@code github_proxy} with a
 * direct fallback (userspace 2), cancellation, and the cache's {@code http-<H>} entries (userspace 3).
 */
final class Http {
    interface Attempt<T> {
        T run(String url) throws IOException, PackageException;
    }

    interface Body<T> {
        T read(InputStream in, long length) throws IOException, PackageException;
    }

    final Cancel cancel;
    final Listener listener;
    private final OkHttpClient client;
    private final File cacheDir;
    private final String proxy;

    /** {@code githubProxy} is the URI prefix for github.com requests, or null. */
    Http(OkHttpClient client, File cacheDir, String githubProxy, Cancel cancel, Listener listener) {
        this.client = client.newBuilder().followRedirects(true).followSslRedirects(false).build();
        this.cacheDir = cacheDir;
        this.proxy = githubProxy == null || githubProxy.isEmpty() ? null : githubProxy;
        this.cancel = cancel;
        this.listener = listener;
    }

    /**
     * The URL of a github.com request through {@code proxy} (lip's GithubProxy.Rewrite): the proxy with the
     * request's path appended and the request's query, if any, in place of the proxy's; null when no proxy applies.
     */
    static String proxied(String proxy, String url) {
        HttpUrl u = HttpUrl.parse(url);
        if (proxy == null || u == null || !u.host().equals("github.com")) return null;
        int cut = proxy.indexOf('#');
        String base = cut < 0 ? proxy : proxy.substring(0, cut);
        int q = base.indexOf('?');
        String query = q < 0 ? "" : base.substring(q);
        base = (q < 0 ? base : base.substring(0, q)).replaceAll("/+$", "");
        String out = base + u.encodedPath() + (u.encodedQuery() != null ? "?" + u.encodedQuery() : query);
        return out.equals(url) ? null : out;
    }

    /** Runs {@code attempt} through the proxy when it applies, then directly; failures are {@code NETWORK}. */
    <T> T withProxy(String url, Attempt<T> attempt) throws PackageException {
        String via = proxied(proxy, url);
        List<String> urls = via == null ? Collections.singletonList(url) : Arrays.asList(via, url);
        PackageException failure = null;
        for (String u : urls) {
            cancel.check();
            try {
                return attempt.run(u);
            } catch (IOException e) {
                cancel.check();
                failure = new PackageException(PackageException.Kind.NETWORK, null, "Cannot get " + u + ": " + e.getMessage(), e);
            } catch (PackageException e) {
                cancel.check();
                failure = e;
            }
            if (u.equals(via)) listener.log(Listener.Level.WARN, failure.getMessage() + "; trying GitHub directly");
        }
        throw failure;
    }

    /** Sends {@code request} and reads a successful response's body (a redirect to http is not followed, so it fails). */
    <T> T send(Request request, Body<T> body) throws IOException, PackageException {
        cancel.check();
        if (!request.url().isHttps()) throw new IOException(request.url() + " is not an https URL");
        Call call = client.newCall(request);
        try (Cancel.Handle ignored = cancel.onCancel(call::cancel); Response r = call.execute()) {
            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code() + " from " + r.request().url());
            return body.read(r.body().byteStream(), r.body().contentLength());
        } catch (IOException e) {
            cancel.check();
            throw e;
        }
    }

    /** The cache entry name of {@code url}: {@code http-} and the SHA-256 of the URL without its fragment. */
    static String entryName(String url) {
        int hash = url.indexOf('#');
        return "http-" + ByteString.encodeUtf8(hash < 0 ? url : url.substring(0, hash)).sha256().hex();
    }

    /**
     * The ZIP archive downloaded from {@code url}, through the cache: a cached entry is used unless
     * {@code fresh}, and one that is not a ZIP archive is deleted and downloaded again. A body that is
     * not a ZIP archive is {@code INVALID_PACKAGE} and is not cached. {@code pkg} labels the progress.
     */
    Archive zip(String url, boolean fresh, String pkg) throws PackageException {
        File entry = new File(cacheDir, entryName(url));
        if (!fresh && entry.isFile()) {
            try {
                return Archive.zip(entry);
            } catch (PackageException e) {
                listener.log(Listener.Level.WARN, "Deleting the damaged cache entry of " + url);
                if (!entry.delete() && entry.exists()) throw new PackageException(PackageException.Kind.IO, "Cannot delete " + entry);
            }
        }
        if (!url.startsWith("https://")) {
            throw new PackageException(PackageException.Kind.NETWORK, null, "'" + url + "' is not an https URL");
        }
        withProxy(url, u -> {
            File tmp = new File(cacheDir, UUID.randomUUID() + ".tmp");
            try {
                if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) throw new IOException("cannot create " + cacheDir);
                send(new Request.Builder().url(u).build(), (in, length) -> copy(in, tmp, length, url, pkg));
                Archive.zip(tmp).close();
                Files.move(tmp.toPath(), entry.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return null;
            } finally {
                Files.deleteIfExists(tmp.toPath());
            }
        });
        return Archive.zip(entry);
    }

    private Void copy(InputStream in, File to, long length, String url, String pkg) throws IOException, PackageException {
        String id = "download:" + url;
        long n = 0, reported = 0;
        listener.progress(id, Listener.Stage.DOWNLOAD, pkg, 0, length, true);
        try (OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            for (int r; (r = in.read(buf)) > 0; ) {
                out.write(buf, 0, r);
                n += r;
                if (n - reported >= 256 * 1024) {
                    cancel.check();
                    listener.progress(id, Listener.Stage.DOWNLOAD, pkg, reported = n, length, true);
                }
            }
        }
        if (length >= 0 && n != length) throw new IOException("the download of " + url + " was cut off");
        listener.progress(id, Listener.Stage.DOWNLOAD, pkg, n, n, true);
        return null;
    }
}
