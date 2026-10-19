package online.openchatcut.android;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First-run / status overlay drawn above the bridge view: shows setup progress
 * and exposes the provider settings entry. Managed statically so it survives
 * host restarts.
 */
final class Overlay {
    private static LinearLayout root;
    private static TextView title;
    private static TextView detail;
    private static TextView logBox;
    private static Activity act;

    private Overlay() {}

    Overlay(Activity a) {
        attach(a);
    }

    Activity owning() { return act; }

    private static void attach(Activity a) {
        float d = a.getResources().getDisplayMetrics().density;
        act = a;
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (18 * d), (int) (22 * d), (int) (18 * d), (int) (16 * d));
        root.setBackgroundColor(Color.parseColor("#0B0E14"));
        root.setClickable(true);
        root.setFocusable(true);
        root.setGravity(Gravity.TOP);

        TextView brand = new TextView(a);
        brand.setText("OpenChatCut");
        brand.setTextSize(26);
        brand.setTextColor(Color.parseColor("#FF4D6D"));
        root.addView(brand);

        TextView sub = new TextView(a);
        sub.setText("Starting the editing engine — one-time setup.\nNo configuration, nothing to install by hand.");
        sub.setTextSize(12);
        sub.setTextColor(Color.parseColor("#8FA3BC"));
        sub.setPadding(0, (int) (4 * d), 0, 0);
        root.addView(sub);

        title = new TextView(a);
        title.setTextSize(14);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, (int) (16 * d), 0, 0);
        root.addView(title);

        detail = new TextView(a);
        detail.setTextSize(11);
        detail.setTextColor(Color.parseColor("#556277"));
        detail.setPadding(0, (int) (2 * d), 0, 0);
        root.addView(detail);

        ScrollView sc = new ScrollView(a);
        sc.setFillViewport(true);
        logBox = new TextView(a);
        logBox.setTextSize(9);
        logBox.setTextColor(Color.parseColor("#6B7E95"));
        logBox.setPadding(0, (int) (6 * d), 0, 0);
        sc.addView(logBox, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, (int) (6 * d), 0, 0);
        Button settings = new Button(a);
        settings.setText("NVIDIA / providers");
        settings.setTextColor(Color.WHITE);
        settings.setTextSize(12);
        settings.setAllCaps(false);
        settings.setBackground(UIUtil.chip(Color.parseColor("#232D3F")));
        settings.setOnClickListener(v -> OccAndroid.openProviderSettings(a));
        buttons.addView(settings, new LinearLayout.LayoutParams(-2, (int) (36 * d)));
        root.addView(buttons);

        if (!(a.findViewById(android.R.id.content) instanceof ViewGroup)) return;
        runOn(a, () -> {
            ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
            if (root.getParent() != content) {
                if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
                if (content != null) content.addView(root, new ViewGroup.LayoutParams(-1, -1));
            }
        });
    }

    void show() {
        if (act != null) refreshLog();
    }

    private static void refreshLog() {
        if (logBox == null) return;
        String s = OccAndroid.bootLog();
        int n = s.length();
        if (n > 2500) s = "…\n" + s.substring(n - 2500);
        logBox.setText(s);
    }

    static void stateIfAny(String msg) {
        if (title == null || act == null || act.isFinishing()) return;
        if (msg.startsWith("READY")) {
            title.setText("Engine online ✓  " + msg.substring(5).trim());
            detail.setText("Opening the editor…");
        } else if (msg.startsWith("FAILED")) {
            title.setText("Setup failed");
            detail.setText(msg);
        } else {
            title.setText(msg);
        }
        refreshLog();
    }

    static void dismissIfAny() {
        if (root == null) return;
        runOn(act, () -> {
            if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        });
    }

    static void failIfAny() {
        stateIfAny("FAILED — check the log; NVIDIA/providers can be edited below.");
    }

    private static void runOn(Activity a, Runnable r) {
        if (a == null) return;
        a.runOnUiThread(r);
    }
}
