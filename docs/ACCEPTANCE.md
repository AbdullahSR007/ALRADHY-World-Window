# Device acceptance checklist — INFINITY v1

Run on Xiaomi TV Box S 3rd Gen, Android 14, using its real TV remote and air mouse.

## Build & install
- [ ] GitHub Actions gets an assigned runner and executes steps (not `runner_id=0`).
- [ ] Gradle build succeeds and artifact contains a real .apk.
- [ ] App installs without signature/version conflicts.
- [ ] App appears under TV launcher, is landscape and fills the screen.

## Interface
- [ ] Every category displays 25 unique topic names; ALL displays 100.
- [ ] DPAD UP/DOWN moves among category/cards/buttons; LEFT/RIGHT selects correctly.
- [ ] Remote OK, Back, mouse hover/click, wheel, horizontal swipe work.
- [ ] Images are visually distinct, correctly scaled and no card uses another scene's cached poster.
- [ ] Local time reflects each scene timezone.
- [ ] Long titles and Arabic titles are readable at 1080p and 4K.

## Sources
- [ ] Real Commons scenes resolve to images / videos and display credits.
- [ ] Real NASA scenes resolve to MP4/images and display usage information.
- [ ] Invalid, missing or unsupported links show an error, never a fake success.
- [ ] Screen remains responsive while sources are indexed.

## Offline
- [ ] Save one scene and switch to another app while downloading.
- [ ] Download All starts a foreground notification and continues without keeping UI open.
- [ ] Pause mid-file; restart resumes .part safely.
- [ ] Low free storage produces a clear failure and preserves completed files.
- [ ] Disconnect Wi-Fi: completed scenes play from device; uncached scenes don't falsely claim offline.
- [ ] Media survives updating **with the same signing certificate**.
- [ ] A provider 404 can be re-resolved to a current link.

## Stability
- [ ] Repeated open/back/next operations don't crash.
- [ ] Videos release player on exit, and playback stops when app backgrounds.
- [ ] Large galleries don't exceed memory or freeze the TV.
- [ ] A download completes with correct byte length.
- [ ] After restart, library persists and counts remain accurate.

**Pass criteria:** device-tested success, no crashes, stable signing, and repeatable offline playback. This document is an intended test plan, not a statement that tests have passed.
