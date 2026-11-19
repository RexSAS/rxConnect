package com.andrea.rxconnect;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Base64;
import java.util.function.Consumer;

/**
 * Records the phone's built-in mic natively and hands out 20 ms chunks of 48 kHz mono 16-bit PCM (base64) for the page.
 * Why not the WebView's own getUserMedia mic: opening it makes WebView switch the phone to call mode and Bluetooth
 * headphones to their 16 kHz call profile, so music and the call drop in quality and sound jumps to the phone speaker
 * for a moment. Recording here, pinned to the built-in mic, never touches the audio mode or the headphones.
 */
final class Mic {
    /**
     * The name the settings show, or null for inputs it doesn't offer. No Bluetooth: its mic only works in call mode,
     * which drops the headphones to their call profile (see above).
     */
    static String label(AudioDeviceInfo d) {
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Phone mic" + (d.getAddress().isEmpty() ? "" : " (" + d.getAddress() + ")");
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired headset";
            case AudioDeviceInfo.TYPE_USB_DEVICE: case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB: " + d.getProductName();
            default: return null;
        }
    }

    static final int RATE = 48000, CHUNK = RATE / 50; // 960 samples = 20 ms

    private final AudioRecord rec;
    private final Thread thread;
    private volatile boolean on = true;

    /** Throws if the mic can't be opened (e.g. no RECORD_AUDIO). {@code out} is called on the recording thread. */
    /** {@code device}: an AudioDeviceInfo id from the settings; "" or unplugged means the built-in mic. */
    Mic(AudioManager am, String device, Consumer<String> out) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // VOICE_COMMUNICATION is the phone's call processing: it cancels echo from the loudspeaker but sounds dull and
        // quiet. With headphones there's no echo to cancel, so the clean full-range VOICE_RECOGNITION source is used.
        // shortcut: chosen when the mic starts; plugging headphones in mid-call keeps the old choice until the next call.
        boolean call = !headphones(am);
        rec = new AudioRecord(call ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 2 * 4));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            throw new IllegalStateException("mic unavailable");
        }
        AudioDeviceInfo pick = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (String.valueOf(d.getId()).equals(device) && label(d) != null) { pick = d; break; }
            if (pick == null && d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) pick = d;
        }
        if (pick != null) rec.setPreferredDevice(pick);
        int session = rec.getAudioSessionId();
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session).setEnabled(true);
        if (call && AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session).setEnabled(true);
        // No AutomaticGainControl: the page levels the voice itself (leveler in index.html), more evenly.
        rec.startRecording();
        thread = new Thread(() -> {
            short[] pcm = new short[CHUNK];
            byte[] bytes = new byte[CHUNK * 2];
            while (on) {
                int n = rec.read(pcm, 0, CHUNK);
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) { // little-endian, as the page's Int16Array reads it
                    bytes[2 * i] = (byte) pcm[i];
                    bytes[2 * i + 1] = (byte) (pcm[i] >> 8);
                }
                out.accept(Base64.encodeToString(bytes, 0, n * 2, Base64.NO_WRAP));
            }
            rec.stop();
            rec.release();
        }, "mic");
        thread.start();
    }

    void stop() { on = false; }

    /** Sound goes to headphones (Bluetooth, wired or USB), so the phone speaker can't echo into the mic. */
    static boolean headphones(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET: case AudioDeviceInfo.TYPE_USB_HEADSET:
                case 26 /* TYPE_BLE_HEADSET, Android 12+ */:
                    return true;
            }
        return false;
    }
}
