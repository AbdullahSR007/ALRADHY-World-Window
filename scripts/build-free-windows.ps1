# ALRADHY INFINITY - FREE offline-ready APK builder for Windows
# Run from the cloned repository: powershell -ExecutionPolicy Bypass -File .\scripts\build-free-windows.ps1
# Needs Android Studio + SDK Platform 35; GitHub Actions, paid runners, and payments are not used.
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Fail([string]$message) {
    Write-Host ""
    Write-Host "[ERROR] $message" -ForegroundColor Red
    exit 1
}

function Note([string]$message) { Write-Host "[INFINITY] $message" -ForegroundColor Cyan }

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not (Test-Path (Join-Path $root "app\build.gradle"))) {
    Fail "Could not find the Android project. Clone the repo and run scripts/build-free-windows.cmd."
}

Note "Local, FREE build. No GitHub Actions quota or billing."
Note "Project: $root"
$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrWhiteSpace($localAppData)) { $localAppData = Join-Path $HOME "AppData\Local" }
$sdkCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $localAppData "Android\Sdk")
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$sdk = $null
foreach ($candidate in $sdkCandidates) {
    if (Test-Path (Join-Path $candidate "platforms")) { $sdk = (Resolve-Path $candidate).Path; break }
}
if (-not $sdk) {
    Fail "Android SDK not found. Install free Android Studio and SDK Platform 35 (Tools > SDK Manager), then run again."
}
if (-not (Test-Path (Join-Path $sdk "platforms\android-36\android.jar"))) {
    Fail "Android SDK Platform 35 is missing. In Android Studio > SDK Manager install Android 15 (API 35), then run again."
}
if (-not (Test-Path (Join-Path $sdk "build-tools\35.0.0\aapt2.exe"))) {
    $installed = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name
    Note "Installed build-tools: $($installed -join ', ')"
    Fail "SDK Build Tools 35.0.0 missing. Install it from Android Studio > SDK Manager > SDK Tools (Show Package Details)."
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Note "Android SDK: $sdk"

$studioJava = Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
$javaCandidates = @($env:JAVA_HOME, $studioJava) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$javaHome = $null
foreach ($candidate in $javaCandidates) {
    if (Test-Path (Join-Path $candidate "bin\java.exe")) { $javaHome = $candidate; break }
}
if (-not $javaHome) {
    $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCmd) { $javaHome = Split-Path (Split-Path $javaCmd.Source -Parent) -Parent }
}
if (-not $javaHome) { Fail "Java 17+ not found. Install Android Studio (includes a suitable JDK)." }
$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome "bin") + ";" + $env:Path
$oldPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $javaVersionOutput = (& (Join-Path $javaHome "bin\java.exe") -version 2>&1 | Out-String)
} finally {
    $ErrorActionPreference = $oldPreference
}
if (-not ($javaVersionOutput -match 'version "(\d+)(?:\.|")')) { Fail "Could not determine Java version: $javaVersionOutput" }
if ([int]$Matches[1] -lt 17) { Fail "Java 17 or newer required. Found version $($Matches[1])." }
Note "JDK: $javaHome"

# Portable Gradle: downloaded once and reused. Nothing is installed system-wide.
$gradleVersion = "8.11.1"
$toolchain = Join-Path $localAppData "ALRADHY\INFINITY\toolchain"
$gradleDir = Join-Path $toolchain ("gradle-" + $gradleVersion)
$gradleExe = Join-Path $gradleDir "bin\gradle.bat"
if (-not (Test-Path $gradleExe)) {
    New-Item -ItemType Directory -Force -Path $toolchain | Out-Null
    $zip = Join-Path $toolchain ("gradle-" + $gradleVersion + "-bin.zip")
    Note "Downloading official Gradle $gradleVersion one time..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri ("https://services.gradle.org/distributions/gradle-" + $gradleVersion + "-bin.zip") -OutFile $zip
    Note "Extracting Gradle..."
    Expand-Archive -Path $zip -DestinationPath $toolchain -Force
    Remove-Item $zip -Force
}
if (-not (Test-Path $gradleExe)) { Fail "Gradle executable unavailable: $gradleExe" }

# Release key stays on the laptop, outside the repo, and is reused for every APK update.
$private = Join-Path $localAppData "ALRADHY\INFINITY\PRIVATE-SIGNING"
$key = Join-Path $private "infinity-release.p12"
New-Item -ItemType Directory -Force -Path $private | Out-Null
Write-Host ""
if (Test-Path $key) {
    Note "Using EXISTING signing key (required to update without uninstall)."
} else {
    Write-Host "FIRST BUILD ONLY: Create your permanent private signing key." -ForegroundColor Yellow
}
Write-Host "Keep the same password and key forever. Back up the .p12 securely." -ForegroundColor Yellow
$secure = Read-Host "INFINITY signing password (12+ characters; input hidden)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$password = $null
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
if ([string]::IsNullOrWhiteSpace($password) -or $password.Length -lt 12) {
    Fail "Choose a password with at least 12 characters."
}
$keytool = Join-Path $javaHome "bin\keytool.exe"
if (-not (Test-Path $key)) {
    Note "Generating a stable, private release signing certificate."
    & $keytool -genkeypair -alias infinity -keyalg RSA -keysize 3072 -validity 10000 -storetype PKCS12 -keystore $key -dname "CN=ALRADHY Infinity, OU=Personal TV, O=ALRADHY, C=SA" -storepass $password -keypass $password -noprompt
    if ($LASTEXITCODE -ne 0) { Fail "Signing key generation failed." }
}
& $keytool -list -keystore $key -storepass $password -alias infinity *> $null
if ($LASTEXITCODE -ne 0) { Fail "Wrong signing password, or the signing key is damaged. DO NOT delete the key." }
$env:INFINITY_KEYSTORE_PATH = $key
$env:INFINITY_STORE_PASS = $password
$env:INFINITY_KEY_ALIAS = "infinity"
$env:INFINITY_KEY_PASS = $password

$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
try {
    Note "Building release APK on YOUR laptop..."
    Push-Location $root
    try {
        & $gradleExe --no-daemon --stacktrace ":app:assembleRelease"
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit code $LASTEXITCODE). Read the error shown above." }
    } finally { Pop-Location }
    if (-not (Test-Path $apk)) { throw "Gradle reported success, but release APK was not found: $apk" }
    $outputDir = Join-Path $HOME "Downloads\ALRADHY-APKs\INFINITY"
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    $target = Join-Path $outputDir "ALRADHY-World-Window-INFINITY-v1.0.0-alpha1.apk"
    Copy-Item -LiteralPath $apk -Destination $target -Force
    Write-Host ""
    Write-Host "SUCCESS! Signed APK built without paying GitHub." -ForegroundColor Green
    Write-Host "APK: $target" -ForegroundColor Green
    Write-Host "SHA256: $((Get-FileHash -Algorithm SHA256 $target).Hash)"
    Write-Host ""
    Write-Host "BACK UP THIS SIGNING KEY SAFELY: $key" -ForegroundColor Yellow
    Write-Host "Never delete it. Same app ID + same key + increasing versionCode = in-place updates." -ForegroundColor Yellow
} catch {
    Write-Host ""
    Write-Host "LOCAL BUILD DID NOT SUCCEED: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "The source project and your signing key were preserved." -ForegroundColor Yellow
    exit 1
} finally {
    Remove-Item Env:\INFINITY_STORE_PASS -ErrorAction SilentlyContinue
    Remove-Item Env:\INFINITY_KEY_PASS -ErrorAction SilentlyContinue
    Remove-Variable password -ErrorAction SilentlyContinue
}
