# ALRADHY WORLD WINDOW — INFINITY

**Android TV • cinematic world exploration • offline-first • independent repository**

A completely new application. The deleted World Window's code, links and unstable downloads are **not reused**. Source is on `feature/infinity-v1`; `main` is kept as a clean bootstrap until build/device validation.

## What is implemented in v1 alpha

- **100 distinct curated destination topics:** 25 Cities, 25 Nature, 25 Oceans, 25 Space; bilingual English/Arabic scene names.
- **Cinematic native TV screen:** a 1920×1080 scalable canvas, 5-card filmstrip, prominent hero, glass panels, gold accents and local destination clock.
- **TV controls:** remote D-pad/OK, Back exits normally, mouse click and hover, wheel, horizontal swipe.
- **Media discovery:** Wikimedia Commons and NASA public APIs. Scene metadata is *not* a guarantee of available media; an item becomes playable only if the provider returns usable licensed content.
- **Full-screen player:** Media3 video streaming or local playback, still-photo Ken Burns presentation, automatic next scene, next/previous, credits and local time.
- **Download All 100:** Android foreground service continues when switching apps; individual downloads, durable .part resume, retries, free-space safety checks, progress notification and offline media index.
- **Offline playback:** media assets stored under app-specific Android Movies/INFINITY. No dependency on original source URLs after a file is fully downloaded. App updates preserve this location when package name and signing certificate remain identical.
- **Attribution:** only explicitly license-tagged Commons candidates accepted; NASA asset details and media usage notes displayed.

## Important truth about the library

100 = 100 distinct destinations, **not 100 pre-bundled working 4K videos**. Source resolution happens online. A scene may yield a video, a photograph, or no supported file; metadata/codec/quality and suitability must be checked on the actual TV. Availability of Commons/NASA content and copyrights can change. Download All may require many gigabytes depending on media selected. No large binaries are stored in GitHub.

If a provider cannot resolve an asset, the UI keeps that destination visible, indicates Retry Available, and never invents a successful download.

## Build

Workflow: [Build INFINITY Android TV APK](../../actions/workflows/build-apk.yml)

This initial branch uses Android Gradle Plugin 8.9.2, Gradle 8.11.1, JDK 17, compile SDK 35 and Android Media3 1.11.1. It targets Android 14 and supports Android API 26+.

When release signing secrets are configured, the workflow emits a **stable release-signed APK**. Without them, it emits an **experimental debug APK**. Debug APKs signed by different GitHub runners may not upgrade each other: do **not** use them to build a long-lived downloaded library.

### Configure stable, non-destructive updates (Termux)

1. Open Termux on your personal Android phone/tablet.
2. Clone this repository and run `bash scripts/setup-release-signing-termux.sh` once. You will choose a strong password.
3. Back up the generated private key outside your device in a secure place. GitHub gets the key only as an encrypted Actions secret. The repository does **not** contain the private key.
4. Trigger a workflow on `feature/infinity-v1`. The APK versionCode will need increasing for later updates.

The script is safe to rerun using the same signing key/password; do not generate a replacement key for the same package.

### Download latest successful APK

Run `bash scripts/get-latest-apk-termux.sh` inside the cloned repository. It looks for the latest **successful** workflow and downloads its artifact to `Download/ALRADHY-APKs/INFINITY`.

## Current build blocker

On 2026-11-15, GitHub Actions attempts in this newly created **private** repository failed *before a runner started*: `runner_id=0`, empty steps, missing job logs. This is a GitHub runner / account-level startup issue, not a reported Java compilation result. **No working APK has yet been verified.** Check [Actions](../../actions) for the exact GitHub annotation. If it mentions payments or spending limits, review GitHub account billing and Actions budgets. Otherwise check Settings → Actions → General and contact GitHub Support with the failed run URL. A public repository may have free hosted compute, but change visibility only if comfortable publishing the source.

## Install and testing

On the Xiaomi TV Box, after downloading a successful signed APK, install it normally or via ADB. If ADB refuses `/sdcard/Download` due to SELinux:

```sh
adb shell 'cp /sdcard/Download/INFINITY.apk /data/local/tmp/infinity.apk && chmod 644 /data/local/tmp/infinity.apk && pm install -r /data/local/tmp/infinity.apk'
```

Replace the input filename with the actual downloaded APK. Avoid `-d` downgrades. Before long downloads, complete [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md).

**Storage note:** Android app-specific files survive updates, but are removed if the application is uninstalled or its data is cleared. This app deliberately does not request broad storage access. Future Storage Access Framework USB-folder support is tracked separately.

## Independent identity

- Application ID: `com.alradhy.worldwindow.infinity`
- Repository: `AbdullahSR007/ALRADHY-World-Window`
- Branch: `feature/infinity-v1`
- Never edits Discovery Universe, Smart TV Player, Cinema Lab, or any other project.
