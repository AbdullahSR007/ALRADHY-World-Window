# ALRADHY INFINITY - one-click in-place update builder (Windows)
# 1. Finds the keystore whose certificate signed the installed APK (never creates a new key).
# 2. Fetches the development branch into a private build folder.
# 3. Runs build-free-windows.ps1, which refuses any APK whose package, certificate or version
#    code would not install as an in-place update.
# 4. Copies the verified APK next to this script and writes a log for review.
param(
    [string]$PreviousApk,
    [string]$Branch = 'feature/infinity-v1-1-download-safety',
    [string]$Repository = 'https://github.com/AbdullahSR007/ALRADHY-World-Window.git',
    [string]$OutputName = 'INFINITY-1.1-Android-TV-ambient-update-10007.apk'
)
# Windows PowerShell 5.1 turns native stderr (git progress, keytool warnings) into terminating
# errors under 'Stop'; every step below checks its own exit code instead.
$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $here 'INFINITY-build-log.txt'
"INFINITY update build  $(Get-Date -Format s)" | Set-Content -LiteralPath $log -Encoding utf8
function Say([string]$text, [string]$color = 'Cyan') {
    Write-Host "[INFINITY] $text" -ForegroundColor $color
    Add-Content -LiteralPath $log -Value "[INFINITY] $text" -Encoding utf8
}
function Stop-Build([string]$text) { Say "STOPPED: $text" 'Red'; Say 'Nothing was installed and no existing file was changed.' 'Yellow'; exit 1 }

# ---- Installed APK baseline -------------------------------------------------------------
if (-not $PreviousApk) {
    $PreviousApk = Get-ChildItem -LiteralPath $here -Filter '*.apk' -File |
        Where-Object { $_.Name -like '*10006*' } | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $PreviousApk -or -not (Test-Path -LiteralPath $PreviousApk)) { Stop-Build 'The installed (10006) APK was not found next to this script.' }
Say "Installed baseline: $PreviousApk"

# ---- Tools --------------------------------------------------------------------------------
$javaHome = @($env:JAVA_HOME, (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr')) |
    Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\keytool.exe')) } | Select-Object -First 1
if (-not $javaHome) {
    $cmd = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($cmd) { $javaHome = Split-Path (Split-Path $cmd.Source -Parent) -Parent }
}
if (-not $javaHome) { Stop-Build 'Java (keytool) not found. Install Android Studio.' }
$keytool = Join-Path $javaHome 'bin\keytool.exe'
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), 'C:\Android\Sdk') |
    Where-Object { $_ -and (Test-Path (Join-Path $_ 'build-tools')) } | Select-Object -First 1
if (-not $sdk) { Stop-Build 'Android SDK not found.' }
$apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apksigner) { Stop-Build 'apksigner not found in the Android SDK build-tools.' }
if (-not (Get-Command git.exe -ErrorAction SilentlyContinue)) { Stop-Build 'Git is not installed (winget install Git.Git).' }

$certLines = & $apksigner verify --print-certs $PreviousApk 2>&1
$expected = @($certLines | ForEach-Object { if ($_ -match 'certificate SHA-256 digest: ([0-9a-f]{64})') { $Matches[1] } })
if ($expected.Count -ne 1) { Stop-Build "Could not read exactly one signing certificate from the installed APK." }
$expected = $expected[0]
Say "Installed APK certificate SHA-256: $expected"

# ---- Find the matching key (debug or release), read-only -----------------------------------
$roots = @($env:USERPROFILE, 'C:\actions-runners', 'C:\Windows\ServiceProfiles', (Join-Path $env:LOCALAPPDATA 'ALRADHY'), 'C:\Android') |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique
Say 'Searching for keystores (this can take a few minutes)...'
$files = foreach ($r in $roots) {
    Get-ChildItem -LiteralPath $r -Recurse -Force -File -ErrorAction SilentlyContinue -Include '*.keystore','*.jks','*.p12','*.pfx' |
        Where-Object { $_.Length -lt 200KB -and $_.FullName -notmatch '\\(node_modules|\.gradle\\caches)\\' }
}
$files = @($files | Sort-Object FullName -Unique)
Say "Found $($files.Count) keystore file(s)."

function Find-Alias($file, $type, $pass) {
    $out = & $keytool -list -v -keystore $file -storetype $type -storepass $pass 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    $alias = $null
    foreach ($line in $out) {
        if ($line -match '^Alias name:\s*(.+)$') { $alias = $Matches[1].Trim() }
        elseif ($line -match '^\s*SHA256:\s*([0-9A-F:]+)\s*$' -and $alias) {
            if (($Matches[1] -replace ':', '').ToLowerInvariant() -eq $expected) { return $alias }
        }
    }
    return $null
}
$match = $null
$locked = @()
foreach ($f in $files) {
    $opened = $false
    foreach ($type in 'PKCS12', 'JKS') {
        $alias = Find-Alias $f.FullName $type 'android'
        & $keytool -list -keystore $f.FullName -storetype $type -storepass 'android' *> $null
        if ($LASTEXITCODE -eq 0) { $opened = $true }
        if ($alias) { $match = [pscustomobject]@{ Path = $f.FullName; Type = $type; Alias = $alias; Pass = 'android' }; break }
    }
    if ($match) { break }
    Say ("  checked: " + $f.FullName + $(if ($opened) { '  (different certificate)' } else { '  (needs another password)' }))
    if (-not $opened) { $locked += $f }
}
if (-not $match -and $locked.Count -gt 0) {
    foreach ($f in $locked) {
        $secure = Read-Host "Password for $($f.FullName) (Enter to skip)" -AsSecureString
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
        if (-not $plain) { continue }
        foreach ($type in 'PKCS12', 'JKS') {
            $alias = Find-Alias $f.FullName $type $plain
            if ($alias) { $match = [pscustomobject]@{ Path = $f.FullName; Type = $type; Alias = $alias; Pass = $plain }; break }
        }
        if ($match) { break }
    }
}
if (-not $match) {
    Stop-Build "No keystore on this PC matches the installed APK's certificate. Building with any other key would force an uninstall and lose your downloads, so the build is held. Tell Claude where the 10006 build was signed."
}
Say "Matching key: $($match.Path)  [$($match.Type), alias '$($match.Alias)']" 'Green'

# ---- Source -----------------------------------------------------------------------------
$src = Join-Path $env:LOCALAPPDATA 'ALRADHY\INFINITY\update-source'
if (Test-Path (Join-Path $src '.git')) {
    Say "Updating source in $src"
    git -C $src fetch --depth 20 origin $Branch 2>&1 | ForEach-Object { Add-Content -LiteralPath $log -Value $_ }
    if ($LASTEXITCODE -ne 0) { Stop-Build 'git fetch failed (sign in with: gh auth login).' }
    git -C $src checkout -q -B $Branch FETCH_HEAD
    git -C $src reset -q --hard FETCH_HEAD
} else {
    Say "Cloning $Branch into $src"
    New-Item -ItemType Directory -Force -Path (Split-Path $src) | Out-Null
    git clone --depth 20 --branch $Branch $Repository $src 2>&1 | ForEach-Object { Add-Content -LiteralPath $log -Value $_ }
    if ($LASTEXITCODE -ne 0) { Stop-Build 'git clone failed (sign in with: gh auth login).' }
}
Say ("Source commit: " + (git -C $src log -1 --format='%h %s'))

# Move an older copy of the same output aside instead of overwriting or deleting it.
$builderOut = Join-Path $HOME 'Downloads\ALRADHY-APKs\INFINITY'
Get-ChildItem -LiteralPath $builderOut -Filter 'ALRADHY-World-Window-INFINITY-update-*.apk' -ErrorAction SilentlyContinue |
    ForEach-Object { Rename-Item -LiteralPath $_.FullName -NewName ($_.Name + '.previous-' + (Get-Date -Format 'yyyyMMddHHmmss')) }

# ---- Build and verify ---------------------------------------------------------------------
$env:INFINITY_STORE_PASS = $match.Pass
try {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $src 'scripts\build-free-windows.ps1') `
        -PreviousApk $PreviousApk -SigningKeyPath $match.Path -SigningKeyAlias $match.Alias -StoreType $match.Type 2>&1 |
        Tee-Object -FilePath $log -Append
    $code = $LASTEXITCODE
} finally { Remove-Item Env:\INFINITY_STORE_PASS -ErrorAction SilentlyContinue }
if ($code -ne 0) { Stop-Build "The verified build did not complete (exit $code). See $log" }

$built = Get-ChildItem -LiteralPath $builderOut -Filter 'ALRADHY-World-Window-INFINITY-update-*.apk' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $built) { Stop-Build 'The builder reported success but no APK was found.' }
$final = Join-Path $here $OutputName
if (Test-Path -LiteralPath $final) { Rename-Item -LiteralPath $final -NewName ($OutputName + '.previous-' + (Get-Date -Format 'yyyyMMddHHmmss')) }
Copy-Item -LiteralPath $built.FullName -Destination $final
& $apksigner verify --print-certs $final 2>&1 | Tee-Object -FilePath $log -Append | Out-Null
Say "READY: $final" 'Green'
Say "SHA-256 file hash: $((Get-FileHash -Algorithm SHA256 $final).Hash)" 'Green'
Say 'Tell Claude it is done. Nothing was installed on the TV.' 'Green'
