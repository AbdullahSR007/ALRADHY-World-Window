# INFINITY update contract

The approved source baseline for this work is Claude's 10007, commit 44ae422.
The user confirms 10007 is installed with 100 downloaded videos and all picture packs.

- Every delivered APK, including test builds, must be an in-place update: preserve
  application ID `com.alradhy.worldwindow.infinity`, the existing signing certificate,
  and increase versionCode. Compare against the exact installed version's APK.
- Never uninstall, clear app data, change existing scene IDs, rename download directories,
  or replace the signing key. If identity/signature compatibility is uncertain, hold delivery.
- Keep `infinity-library-v1.json`, `pictures/<scene-id>/index.json`, media paths, and existing
  SharedPreferences compatible. New metadata belongs in separate additive storage.
- 10007 signer SHA-256: `f2d4cc3e847c93eca2f6024b30ca1acdde1c44a4bddcef78bf585e9c76afc5f8`.
  Do not commit keystores, passwords, or other signing secrets.
- Default full-screen slideshow must include ALL saved places, not just the selected city.
  A single destination and favourites/day/night mixes are explicit separate options.
- Preserve Claude's Ambient Mode, screensaver, TV banner and full-screen playback.
- Deliver one exact clearly named APK link first. State separately whether signature/version
  checks, data regression tests, and installation on the user's physical TV passed.
- Installing on the user's device requires explicit authorization. Tests in an isolated
  emulator or Robolectric must never be described as validation on the user's TV.

Agreed 10008 scope: add places with confirmation, offline Wikipedia facts, cached sun times,
destination/photo favourites and My World, resumable download requests and retry missing photos,
storage overview, slideshow timing/shuffle/fit options. Preserve all existing media.

10009 additions explicitly requested by the user:
- Arabic descriptions for ALL original and user-added places, including previously added
  entries and Space. Never display a legacy English description as the fallback. Fetch/cache
  Arabic descriptions for existing custom places without redownloading their media.
- Choose Cities/Nature/Oceans/Space when adding. Keep old custom entries in Cities by default;
  do not change any saved IDs. Space search must not require Earth coordinates.
- An explicit confirmed UI action may delete a custom place and ONLY its own downloaded
  media/pictures/preferences. Built-in destinations are protected. Block deletion during downloads.
- Any saved photo makes its pack READY. Twenty is a target, not a completeness requirement.
  Allow manual extra-photo requests and a user-controlled automatic top-up option for Download All.
- Compare the next APK to delivered 10008 as well as Claude 10007; signatures must match both.
