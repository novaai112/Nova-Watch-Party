# Nova Watch Party: Free API keys, where to get them, where to put them

Free tiers and limits change. Always confirm the current limits on each provider's pricing page.
**Every key is optional.** Without a key only that feature shows a "not set" message; the rest of the app works.

## Where each value goes (summary)

| Value | Get it from | Put it in |
|---|---|---|
| `YOUTUBE_API_KEY` | Google Cloud Console | `local.properties` (app) |
| `TMDB_API_KEY` | themoviedb.org | `local.properties` (app) |
| `GIPHY_API_KEY` | developers.giphy.com | `local.properties` (app) |
| `GOOGLE_WEB_CLIENT_ID` | Google Cloud Console | `local.properties` **and** server env var of the same name |
| `google-services.json` | Firebase Console | file `app/google-services.json` |
| `FCM_SERVICE_ACCOUNT_JSON` | Firebase Console | **server** env var (path to the JSON file, or the JSON itself) |
| `TURN_URL`, `TURN_USERNAME`, `TURN_CREDENTIAL` | metered.ca (or your own coturn) | `local.properties` (app) |
| `SMTP_URL`, `MAIL_FROM` | Brevo / Gmail / any SMTP | **server** env vars |
| `SERVER_URL` | where you host `server/` | `local.properties` (app) |
| `INVITE_BASE_URL` | your server's https address | `local.properties` (app) |
| ML Kit (translate) | no key needed | nothing |

`local.properties` lives in the project root (copy `local.properties.example`). Never commit it. After changing it, rebuild the app (keys are baked in at build time).
Server env vars are set in your host's dashboard, or in the terminal before `npm start`, e.g. `SMTP_URL=... npm start`.

---

## 1. YouTube Data API v3 -> `YOUTUBE_API_KEY` (in-app YouTube search, Live, Karaoke, Playlists)
1. Go to https://console.cloud.google.com and sign in.
2. Top bar > project picker > **New Project** > name it "Nova" > Create.
3. Menu > **APIs & Services > Library** > search **YouTube Data API v3** > **Enable**.
4. **APIs & Services > Credentials > Create credentials > API key**. Copy it.
5. Click **Edit API key** and restrict it:
   - Application restrictions: **Android apps** > add package `com.nova.watchparty` and your SHA-1 (run `./gradlew signingReport`; debug and release have different SHA-1s, add both).
   - API restrictions: Restrict key > **YouTube Data API v3**.
6. `local.properties`: `YOUTUBE_API_KEY=your_key`

Limit: about 10,000 units/day free; one search costs about 100 units (~100 searches/day across ALL users). When you outgrow it, request a quota increase in the console, or move search to your server with caching.
Without a key: the YouTube tile still works by browsing m.youtube.com and tapping the "watch together" icon.

## 2. TMDB -> `TMDB_API_KEY` (movie trailers)
1. Create an account at https://www.themoviedb.org and verify your email.
2. Avatar menu > **Settings > API** > request a **Developer** key (short form; personal/non-commercial use is the free option).
3. Copy the **API Key (v3 auth)**, not the "Read Access Token".
4. `local.properties`: `TMDB_API_KEY=your_key`

Free key is non-commercial. A commercial app needs a licence from TMDB. Keep the attribution text shown in the Trailers screen.

## 3. GIPHY -> `GIPHY_API_KEY` (GIFs in chat)
1. https://developers.giphy.com > sign up > **Create an App** > choose **API** > accept terms.
2. Copy the key.
3. `local.properties`: `GIPHY_API_KEY=your_key`

New keys are "beta" with low hourly limits. Before public launch, apply for a production key in the GIPHY dashboard. Keep the "Powered by GIPHY" label.

## 4. Google Sign-In -> `GOOGLE_WEB_CLIENT_ID`
1. Same Google Cloud project. **APIs & Services > OAuth consent screen** > External > fill app name, support email > save. (Add yourself as a test user while in Testing mode.)
2. **Credentials > Create credentials > OAuth client ID**:
   - Type **Web application** (name "Nova server"). Copy its **Client ID**. This is `GOOGLE_WEB_CLIENT_ID`.
   - Create a second one: type **Android**, package `com.nova.watchparty`, your SHA-1. (No value to copy; it must just exist for Google to accept your app.)
3. Put the Web Client ID in `local.properties` as `GOOGLE_WEB_CLIENT_ID=...apps.googleusercontent.com` **and** on the server as env var `GOOGLE_WEB_CLIENT_ID` (the server verifies tokens against it).
4. To go public, publish the consent screen (Google may require a verification review).

## 5. Push notifications (Firebase Cloud Messaging, free) -> `google-services.json` + `FCM_SERVICE_ACCOUNT_JSON`
1. https://console.firebase.google.com > **Add project** (can reuse the Google Cloud project).
2. **Project settings > General > Add app > Android**: package `com.nova.watchparty` > register > **download `google-services.json`** > put it at `app/google-services.json`. Rebuild. (The build only enables push when this file exists.)
3. **Project settings > Service accounts > Generate new private key** > downloads a JSON file. Keep it secret.
4. On the **server**, set `FCM_SERVICE_ACCOUNT_JSON` to the path of that file (or paste the JSON as the value).
5. Test: two accounts that are friends, one in a room, tap a friend's avatar in the invite panel.

Used for room invites. On Android 13+ the app asks for notification permission.

## 6. Voice chat servers (WebRTC)
- STUN (Google's public server) is already configured and free. It works for most home/mobile networks.
- Some networks (corporate Wi-Fi, strict mobile carriers) need a **TURN** relay. Free option: https://www.metered.ca (open-source TURN, free tier; check current allowance). Sign up > create a TURN app > copy the credentials.
- `local.properties`:
  ```
  TURN_URL=turn:YOUR_HOST:80,turns:YOUR_HOST:443?transport=tcp
  TURN_USERNAME=...
  TURN_CREDENTIAL=...
  ```
- Self-hosting alternative: install `coturn` on any small VPS.
- Design limit: mesh audio, about 6 people speaking at once per room. For bigger rooms use an SFU (LiveKit has a free cloud tier).

## 7. Email login codes -> `SMTP_URL`, `MAIL_FROM` (server)
Pick any SMTP provider with a free tier (check limits): **Brevo** (about 300 emails/day free), **Resend**, **Mailgun**, or a Gmail account with an App Password (small volume only).
1. Create the account, verify your sender email/domain, create an SMTP key.
2. Server env:
   ```
   SMTP_URL=smtps://USERNAME:PASSWORD@smtp.example.com:465
   MAIL_FROM="Nova Watch Party <no-reply@yourdomain.com>"
   ```
   (URL-encode special characters in the password.)
3. Run `npm install` in `server/` so the optional `nodemailer` package is installed.
Without SMTP the code is only printed in the server log. `DEV_MODE=1` returns it in the API response; never enable that in production.

## 8. Hosting the server (free options) -> `SERVER_URL`, `INVITE_BASE_URL`
Free tiers change often. Options: **Render**, **Fly.io**, **Railway**, an Oracle Cloud "always free" VM.
- Needs: Node 18+, WebSocket support, HTTPS. Start command: `npm start` in `server/`.
- Set env vars: `PORT` (host usually sets it), `DATA_DIR`, plus the SMTP / Google / FCM ones above.
- **Important:** free web services often sleep when idle (first connection is slow, live rooms are dropped) and have **no persistent disk**, so accounts saved in `DATA_DIR/db.json` can be lost on redeploy. For real use, attach a volume (Fly.io volumes) or move the data to a free Postgres (Supabase / Neon). That is listed as a next step.
- App: `SERVER_URL=wss://your-app.example.com`, `INVITE_BASE_URL=https://your-app.example.com`. Remove `android:usesCleartextTraffic` from the manifest for release.

## 9. Things that need NO key
- On-device translation (ML Kit): language packs download on first use.
- Direct mp4/HLS links, web page video sync, YouTube IFrame playback, Picture-in-Picture.

## Not free / not possible with a key
- **Netflix, Disney+, etc. official sync:** no public API. Nova syncs the page's own video element in a WebView; works per site, each viewer needs an account.
- **Premium / removing ads:** needs Google Play Billing, which requires a Play Console developer account (one-time fee) and a published app. Not built.
- **Facebook / X / VK / Apple sign-in:** each needs its own developer app. Not built.

## 10. Server settings added in v4 (all optional)
| Env var | Purpose |
|---|---|
| `ADMIN_TOKEN` | Long random string. Enables `GET /admin/reports`, `POST /admin/ban`, `POST /admin/unban` (send header `x-admin-token`). **Set this before a public launch** (Play requires you to act on reports). |
| `DATABASE_URL` | Free Postgres (Neon / Supabase) so accounts survive redeploys. Also run `npm install pg`. |
| `BAD_WORDS` | Comma-separated words to mask in chat (default list built in). |
| `PRIVACY_HTML`, `TERMS_HTML` | Your reviewed privacy policy and terms, served at `/privacy` and `/terms`. |
| `DEV_MODE` | `1` = local testing only. Never in production. |
| `MAIL_RATE` | Login-code emails per minute per IP (default 5). |

## 11. Release signing (app, in `local.properties`)
`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Create the keystore once with the `keytool` command in `local.properties.example` and back it up; losing it means you can't update the app.
