package online.openchatcut.android;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        OccAndroid.setWebView(bridge.getWebView());
        OccAndroid.bind(this);
        final Activity self = this;
        // Re-apply the Android skin after every routing change. Capacitor's own
        // WebViewClient is left untouched; this just refreshes our stylesheet.
        WebView wv = bridge.getWebView();
        if (wv != null) {
            wv.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        WebView v = bridge.getWebView();
                        if (v == null) return;
                        String url = String.valueOf(v.getUrl());
                        if (url.contains("127.0.0.1") || url.contains("localhost")) {
                            Skin.apply(self, v);
                        }
                        v.postDelayed(this, 500);
                    } catch (Throwable ignore) { }
                }
            }, 700);
        } else {
            OccAndroid.bind(this);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        OccAndroid.unbind(this);
    }
}
