package online.openchatcut.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
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

    static final String PREFS = "occ_android";
    static final String KEY_NVIDIA_KEY = "nvidiaApiKey";
    static final String KEY_NVIDIA_URL = "nvidiaBaseUrl";
    static final String KEY_NVIDIA_MODEL = "nvidiaModel";
    static final String DEFAULT_NIM_URL = "https://integrate.api.nvidia.com/v1";
    static final String DEFAULT_NIM_MODEL = "nvidia/llama-3.3-nemotron-super-49b-v1.5";

    static final Handler UI = new Handler(Looper.getMainLooper());
    private static Host host;
    private static Overlay overlay;
    private static volatile WebView capWebView;
    private static volatile Activity activity;

    private OccAndroid() {}

    // ───────────────────────────────────────────────────────────── lifecycle
    static void bind(Activity a) {
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
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Writes /occ/.env.local — the exact keystore seeding file the desktop app uses. */
    static void writeEnvFile(File workDir, Context c) {
        SharedPreferences p = prefs(c);
        String key = p.getString(KEY_NVIDIA_KEY, "").trim();
        String url = p.getString(KEY_NVIDIA_URL, "").trim();
        String model = p.getString(KEY_NVIDIA_MODEL, "").trim();
        StringBuilder sb = new StringBuilder();
        if (!key.isEmpty()) {
            sb.append("LLM_PROVIDER=nvidia\n");
            sb.append("LLM_NVIDIA_API_KEY=").append(key).append('\n');
            sb.append("LLM_NVIDIA_BASE_URL=").append(url.isEmpty() ? DEFAULT_NIM_URL : url).append('\n');
            sb.append("LLM_NVIDIA_MODEL=").append(model.isEmpty() ? DEFAULT_NIM_MODEL : model).append('\n');
        }
        try {
            Files.write(Paths.get(new File(workDir, ".env.local").getAbsolutePath()),
                    sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w("OccAndroid", "env write failed", t);
        }
    }

    static void openProviderSettings(Activity a) {
        final SharedPreferences p = prefs(a);
        final android.widget.EditText keyIn = new android.widget.EditText(a);
        final android.widget.EditText urlIn = new android.widget.EditText(a);
        final android.widget.EditText modelIn = new android.widget.EditText(a);
        keyIn.setHint("nvapi-…");
        urlIn.setHint(DEFAULT_NIM_URL);
        modelIn.setHint(DEFAULT_NIM_MODEL);
        keyIn.setText(p.getString(KEY_NVIDIA_KEY, ""));
        urlIn.setText(p.getString(KEY_NVIDIA_URL, ""));
        modelIn.setText(p.getString(KEY_NVIDIA_MODEL, ""));
        keyIn.setSingleLine(true);
        urlIn.setSingleLine(true);
        modelIn.setSingleLine(true);

        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        float d = a.getResources().getDisplayMetrics().density;
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        box.addView(field(a, "NVIDIA NIM · API key", keyIn));
        box.addView(field(a, "Endpoint (URL)", urlIn));
        box.addView(field(a, "Model", modelIn));

        new AlertDialog.Builder(a)
                .setTitle("Provider — NVIDIA NIM")
                .setView(box)
                .setPositiveButton("save", (dlg, w) -> {
                    p.edit()
                            .putString(KEY_NVIDIA_KEY, keyIn.getText().toString().trim())
                            .putString(KEY_NVIDIA_URL, urlIn.getText().toString().trim())
                            .putString(KEY_NVIDIA_MODEL, modelIn.getText().toString().trim())
                            .apply();
                    dlg.dismiss();
                    android.widget.Toast.makeText(a, "saved — restarting server…", android.widget.Toast.LENGTH_SHORT).show();
                    restartHost();
                })
                .setNegativeButton("cancel", null)
                .show();
    }

    private static android.view.View field(Activity a, String label, android.widget.EditText in) {
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout v = new android.widget.LinearLayout(a);
        v.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView t = new android.widget.TextView(a);
        t.setText(label);
        t.setTextSize(11);
        t.setTextColor(Color.parseColor("#7EE3C5"));
        t.setPadding(0, (int) (10 * d), 0, (int) (3 * d));
        v.addView(t);
        in.setTextColor(Color.WHITE);
        in.setTextSize(13);
        in.setBackground(UIUtil.box());
        android.widget.LinearLayout.LayoutParams et =
                new android.widget.LinearLayout.LayoutParams(-1, -2);
        et.topMargin = (int) (2 * d);
        v.addView(in, et);
        return v;
    }

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

        private Host(Activity a) {
            setDaemon(true);
            setName("occ-host");
            act = a;
        }

        boolean alive() {
            return isAlive();
        }

        static String origin() { return ORIGIN.get(); }

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
                prepareWork(work);

                state("unpacking Debian rootfs (first run only)…");
                File rootfsMarker = new File(base, ".rootfs_ok");
                if (!rootfsMarker.exists()) {
                    TarUtil.extractTgz(act.getAssets().open("occ/guest/rootfs.tar.gz"), rootDir,
                            n -> state("rootfs …" + n));
                    Files.write(Paths.get(rootfsMarker.getAbsolutePath()), "ok".getBytes(StandardCharsets.UTF_8));
                } else {
                    state("rootfs already unpacked");
                }

                state("unpacking Node runtime…");
                File nodeMarker = new File(work, ".node_ok");
                if (!nodeMarker.exists()) {
                    TarUtil.extractTgz(act.getAssets().open("occ/guest/node.tar.gz"), nodeDir,
                            n -> state("node …" + n));
                    Files.write(Paths.get(nodeMarker.getAbsolutePath()), "ok".getBytes(StandardCharsets.UTF_8));
                }
                File nodeBin = new File(nodeDir, "bin/node");
                nodeBin.setExecutable(true, false);

                writeEnvFile(work, act);

                state("starting OpenChatCut server…");
                File proot = new File(base, "host/bin/proot");
                proot.setExecutable(true, false);
                new File(base, "host/libexec/proot/loader").setExecutable(true, false);
                new File(base, "host/libexec/proot/loader32").setExecutable(true, false);

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
                    state("FAILED: bootstrap exited (" + rc + ") — see /occ/data/npm.log in log below");
                    UI.post(() -> Overlay.failIfAny());
                }
            } catch (Throwable t) {
                if (!keepGoing) return;
                state("FAILED " + t);
                Log.w("OccAndroid", "host thread failed", t);
                UI.post(() -> Overlay.failIfAny());
            }
        }

        /**
         * Copies the guest payload from APK assets into work/.
         * Gated on the app version so an update re-copies; env file always refreshed.
         */
        private void prepareWork(File work) throws Exception {
            Files.createDirectories(Paths.get(work.getAbsolutePath()));
            String version = version();
            File marker = new File(work, ".payload_version");
            String current = marker.exists() ? new String(Files.readAllBytes(Paths.get(marker.getAbsolutePath())), StandardCharsets.UTF_8).trim() : "";
            if (!current.equals(version)) {
                copyAsset("occ/guest/bootstrap.sh", new File(work, "bootstrap.sh"), true);
                copyAsset("occ/guest/guest-server.mjs", new File(work, "guest-server.mjs"), false);
                copyAsset("occ/guest/package.json", new File(work, "package.json"), false);
                copyAsset("occ/guest/package-lock.json", new File(work, "package-lock.json"), false);
                copyAssetTree("occ/guest/dist", new File(work, "dist"));
                Files.write(Paths.get(marker.getAbsolutePath()), version.getBytes(StandardCharsets.UTF_8));
                state("payload copied");
            } else {
                state("payload up to date");
            }
            // always fresh: user provider settings feed this file
            writeEnvFile(work, act);
            new File(work, "data").mkdirs();
            new File(work, "data/tmp").mkdirs();
            // invalidate markers that refer to shipped payloads when payload changes
            if (!current.equals(version)) {
                File nodeMarker = new File(work, ".node_ok");
                if (!current.isEmpty()) nodeMarker.delete();   // re-extract node on payload update
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
