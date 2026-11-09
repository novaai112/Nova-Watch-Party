# Nova Watch Party: Status, handoff and next steps

Last updated at the end of build session 3 (v4). Give this file plus the whole zip to the next developer or AI session.

## Honest status
- **Server (`server/`)**: complete and **tested** (friend requests, block/report, gallery, chat photos, profanity mask, admin ban, optional Postgres, reconnect grace) (`cd server && npm install && npm test`, 30+ assertions: auth, profiles, rooms, sync, voting, privacy, nearby, WebRTC signaling relay, invites, friends, history).
- **Android app (`app/`)**: fully written but **never compiled or run** (the build sandbox had no Kotlin/Gradle/network). Brackets were machine-checked; types, imports and library APIs were reviewed by eye only.
  **Step 1 for the next session: open in Android Studio, Gradle sync, fix compile errors, run on a device.** Likely trouble spots: WebRTC library API names (`stream-webrtc-android`), Compose/Material3 experimental opt-ins, `Icons.Default.*` names, Credential Manager (Google sign-in) usage.

## Session 6 (v2.3): Rave-parity batch - profiles, presence, search, passcode, privacy
Compared against the Rave screenshots (profile with Online/Top/History/Liked/Stats/Interests, people search, settings, privacy) and the PRD.
Server (tested): `GET /users/lookup` now returns presence (online / last active), relation (self/friends/requested/incoming/none) and stats (joined, watch minutes, rooms, videos, likes, provider mix); `GET /users/videos?tab=top|history|liked` (Top = most-watched, per-video view counts); `GET /users/search?q=` (people by name/@handle, respects blocks); room **passcode** (4-12 chars, hashed in memory, required on join, rejoin grace); privacy flags `hidePresence`, `privateActivity`; notification flags `notifyInvites`, `notifyFriends` (honoured by push); `GET /sessions`, `POST /auth/logout-others`. Flag values are type-coerced.
Android (not compiled here): profile sheet shows online dot / last seen, stats, Top-History-Liked tabs with view counts and the right friend button state; Home search also finds people; settings gained Hide Online Status, Keep Activity Private, Invitation / Friend notifications, Sign out of other devices; room settings gained Passcode; joining a passcode room prompts for it.
Deliberately NOT copied from Rave: Rave Premium billing, the Rave DJ audio rooms, and site-specific injection scripts for Netflix / Prime / Disney+ / Crunchyroll (automating DRM streaming sites conflicts with their terms and with the PRD's provider-compliance gate).
Still open: in-app join-preview screen, expiring invite tokens, QR invites, language picker, interests donut chart, Rave-DJ-style audio rooms, SFU voice, analytics/crash reporting.

## Session 5 (v2.2): PRD slice - room controls, moderation console, App Links
Server (all covered by `npm test`, which passes): host controls **lock room, approve joins (pending/approve/deny), capacity 2-100, chat on/off, mute (minutes), explicit host transfer, end room for everyone**; removing someone now **bans them from rejoining** that room; `GET /rooms/preview?code=` join preview (host, provider, privacy, count, canJoin + reason, never the media URL); **moderation console** at `/admin` (enter `ADMIN_TOKEN`; report queue with search, dismiss / warn / mute 24 h / ban, immutable audit log at `/admin/audit`, `/admin/metrics`); timing-safe admin token check; structured JSON logs for room/admin events; `/.well-known/assetlinks.json` from env `ANDROID_SHA256`.
Android (written, **not compiled here**): room settings toggles (lock, approve joins, chat, max people), member menu (make host / mute / unmute / remove and block), join-request dialog for the host, "End room for everyone" in the leave dialog, `closed` / `pending` handling, verified https App Link `https://<host>/r/CODE` (host from `INVITE_BASE_URL` or `SERVER_URL`).
Still open from the PRD: join-preview screen in the app (endpoint is ready), passcodes/expiring invite tokens, presence + last-active, profile Top/History/Liked tabs and stats, QR invite, notification preferences, session list, SFU voice, provider verification, analytics/crash reporting, load tests.

## Session 4 (v2.1): YouTube playback fix + reliability
- **Fixed "An error occurred. Please try again later (Playback ID …)"** in the room player. Cause: the wrapper library (`androidyoutubeplayer 12.1.0`) loads the IFrame page without a valid HTTPS origin/Referer, which YouTube now rejects. `ui/YouTubeSync.kt` now hosts the official IFrame player itself, loaded with a proper origin (`https://<applicationId>`), automatically retries with the next origin (server https URL, then youtube.com) on error 153/5/script failure/15 s watchdog, and shows a clear message with **Open in YouTube / Retry / Skip** if the video really can't be embedded. The wrapper dependency was removed.
- **Server**: `GET /yt/check?id=` (oEmbed embeddability pre-check, cached 30 min); WS message `unplayable {url, reason}` (host, or a majority of guests) -> room is told and the next queued video starts; a room created with a video now autoplays; `advance(room, force)`.
- **App**: picks are pre-checked (toast if the owner blocks embedding), playlist import queues the first playable video then the rest; chat sheet has an **Emoji** picker tab; cleartext traffic only in debug builds (release forces wss/https); version 2.1.
- Tests added to `server/test.js` (unplayable skip, autoplay on create, `/yt/check` validation). **Not run here (no network for `npm install`)**: run `cd server && npm install && npm test`.

## Done
| Area | Details |
|---|---|
| Onboarding | Splash, 6-page carousel with original phone illustrations, animated blurred background, original "nova" logo |
| Auth | Email 6-digit code, Google sign-in (needs key), guest, terms acceptance, profile setup (name, @handle suggestions, avatar upload), delete account, sign out |
| Home | Public rooms list (auto-refresh), search, join by code, FAB to create, deep links `novaparty://room/CODE` and `https://server/r/CODE` |
| Sources | YouTube (API search or web browse), Live, Playlists (queues videos), Karaoke, Trailers (TMDB), Likes, History, web browser for X/Netflix/Prime/Crunchyroll/Twitch/Drive/Photos/any site |
| Playback sync | YouTube IFrame, ExoPlayer (mp4/HLS), any web page `<video>` via injected JS; host heartbeat; late joiners; host transfer |
| Room | Privacy (public/nearby/friends/invite), playback modes (vote/just play/autoplay/leader), vote queue, text + GIF chat, double-tap quick reaction, floating emoji, members list + kick, invite sharing to installed apps, invite friends by push, volume, Picture-in-Picture |
| Voice chat | WebRTC mesh audio, mic button, host can disable voice, speaking indicator in members list (needs TURN for strict networks) |
| Auto-translate | ML Kit on-device, per-user toggle |
| Nearby | Coarse (~1 km) location, rooms within 50 km, Hide Location setting |
| Push | FCM invites (needs Firebase files) |
| Friends | Add by @handle, friends-only rooms, restrict invitations |
| Profile/Settings | Bio, interests, stats, history, quick reaction, hide mature (YouTube safe search), floating player, feedback, replay tutorial, version |
| Docs | `README.md`, `API_KEYS_GUIDE.md`, this file |

## Added in session 3 (v4)
- Friend requests with accept/decline (replaces one-way follow), badge on Home, sent-requests list
- Block + report (in-app, Play UGC requirement), blocked-users list in Settings, `GET /admin/reports`, `POST /admin/ban|unban` (header `x-admin-token`, env `ADMIN_TOKEN`)
- Profanity mask on chat (extend with env `BAD_WORDS=a,b,c`)
- Profile gallery (6 photos), channels, social links; tap a name/avatar in chat or members for a profile sheet (Add friend / Report / Block)
- Photo sharing in chat (kept 24 h)
- Auto-reconnect of the room WebSocket (5 tries) + server 60 s empty-room grace, host repair
- Optional Postgres storage (`DATABASE_URL`), Dockerfile, fly.toml.example, `.env.example`, GitHub Actions CI, release signing config, `STORE_LISTING.md`
- Fixed: nullable audio source in VoiceChat.kt; test suite rate-limit (`MAIL_RATE` env)
Items 3, 4, 6 (friend requests), 9 (moderation basics), 14 below are now done; the rest of the list still applies.

## Not done (honest list)
1. **Compile + device testing of the whole Android app** (see above). Highest priority.
2. **Premium / ads-free / Play Billing**: skipped (needs Play Console account).
3. **Facebook, X, VK, Apple sign-in**: skipped.
4. **Profile extras**: gallery, channels, social-link icons, keywords. Only bio + interests exist.
5. **Language picker / localized strings**: UI is English only.
6. **Friend requests**: "add friend" is one-way follow (no accept flow). Friends-only rooms use the owner's follow list.
7. **Mutual voice at scale**: mesh limit ~6 speakers. Larger rooms need an SFU (e.g. LiveKit).
8. **Data storage**: JSON file. Move to Postgres (Supabase/Neon free) before real users; run one server instance only.
9. **Moderation**: no report/block flow, no profanity filter, no admin tools. Needed before a public Play Store release.
10. **Legal**: privacy policy and terms are placeholder templates. Replace with reviewed text. Streaming-site syncing may conflict with those sites' terms.
11. **Play Store release**: icons for all densities (only adaptive vector now), screenshots, store listing, data-safety form, signing keystore, AAB build.
12. **Automated Android tests** (none written).
13. **Floating player nuance**: PiP only shows the video box; web-sync pages in PiP are untested.
14. **Photo/video sending in chat** (Rave sends photos): only GIFs implemented.

## Recommended next steps (in order)
1. Android Studio: Sync, fix compile errors, run on emulator + a real phone with `DEV_MODE=1 npm start` on your PC (`SERVER_URL=ws://<PC-LAN-IP>:8080`).
2. Test the checklist below, fix bugs.
3. Add API keys using `API_KEYS_GUIDE.md` (YouTube first, then TMDB, GIPHY, SMTP, Google, Firebase, TURN).
4. Deploy the server (HTTPS/WSS) with persistent storage; switch to Postgres.
5. Add report/block + basic moderation; real legal pages.
6. Missing features from the list above by priority: friend requests, profile extras, localization, Premium.
7. Release prep (icons, listing, keystore, AAB).

## Test checklist
- [ ] Sign up by email code; sign in as guest; Google (with key)
- [ ] Create room from YouTube link; second phone joins by code and by invite link
- [ ] Host play/pause/seek mirrored on guest; guest joins mid-video lands at the right time
- [ ] Host leaves -> host transfers; host kicks a member
- [ ] Vote queue: guest adds video, both vote, host skip plays winner
- [ ] Privacy: switch to invite-only -> disappears from Home list; nearby needs location permission
- [ ] GIF chat, quick-reaction double-tap, floating emoji
- [ ] Voice: both speak at the same time; test on mobile data (needs TURN)
- [ ] Auto-translate with a foreign-language message
- [ ] Web source: open a site with a video, tap "watch together", guest follows
- [ ] Push invite (needs Firebase), PiP on Home button
- [ ] Delete account removes data

## Architecture map
```
server/server.js         REST (auth, me, rooms/public, history, likes, friends, push, invite) + WebSocket rooms (sync, chat, queue, settings, mic, rtc relay)
server/test.js           end-to-end smoke tests
app/.../AppViewModel.kt  navigation stack, auth, profile, rooms, voice/location/push glue
app/.../net/             Backend.kt (REST), RoomClient.kt (WebSocket), Api.kt (YouTube/TMDB/GIPHY)
app/.../ui/              Nav, Onboarding(+auth+setup), Home, Sources, Room, ProfileScreens(Profile/Friends/Settings), WebSync, YouTubeSync, Discover, Common, Theme
app/.../VoiceChat.kt     WebRTC mesh voice;  Translate.kt  ML Kit;  NovaMessagingService.kt  FCM
```
WebSocket protocol: client -> `create/join` (with token), `control(play|pause|seek)`, `ended`, `pick(url,title,thumb,enqueue)`, `vote`, `settings`, `chat`, `reaction`, `mic`, `rtc`, `kick`. Server -> `joined`, `state`, `members`, `settings`, `queue`, `chat`, `system`, `reaction`, `rtc`, `kicked`, `error`.
Video URL scheme in a room: `yt:<id>` (YouTube), `web:<page url>` (web sync), anything else = direct media URL.

## Prompt to give the next AI/developer
> I'm attaching NovaWatchParty_v3.zip (Android Kotlin/Compose app + Node server, an original watch-party app). Read STATUS_AND_NEXT_STEPS.md and API_KEYS_GUIDE.md first. The Android app has never been compiled. Start by fixing compile errors until `./gradlew assembleDebug` succeeds, then work through "Not done" in priority order. Don't copy code or assets from any other app. Keep the server tests (`npm test`) passing.

## About usage limits
The assistant cannot see how much of its usage limit is left, so this handoff and the zip are kept current after every build step instead of only at the end.
