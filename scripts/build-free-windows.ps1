# ALRADHY INFINITY - FREE offline-ready APK builder for Windows
# Build an update against the APK currently installed on the TV.
param(
    [string]$PreviousApk,
    [string]$SigningKeyPath = $env:INFINITY_KEYSTORE_PATH,
    [string]$SigningKeyAlias = $env:INFINITY_KEY_ALIAS,
    [string]$GradlePath
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Fail([string]$message) {
    Write-Host ""
    Write-Host "[ERROR] $message" -ForegroundColor Red
    exit 1
}

function Note([string]$message) { Write-Host "[INFINITY] $message" -ForegroundColor Cyan }

if (-not $PreviousApk) { $PreviousApk = Read-Host "Path to the APK currently installed on the TV" }
if (-not (Test-Path -LiteralPath $PreviousApk -PathType Leaf)) { Fail "The installed version's APK is required." }
$PreviousApk = (Resolve-Path -LiteralPath $PreviousApk).Path

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
    Fail "Android SDK not found. Install Android SDK Platform 36, then run again."
}
if (-not (Test-Path (Join-Path $sdk "platforms\android-36\android.jar"))) {
    Fail "Android SDK Platform 36 is missing. Select API 36 in Android Studio SDK Manager."
}
if (-not (Test-Path (Join-Path $sdk "build-tools\35.0.0\aapt2.exe"))) {
    $installed = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name
    Note "Installed build-tools: $($installed -join ', ')"
    Fail "SDK Build Tools 35.0.0 missing. Install it from Android Studio > SDK Manager > SDK Tools (Show Package Details)."
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Note "Android SDK: $sdk"
$aapt = Join-Path $sdk "build-tools\35.0.0\aapt.exe"
$apksigner = Join-Path $sdk "build-tools\35.0.0\apksigner.bat"

function Read-ApkIdentity([string]$Path) {
    $badging = & $aapt dump badging $Path
    if ($LASTEXITCODE -ne 0) { throw "Could not read the APK manifest." }
    $packageLine = $badging | Where-Object { $_ -like "package: *" } | Select-Object -First 1
    if ($packageLine -notmatch "^package: name='([^']+)' versionCode='([0-9]+)'") {
        throw "The APK does not declare a readable package name and version code."
    }
    $applicationId = $Matches[1]
    $versionCode = [long]$Matches[2]
    $verification = & $apksigner verify --print-certs $Path
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed." }
    $signers = @(foreach ($line in $verification) {
        if ($line -match '^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$') { $Matches[1] }
    })
    if ($signers.Count -ne 1) { throw "Expected exactly one APK signing certificate." }
    [pscustomobject]@{ ApplicationId = $applicationId; VersionCode = $versionCode; Certificate = $signers[0] }
}

function Assert-ApkUpdate($Previous, $Candidate) {
    if ($Candidate.ApplicationId -cne $Previous.ApplicationId) { throw "The application ID changed." }
    if ($Candidate.Certificate -ne $Previous.Certificate) { throw "The signing certificate differs from the installed version." }
    if ($Candidate.VersionCode -le $Previous.VersionCode) { throw "The update's version code must exceed the installed version." }
}

function Read-KeyCertificate([string]$KeyTool, [string]$Path, [string]$Alias) {
    $pem = & $KeyTool -exportcert -rfc -storetype PKCS12 -keystore $Path -alias $Alias -storepass:env INFINITY_STORE_PASS
    if ($LASTEXITCODE -ne 0) { throw "Could not read the certificate for the existing signing key." }
    $der = [Convert]::FromBase64String(($pem | Where-Object { $_ -notmatch '^-----' }) -join '')
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($sha.ComputeHash($der)).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

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

$previous = Read-ApkIdentity $PreviousApk
if ($previous.ApplicationId -cne 'com.alradhy.worldwindow.infinity') { Fail "Select the installed INFINITY APK." }
if (-not $SigningKeyPath) {
    $SigningKeyPath = Join-Path $localAppData "ALRADHY\INFINITY\PRIVATE-SIGNING\infinity-release.p12"
}
if (-not (Test-Path -LiteralPath $SigningKeyPath -PathType Leaf)) { Fail "The existing INFINITY signing key is required." }
$key = (Resolve-Path -LiteralPath $SigningKeyPath).Path
if (-not $SigningKeyAlias) { $SigningKeyAlias = 'infinity' }

# Portable Gradle: downloaded once and reused. Nothing is installed system-wide.
$gradleVersion = "8.11.1"
$toolchain = Join-Path $localAppData "ALRADHY\INFINITY\toolchain"
$gradleDir = Join-Path $toolchain ("gradle-" + $gradleVersion)
$gradleExe = Join-Path $gradleDir "bin\gradle.bat"
if ($GradlePath) {
    if (-not (Test-Path -LiteralPath $GradlePath -PathType Leaf)) { Fail "The specified Gradle executable does not exist." }
    $gradleExe = (Resolve-Path -LiteralPath $GradlePath).Path
}
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

$secure = Read-Host "Password for the existing INFINITY signing key (input hidden)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$password = $null
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
if ([string]::IsNullOrEmpty($password)) { Fail "Enter the existing signing password." }

$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
$signingVariables = 'INFINITY_KEYSTORE_PATH', 'INFINITY_STORE_PASS', 'INFINITY_KEY_ALIAS', 'INFINITY_KEY_PASS'
$savedSigning = @{}
foreach ($name in $signingVariables) { $savedSigning[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:INFINITY_KEYSTORE_PATH = $key
    $env:INFINITY_STORE_PASS = $password
    $env:INFINITY_KEY_ALIAS = $SigningKeyAlias
    $env:INFINITY_KEY_PASS = $password
    $keyCertificate = Read-KeyCertificate (Join-Path $javaHome 'bin\keytool.exe') $key $SigningKeyAlias
    if ($keyCertificate -ne $previous.Certificate) {
        throw "This key does not match the certificate of the installed version."
    }
    Note "Building release APK on YOUR laptop..."
    Push-Location $root
    try {
        & $gradleExe --no-daemon --stacktrace ":app:assembleRelease"
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit code $LASTEXITCODE). Read the error shown above." }
    } finally { Pop-Location }
    if (-not (Test-Path $apk)) { throw "Gradle reported success, but release APK was not found: $apk" }
    $candidate = Read-ApkIdentity $apk
    Assert-ApkUpdate $previous $candidate
    $outputDir = Join-Path $HOME "Downloads\ALRADHY-APKs\INFINITY"
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    $target = Join-Path $outputDir "ALRADHY-World-Window-INFINITY-update-$($candidate.VersionCode).apk"
    if (Test-Path -LiteralPath $target) { throw "An APK already exists at the destination." }
    Copy-Item -LiteralPath $apk -Destination $target
    Write-Host ""
    Write-Host "SUCCESS! Package, signing certificate and increasing version verified." -ForegroundColor Green
    Write-Host "APK: $target" -ForegroundColor Green
    Write-Host "SHA256: $((Get-FileHash -Algorithm SHA256 $target).Hash)"
    Write-Host ""
} catch {
    Write-Host ""
    Write-Host "LOCAL BUILD DID NOT SUCCEED: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "The source project and your signing key were preserved." -ForegroundColor Yellow
    exit 1
} finally {
    foreach ($name in $signingVariables) { [Environment]::SetEnvironmentVariable($name, $savedSigning[$name], 'Process') }
    Remove-Variable password -ErrorAction SilentlyContinue
}
