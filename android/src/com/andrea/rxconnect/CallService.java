package com.andrea.rxconnect;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.widget.RemoteViews;

/**
 * Runs only while you're in a room (from Join to leaving): keeps camera and mic alive in the background and shows the
 * "Calling on <room>" notification, whose Mute / Camera / Leave buttons press the page's own buttons.
 */
public class CallService extends Service {
    static final int ID = 1;
    static String room = "";
    static boolean muted, camOff;
    static long since;
    static boolean running;

    /** Join: start the service and its notification. */
    static void start(Context c, String room) {
        CallService.room = room;
        muted = camOff = false;
        since = System.currentTimeMillis();
        c.startForegroundService(new Intent(c, CallService.class));
    }

    /** Mute or camera changed (in the app or from the notification): redraw the buttons. */
    static void update(Context c, boolean muted, boolean camOff) {
        CallService.muted = muted;
        CallService.camOff = camOff;
        if (running) c.getSystemService(NotificationManager.class).notify(ID, build(c)); // never resurrect it after leaving
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String action = i == null ? null : i.getAction();
        if (action != null) { // a notification button
            MainActivity.press(action);
            if (!running) stopSelf(id); // a stale notification pressed outside a call
            return START_NOT_STICKY;
        }
        running = true;
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 14 refuses a type whose permission isn't granted, so declare only the granted ones.
            int type = 0;
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(ID, build(this), type);
        } else {
            startForeground(ID, build(this));
        }
        return START_NOT_STICKY;
    }

    static Notification build(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        // Default importance (not LOW): LOW puts it in the "Silent" section, which is always collapsed, hiding the big
        // buttons. No sound or vibration either way. A channel's importance can't change, hence a new id.
        nm.deleteNotificationChannel("call");
        NotificationChannel ch = new NotificationChannel("ongoing_call", "Ongoing call", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
        return new Notification.Builder(c, "ongoing_call")
                .setSmallIcon(R.drawable.ic_notify)
                // Title and text still matter: the lock screen and accessibility read them.
                .setContentTitle("Calling on " + room)
                .setContentText(muted ? "You're muted" : "Tap to go back to the call")
                .setContentIntent(PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true) // updates (mute, camera) never re-alert
                // Standard notification buttons are plain text; our own layout gives icon buttons that fill in when on.
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(view(c, R.layout.notification_call)) // collapsed: one line, small buttons
                .setCustomBigContentView(view(c, R.layout.notification_call_big)) // expanded: big buttons
                .build();
    }

    private static RemoteViews view(Context c, int layout) {
        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        v.setTextViewText(R.id.title, "Calling on " + room);
        long elapsed = System.currentTimeMillis() - since;
        v.setChronometer(R.id.timer, SystemClock.elapsedRealtime() - elapsed, muted ? "%s · muted" : null, true);
        toggle(c, v, R.id.mic, muted, R.drawable.ic_mic_off, R.drawable.ic_mic, muted ? "Unmute" : "Mute", button(c, "mic"));
        toggle(c, v, R.id.cam, camOff, R.drawable.ic_cam_off, R.drawable.ic_cam, camOff ? "Turn camera on" : "Turn camera off", button(c, "cam"));
        v.setOnClickPendingIntent(R.id.leave, button(c, "leave"));
        return v;
    }

    /** A round button: a thin ring normally, filled with the crossed-out icon when on (muted / camera off). */
    private static void toggle(Context c, RemoteViews v, int id, boolean on, int iconOn, int iconOff, String label, PendingIntent click) {
        v.setImageViewResource(id, on ? iconOn : iconOff);
        v.setInt(id, "setBackgroundResource", on ? R.drawable.btn_on : R.drawable.btn_ring);
        v.setInt(id, "setColorFilter", c.getColor(on ? R.color.btn_icon_on : R.color.btn_icon));
        v.setContentDescription(id, label);
        v.setOnClickPendingIntent(id, click);
    }

    /** {@code page}: the id of the page button it presses. */
    private static PendingIntent button(Context c, String page) {
        return PendingIntent.getService(c, page.hashCode(), new Intent(c, CallService.class).setAction(page), PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onDestroy() { running = false; }

    @Override public IBinder onBind(Intent i) { return null; }
}
