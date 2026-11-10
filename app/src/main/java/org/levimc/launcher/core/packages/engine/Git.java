package org.levimc.launcher.core.packages.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.ByteString;

/**
 * A git smart HTTP protocol version 2 client (enough for package 2.1): {@code ls-refs} of branches
 * and tags with annotated tags peeled, and the git archive of one commit, fetched shallow
 * ({@code deepen 1}) and, when the server supports it, without blobs ({@code filter blob:none}); the
 * blobs of the files read are fetched later. Packs are read as they arrive (OFS and REF deltas).
 */
final class Git {
    private static final int COMMIT = 1, TREE = 2, BLOB = 3, TAG = 4, OFS_DELTA = 6, REF_DELTA = 7;
    private static final String[] TYPE_NAMES = {null, "commit", "tree", "blob", "tag"};
    private static final byte[] FLUSH = {}, DELIM = {}, END = {};
    private static final MediaType REQUEST = MediaType.get("application/x-git-upload-pack-request");

    /** A branch or tag: its full name ({@code refs/tags/v1.0.0}) and the commit it points to (peeled). */
    static final class Ref {
        final String name, commit;

        Ref(String name, String commit) {
            this.name = name;
            this.commit = commit;
        }
    }

    static final class Obj {
        final int type;
        final byte[] data;

        Obj(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    private final Http http;
    /** Whether a repository URL (as sent, proxied or not) supports {@code filter}. */
    private final Map<String, Boolean> filter = new HashMap<>();

    Git(Http http) {
        this.http = http;
    }

    static String url(String repo) {
        return "https://" + repo + ".git";
    }

    /** The branches and tags of the remote repository {@code repo} (a module path). */
    List<Ref> lsRefs(String repo) throws PackageException {
        return http.withProxy(url(repo), base -> {
            capabilities(base);
            return post(base, "ls-refs", Arrays.asList("peel", "ref-prefix refs/heads/", "ref-prefix refs/tags/"), (in, length) -> {
                List<Ref> refs = new ArrayList<>();
                for (byte[] line; (line = pkt(in)) != FLUSH; ) {
                    String[] f = text(line).split(" ");
                    if (f.length < 2 || !f[0].matches("[0-9a-f]{40}")) throw new IOException("bad ls-refs line '" + text(line) + "'");
                    String commit = f[0];
                    for (int i = 2; i < f.length; i++) if (f[i].startsWith("peeled:")) commit = f[i].substring(7);
                    if (f[1].startsWith("refs/heads/") || f[1].startsWith("refs/tags/")) refs.add(new Ref(f[1], commit));
                }
                return refs;
            });
        });
    }

    /**
     * The git archive of {@code commit} of {@code repo}; one whose tree breaks package 1 is INVALID_PACKAGE.
     * Only its file list is kept: the trees are dropped after the walk, and a blob is held from its
     * fetch until it is opened, so archives read while resolving cost no more than their paths.
     */
    Archive archive(String repo, String commit, String pkg) throws PackageException {
        Map<String, Obj> got = fetch(repo, Arrays.asList(commit), true, pkg);
        Obj c = got.get(commit);
        if (c == null || c.type != COMMIT || !text(c.data).startsWith("tree ")) {
            throw new PackageException(PackageException.Kind.INVALID_PACKAGE, null, commit + " of " + repo + " is not a commit");
        }
        Map<String, String> files = new LinkedHashMap<>();
        walk(got, repo, text(c.data).substring(5, 45), "", files);
        Map<String, Obj> blobs = new HashMap<>(); // sent with the commit by a server without filter
        for (String id : files.values()) if (got.get(id) != null && got.get(id).type == BLOB) blobs.put(id, got.get(id));
        List<String> paths = new ArrayList<>(files.keySet());
        return new Archive() {
            @Override
            List<String> files() {
                return paths;
            }

            @Override
            void prefetch(Collection<String> wanted) throws PackageException {
                List<String> ids = new ArrayList<>();
                for (String p : wanted) {
                    String id = files.get(p);
                    if (id != null && !blobs.containsKey(id) && !ids.contains(id)) ids.add(id);
                }
                if (!ids.isEmpty()) blobs.putAll(fetch(repo, ids, false, pkg));
            }

            @Override
            InputStream open(String path) throws IOException, PackageException {
                prefetch(Arrays.asList(path));
                Obj blob = blobs.remove(files.get(path));
                if (blob == null || blob.type != BLOB) throw new IOException("the server did not send " + path);
                return new ByteArrayInputStream(blob.data);
            }

            @Override
            public void close() {
                blobs.clear();
            }
        };
    }

    private void walk(Map<String, Obj> objects, String repo, String tree, String prefix, Map<String, String> out) throws PackageException {
        Obj t = objects.get(tree);
        if (t == null || t.type != TREE) {
            throw new PackageException(PackageException.Kind.NETWORK, null, "The server of " + repo + " did not send the tree " + tree);
        }
        byte[] d = t.data;
        for (int p = 0; p < d.length; ) {
            int sp = indexOf(d, (byte) ' ', p), nul = indexOf(d, (byte) 0, sp);
            if (sp < 0 || nul < 0 || nul + 21 > d.length) throw Archive.invalid(repo, "a tree is damaged");
            String mode = text(Arrays.copyOfRange(d, p, sp)), name;
            try {
                name = TextFormats.utf8(Arrays.copyOfRange(d, sp + 1, nul));
            } catch (IllegalArgumentException e) {
                throw Archive.invalid(repo, "a name in " + prefix + " is not UTF-8");
            }
            String id = ByteString.of(d, nul + 1, 20).hex();
            p = nul + 21;
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
                throw Archive.invalid(repo, "'" + prefix + name + "' is not a valid name");
            }
            if (mode.equals("40000") && prefix.length() + name.length() > 4096) throw Archive.invalid(repo, "a path is too long");
            if (mode.equals("40000")) walk(objects, repo, id, prefix + name + "/", out);
            else if (mode.equals("100644") || mode.equals("100755")) out.put(prefix + name, id);
        }
    }

    /** Fetches {@code ids}: a commit with its trees ({@code commit}), or blobs; returns the objects by id. */
    private Map<String, Obj> fetch(String repo, List<String> ids, boolean commit, String pkg) throws PackageException {
        return http.withProxy(url(repo), base -> {
            List<String> args = new ArrayList<>(Arrays.asList("no-progress", "ofs-delta"));
            if (commit) {
                args.add("deepen 1");
                if (capabilities(base)) args.add("filter blob:none");
            } else {
                capabilities(base);
            }
            for (String id : ids) args.add("want " + id);
            args.add("done");
            return post(base, "fetch", args, (in, length) -> {
                for (byte[] line; (line = pkt(in)) != FLUSH; ) {
                    if (line == DELIM || line == END) continue;
                    String section = text(line);
                    if (section.equals("packfile")) return readPack(new Sideband(in), "git:" + repo, pkg);
                    while ((line = pkt(in)) != DELIM && line != FLUSH) {
                        if (line == END) throw new IOException("the fetch response ended early");
                    }
                    if (line == FLUSH) break;
                }
                throw new IOException("the fetch response has no packfile");
            });
        });
    }

    /** Reads the capability advertisement of {@code base}; returns whether {@code fetch} supports {@code filter}. */
    private boolean capabilities(String base) throws IOException, PackageException {
        Boolean known = filter.get(base);
        if (known != null) return known;
        Request req = new Request.Builder().url(base + "/info/refs?service=git-upload-pack").header("Git-Protocol", "version=2").build();
        boolean f = http.send(req, (in, length) -> {
            byte[] line = pkt(in);
            if (line != FLUSH && text(line).startsWith("# service=")) {
                while (pkt(in) != FLUSH) {
                    // the service announcement
                }
                line = pkt(in);
            }
            if (line == FLUSH || !text(line).equals("version 2")) throw new IOException("the server does not speak git protocol version 2");
            boolean hasFilter = false;
            for (byte[] cap; (cap = pkt(in)) != FLUSH; ) {
                String c = text(cap);
                if (c.startsWith("fetch=") && Arrays.asList(c.substring(6).split(" ")).contains("filter")) hasFilter = true;
            }
            return hasFilter;
        });
        filter.put(base, f);
        return f;
    }

    /** Sends one protocol v2 command; {@code response} reads the response as it arrives (a pack is never buffered whole). */
    private <T> T post(String base, String command, List<String> args, Http.Body<T> response) throws IOException, PackageException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writePkt(body, "command=" + command + "\n");
        body.write("0001".getBytes(StandardCharsets.US_ASCII));
        for (String a : args) writePkt(body, a + "\n");
        body.write("0000".getBytes(StandardCharsets.US_ASCII));
        Request req = new Request.Builder().url(base + "/git-upload-pack").header("Git-Protocol", "version=2")
                .header("Accept", "application/x-git-upload-pack-result").post(RequestBody.create(body.toByteArray(), REQUEST)).build();
        return http.send(req, response);
    }

    private static void writePkt(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(String.format("%04x", b.length + 4).getBytes(StandardCharsets.US_ASCII), 0, 4);
        out.write(b, 0, b.length);
    }

    /** One pkt-line: its payload, or FLUSH, DELIM or END. */
    private static byte[] pkt(InputStream in) throws IOException {
        byte[] len = readN(in, 4);
        int n;
        try {
            n = Integer.parseInt(text(len), 16);
        } catch (NumberFormatException e) {
            throw new IOException("not a git pkt-line response");
        }
        if (n == 0) return FLUSH;
        if (n == 1) return DELIM;
        if (n == 2) return END;
        if (n < 4) throw new IOException("bad pkt-line length");
        byte[] payload = readN(in, n - 4);
        if (payload.length > 4 && text(Arrays.copyOf(payload, 4)).equals("ERR ")) throw new IOException("git server: " + text(payload));
        return payload;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        for (int p = 0, r; p < n; p += r) if ((r = in.read(b, p, n - p)) < 0) throw new EOFException("the git response ended early");
        return b;
    }

    /** The data of side-band channel 1 of the packfile section; channel 3 is an error. */
    private static final class Sideband extends InputStream {
        private final InputStream in;
        private byte[] cur = {};
        private int p;
        private boolean done;

        Sideband(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (p == cur.length) {
                if (done) return -1;
                byte[] line = pkt(in);
                if (line == FLUSH || line == END) {
                    done = true;
                } else if (line.length > 0 && line[0] == 1) {
                    cur = line;
                    p = 1;
                } else if (line.length > 0 && line[0] == 3) {
                    throw new IOException("git server: " + text(Arrays.copyOfRange(line, 1, line.length)));
                }
            }
            int n = Math.min(len, cur.length - p);
            System.arraycopy(cur, p, b, off, n);
            p += n;
            return n;
        }
    }

    /** Reads a pack (version 2 or 3) with its SHA-1 trailer checked; returns its objects by id. */
    Map<String, Obj> readPack(InputStream raw, String bar, String pkg) throws IOException, PackageException {
        PackInput in = new PackInput(raw);
        byte[] head = in.bytes(12);
        if (!text(Arrays.copyOf(head, 4)).equals("PACK") || head[7] != 2 && head[7] != 3) throw new IOException("not a git pack");
        long count = (head[8] & 0xffL) << 24 | (head[9] & 0xff) << 16 | (head[10] & 0xff) << 8 | head[11] & 0xff;
        Map<Long, Obj> byOffset = new HashMap<>();
        Map<String, Obj> byId = new HashMap<>();
        List<Object[]> deltas = new ArrayList<>(); // {offset, base offset or base id, delta}
        long reported = 0;
        for (long i = 0; i < count; i++) {
            long offset = in.offset;
            int c = in.read(), type = c >> 4 & 7;
            long size = c & 15;
            for (int shift = 4; (c & 0x80) != 0; shift += 7) size |= (long) ((c = in.read()) & 0x7f) << shift;
            Object base = null;
            if (type == OFS_DELTA) {
                c = in.read();
                long back = c & 0x7f;
                while ((c & 0x80) != 0) back = (back + 1) << 7 | (c = in.read()) & 0x7f;
                base = offset - back;
            } else if (type == REF_DELTA) {
                base = ByteString.of(in.bytes(20)).hex();
            } else if (type < COMMIT || type > TAG) {
                throw new IOException("bad object type " + type + " in the pack");
            }
            byte[] data = in.inflate(size);
            if (base != null) {
                deltas.add(new Object[] {offset, base, data});
            } else {
                Obj o = new Obj(type, data);
                byOffset.put(offset, o);
                byId.put(id(o), o);
            }
            if (in.offset - reported >= 256 * 1024) {
                http.cancel.check();
                http.listener.progress(bar, Listener.Stage.GIT_FETCH, pkg, reported = in.offset, -1, true);
            }
        }
        byte[] digest = in.sha1.digest(), trailer = in.bytes(20);
        if (!Arrays.equals(digest, trailer)) throw new IOException("the pack's checksum does not match");
        http.listener.progress(bar, Listener.Stage.GIT_FETCH, pkg, in.offset, in.offset, true);
        for (boolean progress = true; progress && !deltas.isEmpty(); ) {
            progress = false;
            for (int i = 0; i < deltas.size(); i++) {
                Object[] d = deltas.get(i);
                Obj b = d[1] instanceof Long ? byOffset.get(d[1]) : byId.get(d[1]);
                if (b == null) continue;
                Obj o = new Obj(b.type, applyDelta(b.data, (byte[]) d[2]));
                byOffset.put((Long) d[0], o);
                byId.put(id(o), o);
                deltas.remove(i--);
                progress = true;
            }
        }
        if (!deltas.isEmpty()) throw new IOException("the pack has deltas without a base");
        return byId;
    }

    /** The bytes of a pack as they are read: offset, running SHA-1, and zlib streams of known size. */
    private static final class PackInput {
        final InputStream in;
        final MessageDigest sha1;
        final byte[] buf = new byte[65536];
        int pos, lim;
        long offset;

        PackInput(InputStream in) {
            this.in = in;
            sha1 = sha1();
        }

        private void fill() throws IOException {
            if (pos < lim) return;
            pos = 0;
            lim = Math.max(0, in.read(buf, 0, buf.length));
            if (lim == 0) throw new EOFException("the pack ended early");
        }

        private void consume(int n) {
            sha1.update(buf, pos, n);
            pos += n;
            offset += n;
        }

        int read() throws IOException {
            fill();
            int b = buf[pos] & 0xff;
            consume(1);
            return b;
        }

        byte[] bytes(int n) throws IOException {
            byte[] out = new byte[n];
            for (int p = 0; p < n; ) {
                fill();
                int k = Math.min(n - p, lim - pos);
                System.arraycopy(buf, pos, out, p, k);
                consume(k);
                p += k;
            }
            return out;
        }

        /**
         * Inflates one zlib stream that must give exactly {@code size} bytes. The output grows as it is
         * inflated, so a forged size cannot make it allocate more than the data gives.
         */
        byte[] inflate(long size) throws IOException {
            if (size > Integer.MAX_VALUE - 16) throw new IOException("an object of the pack is too large");
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(size, 65536));
            byte[] chunk = new byte[65536];
            Inflater inf = new Inflater();
            try {
                for (boolean first = true; !inf.finished(); first = false) {
                    if (inf.needsInput()) {
                        if (!first) consume(lim - pos);
                        fill();
                        inf.setInput(buf, pos, lim - pos);
                    }
                    out.write(chunk, 0, inf.inflate(chunk));
                    if (out.size() > size || inf.needsDictionary()) throw new IOException("an object of the pack is damaged");
                }
                consume(lim - pos - inf.getRemaining());
                if (out.size() != size) throw new IOException("an object of the pack has the wrong size");
                return out.toByteArray();
            } catch (DataFormatException e) {
                throw new IOException("an object of the pack is damaged", e);
            } finally {
                inf.end();
            }
        }
    }

    /** Applies a git delta to {@code base}. */
    static byte[] applyDelta(byte[] base, byte[] delta) throws IOException {
        int[] p = {0};
        long srcSize = varint(delta, p), dstSize = varint(delta, p);
        if (srcSize != base.length || dstSize > Integer.MAX_VALUE) throw new IOException("a delta does not fit its base");
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(dstSize, base.length + delta.length));
        try {
            while (p[0] < delta.length) {
                int op = delta[p[0]++] & 0xff;
                if ((op & 0x80) != 0) {
                    long off = 0, len = 0;
                    for (int i = 0; i < 4; i++) if ((op & 1 << i) != 0) off |= (delta[p[0]++] & 0xffL) << 8 * i;
                    for (int i = 0; i < 3; i++) if ((op & 0x10 << i) != 0) len |= (delta[p[0]++] & 0xffL) << 8 * i;
                    if (len == 0) len = 0x10000;
                    if (off + len > base.length) throw new IOException("a delta copies beyond its base");
                    out.write(base, (int) off, (int) len);
                } else if (op != 0) {
                    out.write(delta, p[0], op);
                    p[0] += op;
                } else {
                    throw new IOException("a delta has a bad instruction");
                }
                if (out.size() > dstSize) throw new IOException("a delta gives the wrong size");
            }
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("a delta is damaged");
        }
        if (out.size() != dstSize) throw new IOException("a delta gives the wrong size");
        return out.toByteArray();
    }

    private static long varint(byte[] b, int[] p) throws IOException {
        long v = 0;
        for (int shift = 0, c = 0x80; (c & 0x80) != 0; shift += 7) {
            if (p[0] >= b.length || shift > 56) throw new IOException("a delta is damaged");
            c = b[p[0]++] & 0xff;
            v |= (long) (c & 0x7f) << shift;
        }
        return v;
    }

    private static String id(Obj o) {
        MessageDigest d = sha1();
        d.update((TYPE_NAMES[o.type] + " " + o.data.length + "\0").getBytes(StandardCharsets.US_ASCII));
        return ByteString.of(d.digest(o.data)).hex();
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int indexOf(byte[] b, byte v, int from) {
        if (from < 0) return -1;
        for (int i = from; i < b.length; i++) if (b[i] == v) return i;
        return -1;
    }

    /** A pkt-line payload as text, without its trailing LF. */
    private static String text(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }
}
