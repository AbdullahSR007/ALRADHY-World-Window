# Build INFINITY for FREE on a Windows laptop

No GitHub Actions, no billing, no paid runners. The first build downloads dependencies, but future builds reuse them.

## One-time prerequisites

Install [Android Studio](https://developer.android.com/studio) (free). In Android Studio **Tools → SDK Manager**, ensure:
- Android SDK Platform 35 (Android 15 / API 35)
- Android SDK Build-Tools 35.0.0
- Android SDK Platform-Tools (optional for ADB install)

Android Studio includes a suitable JDK; the builder also accepts JDK 17+ installed separately.

If Git and GitHub CLI are missing, in Windows Terminal / PowerShell:

```powershell
winget install --id Git.Git -e
winget install --id GitHub.cli -e
```

Restart Terminal afterwards. Sign in to access this **private** repository:

```powershell
gh auth login
```

## First build (PowerShell)

```powershell
cd "$HOME\Documents"
gh repo clone AbdullahSR007/ALRADHY-World-Window -- --branch feature/infinity-v1
cd .\ALRADHY-World-Window
.\scripts\build-free-windows.cmd
```

On the first build, the script downloads and caches official Gradle 8.11.1, verifies Android SDK and JDK, and asks for a **new signing password with at least 12 characters**. It creates the permanent signing certificate in:

```text
%LOCALAPPDATA%\ALRADHY\INFINITY\PRIVATE-SIGNING\infinity-release.p12
```

**Back up this key securely outside the laptop.** Future builds must use the **same key and password**, or Android will reject updates without uninstall. The key is never committed to GitHub. Do not share the key or password.

If the build succeeds, APK is copied to:

```text
%USERPROFILE%\Downloads\ALRADHY-APKs\INFINITY\ALRADHY-World-Window-INFINITY-v1.0.0-alpha1.apk
```

This is a **release-signed** APK with the stable application ID `com.alradhy.worldwindow.infinity`. No payment or GitHub Actions quota is consumed.

## Subsequent builds

```powershell
cd "$HOME\Documents\ALRADHY-World-Window"
git checkout feature/infinity-v1
git pull --ff-only
.\scripts\build-free-windows.cmd
```

Enter the **same signing password**. Source updates must increase `versionCode` for Android to accept updates.

## Failure messages

- Missing SDK 35 or Build Tools 35: install them via Android Studio SDK Manager.
- Java missing: install Android Studio or JDK 17+.
- Incorrect password: enter your original password; **never delete the existing .p12 file**.
- Failed Gradle compilation: copy the **first useful error** from the console; no APK should be claimed until Gradle reports BUILD SUCCESSFUL.
- Network/timeouts: retry; portable Gradle and downloaded dependencies cache locally.

## Notes

No change to the GitHub repository's privacy. GitHub Actions remains **manual only** and is not needed for this build. A source-code checkout and local dependency downloads are free. The user's own data/internet usage is not part of GitHub billing.

The Android app's catalog lists 100 topics but **does not bundle 100 guaranteed 4K videos**. Test sources and offline download behavior on the Xiaomi TV Box before a production baseline.
