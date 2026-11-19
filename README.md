# rxConnect

Private video calls for two people: video, voice, mute/unmute, camera on/off and screen sharing (from Windows). Peer to peer with WebRTC: the call goes straight between the two devices. PeerJS's free public broker is only used to find each other at the start.

Open the app, type the same room name on both sides, and you're in. The first to join waits alone until the other arrives; if one drops, the other goes back to waiting.

[Download for Android and Windows](https://github.com/RexSAS/rxConnect/releases/latest): `rxconnect.apk` and `rxconnect-windows-x86_64.exe`. Both apps check here at startup and offer to update.

| Folder | What it is |
|---|---|
| `web/index.html` | The whole call: UI, WebRTC, the update box. Both apps show this page. |
| `android/` | Java wrapper (WebView + a foreground service so calls survive the background). `./build.sh`, no Gradle. |
| `windows/` | Tauri wrapper using Windows' built-in WebView2. Built by GitHub Actions. |

**Release:** commit, then `./release.sh`. It builds the APK and makes release `v<commit count>`; GitHub Actions adds the Windows exe. Keep `android/signing.keystore` safe: updates must be signed with the same key.

**Limits:** anyone who knows the room name can join. On networks that block direct connections (some carriers, strict Wi-Fi) the call falls back to PeerJS's free relay servers, which pass along the still-encrypted stream but add a little delay. Phones can't share their screen.
