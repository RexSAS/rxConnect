package com.andrea.rxconnect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Base64;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Map;

/**
 * Messages from the other phone of your room, through the push server (../push) and Firebase. They carry only a kind,
 * the sender's name and, for images, which one; the room name stays on the phones (it's the images' key too).
 *   here / gone: they joined / left the room (widgets, and "<name> is online in <room> room 💗", their note below)
 *   nudge: the heart on your screen (Nudge)    think: "<name> is thinking of you 💗"
 *   call: "<name> wants to call 💗" with Join / Later    image: a photo or the board, fetched and decrypted here
 */
public class PushService extends FirebaseMessagingService {
    static final int ONLINE = 1, CALL = 2, LOVE = 3, PHOTO = 4, BOARD = 5;

    @Override public void onMessageReceived(RemoteMessage msg) {
        Map<String, String> d = msg.getData();
        String kind = d.containsKey("kind") ? d.get("kind") : "here";
        String name = d.get("name");
        if (name == null || name.trim().isEmpty()) name = "Someone";
        SharedPreferences p = Widgets.prefs(this);
        if (!name.equals("Someone")) p.edit().putString("partner", name).apply();
        String room = p.getString("room", "your");
        switch (kind) {
            case "here":
                p.edit().putBoolean("here", true).apply();
                Widgets.update(this);
                // Joining after "wants to call": that notification already says it, so no second one.
                if (!inCall() && !showing(CALL)) note(this, ONLINE, name + " is online in " + room + " room 💗", d.get("text"), Widgets.open(this, "join"), null);
                break;
            case "gone":
                p.edit().putBoolean("here", false).apply();
                Widgets.update(this);
                getSystemService(NotificationManager.class).cancel(ONLINE);
                break;
            case "nudge":
                Nudge.show(this, name);
                break;
            case "think":
                love(this, name + " is thinking of you 💗");
                break;
            case "call":
                // Already in the room (e.g. still on "Tell them you're here", so her widget didn't know): she's joining you.
                if (inCall()) break;
                Notification.Action join = new Notification.Action.Builder(null, "Join", Widgets.open(this, "join")).build();
                Notification.Action later = new Notification.Action.Builder(null, "Later", Widgets.act(this, "later")).build();
                note(this, CALL, name + " wants to call 💗", "Tap to join " + room, Widgets.open(this, "join"), null, join, later);
                break;
            case "image":
                image(d.get("which"), name);
                break;
        }
    }

    boolean showing(int id) {
        for (android.service.notification.StatusBarNotification s : getSystemService(NotificationManager.class).getActiveNotifications())
            if (s.getId() == id) return true;
        return false;
    }

    static boolean inCall() {
        MainActivity a = MainActivity.onScreen;
        return a != null && a.calling;
    }

    /** A photo or the board: fetched from the server, decrypted with the room name, shown on the widget. */
    void image(String which, String name) {
        if (!"photo".equals(which) && !"board".equals(which)) return;
        SharedPreferences p = Widgets.prefs(this);
        try {
            byte[] sealed = Base64.decode(new String(Widgets.get("image?room=" + p.getString("hash", "") + "&which=" + which),
                    StandardCharsets.UTF_8), Base64.DEFAULT);
            byte[] jpeg = Widgets.unseal(p.getString("room", ""), sealed);
            try (FileOutputStream f = new FileOutputStream(Widgets.file(this, which))) { f.write(jpeg); }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            android.util.Log.w("rxConnect", "image " + which, e);
            return;
        }
        p.edit().putString(which + "From", name).putBoolean(which + "New", true).apply();
        Widgets.update(this);
        boolean photo = which.equals("photo");
        note(this, photo ? PHOTO : BOARD, photo ? "📷 " + name + " sent you a photo" : "✏️ " + name + " drew something for you",
                null, Widgets.open(this, which), Widgets.thumb(Widgets.file(this, which), 720));
    }

    /** The quiet ones: thinking of you, and a nudge when it can't be shown over other apps. */
    static void love(Context c, String text) {
        note(c, LOVE, text, null, Widgets.open(c, ""), null);
    }

    static void note(Context c, int id, String title, String text, PendingIntent tap, Bitmap picture, Notification.Action... actions) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("room", "Your person", NotificationManager.IMPORTANCE_HIGH));
        Notification.Builder n = new Notification.Builder(c, "room")
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentIntent(tap)
                .setAutoCancel(true);
        if (text != null && !text.isEmpty()) n.setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)); // a long note unfolds
        if (picture != null) n.setLargeIcon(picture).setStyle(new Notification.BigPictureStyle().bigPicture(picture).bigLargeIcon((android.graphics.drawable.Icon) null));
        for (Notification.Action a : actions) n.addAction(a);
        nm.notify(id, n.build());
    }

    // A new token reaches the server the next time the app starts (MainActivity.pushToken).
    @Override public void onNewToken(String token) {}
}
