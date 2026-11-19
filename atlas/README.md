# ALRADHY Atlas V47 (update for com.alradhy.worldwindow.atlas46, versionCode 4 -> 5)

Built by patching the installed V46 APK (ChatGPT build, no Gradle source). Only `assets/atlas.html`
changed, plus offline Leaflet in `assets/vendor/`. Native code, gallery pictures and data files are untouched.

- Satellite map only (Esri World Imagery + optional place-name overlay); street map and tour removed
- Two-row header: category tabs can no longer hide under the search box
- Physical-remote navigation: spatial D-pad focus, OK, Back, play/pause = read, FF/RW/channel = next/prev, 1-7 = categories, map explore mode (OK on map)
- Info card (period, years ago, coordinates, distance from Riyadh), Arabic Wikipedia summary, 4 nearest stations, favourites, resume last station, Arabic-normalised ranked search

Must be signed with the original "ALRADHY Atlas / TV Lab" key (SHA-256 65:56:1F:BF:...:66:A0) to install as an in-place update.
