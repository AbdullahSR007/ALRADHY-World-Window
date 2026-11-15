# INFINITY Android TV 10008

Based on Claude's 10007 source at 44ae422. Existing media IDs, file paths,
picture indexes, app ID, and preferences remain compatible.

## Use

- **+ PLACE**: type a city or landmark. Choose a geographical Wikipedia result,
  confirm its description, and optionally save its picture pack. Duplicate places
  are rejected. New places persist in MY WORLD and ALL after restarting.
- **AMBIENT** and **SLIDESHOW • ALL PLACES** play pictures from the whole saved
  library. They explicitly reset a previous mix so selection of a city cannot
  silently restrict playback. OPTIONS has a separate single-destination slideshow.
- **OPTIONS** (or remote MENU) can favourite the selected destination or picture.
  MY WORLD contains favourite destinations and added places. Individual favourite
  pictures are included in the favourites Ambient mix.
- **Slideshow settings**: 8/12/20/30/60-second intervals, shuffle, whole-image fit
  without cropping, or full-screen fill with gentle motion.
- **About this place**: saved Wikipedia introduction, attribution, local time,
  and cached sunrise/sunset. **Save / refresh all place facts and sun times**
  prepares information for existing packs without downloading their pictures again.
- **Downloads and storage** shows saved totals, free space in both storage locations,
  incomplete packs and current status. Retry missing pictures skips complete packs.
  One automatic second pass retries incomplete picture packs. Video partial transfers
  retain the existing validator/range integrity checks.
- **Resume last interrupted download** remembers whether the request was pictures,
  videos, one place or all. It remains available after an interrupted process/reboot;
  resuming after a reboot is user-triggered, not a scheduled overnight job.

## Boundaries

Internet is needed to search for a new place and download its media/information.
Available legally reusable media determines pack completeness; the target is 20
distinct images, and incomplete counts remain visible. Search currently uses English
Wikipedia and includes only results with geographical coordinates.

Sun data is cached for seven days. Expired/unavailable data is labeled, never presented
as current. Day/night mixes select matching places when the slideshow starts. Places
without matched coordinates are excluded from those mixes. All-places playback still
includes their pictures. Refresh sun data online as needed. Facts may not exist for
some descriptive catalog entries; no substitute location is silently invented.

Sources: [Wikipedia API](https://www.mediawiki.org/wiki/API:Search) and
[Open-Meteo](https://open-meteo.com/en/docs). Wikipedia extracts retain source title/page
ID and CC BY-SA attribution. Open-Meteo sun data is attributed in the About dialog.

## Update verification

Use scripts/build-free-windows.ps1 with the actual installed APK as PreviousApk,
the existing signing key, and optionally OutputDirectory. The script checks the key
against the baseline before building, then verifies the candidate app ID, signing
certificate, and strictly increasing version before copying a deliverable.

Unit regression tests cover a legacy-format library containing 100 video fixtures
and 2,000 picture fixtures, byte-for-byte preservation after adding a place and
reloading, favourites, duplicate handling, all-places slideshow collection, expired
sun data, and skipping complete picture packs. These are data/behavior tests;
they do not prove that an installation was accepted on the user's physical TV.
