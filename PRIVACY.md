# Privacy Policy

Artlog is a **local-first, fully offline** app. This policy describes
what the app does with your data.

## What the app stores — only on your device

- Your artworks, tags, journals, statistics, and settings are stored in
  a local database and in the app's private directory on your device.
- Music files you import are copied into the app's private directory;
  the original files are never modified. Album covers are read from the
  embedded artwork of your own music files.
- Backups are ZIP files that **you** create and manage yourself. The app
  never uploads a backup anywhere.

## What the app does not do

- **No accounts, no login, no cloud sync.**
- **No network uploads.** The app itself makes no network requests. The
  visual inspiration directory only lists public websites; tapping one
  opens it in your system browser, outside the app.
- **No analytics, no tracking, no advertising.**
- **No access to your media library.** Music and images are imported
  through the system file picker, one file at a time, with your explicit
  choice each time.

## Permissions

- `INTERNET` and `ACCESS_NETWORK_STATE` are still declared in the app
  manifest as a leftover from an earlier recommendation-feed feature that
  has since been removed. The app itself currently makes no network
  requests; whether to remove these declarations is left for a later
  version.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and
  `POST_NOTIFICATIONS` are used for background music playback and its
  notification controls.
- `SYSTEM_ALERT_WINDOW` is used only for the optional system-level
  floating lyrics, and only after you grant it on the system settings
  page. The app never requests it at launch.

## Your data stays yours

Artlog does not claim ownership of the artworks, music, lyrics, or other
personal content you import. You are responsible for ensuring that the
content you import and share is content you have the right to use. The
app's own privacy behaviour is described in the in-app author page and in
this file; if they ever disagree, this file governs.
