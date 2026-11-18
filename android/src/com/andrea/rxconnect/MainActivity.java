package com.andrea.rxconnect;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.IOException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Hosts the shared call page (web/index.html, bundled as assets/web) in a WebView. */
public class MainActivity extends Activity {
    // Google's reserved host for serving app assets: gives the page an https origin, which camera/mic access requires.
    static final String HOST = "appassets.androidplatform.net";
    WebView web;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                try {
                    return new WebResourceResponse(URLConnection.guessContentTypeFromName(path), "utf-8",
                            getAssets().open("web" + path));
                } catch (IOException e) {
                    return null;
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    // Grant the page only what Android already granted us; the page falls back to audio-only.
                    List<String> ok = new ArrayList<>();
                    for (String res : req.getResources()) {
                        if (res.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && has(Manifest.permission.CAMERA)) ok.add(res);
                        if (res.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && has(Manifest.permission.RECORD_AUDIO)) ok.add(res);
                    }
                    if (ok.isEmpty()) req.deny(); else req.grant(ok.toArray(new String[0]));
                });
            }
        });
        web.addJavascriptInterface(new Bridge(), "RxNative");
        onScreen = this;

        List<String> ask = new ArrayList<>();
        ask.add(Manifest.permission.CAMERA);
        ask.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) ask.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(ask.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // Load only now, so the page's getUserMedia sees the final answer.
        if (has(Manifest.permission.CAMERA) || has(Manifest.permission.RECORD_AUDIO))
            startForegroundService(new Intent(this, CallService.class));
        web.loadUrl("https://" + HOST + "/index.html");
    }

    boolean has(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    // Back keeps the call going in the background; swiping the app away from recents ends it.
    @Override public void onBackPressed() { moveTaskToBack(true); }

    // --- Updates: the page shows the question and the bar (rxUpdate in index.html), Updater does the work ---

    static MainActivity onScreen;
    Updater.Release update;

    /** What the page can call as RxNative.*; these run on a binder thread, so the network work goes to its own. */
    class Bridge {
        @JavascriptInterface public void check() {
            new Thread(() -> {
                try {
                    Updater.Release r = Updater.newer(MainActivity.this);
                    if (r == null) return;
                    update = r;
                    js("rxUpdate.ask(" + r.code + ")");
                } catch (IOException e) {
                    android.util.Log.w("rxConnect", "update check", e);
                }
            }, "update-check").start();
        }

        @JavascriptInterface public void install() {
            final Updater.Release r = update;
            if (r == null) return;
            new Thread(() -> {
                try {
                    Updater.install(MainActivity.this, r, pct -> js("rxUpdate.progress(" + pct + ")"));
                } catch (IOException | RuntimeException e) {
                    updateFailed(String.valueOf(e.getMessage()));
                }
            }, "update").start();
        }
    }

    /** null: she backed out of the system's install screen. */
    static void updateFailed(String why) {
        MainActivity a = onScreen;
        if (a != null) a.js(why == null ? "rxUpdate.cancel()" : "rxUpdate.failed(" + JSONObject.quote(why) + ")");
    }

    void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    @Override protected void onDestroy() {
        if (onScreen == this) onScreen = null;
        stopService(new Intent(this, CallService.class));
        web.destroy();
        super.onDestroy();
    }
}
