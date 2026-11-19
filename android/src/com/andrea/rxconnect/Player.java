package com.andrea.rxconnect;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;

/**
 * Plays her voice on the phone's own speaker while other apps keep playing on Bluetooth (the "Phone speaker" setting
 * with a Bluetooth speaker connected). The WebView can't pick a speaker without call mode, and call mode would pull
 * the music off Bluetooth, so the page sends her voice here instead: 20 ms chunks of 48 kHz mono 16-bit PCM as base64
 * on a message port, the reverse of Mic.
 */
final class Player {
    static final int RATE = 48000, CUSHION = 4; // chunks buffered before playing starts: 80 ms against bursty delivery

    private final AudioTrack track;
    private final HandlerThread thread = new HandlerThread("speaker");
    private final Handler handler;
    private int queued;

    Player(AudioManager am, WebMessagePort port) {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, RATE * 2 / 5)) // 200 ms: full means behind, and new audio is dropped
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) track.setPreferredDevice(d);
        thread.start();
        handler = new Handler(thread.getLooper());
        port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
            @Override public void onMessage(WebMessagePort p, WebMessage m) {
                byte[] pcm = Base64.decode(m.getData(), Base64.DEFAULT);
                track.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING);
                if (++queued == CUSHION) track.play();
            }
        }, handler);
    }

    /** On the playing thread, so it can't release the track in the middle of a write. */
    void stop() {
        handler.post(() -> {
            track.release();
            thread.quit();
        });
    }
}
