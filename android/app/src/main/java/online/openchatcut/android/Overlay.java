package online.openchatcut.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Minimal boot-status chip pinned to the bottom edge while the Linux guest
 * starts. Disappears the instant upstream's server is serving. There is no
 * provider UI here — keys live in the app's own Settings → agent providers
 * list, exactly like Gemini/Claude/OpenAI/etc.
 */
final class Overlay {
    private static LinearLayout root;
    private static TextView text;
    private static View spinner;
    private static Activity act;

    private Overlay() {}

    Overlay(Activity a) {
        attach(a);
    }

    Activity owning() { return act; }

    private static void attach(final Activity a) {
        act = a;
        float d = a.getResources().getDisplayMetrics().density;
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(Color.parseColor("#B210141C"));
        root.setPadding((int) (16 * d), (int) (9 * d), (int) (16 * d), (int) (9 * d));
        root.setClickable(true);

        spinner = new View(a);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams((int) (14 * d), (int) (14 * d));
        sp.rightMargin = (int) (10 * d);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke((int) (2 * d), Color.parseColor("#7EE3C5"));
        ring.setColor(Color.TRANSPARENT);
        spinner.setBackground(ring);
        root.addView(spinner, sp);

        text = new TextView(a);
        text.setTextSize(12);
        text.setTextColor(Color.parseColor("#CFE0F0"));
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(text, new LinearLayout.LayoutParams(-1, -2));

        runOn(a, () -> {
            if (!(a.findViewById(android.R.id.content) instanceof ViewGroup)) return;
            ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
            if (root.getParent() != content) {
                if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
                if (content != null) {
                    content.addView(root, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                    if (spinner != null) {
                        AlphaAnimation pulse = new AlphaAnimation(0.25f, 1f);
                        pulse.setDuration(700);
                        pulse.setRepeatMode(AlphaAnimation.REVERSE);
                        pulse.setRepeatCount(AlphaAnimation.INFINITE);
                        spinner.startAnimation(pulse);
                    }
                }
            }
        });
    }

    void show() { }

    static void stateIfAny(String msg) {
        if (text == null || act == null || act.isFinishing()) return;
        final String s;
        if (msg.startsWith("FAILED")) {
            s = "engine setup failed — reopening the app retries: " + shortMsg(msg);
        } else if (msg.startsWith("rootfs …")) {
            s = "unpacking system files…";
        } else if (msg.startsWith("node …")) {
            s = "unpacking runtime…";
        } else if (msg.startsWith("installing deps") || msg.startsWith("OCC_LINE installing")) {
            s = "fetching runtime dependencies (one time)…";
        } else if (msg.contains("OCC_LINE")) {
            s = "starting engine…";
        } else if (msg.startsWith("Engine online")) {
            s = "done";
        } else {
            s = msg;
        }
        final boolean hide = msg.startsWith("READY") || msg.startsWith("Engine online");
        runOn(act, () -> {
            text.setText(s);
            if (hide) dismissIfAny();
        });
    }

    private static String shortMsg(String msg) {
        return msg.length() > 40 ? "see logcat (OccAndroid)." : msg;
    }

    static void dismissIfAny() {
        if (root == null) return;
        runOn(act, () -> {
            if (spinner != null) spinner.clearAnimation();
            if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        });
    }

    static void failIfAny() { stateIfAny("FAILED"); }

    private static void runOn(Activity a, Runnable r) {
        if (a == null || a.isFinishing()) return;
        a.runOnUiThread(r);
    }
}
