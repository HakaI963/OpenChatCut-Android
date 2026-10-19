package online.openchatcut.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenChatCut Android host.
 *
 * Boots an embedded Linux guest (Debian trixie arm64 + Node 24) via Termux's
 * proot build, runs upstream's own production server — desktop/embedded-server.ts,
 * bundled without changes — then points the Capacitor WebView at that server on
 * 127.0.0.1. Android-side additions only; no editor logic lives here.
 */
public final class OccAndroid {

    // Provider keys are managed inside OpenChatCut's own Settings → agent providers
    // list (NVIDIA NIM included) and persist to the guest's .env.local, exactly as on
    // the desktop app. Nothing Android-side to configure.

    static final Handler UI = new Handler(Looper.getMainLooper());
    private static Host host;
    private static Overlay overlay;
    private static volatile WebView capWebView;
    private static volatile Activity activity;

    private OccAndroid() {}

    // ───────────────────────────────────────────────────────────── lifecycle
    static void bind(Activity a) {
        Host.ATTEMPTS_RESET();
        activity = a;
        if (overlay == null || overlay.owning() != a) overlay = new Overlay(a);
        overlay.show();
        if (host == null || !host.alive()) host = new Host(a);
        host.ensureRunning();
    }

    static void unbind(Activity a) {
        if (activity == a) activity = null;
    }

    static void setWebView(WebView wv) { capWebView = wv; }

    static Activity activity() { return activity; }

    /** Redirects the Capacitor WebView to the guest server once it is up. */
    static boolean gotoServer() {
        Activity a = activity;
        String origin = Host.origin();
        if (a == null || origin == null) return false;
        try {
            WebView wv = capWebView;
            if (wv == null) wv = WebViewUtil.deepWebView(a);
            if (wv == null) return false;
            String cur = wv.getUrl();
            if (cur != null && (cur.startsWith("http://127.0.0.1") || cur.startsWith("http://localhost"))) return true;
            wv.loadUrl(origin);
            return true;
        } catch (Throwable t) {
            Log.w("OccAndroid", "gotoServer failed", t);
        }
        return false;
    }

    // ───────────────────────────────────────────────────────────── settings
    // ───────────────────────────────────────────────────────────── logging
    public static String bootLog() {
        synchronized (Host.LOG) {
            return Host.LOG.toString();
        }
    }

    // ───────────────────────────────────────────────────────────── restart
    static void restartHost() {
        if (host != null) host.cancel();
        host = null;
        Host.resetOrigin();
        if (activity != null) {
            overlay = new Overlay(activity);
            overlay.show();
            host = new Host(activity);
            host.ensureRunning();
        } else {
            UI.post(() -> {
                if (activity != null) {
                    overlay = new Overlay(activity);
                    overlay.show();
                    host = new Host(activity);
                    host.ensureRunning();
                }
            });
        }
    }

    // ───────────────────────────────────────────────────────────── host
    /** The proot guest lifecycle. One instance per attempt. */
    static final class Host extends Thread {
        static final StringBuilder LOG = new StringBuilder();
        private static final AtomicReference<String> ORIGIN = new AtomicReference<>(null);
        private static final Object PROCESS_LOCK = new Object();
        private static Process prootProcess;

        private final Activity act;
        private volatile boolean keepGoing = true;
        private static volatile int ATTEMPTS = 0;

        private Host(Activity a) {
            setDaemon(true);
            setName("occ-host");
            act = a;
        }

        boolean alive() {
            return isAlive();
        }

        static String origin() { return ORIGIN.get(); }

        static void ATTEMPTS_RESET() { ATTEMPTS = 0; }

        static void resetOrigin() { ORIGIN.set(null); }

        void ensureRunning() {
            if (getState() == State.NEW) start();
        }

        void cancel() {
            keepGoing = false;
            synchronized (PROCESS_LOCK) {
                Process p = prootProcess;
                if (p != null) p.destroy();
            }
        }

        @Override
        public void run() {
            if (!keepGoing) return;
            try {
                File base = new File(act.getFilesDir(), "occ");
                File work = new File(base, "work");
                File rootDir = new File(base, "rootfs");
                File nodeDir = new File(work, "node");

                state("copying app payload…");
                prepareWork(base, work);

                verifyAssets(base, work);

                state("unpacking Debian rootfs (first run only)…");
                File rootfsMarker = new File(base, ".rootfs_ok");
                if (!rootfsMarker.exists()) {
                    java.util.concurrent.atomic.AtomicInteger rc0 = new java.util.concurrent.atomic.AtomicInteger();
                    TarUtil.extractTgz(act.getAssets().open("occ/guest/rootfs.occ"), rootDir,
                            n -> { if (rc0.incrementAndGet() % 400 == 0) state("unpacking system files… " + rc0.get()); });
                    Files.write(Paths.get(rootfsMarker.getAbsolutePath()), "ok".getBytes(StandardCharsets.UTF_8));
                } else {
                    state("rootfs already unpacked");
                }

                fixDns(rootDir);

                state("unpacking Node runtime…");
                File nodeMarker = new File(work, ".node_ok");
                if (!nodeMarker.exists()) {
                    java.util.concurrent.atomic.AtomicInteger rc1 = new java.util.concurrent.atomic.AtomicInteger();
                    TarUtil.extractTgz(act.getAssets().open("occ/guest/node.occ"), nodeDir,
                            n -> { if (rc1.incrementAndGet() % 200 == 0) state("unpacking runtime… " + rc1.get()); });
                    Files.write(Paths.get(nodeMarker.getAbsolutePath()), "ok".getBytes(StandardCharsets.UTF_8));
                }
                File nodeBin = new File(nodeDir, "bin/node");
                nodeBin.setExecutable(true, false);


                state("starting OpenChatCut server…");
                File proot = new File(base, "host/bin/proot");
                proot.setExecutable(true, false);
                new File(base, "host/libexec/proot/loader").setExecutable(true, false);
                new File(base, "host/libexec/proot/loader32").setExecutable(true, false);

                // launcher self-test: prove the Termux proot build actually executes
                // on this device BEFORE the long bootstrap, so failures print here.
                try {
                    ProcessBuilder st = new ProcessBuilder(proot.getAbsolutePath(), "--version");
                    st.redirectErrorStream(true);
                    Process sp = st.start();
                    String out = "";
                    try (java.io.BufferedReader sr = new java.io.BufferedReader(
                            new java.io.InputStreamReader(sp.getInputStream(), StandardCharsets.UTF_8))) {
                        String l;
                        while ((l = sr.readLine()) != null) out += l + " ";
                    }
                    int src = sp.waitFor();
                    log("proot self-test: exit " + src + " out: " + out.trim());
                    if (src != 0) throw new java.io.IOException("proot self-test failed (exit " + src + ")");
                } catch (java.io.IOException e) {
                    state("FAILED " + e);
                    log("proot exec check detail: " + e.getMessage());
                    autoRetry();
                    return;
                }

                File tmp = new File(act.getCacheDir(), "proot-tmp");
                tmp.mkdirs();

                String[] args = {
                        proot.getAbsolutePath(), "--kill-on-exit", "-0",
                        "-R", rootDir.getAbsolutePath(),
                        "-b", "/dev:/dev",
                        "-b", "/proc:/proc",
                        "-b", "/sys:/sys",
                        "-b", work.getAbsolutePath() + ":/occ",
                        "/bin/sh", "-c",
                        "cd /occ && PATH=/occ/node/bin:/usr/bin:/bin "
                                + "TMPDIR=/occ/data/tmp "
                                + "npm_config_update_notifier=false "
                                + "sh ./bootstrap.sh"
                };
                ProcessBuilder pb = new ProcessBuilder(args);
                pb.redirectErrorStream(true);
                pb.environment().put("PROOT_TMP_DIR", tmp.getAbsolutePath());
                synchronized (PROCESS_LOCK) {
                    prootProcess = pb.start();
                }
                Process p;
                synchronized (PROCESS_LOCK) {
                    p = prootProcess;
                }

                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    log(line);
                    int idx = line.indexOf("OCC_SERVER_READY ");
                    if (idx >= 0) {
                        String origin = parseOrigin(line.substring(idx + "OCC_SERVER_READY ".length()).trim());
                        if (origin != null) {
                            ORIGIN.set(origin);
                            state("READY " + origin);
                            UI.post(Overlay::dismissIfAny);
                            UI.post(() -> gotoServer());
                        }
                    }
                }
                int rc = p.waitFor();
                if (ORIGIN.get() == null && keepGoing) {
                    state("FAILED bootstrap exit " + rc);
                    autoRetry();
                }
            } catch (Throwable t) {
                if (!keepGoing && isInterrupted()) return;
                state("FAILED " + t);
                Log.w("OccAndroid", "host thread failed", t);
                autoRetry();
            }
        }

        /**
         * Copies the guest payload from APK assets into work/.
         * Gated on the app version so an update re-copies; env file always refreshed.
         */
        private void prepareWork(File base, File work) throws Exception {
            Files.createDirectories(Paths.get(work.getAbsolutePath()));
            String version = version();
            File marker = new File(work, ".payload_version");
            String current = marker.exists() ? new String(Files.readAllBytes(Paths.get(marker.getAbsolutePath())), StandardCharsets.UTF_8).trim() : "";
            if (!current.equals(version)) {
                // everything (bootstrap, server bundle, manifests, dist/) ships in ONE
                // archive — a single AssetManager.open(), like rootfs.occ / node.occ
                java.util.concurrent.atomic.AtomicInteger pf = new java.util.concurrent.atomic.AtomicInteger();
                TarUtil.extractTgz(act.getAssets().open("occ/guest-files.occ"), work,
                        n -> { if (pf.incrementAndGet() % 300 == 0) state("copying app payload… " + pf.get()); });
                new File(work, "bootstrap.sh").setExecutable(true, false);
                Files.write(Paths.get(marker.getAbsolutePath()), version.getBytes(StandardCharsets.UTF_8));
                state("payload copied (" + pf.get() + " files)");
            } else {
                state("payload up to date");
            }
            // the proot launcher (Termux bionic build + external loaders) must exist
            // under app storage regardless of payload-version (guarded, idempotent)
            File launcher = new File(base, "host/bin/proot");
            if (!launcher.exists() || !current.equals(version)) {
                copyAsset("occ/host/bin/proot", launcher, true);
                copyAsset("occ/host/libexec/proot/loader", new File(base, "host/libexec/proot/loader"), true);
                copyAsset("occ/host/libexec/proot/loader32", new File(base, "host/libexec/proot/loader32"), true);
                state("launcher ready");
            }
            new File(work, "data").mkdirs();
            new File(work, "data/tmp").mkdirs();
            // invalidate markers that refer to shipped payloads when payload changes
            if (!current.equals(version)) {
                File nodeMarker = new File(work, ".node_ok");
                if (!current.isEmpty()) nodeMarker.delete();   // re-extract node on payload update
            }
        }

        /**
         * The built-in resolv.conf uses 1.1.1.1 which some carriers blacklist.
         * Mirror the phone's active DNS (plus public fallbacks) into the guest.
         */
        /** Hard pre-flight: every asset the boot path depends on, opened and counted. */
        private void verifyAssets(File base, File work) throws Exception {
            String[] mustOpen = {
                    "occ/guest-files.occ",
                    "occ/guest/rootfs.occ",
                    "occ/guest/node.occ",
                    "occ/host/bin/proot",
                    "occ/host/libexec/proot/loader",
                    "occ/host/libexec/proot/loader32",
            };
            StringBuilder report = new StringBuilder();
            for (String a : mustOpen) {
                try (InputStream in = act.getAssets().open(a)) {
                    long total = 0;
                    byte[] buf = new byte[65536];
                    int n;
                    while (total < 65536 && (n = in.read(buf)) > 0) total += n;   // 64KB probe is enough
                    report.append("asset ok ").append(a).append(" (probe ").append(total / 1024).append(" KB)\n");
                } catch (Throwable t) {
                    report.append("asset MISSING ").append(a).append(" — ").append(t).append('\n');
                }
            }
            File[] mustExist = {
                    new File(work, "bootstrap.sh"),
                    new File(work, "guest-server.mjs"),
                    new File(work, "package.json"),
                    new File(work, "package-lock.json"),
                    new File(work, "dist/index.html"),
                    new File(base, "host/bin/proot"),
            };
            for (File f : mustExist) {
                if (!f.exists()) report.append("pre-flight FAILED: ").append(f.getAbsolutePath()).append('\n');
            }
            for (String line : report.toString().split("\n")) log(line);
        }

        private void fixDns(File rootDir) {
            try {
                StringBuilder dns = new StringBuilder();
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        act.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.Network nw = cm == null ? null : cm.getActiveNetwork();
                android.net.LinkProperties lp = nw == null ? null : cm.getLinkProperties(nw);
                if (lp != null) {
                    for (java.net.InetAddress a : lp.getDnsServers()) {
                        dns.append("nameserver ").append(a.getHostAddress()).append('\n');
                    }
                }
                dns.append("nameserver 1.1.1.1\n").append("nameserver 8.8.8.8\n");
                File etc = new File(rootDir, "etc");
                File f = new File(etc, "resolv.conf");
                if (etc.exists() || etc.mkdirs()) {
                    Files.write(Paths.get(f.getAbsolutePath()),
                            dns.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable t) {
                log("dns fixup said: " + t);
            }
        }

        private String version() {
            try {
                return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
            } catch (Throwable t) {
                return "0";
            }
        }

        private void copyAssetTree(String assetDir, File destDir) throws Exception {
            String[] list = act.getAssets().list(assetDir);
            if (list == null || list.length == 0) return;
            Files.createDirectories(Paths.get(destDir.getAbsolutePath()));
            for (String name : list) {
                String src = assetDir + "/" + name;
                File dst = new File(destDir, name);
                String[] nested = act.getAssets().list(src);
                if (nested != null && nested.length > 0) {
                    copyAssetTree(src, dst);
                } else {
                    copyAsset(src, dst, false);
                }
            }
        }

        private void copyAsset(String path, File dest, boolean exec) throws Exception {
            File parent = dest.getParentFile();
            if (parent != null) Files.createDirectories(Paths.get(parent.getAbsolutePath()));
            try (InputStream in = act.getAssets().open(path)) {
                Files.copy(in, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (exec) dest.setExecutable(true, false);
        }

        private String parseOrigin(String json) {
            try {
                org.json.JSONObject jo = new org.json.JSONObject(json);
                String o = jo.optString("origin", "");
                return o.isEmpty() ? null : o;
            } catch (Throwable t) {
                return null;
            }
        }

        private void autoRetry() {
            final int n = ++ATTEMPTS;
            if (n >= 2 || ORIGIN.get() != null) return;   // two tries max per app run
            log("retrying automatically (attempt " + (n + 1) + ")…");
            UI.postDelayed(() -> restartHost(), 2500);
        }

        private void state(String s) {
            log(s);
            final String msg = s;
            UI.post(() -> Overlay.stateIfAny(msg));
        }

        private void log(String line) {
            synchronized (LOG) {
                LOG.append(line).append('\n');
                if (LOG.length() > 12000) LOG.delete(0, 4000);
            }
            Log.i("OccAndroid", line);
        }
    }
}
