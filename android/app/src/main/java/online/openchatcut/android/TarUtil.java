package online.openchatcut.android;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Minimal tar reader (plain or GZIP) supporting GNU longname ('L') and symlinks ('2'/'1'). */
final class TarUtil {
    private TarUtil() {}

    interface Progress {
        void on(String file);
    }

    static void extractTgz(InputStream raw, File destDir, Progress... cb) throws Exception {
        Files.createDirectories(Paths.get(destDir.getAbsolutePath()));
        BufferedInputStream src = new BufferedInputStream(raw, 1 << 17);
        src.mark(2);
        int b0 = src.read();
        int b1 = src.read();
        src.reset();
        InputStream in = (b0 == 0x1f && b1 == 0x8b)
                ? new java.util.zip.GZIPInputStream(src, 1 << 15)   // gzip payload
                : src;                                             // plain tar (aapt2 pre-un-gzips *.gz)
        readTar(in, destDir, cb);
        in.close();
    }

    static boolean isGzip(InputStream in) {
        in.mark(2);
        try {
            int b0 = in.read();
            int b1 = in.read();
            in.reset();
            return b0 == 0x1f && b1 == 0x8b;
        } catch (Exception e) {
            return false;
        }
    }

    private static void readTar(InputStream in, File destDir, Progress... cb) throws Exception {
        byte[] head = new byte[512];
        byte[] block = new byte[8192];
        String longName = null;
        List<String> paxPath = new ArrayList<>();

        while (true) {
            int n = in.read(head, 0, 512);
            if (n < 0) break;
            if (n < 512) { // pad
                int extra = in.read(head, n, 512 - n);
                if (extra <= 0) break;
            }
            if (isZeroBlock(head)) {
                // EOF double-block; consume trailing zeros silently
                break;
            }

            String name = readString(head, 0, 100);
            long size = readOctal(head, 124, 12);
            long mode = readOctal(head, 100, 8);
            int type = head[156] != 0 ? head[156] : '0';
            String link = readString(head, 157, 100);

            if (type == 'L') {           // GNU long name entry
                byte[] ld = new byte[(int) size];
                readInto(in, ld);
                longName = readString(ld, 0, (int) size);
                continue;
            }
            if (type == 'x' || type == 'g') {   // PAX header — only care about path override
                byte[] px = new byte[(int) size + 1];
                readInto(in, px);
                paxPath.clear();
                paxPath.add(parsePaxPath(new String(px, 0, (int) Math.min(size, px.length), "UTF-8")));
                continue;
            }
            if (longName != null || !paxPath.isEmpty()) {
                name = paxPath.size() > 0 ? paxPath.get(0) : longName;
                longName = null;
                paxPath.clear();
            }
            name = sanitize(name);

            File target = new File(destDir, name);
            for (Progress p : cb) if (p != null) p.on(name);

            switch (type) {
                case '0':
                case '\0': {
                    if (target.getParentFile() != null) Files.createDirectories(Paths.get(target.getParentFile().getAbsolutePath()));
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(target)) {
                        long left = size;
                        while (left > 0) {
                            int want = (int) Math.min(block.length, left);
                            int rd = in.read(block, 0, want);
                            if (rd < 0) break;
                            fo.write(block, 0, rd);
                            left -= rd;
                        }
                    }
                    applyMode(target, mode);
                    skipPad(in, size);
                    break;
                }
                case '5': {
                    Files.createDirectories(Paths.get(target.getAbsolutePath()));
                    // dirs have no data (size usually 0)
                    break;
                }
                case '2':
                case '1': {
                    if (target.getParentFile() != null) Files.createDirectories(Paths.get(target.getParentFile().getAbsolutePath()));
                    try {
                        Files.deleteIfExists(Paths.get(target.getAbsolutePath()));
                        // Link TARGETS stay verbatim (relative "../…" targets are valid and
                        // stay inside the guest; absolute targets are re-jailed by proot).
                        Files.createSymbolicLink(Paths.get(target.getAbsolutePath()), Paths.get(link));
                    } catch (UnsupportedOperationException | java.io.IOException ignore) {
                        // Android 8 (API 26) supports Files.createSymbolicLink; unreachable in practice.
                    }
                    break;
                }
                default: {
                    // skip content types we don't handle (hard links '1', dev '3'+'4', fifo '6', 'K' 'M' 'S'...)
                    if (type == '1' || type == '3' || type == '4' || type == '6') break;
                    long left = size;
                    while (left > 0) {
                        long skip = Math.min(in.available() > 0 ? in.available() : (int) left, left);
                        if (skip == 0) skip = (int) Math.min(8192, left);
                        int r = in.read(block, 0, (int) Math.min(skip, block.length));
                        if (r < 0) break;
                        left -= r;
                    }
                    skipPad(in, size);
                }
            }
        }
    }

    private static void skipPad(InputStream in, long size) throws Exception {
        long padding = (512 - (size % 512)) % 512;
        if (padding <= 0) return;
        byte[] pad = new byte[512];
        long left = padding;
        while (left > 0) {
            int r = readStream(in, pad, (int) Math.min(left, pad.length));
            if (r < 0) break;
            left -= r;
        }
    }

    private static int readStream(InputStream in, byte[] b, int len) throws Exception {
        return in.read(b, 0, len);
    }

    private static void readInto(InputStream in, byte[] buf) throws Exception {
        int pos = 0;
        while (pos < buf.length) {
            int r = in.read(buf, pos, buf.length - pos);
            if (r < 0) break;
            pos += r;
        }
        skipPad(in, buf.length);
    }

    private static String sanitize(String name) {
        String n = name.trim();
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        // never let an entry escape destDir; only rewrite whole ".." segments
        n = n.replace("../", "__/").replace("/../", "/__/");
        return n;
    }

    /** Preserve the tar mode bits (exec flags matter inside the guest). */
    private static void applyMode(File target, long mode) {
        if (mode <= 0) return;
        try {
            boolean ownerExec = (mode & 0100) != 0;
            boolean groupExec = (mode & 0010) != 0;
            boolean otherExec = (mode & 0001) != 0;
            target.setReadable(true, false);
            target.setWritable(true, true);
            target.setExecutable(ownerExec || groupExec || otherExec, !otherExec);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private static String readString(byte[] b, int off, int len) {
        int e = off;
        for (int i = off; i < off + len; i++) {
            if (b[i] == 0) break;
            e++;
        }
        return new String(b, off, e - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long readOctal(byte[] b, int off, int len) {
        String s = readString(b, off, len).trim();
        if (s.isEmpty() || s.equals("00000000000") || s.equals("0000000000")) return 0;
        // GNU base-256 (binary) encoded sizes start with 0x80
        if ((b[off] & 0xff) == 0x80) {
            long v = 0;
            for (int i = off + 12 - 8; i < off + 12; i++) v = (v << 8) | (b[i] & 0xff);
            return v;
        }
        long v = 0;
        for (char c : s.toCharArray()) {
            if (c == ' ') continue;
            if (c < '0' || c > '7') break;
            v = (v << 3) | (c - '0');
        }
        return v;
    }

    private static String parsePaxPath(String paxText) {
        for (String line : paxText.split("\n")) {
            int idx = line.indexOf(" path=");
            if (idx >= 0) {
                String rest = line.substring(idx + " path=".length());
                return rest.replaceAll("(\\u00b7?)$", "");
            }
        }
        return null;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte v : b) {
            if (v != 0) return false;
        }
        return true;
    }
}
