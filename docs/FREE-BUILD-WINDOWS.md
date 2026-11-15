# Build an INFINITY update on Windows

Every APK delivered for installation, including a test APK, must update the installed app in place. Keep the application ID and signing certificate, increase the version code, and preserve downloaded media and settings. Leave the installed app and its data in place during development. A device installation requires explicit approval.

If Android reports an install conflict, leave the existing installation in place. Read the installed package, version and certificate from the device, compare them against the exact file being opened, and capture the installer error before attempting a fix.

## Xiaomi update baseline

The user reported replacing the old installation with the Pictures and download-queue update. The expected installed baseline is `INFINITY-1.1-TV-pictures-update-10004.apk` (`versionCode 10004`). Verify the APK actually installed before publishing the next update. Preserve the downloads and settings created in this installation.

## Build the update

Use JDK 17+, Android SDK Platform 36 and Build Tools 35.0.0. The script caches Gradle 8.11.1 or accepts an existing installation through `-GradlePath`.

Open the isolated INFINITY development checkout and run:

```powershell
.\scripts\build-free-windows.cmd -PreviousApk 'C:\path\to\installed-version.apk' -SigningKeyPath 'C:\path\to\existing-signing-key.p12'
```

The previous APK must correspond to the version currently installed on the TV. If you omit its path, the script prompts for it. If you omit the signing key path, the script uses `INFINITY_KEYSTORE_PATH` or checks the original builder's location under `%LOCALAPPDATA%\ALRADHY\INFINITY\PRIVATE-SIGNING`. The script prompts locally for the existing password without echoing it.

The script verifies the previous APK with Android Build Tools and checks the key's public certificate before starting Gradle. It copies the candidate to `Downloads\ALRADHY-APKs\INFINITY` only when the package ID and signing certificate match and the version code increases. Keep the original signing key private.

## Android TV checks before release

Confirm the app card appears in the TV launcher, the remote reaches the playback and download controls, and the previously saved library is still available. A compilation or APK signature check alone cannot verify these device behaviors.
