package online.openchatcut.android;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

/** Locates the Capacitor WebView without hard resource ids. */
final class WebViewUtil {
    private WebViewUtil() {}

    static WebView deepWebView(Activity a) {
        if (a == null) return null;
        View content = a.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            WebView found = scan((ViewGroup) content, 0);
            if (found != null) return found;
        }
        return null;
    }

    private static WebView scan(ViewGroup group, int depth) {
        if (depth > 8) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c instanceof WebView) return (WebView) c;
            if (c instanceof ViewGroup) {
                WebView r = scan((ViewGroup) c, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }
}

/** Formatting helpers (flat components, no androidx dependency). */
final class UIUtil {
    private UIUtil() {}

    static android.graphics.drawable.GradientDrawable box() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(android.graphics.Color.parseColor("#151B26"));
        g.setStroke(1, android.graphics.Color.parseColor("#2A3448"));
        g.setCornerRadius(10);
        return g;
    }

    static android.graphics.drawable.GradientDrawable chip(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(14);
        return g;
    }
}
