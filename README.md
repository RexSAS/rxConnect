# rxConnect

Private video calls for two people: video, voice, mute/unmute, camera on/off and screen sharing (from Windows). Peer to peer with WebRTC: the call goes straight between the two devices. PeerJS's free public broker is only used to find each other at the start.

Open the app, type the same room name on both sides, and you're in. The first to join waits alone until the other arrives; if one drops, the other goes back to waiting.

[Download for Android and Windows](https://github.com/RexSAS/rxConnect/releases/latest): `rxconnect.apk` and the Windows installer `rxconnect-windows-x86_64-setup.exe` (installs for your user, no admin needed). Both apps check here at startup and offer to update.

| Folder | What it is |
|---|---|
| `web/index.html` | The whole call: UI, WebRTC, the update box. Both apps show this page. |
| `android/` | Java wrapper (WebView + a foreground service so calls survive the background), home-screen widgets (`Widgets.java`) and the nudge's heart over other apps (`Nudge.java`). `./build.sh` (Gradle, for Firebase push). |
| `windows/` | Tauri wrapper using Windows' built-in WebView2. Built by GitHub Actions. |
| `pages/` | Where invite links land (`https://rxsas.it/rxConnect/#room`): opens the app, or offers the download or the web version. Published with `web/` by `.github/workflows/pages.yml` on each release. |
| `push/` | Cloudflare Worker that relays between the phones of a room through Firebase: who's in the room, nudges, "thinking of you", "wanna call?", and the widgets' photo and board (end-to-end encrypted with the room name). Tests: `node --test push/worker.test.mjs`. |

**Release:** commit, then `./release.sh`. It builds the APK and makes release `v<commit count>`; GitHub Actions adds the Windows installer. Keep `android/signing.keystore` safe: updates must be signed with the same key.

**Push setup (once):**
1. [Firebase console](https://console.firebase.google.com): create a project, add an Android app with package `com.andrea.rxconnect`, and save its `google-services.json` in `android/` (not secret: it ships inside the APK).
2. Project settings → Service accounts → *Generate new private key*. This JSON **is** secret: never commit it.
3. In `push/`: `npx wrangler login`, then `npx wrangler kv namespace create ROOMS` and put the id it prints in `wrangler.toml`, then `npx wrangler secret put FCM_SERVICE_ACCOUNT < the-key.json`, then `npx wrangler deploy`.

**Limits:** anyone who knows the room name can join. On networks that block direct connections (some carriers, strict Wi-Fi) the call falls back to PeerJS's free relay servers, which pass along the still-encrypted stream but add a little delay. Phones can't share their screen.
