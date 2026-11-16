# INFINITY Android TV 10009

In-place update over 10008 or Claude's 10007, with the same signing certificate and
application ID. Existing scene IDs, media paths and picture indexes are unchanged.

- **Arabic descriptions everywhere:** 100 original destinations have built-in Arabic
  descriptions available offline. Legacy English cached descriptions are never used as
  visible fallback text. Arabic Wikipedia introductions are fetched and cached when
  available. Existing custom places missing Arabic facts are refreshed in the background
  on launch; this does not redownload pictures. New custom places fetch Arabic before
  confirmation. If the network/source cannot provide Arabic, a clear Arabic notice appears.
  Author names and licence credits remain attributed as supplied by the media provider.
- **Choose a category before adding:** Cities, Nature, Oceans, Space. The choice survives
  restart; old 10008 custom places default to Cities. Arabic and English search are supported.
  Space results do not require terrestrial coordinates, and use NASA as an additional image
  source. Cross-language article links supply an English media search term when available.
- **Delete added places:** select an added place, OPTIONS, then the Arabic delete action.
  Confirming removes only that place and its own saved files/favourites. Original catalog
  places cannot be deleted. Pause downloads and wait for discovery before deleting.
- **Every nonempty picture pack is ready:** the UI shows the saved count, not "incomplete"
  for fewer than twenty. Picture management offers keeping the current pack, asking for
  more (up to twenty), or deleting that pack. Download All skips nonempty packs by default.
- **Optional automatic top-up:** OPTIONS has an Arabic on/off toggle. When enabled,
  Download All also tries to add pictures to existing packs, with one bounded retry pass.
  This applies to a user-started Download All run, not a periodic/scheduled background job.

Default full-screen slideshows still include all saved places. Single-place and favourites
mixes remain explicit options.

Validation includes the previous 100-video/2,000-picture preservation regression plus tests
for category persistence, legacy category defaults, protected deletion, Arabic fallback
across all 100 catalog entries, and a two-photo pack being ready/skipped by manual-mode bulk
downloads. APK comparison is separate from acceptance on the user's physical TV.
