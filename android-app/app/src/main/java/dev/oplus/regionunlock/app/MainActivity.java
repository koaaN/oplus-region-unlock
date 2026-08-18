package dev.oplus.regionunlock.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WebView webView;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor("#080B12"));
        getWindow().setNavigationBarColor(Color.parseColor("#080B12"));

        webView = new WebView(this);
        WebView.setWebContentsDebuggingEnabled(false);
        webView.setBackgroundColor(Color.parseColor("#080B12"));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.removeJavascriptInterface("searchBoxJavaBridge_");
        webView.removeJavascriptInterface("accessibility");
        webView.removeJavascriptInterface("accessibilityTraversal");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        webView.addJavascriptInterface(new Bridge(), "RegionUnlock");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("RegionUnlock");
            webView.destroy();
        }
        super.onDestroy();
    }

    private void deliver(String callback, String result) {
        if (callback == null || !callback.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
            return;
        }
        runOnUiThread(() -> webView.evaluateJavascript(
                callback + "(" + JSONObject.quote(result) + ")", null));
    }

    public final class Bridge {
        @JavascriptInterface
        public String deviceInfo() {
            return RootOps.deviceInfo();
        }

        @JavascriptInterface
        public void rootCheck(String callback) {
            worker.execute(() -> deliver(callback, RootOps.rootCheck()));
        }

        @JavascriptInterface
        public void status(String callback) {
            worker.execute(() -> {
                String result = RootOps.status(MainActivity.this);
                getPreferences(MODE_PRIVATE).edit().putString("last_result", result).apply();
                deliver(callback, result);
            });
        }

        @JavascriptInterface
        public void policy(String callback) {
            worker.execute(() -> deliver(callback, RootOps.policy(MainActivity.this)));
        }

        @JavascriptInterface
        public void settings(String callback) {
            worker.execute(() -> deliver(callback, RootOps.settings(MainActivity.this)));
        }

        @JavascriptInterface
        public void unlock(String callback) {
            worker.execute(() -> {
                String result = RootOps.unlock(MainActivity.this);
                getPreferences(MODE_PRIVATE).edit().putString("last_result", result).apply();
                deliver(callback, result);
            });
        }

        @JavascriptInterface
        public void lock(String confirmation, String callback) {
            worker.execute(() -> {
                String result = RootOps.lock(MainActivity.this, confirmation);
                getPreferences(MODE_PRIVATE).edit().putString("last_result", result).apply();
                deliver(callback, result);
            });
        }

        @JavascriptInterface
        public String lastResult() {
            return getPreferences(MODE_PRIVATE).getString("last_result", "");
        }

        @JavascriptInterface
        public void copy(String value) {
            runOnUiThread(() -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Region Unlock log", value));
                Toast.makeText(MainActivity.this, "Log copied", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void reboot(String callback) {
            worker.execute(() -> {
                deliver(callback, "{\"ok\":true,\"message\":\"Reboot requested.\"}");
                try {
                    Thread.sleep(700L);
                    RootOps.reboot();
                } catch (Throwable error) {
                    try {
                        deliver(callback, new JSONObject()
                                .put("ok", false)
                                .put("message", "Could not reboot.")
                                .put("log", String.valueOf(error.getMessage()))
                                .toString());
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void setBrightMode(boolean bright) {
            runOnUiThread(() -> {
                int color = Color.parseColor(bright ? "#F4F6FB" : "#080B12");
                getWindow().setStatusBarColor(color);
                getWindow().setNavigationBarColor(color);
                int flags = bright
                        ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        : 0;
                getWindow().getDecorView().setSystemUiVisibility(flags);
            });
        }
    }
}
