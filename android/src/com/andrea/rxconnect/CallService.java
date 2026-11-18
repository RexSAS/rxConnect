package com.andrea.rxconnect;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps camera and mic alive while the app is in the background or the screen is off. Does nothing else. */
public class CallService extends Service {
    @Override public int onStartCommand(Intent i, int flags, int id) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("call", "Call", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "call")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("rxConnect is running")
                .setContentText("Tap to go back to the call")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 14 refuses a type whose permission isn't granted, so declare only the granted ones.
            int type = 0;
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(1, n, type);
        } else {
            startForeground(1, n);
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
