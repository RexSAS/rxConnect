package com.andrea.rxconnect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Home-screen widgets (mockup: "rxConnect widgets") and what they share with PushService:
 * Big (4x2): heart + who's in the room + Thinking of you + Wanna call?  Heart (2x2).  Board (2x2): your shared drawing.
 * Photo (2x2): their last photo to you (never yours); tap: see it, with "Send one".
 *
 * State, in the "push" preferences: room, hash (its SHA-256, what the server knows), name (yours), token (Firebase),
 * partner (their name), here (they're in the room), photoFrom / boardFrom ("" = you sent it), photoNew / boardNew.
 * Images: files photo.jpg and board.jpg, sent end-to-end encrypted with a key from the room name (seal in index.html).
 */
public class Widgets {
    static final String SERVER = "https://rxsas.it/rxconnect-push/";
    static final int PINK = 0xFFFF7AA8, MUTED = 0xFF8B91A1;

    public static class Big extends Provider {}
    public static class Heart extends Provider {}
    public static class Board extends Provider {}
    public static class Photo extends Provider {}

    public static class Provider extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { update(c); }
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("push", Context.MODE_PRIVATE); }

    static File file(Context c, String which) { return new File(c.getFilesDir(), which + ".jpg"); }

    /** Redraws every rxConnect widget from the saved state. */
    static void update(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        SharedPreferences p = prefs(c);
        String room = p.getString("room", ""), partner = p.getString("partner", "");
        boolean here = p.getBoolean("here", false) && !room.isEmpty();
        String who = partner.isEmpty() ? "rxConnect" : partner;
        // The heart: join when they're there, otherwise a nudge (no room yet: just open the app).
        PendingIntent heart = room.isEmpty() ? open(c, "") : here ? open(c, "join") : act(c, "nudge");
        int heartImg = here ? R.drawable.heart_on : R.drawable.heart_off;

        for (int id : m.getAppWidgetIds(new ComponentName(c, Big.class))) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_big);
            v.setImageViewResource(R.id.heart, heartImg);
            v.setTextViewText(R.id.name, who);
            v.setTextViewText(R.id.state, room.isEmpty() ? "Open rxConnect and join your room"
                    : here ? "In " + room + " now · tap to join" : "Not in the room · tap to nudge");
            v.setTextColor(R.id.state, here ? PINK : MUTED);
            v.setOnClickPendingIntent(R.id.heart, heart);
            v.setOnClickPendingIntent(R.id.name, heart);
            v.setOnClickPendingIntent(R.id.think, room.isEmpty() ? open(c, "") : act(c, "think"));
            // Set explicitly: launchers reapply onto the existing views, so the tap's pink "Sent" would otherwise stay.
            v.setInt(R.id.think, "setBackgroundResource", R.drawable.widget_btn);
            v.setTextViewText(R.id.think, "💭 Thinking of you");
            // She's already there: no need to ask, just join her.
            v.setTextViewText(R.id.call, here ? "📞 Wanna join?" : "📞 Wanna call?");
            v.setOnClickPendingIntent(R.id.call, open(c, here ? "join" : "call"));
            m.updateAppWidget(id, v);
        }
        for (int id : m.getAppWidgetIds(new ComponentName(c, Heart.class))) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_heart);
            v.setImageViewResource(R.id.heart, heartImg);
            v.setTextViewText(R.id.state, here ? who + " is here" : room.isEmpty() ? "rxConnect" : "Tap to nudge");
            v.setTextColor(R.id.state, here ? PINK : MUTED);
            v.setOnClickPendingIntent(R.id.root, heart);
            m.updateAppWidget(id, v);
        }
        picture(c, m, Photo.class, R.layout.widget_photo, "photo");
        picture(c, m, Board.class, R.layout.widget_board, "board");
    }

    static void picture(Context c, AppWidgetManager m, Class<?> provider, int layout, String which) {
        int[] ids = m.getAppWidgetIds(new ComponentName(c, provider));
        if (ids.length == 0) return;
        SharedPreferences p = prefs(c);
        String from = p.getString(which + "From", "");
        // The photo widget shows only theirs (a photo you sent before this rule stays hidden).
        Bitmap img = which.equals("photo") && from.isEmpty() ? null : thumb(file(c, which), 500);
        String cap = which.equals("photo") ? "from " + from
                : (from.isEmpty() ? "You drew something" : from + " drew something ✏️");
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), layout);
            if (img != null) v.setImageViewBitmap(R.id.image, img);
            v.setViewVisibility(R.id.image, img != null ? android.view.View.VISIBLE : android.view.View.GONE);
            v.setViewVisibility(R.id.empty, img != null ? android.view.View.GONE : android.view.View.VISIBLE);
            v.setViewVisibility(R.id.cap, img != null ? android.view.View.VISIBLE : android.view.View.GONE);
            v.setViewVisibility(R.id.dot, p.getBoolean(which + "New", false) ? android.view.View.VISIBLE : android.view.View.GONE);
            v.setTextViewText(R.id.cap, cap);
            v.setOnClickPendingIntent(R.id.root, open(c, which));
            m.updateAppWidget(id, v);
        }
    }

    /** The image scaled down to about max px a side (widgets have a memory limit for bitmaps). */
    static Bitmap thumb(File f, int max) {
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= max) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Opens the app for: join (the room), call (Wanna call?: tell them, then join), photo, board, or "" (just open). */
    static PendingIntent open(Context c, String what) {
        Intent i = new Intent(c, MainActivity.class).setAction("rx.open." + what).putExtra("what", what)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, ("open" + what).hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // Mutable (to our own receiver only): the launcher then adds where the tap was (getSourceBounds), for Nudge.sent.
    // nudge/think go to the Send service: Battery Saver and Doze cut a background receiver's internet, not a foreground service's.
    static PendingIntent act(Context c, String kind) {
        int f = PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT, code = ("act" + kind).hashCode();
        return "later".equals(kind) ? PendingIntent.getBroadcast(c, code, new Intent(c, Act.class).setAction(kind), f)
                : PendingIntent.getForegroundService(c, code, new Intent(c, Send.class).setAction(kind), f);
    }

    /** The widget hearts in pink for a moment: your nudge going out. update() puts them back. */
    static void flash(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (Class<?> k : new Class<?>[] {Big.class, Heart.class})
            for (int id : m.getAppWidgetIds(new ComponentName(c, k))) {
                RemoteViews v = new RemoteViews(c.getPackageName(), k == Big.class ? R.layout.widget_big : R.layout.widget_heart);
                v.setImageViewResource(R.id.heart, R.drawable.heart_on);
                m.partiallyUpdateAppWidget(id, v);
            }
    }

    /** "Later" on a "wanna call?" notification: dismiss it. */
    public static class Act extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if ("later".equals(i.getAction())) c.getSystemService(NotificationManager.class).cancel(PushService.CALL);
        }
    }

    /** Widget taps that don't open the app: nudge, think (Thinking of you). A short foreground service, for the internet. */
    public static class Send extends Service {
        @Override public IBinder onBind(Intent i) { return null; }

        @Override public int onStartCommand(Intent i, int flags, int startId) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("sending", "Sending from the widget", NotificationManager.IMPORTANCE_MIN));
            Notification n = new Notification.Builder(this, "sending").setSmallIcon(R.drawable.heart_on).setContentTitle("Sending…").build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            else startForeground(1, n);
            String kind = i == null ? null : i.getAction();
            if (!"nudge".equals(kind) && !"think".equals(kind)) { stopSelf(startId); return START_NOT_STICKY; }
            final Context app = getApplicationContext();
            if (kind.equals("nudge")) { // you see it go: hearts burst from the one you tapped
                flash(app);
            } else { // the button goes pink with "Sent", hearts fly up from it
                AppWidgetManager m = AppWidgetManager.getInstance(app);
                for (int id : m.getAppWidgetIds(new ComponentName(app, Big.class))) {
                    RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.widget_big);
                    v.setInt(R.id.think, "setBackgroundResource", R.drawable.widget_btn_pink);
                    v.setTextViewText(R.id.think, "💗 Sent");
                    m.partiallyUpdateAppWidget(id, v);
                }
            }
            Nudge.sent(app, i.getSourceBounds());
            Handler main = new Handler(Looper.getMainLooper());
            main.postDelayed(() -> update(app), 1200); // back to normal
            new Thread(() -> {
                boolean ok = send(app, kind);
                main.post(() -> {
                    Toast.makeText(app, !ok ? "Couldn't send: check your connection" : kind.equals("nudge") ? "💗 Nudge sent" : "💭 Sent",
                            Toast.LENGTH_SHORT).show();
                    stopSelf(startId);
                });
            }, "widget-send").start();
            return START_NOT_STICKY;
        }

        @Override public void onTimeout(int startId) { stopSelf(); } // shortService's 3 minutes: send() gives up long before
    }

    /** One of POST /send's kinds (push/worker.js) to the other phones of your room. Network: not on the main thread. */
    static boolean send(Context c, String kind) {
        SharedPreferences p = prefs(c);
        if (p.getString("hash", "").isEmpty()) return false;
        try {
            JSONObject o = new JSONObject().put("room", p.getString("hash", "")).put("token", p.getString("token", ""))
                    .put("kind", kind).put("name", p.getString("name", ""));
            return post("send", o.toString());
        } catch (JSONException e) {
            return false;
        }
    }

    static boolean post(String path, String json) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(SERVER + path).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(10000);
            h.setDoOutput(true);
            h.setRequestProperty("content-type", "application/json");
            try (OutputStream o = h.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
            return h.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    static byte[] get(String pathAndQuery) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(SERVER + pathAndQuery).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(20000);
        try (InputStream in = h.getInputStream()) {
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            h.disconnect();
        }
    }

    /** Opens an image sealed by seal() in index.html: AES-GCM, key SHA-256("rxconnect-key:" + room), 12-byte IV first. */
    static byte[] unseal(String room, byte[] box) throws GeneralSecurityException {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(("rxconnect-key:" + room).getBytes(StandardCharsets.UTF_8));
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, box, 0, 12));
        return ci.doFinal(box, 12, box.length - 12);
    }
}
