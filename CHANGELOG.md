# Changelog

All notable changes to Artlog are recorded here. Dates are in UTC+8.
This file covers the public history of the project; the detailed
development log (in Chinese) is kept separately by the author.

## [1.0.0] — 2026-11-14 (first public release)

First public release of Artlog (versionCode 28).

- Personal creative archive: add, view, edit, and delete artworks, with
  tags, journals, and statistics based only on real records.
- Visual inspiration directory: a curated list of visual websites
  (illustration, photography, architecture), opened in the system browser.
- Local music player: background playback, notification and lock-screen
  controls, lyrics (embedded ID3/MP4 lyrics and LRC files, UTF-8 with GBK
  fallback), in-app and system-level floating lyrics, repeat modes
  (sequential / repeat all / repeat one) and shuffle, album covers read
  from embedded artwork.
- Personalisation: custom background image and colours, scrim opacity,
  text size and colour, custom home title.
- Data safety: local ZIP backup and restore (database, originals and
  thumbnails, per-file SHA-256 manifest, validation before restore,
  rollback on failure), image integrity checks.
- Fully offline and local-first: no account, no cloud sync, no analytics.

### Known issues

- Device acceptance for several areas (playback modes, the redesigned UI,
  album covers, backup round-trips, and the top-bar fixes in v23–v27) has
  not been reported yet; these items are untested on real devices.
- A single-character GBK lyrics file whose bytes happen to be valid UTF-8
  may be mis-decoded.
- Playing from a filtered list builds the queue from the full library
  instead of the filtered list.
- If deleting a track's file fails, the playback state has already been
  cleared and cannot be restored.
- The system-level floating lyrics service is a plain started service
  (not a foreground service); it relies on START_STICKY and re-sync when
  the app returns to the foreground.
- Dynamic lockscreen lyrics are not supported (no public, stable API for
  third-party apps).
- `INTERNET` permission is still declared although the app itself makes no
  network requests (the old recommendation feed and diagnostics were
  removed); whether to remove the declaration is left for a later version.

## Development history (pre-release, versionCode 7–27)

The app was developed in rounds between 2026-11-12 and 2026-11-14. The
public 1.0.0 release is based on versionCode 28, which only adds the
GitHub repository address and license information to the in-app author
page and aligns the version name with the release tag.

- v7–v10: first working builds; artwork archive, tags, journals,
  statistics, backup and restore; navigation and delete bugs fixed.
- v11–v16: global visual-inspiration feed (Openverse, Commons, Met, AIC),
  dual-channel content types, diagnostics module, circuit breaker, and an
  image display safety gate.
- v17: the feed was replaced by a website navigation directory (35 sites
  audited; 5 officially listed); old favourites became read-only.
- v18–v19: local music player, lyrics, personalised backgrounds, global
  floating lyrics, audio focus, system overlay lyrics.
- v20: playback modes — repeat all and shuffle.
- v21: full UI redesign — creative-studio home, digital-gallery artwork
  view, redesigned music player with waterfall lyrics, regrouped settings.
- v22: album covers from embedded artwork; public author introduction.
- v23: player layout fixes; author's origin story and creative philosophy
  moved to the project documentation appendix.
- v24: transparent bottom navigation bar with edge-to-edge display.
- v25–v27: top-bar double-inset fixes on the Settings, Visual Inspiration,
  and artwork detail pages.
