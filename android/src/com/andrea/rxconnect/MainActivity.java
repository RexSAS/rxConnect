package com.andrea.rxconnect;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
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
                stopSpeaker();
                unroute();
                calling = false;
                autoPip(false);
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
            // The chat's photo/video button: WebView shows no file picker by itself. capture (the photo widget's
            // "Send one"): the phone's camera app, writing the full-size photo into shot.jpg through Shot.
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (pick != null) pick.onReceiveValue(null);
                pick = cb;
                shooting = params.isCaptureEnabled();
                Intent i = params.createIntent();
                if (shooting) {
                    Shot.file(MainActivity.this).delete();
                    i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, Uri.parse("content://" + getPackageName() + ".shot/shot.jpg"))
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                try {
                    startActivityForResult(i, PICK);
                } catch (android.content.ActivityNotFoundException | SecurityException e) { // no camera app, or camera denied
                    pick = null;
                    return false;
                }
                return true;
            }

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
        // This phone's Firebase address, for "someone's in your room" (PushService): the page passes it to the push
        // server with your room (announce in index.html).
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken().addOnSuccessListener(t -> {
            pushToken = t;
            Widgets.prefs(this).edit().putString("token", t).apply(); // the widgets send with it too
            js("window.rxPushReady && rxPushReady()");
        });

        List<String> ask = new ArrayList<>();
        ask.add(Manifest.permission.CAMERA);
        ask.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) ask.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(ask.toArray(new String[0]), 1);
    }

    static final int PICK = 2;
    ValueCallback<Uri[]> pick;
    boolean shooting;

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code != PICK || pick == null) return;
        java.io.File shot = Shot.file(this);
        pick.onReceiveValue(!shooting ? WebChromeClient.FileChooserParams.parseResult(result, data)
                : result == RESULT_OK && shot.length() > 0 ? new Uri[] {Uri.fromFile(shot)} : null);
        pick = null;
    }

    /** Where the camera app writes the photo you take (shot.jpg in the app's cache): it can't reach our files otherwise. */
    public static class Shot extends android.content.ContentProvider {
        static java.io.File file(Context c) { return new java.io.File(c.getCacheDir(), "shot.jpg"); }
        @Override public boolean onCreate() { return true; }
        @Override public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            return android.os.ParcelFileDescriptor.open(file(getContext()), android.os.ParcelFileDescriptor.parseMode(mode));
        }
        @Override public String getType(Uri uri) { return "image/jpeg"; }
        @Override public android.database.Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public Uri insert(Uri u, android.content.ContentValues v) { return null; }
        @Override public int delete(Uri u, String s, String[] a) { return 0; }
        @Override public int update(Uri u, android.content.ContentValues v, String s, String[] a) { return 0; }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != 1) { js("window.rxCheck && rxCheck()"); return; } // asked again from the setup check
        // Load only now, so the page's getUserMedia sees the final answer.
        String what = opened(getIntent());
        web.loadUrl("https://" + HOST + "/index.html" + (what == null ? "" : "?open=" + what) + linkHash(getIntent()));
    }

    /** What a widget or notification asked to open (Widgets.open): join, call, photo, board; null otherwise. */
    String opened(Intent i) {
        // Reopened from Recents, Android hands back the old widget intent: not a new tap.
        if (i == null || (i.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null;
        String what = i.getStringExtra("what");
        i.removeExtra("what"); // used once: an activity recreated (rotation, theme) gets getIntent() again
        if (!"join".equals(what) && !"call".equals(what) && !"photo".equals(what) && !"board".equals(what)) return null;
        if (what.equals("photo") || what.equals("board")) { // seen: the widget's "new" dot goes
            Widgets.prefs(this).edit().putBoolean(what + "New", false).apply();
            Widgets.update(this);
        }
        getSystemService(android.app.NotificationManager.class).cancel(PushService.CALL);
        return what;
    }

    /** "#room" (URL-encoded) from an invite link rxconnect://join#room, or "" for a normal start. */
    static String linkHash(Intent i) {
        Uri u = i == null ? null : i.getData();
        String f = u == null ? null : u.getEncodedFragment();
        return f == null || f.isEmpty() ? "" : "#" + f;
    }

    // An invite link while the app is open (singleTask): the page fills in the room, unless you're in a call.
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String h = linkHash(i);
        if (!h.isEmpty()) js("window.rxOpenRoom && rxOpenRoom(" + JSONObject.quote(h.substring(1)) + ")");
        String what = opened(i);
        if (what != null) js("window.rxOpen && rxOpen('" + what + "')");
    }

    // Back from the settings the setup check opened: check again (the page closes its dialog once all is set).
    @Override protected void onResume() {
        super.onResume();
        js("window.rxCheck && rxCheck()");
    }

    // --- Setup check: what the app needs to keep a call going when you leave it. The page asks at start (so also
    // after an update) and shows a dialog for whatever is missing; fix() asks for it or opens the right settings. ---

    /** The floating-window switch for this app (Settings > Apps > rxConnect > Picture-in-picture), off on some phones. */
    boolean pipAllowed() {
        return getSystemService(AppOpsManager.class).checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                getApplicationInfo().uid, getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    String missing() {
        org.json.JSONArray m = new org.json.JSONArray();
        if (!pipAllowed()) m.put("pip");
        if (!has(Manifest.permission.CAMERA)) m.put("camera");
        if (!has(Manifest.permission.RECORD_AUDIO)) m.put("mic");
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) m.put("notifications");
        if (!Settings.canDrawOverlays(this)) m.put("overlay"); // the nudge's heart over other apps (Nudge)
        return m.toString();
    }

    void fix(String what) {
        String perm = what.equals("camera") ? Manifest.permission.CAMERA : what.equals("mic") ? Manifest.permission.RECORD_AUDIO
                : what.equals("notifications") ? "android.permission.POST_NOTIFICATIONS" : null;
        // Android still asks for it itself, unless it was refused for good: then only the app's settings can.
        if (perm != null && shouldShowRequestPermissionRationale(perm)) { requestPermissions(new String[] {perm}, 3); return; }
        Uri app = Uri.parse("package:" + getPackageName());
        if (what.equals("overlay")) { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, app)); return; }
        try {
            startActivity(new Intent(what.equals("pip") ? "android.settings.PICTURE_IN_PICTURE_SETTINGS" : Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
        } catch (android.content.ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
        }
    }

    boolean has(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    // Back keeps the call going in the background; swiping the app away from recents ends it.
    // The page first: back closes an open photo or the chat (rxBack in index.html). Only then the call goes to the
    // floating window, or the app to the background.
    @Override public void onBackPressed() {
        web.evaluateJavascript("window.rxBack ? rxBack() : false", handled -> { if (!"true".equals(handled) && !pip()) moveTaskToBack(true); });
    }

    // --- Floating window: leaving the app during a call shrinks it to picture-in-picture, like WhatsApp ---

    boolean calling; // in a room: set by inCall, cleared when the page reloads on leaving

    // In a room the phone is in call mode, so the volume keys would change the call volume, but her voice (and
    // everything the app plays) is media: point them at that.
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (!calling || (k != KeyEvent.KEYCODE_VOLUME_UP && k != KeyEvent.KEYCODE_VOLUME_DOWN)) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN)
            getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    k == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        return true;
    }

    // Android 12+: auto-enter (autoPip) is what reliably catches the home gesture; older ones get this.
    @Override protected void onUserLeaveHint() { if (Build.VERSION.SDK_INT < 31) pip(); }

    /** In a call: leaving the app (home, gesture, another app) turns it into the floating window by itself. */
    void autoPip(boolean on) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            setPictureInPictureParams(new PictureInPictureParams.Builder().setAspectRatio(new Rational(3, 4)).setAutoEnterEnabled(on).build());
        } catch (IllegalStateException e) { // floating window switched off for the app: the setup check asks for it
        }
    }

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
        js("document.body.classList.toggle('pip'," + on + "); $('call').classList.remove('swap'); closeViewer()"); // the small window shows her
    }

    // --- Updates: the page shows the question and the bar (rxUpdate in index.html), Updater does the work ---

    static MainActivity onScreen;
    volatile String pushToken = "";
    Updater.Release update;

    /** What the page can call as RxNative.*; these run on a binder thread, so the network work goes to its own. */
    class Bridge {
        /** What's missing for calls in the background, as JSON ["pip", "camera", "mic", "notifications"]. */
        @JavascriptInterface public String missing() { return MainActivity.this.missing(); }

        @JavascriptInterface public void fix(String what) { runOnUiThread(() -> MainActivity.this.fix(what)); }

        /** This phone's Firebase token, "" until Firebase has it (then the page gets rxPushReady()). */
        @JavascriptInterface public String pushToken() { return pushToken; }

        /** Your room, its hash and your name, for the widgets and notifications (Widgets, PushService). */
        @JavascriptInterface public void pushRoom(String room, String hash, String name) {
            SharedPreferences p = Widgets.prefs(MainActivity.this);
            if (!room.equals(p.getString("room", ""))) // another room: what was there belongs to the old one
                p.edit().remove("partner").remove("here").remove("photoFrom").remove("boardFrom").apply();
            p.edit().putString("room", room).putString("hash", hash).putString("name", name).apply();
            Widgets.update(MainActivity.this);
        }

        /** Whether someone is in your room, as the home screen just saw it: fixes the widget if a "gone" never came. */
        @JavascriptInterface public void roomHere(String room, boolean here) {
            SharedPreferences p = Widgets.prefs(MainActivity.this);
            if (!room.equals(p.getString("room", "")) || p.getBoolean("here", false) == here) return;
            p.edit().putBoolean("here", here).apply();
            Widgets.update(MainActivity.this);
        }

        /** The saved board, or their last photo, as a data: URL ("" if none) (openSnap in index.html). */
        @JavascriptInterface public String image(String which) {
            if (!which.equals("board") && Widgets.prefs(MainActivity.this).getString("photoFrom", "").isEmpty()) return "";
            java.io.File f = Widgets.file(MainActivity.this, which.equals("board") ? "board" : "photo");
            try {
                return f.exists() ? "data:image/jpeg;base64," + android.util.Base64.encodeToString(java.nio.file.Files.readAllBytes(f.toPath()), android.util.Base64.NO_WRAP) : "";
            } catch (IOException e) {
                return "";
            }
        }

        /** The board you just drew (JPEG, base64), for your own widget. Not your photos: that widget shows only theirs. */
        @JavascriptInterface public void saveImage(String which, String base64) {
            if (!which.equals("board")) return;
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(Widgets.file(MainActivity.this, which))) {
                f.write(android.util.Base64.decode(base64, android.util.Base64.DEFAULT));
            } catch (IOException e) {
                return;
            }
            Widgets.prefs(MainActivity.this).edit().putString(which + "From", "").putBoolean(which + "New", false).apply();
            Widgets.update(MainActivity.this);
        }

        /** The Invite button: Android's share sheet (WebView has no navigator.share). */
        @JavascriptInterface public void share(String text) {
            runOnUiThread(() -> startActivity(Intent.createChooser(
                    new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)));
        }

        /** Battery as JSON {level: 0-100, charging}, for the low-battery popup in a call (rxBattery in index.html). */
        @JavascriptInterface public String battery() {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); // the last broadcast, no receiver kept
            if (i == null) return "null";
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            return level < 0 ? "null" : "{\"level\":" + level * 100 / scale + ",\"charging\":" + charging + "}";
        }

        /** This app's version (the release number N, see build.sh), for the settings. */
        @JavascriptInterface public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                return "?";
            }
        }

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

        /**
         * Whether the page should use the app's own mic (nativeMic) for this call, given the speaker chosen in the
         * settings ("" automatic, "speaker", "earpiece" or "headphones"): yes when sound goes to headphones, which
         * keeps Bluetooth ones in their good-sound profile and can't echo. Speaker and earpiece need the WebView mic,
         * whose call mode gives the phone's echo cancelling the sound and lets route() pick the speaker.
         */
        @JavascriptInterface public boolean ownMic(String out) {
            return !out.equals("earpiece") && headphonesName() != null;
        }

        /**
         * "Phone speaker" chosen while Bluetooth (or other headphones) is connected: her voice plays on the phone
         * (Player, through startSpeaker) and other apps' music stays on the Bluetooth speaker. Also implies ownMic.
         */
        @JavascriptInterface public boolean phoneSpeaker(String out) {
            return out.equals("speaker") && headphonesName() != null;
        }

        /** Starts Player; its port reaches the page posted as a "speaker" message. */
        @JavascriptInterface public void startSpeaker() { runOnUiThread(MainActivity.this::startSpeaker); }

        /** Back to automatic in a call (settings): her voice to the headphones again, through the WebView. */
        @JavascriptInterface public void stopSpeaker() { runOnUiThread(MainActivity.this::stopSpeaker); }

        /** The speakers the settings offer, as JSON [{id, name}]; the id comes back through ownMic and route. */
        @JavascriptInterface public String outputs() {
            org.json.JSONArray list = new org.json.JSONArray();
            try {
                list.put(new JSONObject().put("id", "speaker").put("name", "Phone speaker"));
                for (AudioDeviceInfo d : getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        list.put(new JSONObject().put("id", "earpiece").put("name", "Earpiece"));
                        break;
                    }
                String h = headphonesName();
                if (h != null) list.put(new JSONObject().put("id", "headphones").put("name", h));
            } catch (org.json.JSONException e) {
                throw new IllegalStateException(e);
            }
            return list.toString();
        }

        /** In a call on the WebView mic (call mode): sends the sound to the chosen phone speaker or earpiece. */
        @JavascriptInterface public void route(String out) { runOnUiThread(() -> MainActivity.this.route(out)); }

        @JavascriptInterface public void stopMic() { runOnUiThread(MainActivity.this::stopMic); }

        /** Joined a room: the call notification appears (CallService); it goes when the page reloads on leaving. */
        @JavascriptInterface public void inCall(String room) { runOnUiThread(() -> { calling = true; autoPip(true); CallService.start(MainActivity.this, room); }); }

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
        // Not when her voice plays on the phone speaker (Player): the drop is on the Bluetooth music, not her.
        if (player == null) js("window.rxVoiceBoost && rxVoiceBoost(" + boost(am, v, musicDuring) + ")");
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

    /** Name of the connected headphones (Bluetooth, wired or USB), or null: sound goes to the phone itself. */
    String headphonesName() {
        for (AudioDeviceInfo d : getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: case 26 /* TYPE_BLE_HEADSET, Android 12+ */:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                    return d.getProductName().length() > 0 ? d.getProductName().toString() : "Headphones";
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    return "Wired headphones";
            }
        return null;
    }

    Player player;

    void startSpeaker() {
        stopSpeaker();
        WebMessagePort[] ports = web.createWebMessageChannel();
        player = new Player(getSystemService(AudioManager.class), ports[0]);
        web.postWebMessage(new WebMessage("speaker", new WebMessagePort[] {ports[1]}), Uri.parse("https://" + HOST));
    }

    void stopSpeaker() {
        if (player != null) player.stop();
        player = null;
    }

    boolean routed;

    void route(String out) {
        int type = out.equals("speaker") ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                : out.equals("earpiece") ? AudioDeviceInfo.TYPE_BUILTIN_EARPIECE : -1;
        if (type < 0) { // automatic / headphones: the phone's own routing (in a call, switching back from a choice)
            unroute();
            return;
        }
        AudioManager am = getSystemService(AudioManager.class);
        if (Build.VERSION.SDK_INT >= 31) {
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices())
                if (d.getType() == type) routed = am.setCommunicationDevice(d);
        } else {
            am.setSpeakerphoneOn(type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            routed = true;
        }
    }

    void unroute() {
        if (!routed) return;
        AudioManager am = getSystemService(AudioManager.class);
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice(); else am.setSpeakerphoneOn(false);
        routed = false;
    }

    void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    @Override protected void onDestroy() {
        if (onScreen == this) onScreen = null;
        if (calling) { // swiped away in a room: the page can't say goodbye any more, so the other phone hears it here
            Context app = getApplicationContext();
            new Thread(() -> Widgets.send(app, "gone"), "gone").start();
        }
        duck(false);
        stopMic();
        stopSpeaker();
        unroute();
        stopService(new Intent(this, CallService.class));
        web.destroy();
        super.onDestroy();
    }
}
