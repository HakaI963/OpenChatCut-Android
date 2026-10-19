package online.openchatcut.android;

import android.content.Context;
import android.webkit.WebView;

import java.io.InputStream;

/** Injects the Android chrome/compactness stylesheet once per origin. */
final class Skin {
    private Skin() {}

    static void apply(Context c, WebView wv) {
        try (InputStream in = c.getAssets().open("occ/android-skin.css")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String css = bo.toString("UTF-8")
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n");
            String js = "(function(){try{\n"
                    + "  if(!/^http:\\/\\/(127\\.0\\.0\\.1|localhost)/.test(location.origin)) return;\n"
                    + "  var s=document.getElementById('occ-android-skin');\n"
                    + "  if(!s){s=document.createElement('style');s.id='occ-android-skin';(document.head||document.documentElement).appendChild(s);}\n"
                    + "  s.textContent='" + css + "';\n"
                    + "  var m=document.querySelector('meta[name=viewport]');\n"
                    + "  if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}\n"
                    + "  m.content='width=device-width, initial-scale=1, viewport-fit=cover';\n"
                    + "}catch(e){console.error('skin',e)}})();";
            wv.evaluateJavascript(js, null);
        } catch (Throwable t) {
            // skin is best-effort
        }
    }
}
