# Nova Watch Party

An original watch-party app for Android (Kotlin, Jetpack Compose) with a Node.js backend. Watch videos in sync with friends, chat, react, vote on what plays next.
The screen structure and flow follow the reference screenshots (onboarding, sign-in, home with public rooms, source picker, room with chat/privacy/playback settings, profile, settings). Logo, icons, illustrations and the animated background are original.

See **API_KEYS_GUIDE.md** (free keys, step by step) and **STATUS_AND_NEXT_STEPS.md** (what is done / left).

## What works
- **Accounts:** email login code, optional Google sign-in, guest mode, profile setup (name, @handle, avatar upload), bio, delete account
- **Home:** live list of public rooms (auto-refresh), search, join by code, invite deep link `novaparty://room/CODE` and `https://<server>/r/CODE`
- **Sources:** YouTube (in-app search with API key, or browse m.youtube.com and tap "watch together"), Live, Playlists (queues all videos), Karaoke, Trailers (TMDB), Likes, History, and a web browser for X / Netflix / Prime / Crunchyroll / Twitch / Drive / Photos / any site
- **Room:** host-controlled synced playback (YouTube IFrame, direct mp4/HLS via ExoPlayer, and any web page `<video>`), host transfer, privacy (public / nearby / friends only / invite only), playback mode (vote / just play / autoplay / leader's choice) with a vote queue, text + GIF chat, double-tap quick reaction, floating emoji reactions, members list with kick, invite sharing to installed apps, volume, Picture-in-Picture ("Floating video player")
- **Voice chat** (WebRTC), **auto-translate** (on-device), **nearby rooms**, **push invites**
- **Friends:** add by handle, friends-only rooms

## Not included (be aware)
- **Premium/billing, Facebook/X/VK/Apple sign-in, friend-request accept flow, profile gallery:** not built (see STATUS_AND_NEXT_STEPS.md)
- **Streaming services:** Netflix/Prime/etc. are loaded in a WebView and synced by controlling the page's video element. Each viewer needs their own subscription, results vary by site, and it may conflict with a service's terms. Only YouTube uses an official embed.
- **This code has not been compiled or run on a device.** The server is tested (`cd server && npm test`). Expect to fix a few small compile errors on first Gradle sync.

## Run it
1. Server: `cd server && npm install && DEV_MODE=1 npm start` (DEV_MODE returns the login code in the API so you can test without email; **never use in production**)
2. `cp local.properties.example local.properties`, set `sdk.dir` and keys, open in Android Studio, run.
   - Emulator reaches your PC at `ws://10.0.2.2:8080`. Real phone: set `SERVER_URL=ws://<PC-LAN-IP>:8080` or long-press the logo on the sign-in screen to change it.

## Keys (all optional; features degrade gracefully)
`YOUTUBE_API_KEY` (search), `TMDB_API_KEY` (trailers), `GIPHY_API_KEY` (GIFs), `GOOGLE_WEB_CLIENT_ID` (Google sign-in; also set the same env var on the server), `INVITE_BASE_URL` (invite link host). See the key guide in Google/TMDB/GIPHY docs; TMDB free keys are non-commercial, GIPHY beta keys are rate limited.

## Server environment
`PORT`, `DATA_DIR` (db + avatars; use a persistent disk), `SMTP_URL` + `MAIL_FROM` (real login emails via nodemailer), `GOOGLE_WEB_CLIENT_ID`, `PRIVACY_HTML` / `TERMS_HTML` (replace the template legal pages with text reviewed by a lawyer).

## Before release
- Deploy behind TLS; use `wss://`; remove `usesCleartextTraffic` from the manifest; unset `DEV_MODE`
- The server stores data in a JSON file, fine for a start; move to a real database before scaling, and run a single instance
- Replace privacy policy / terms templates; add a content-report flow if you allow public rooms
- Create a keystore, `./gradlew bundleRelease`, upload to Play Console
