package com.andrea.rxconnect;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
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
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) { // left the call (page reload)
                stopMic();
                calling = false;
                stopService(new Intent(MainActivity.this, CallService.class));
            }

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
        web.addJavascriptInterface(new AudioBridge(), "RxAudio");
        onScreen = this;

        List<String> ask = new ArrayList<>();
        ask.add(Manifest.permission.CAMERA);
        ask.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) ask.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(ask.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // Load only now, so the page's getUserMedia sees the final answer.
        web.loadUrl("https://" + HOST + "/index.html");
    }

    boolean has(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    // Back keeps the call going in the background; swiping the app away from recents ends it.
    @Override public void onBackPressed() { if (!pip()) moveTaskToBack(true); }

    // --- Floating window: leaving the app during a call shrinks it to picture-in-picture, like WhatsApp ---

    boolean calling; // in a room: set by inCall, cleared when the page reloads on leaving

    @Override protected void onUserLeaveHint() { pip(); }

    boolean pip() {
        if (!calling || isInPictureInPictureMode()) return false;
        try {
            return enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(3, 4)).build());
        } catch (IllegalStateException e) { // PiP turned off for this app in system settings
            return false;
        }
    }

    // The page hides its controls in the small window (.pip in index.html).
    @Override public void onPictureInPictureModeChanged(boolean on, Configuration c) {
        super.onPictureInPictureModeChanged(on, c);
        js("document.body.classList.toggle('pip'," + on + ")");
    }

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

    // --- Audio: the mic is recorded natively (see Mic) and other apps are turned down during a call ---

    /** What the page can call as RxAudio.*. */
    class AudioBridge {
        /** While a call is live, other apps' audio (music, videos) is turned down instead of stopped. */
        @JavascriptInterface public void callActive(boolean on) { runOnUiThread(() -> duck(on)); }

        /** Starts the native mic; its chunks reach the page through a message port posted as a "mic" message. */
        @JavascriptInterface public boolean startMic(String device) {
            if (!has(Manifest.permission.RECORD_AUDIO)) return false;
            runOnUiThread(() -> MainActivity.this.startMic(device));
            return true;
        }

        /** The mics the settings can offer, as JSON [{id, name}]; the id goes back through startMic. */
        @JavascriptInterface public String inputs() {
            org.json.JSONArray list = new org.json.JSONArray();
            for (AudioDeviceInfo d : getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                String name = Mic.label(d);
                if (name == null) continue;
                try {
                    list.put(new JSONObject().put("id", String.valueOf(d.getId())).put("name", name));
                } catch (org.json.JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
            return list.toString();
        }

        /** Sound goes to headphones (Bluetooth, wired or USB), so the phone speaker can't echo into the mic. */
        @JavascriptInterface public boolean headphones() {
            for (AudioDeviceInfo d : getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                switch (d.getType()) {
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET: case AudioDeviceInfo.TYPE_USB_HEADSET:
                    case 26 /* TYPE_BLE_HEADSET, Android 12+ */:
                        return true;
                }
            return false;
        }

        @JavascriptInterface public void stopMic() { runOnUiThread(MainActivity.this::stopMic); }

        /** Joined a room: the call notification appears (CallService); it goes when the page reloads on leaving. */
        @JavascriptInterface public void inCall(String room) { runOnUiThread(() -> { calling = true; CallService.start(MainActivity.this, room); }); }

        @JavascriptInterface public void callState(boolean muted, boolean camOff) {
            runOnUiThread(() -> CallService.update(MainActivity.this, muted, camOff));
        }
    }

    /** A notification button: press the same button in the page ("mic", "cam" or "leave"). */
    static void press(String button) {
        MainActivity a = onScreen;
        if (a != null && button.matches("mic|cam|leave")) a.js("$('" + button + "').click()");
    }

    Mic mic;

    void startMic(String device) {
        stopMic();
        WebMessagePort[] ports = web.createWebMessageChannel();
        web.postWebMessage(new WebMessage("mic", new WebMessagePort[] {ports[1]}), Uri.parse("https://" + HOST));
        try {
            // Ports must be used on the UI thread; 50 small posts a second is nothing for it.
            mic = new Mic(getSystemService(AudioManager.class), device, chunk -> runOnUiThread(() -> ports[0].postMessage(new WebMessage(chunk))));
        } catch (RuntimeException e) {
            android.util.Log.w("rxConnect", "mic", e);
            ports[0].close();
        }
    }

    void stopMic() {
        if (mic != null) mic.stop();
        mic = null;
    }

    AudioFocusRequest focus;

    void duck(boolean on) {
        AudioManager am = getSystemService(AudioManager.class);
        if (focus == null) focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build();
        if (on) {
            am.requestAudioFocus(focus); // asks other apps to duck; Spotify ignores it here (see lowerOthers)
            lowerOthers(am);
        } else {
            am.abandonAudioFocusRequest(focus);
            restoreOthers(am);
        }
    }

    int musicBefore = -1, musicDuring;

    /**
     * Android doesn't actually duck Spotify on this phone (it plays through AAudio, which the system can't turn down),
     * so: media volume 2 steps down for the call, and her voice boosted in the page by the same amount, so only the
     * other apps end up quieter.
     */
    void lowerOthers(AudioManager am) {
        if (musicBefore >= 0) return;
        int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (v <= 2) return; // already quiet
        musicBefore = v;
        musicDuring = v - 2;
        am.setStreamVolume(AudioManager.STREAM_MUSIC, musicDuring, 0);
        js("window.rxVoiceBoost && rxVoiceBoost(" + boost(am, v, musicDuring) + ")");
    }

    void restoreOthers(AudioManager am) {
        if (musicBefore < 0) return;
        // Put it back, unless you changed the volume yourself during the call: then your choice stays.
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == musicDuring)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, musicBefore, 0);
        musicBefore = -1;
        js("window.rxVoiceBoost && rxVoiceBoost(1)");
    }

    /** How much louder her voice must be to sound the same after the drop; ×2 (about 2 typical steps) if unknown. */
    static float boost(AudioManager am, int from, int to) {
        if (Build.VERSION.SDK_INT >= 28) {
            int out = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) out = d.getType();
            try {
                float db = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, from, out) - am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, to, out);
                if (db > 0 && db < 20) return (float) Math.pow(10, db / 20);
            } catch (RuntimeException e) {
                // fall through to the estimate
            }
        }
        return 2f;
    }

    /** null: the install was cancelled from the system's install screen. */
    static void updateFailed(String why) {
        MainActivity a = onScreen;
        if (a != null) a.js(why == null ? "rxUpdate.cancel()" : "rxUpdate.failed(" + JSONObject.quote(why) + ")");
    }

    void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    @Override protected void onDestroy() {
        if (onScreen == this) onScreen = null;
        duck(false);
        stopMic();
        stopService(new Intent(this, CallService.class));
        web.destroy();
        super.onDestroy();
    }
}
