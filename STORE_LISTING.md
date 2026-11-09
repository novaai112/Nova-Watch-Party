# Google Play listing draft (edit before submitting)

**App name:** Nova Watch Party (max 30 chars)
**Short description (80):** Watch videos in sync with friends. Chat, react, vote, talk.
**Full description:**
Nova Watch Party lets you watch videos together in real time, wherever your friends are.
- Create a room, pick a video, and everyone stays perfectly in sync
- Chat with text, GIFs and photos; double-tap to react
- Voice chat with friends in the room
- Vote on what plays next, or let the host choose
- Public, nearby, friends-only and invite-only rooms
- Works with YouTube, direct video links and many websites
- Auto-translate chat on your device

**Category:** Social (or Video Players & Editors)
**Content rating:** answer the questionnaire honestly (user-generated content, chat => not "Everyone" without moderation notes)

## Data safety form (answers reflecting this app)
| Data | Collected | Shared | Purpose | Optional |
|---|---|---|---|---|
| Email | yes | no | account, login code | required (or guest) |
| Name, handle, bio, photos | yes | shown to other users | profile | yes |
| Approximate location (~1 km) | yes | no | nearby rooms | yes (Hide Location) |
| Chat messages, photos | yes | other room members | app function (kept in memory; photos 24 h) | no |
| Voice | not stored; streamed peer to peer | other room members | voice chat | yes |
| Watch history, likes | yes | no | features | yes |
| Device push token | yes | Google (FCM) | notifications | yes |
Data encrypted in transit (wss/https). Users can delete the account and data in Settings > Delete Account.

## Required before submitting
- Privacy policy URL (host the reviewed text; server serves /privacy)
- UGC policy: Play requires in-app **report and block** for user-generated content (implemented) and a way to act on reports (`GET /admin/reports` with `ADMIN_TOKEN`)
- 512x512 icon, 1024x500 feature graphic, 2-8 phone screenshots
- Target API level meets current Play requirement (check Play Console)
