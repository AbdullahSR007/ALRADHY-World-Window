# ALRADHY INFINITY - register this laptop as the self-hosted build runner for ALRADHY-World-Window.
# Same layout and command style as the other runners in C:\actions-runners. Runs as a Windows service.
$ErrorActionPreference = 'Continue'
$repo = 'AbdullahSR007/ALRADHY-World-Window'
$dir  = 'C:\actions-runners\ALRADHY-World-Window'
$log  = Join-Path (Split-Path -Parent $PSCommandPath) 'INFINITY-runner-setup-log.txt'

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    Start-Process powershell.exe -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}
Start-Transcript -Path $log -Force | Out-Null
function Say([string]$t, [string]$c = 'Cyan') { Write-Host "[INFINITY] $t" -ForegroundColor $c }
function Done([int]$code) { Stop-Transcript | Out-Null; Read-Host 'Press Enter to close'; exit $code }

if (Test-Path (Join-Path $dir '.runner')) {
    Say 'Runner already configured. Starting its service...'
    Get-Service 'actions.runner.*' | Where-Object { $_.Name -like '*World-Window*' } | Start-Service
    Get-Service 'actions.runner.*' | Where-Object { $_.Name -like '*World-Window*' } | Format-Table Name, Status
    Done 0
}
if (-not (Get-Command gh.exe -ErrorAction SilentlyContinue)) { Say 'GitHub CLI missing: winget install GitHub.cli, then gh auth login' 'Red'; Done 1 }
$token = (gh api -X POST "repos/$repo/actions/runners/registration-token" --jq .token) 2>$null
if (-not $token) { Say 'Could not get a registration token. Run: gh auth login   (then run this again)' 'Red'; Done 1 }

Say 'Downloading the latest official GitHub Actions runner...'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$release = Invoke-RestMethod 'https://api.github.com/repos/actions/runner/releases/latest'
$asset = $release.assets | Where-Object { $_.name -match '^actions-runner-win-x64-[0-9.]+\.zip$' } | Select-Object -First 1
if (-not $asset) { Say 'Runner download not found.' 'Red'; Done 1 }
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$zip = Join-Path $env:TEMP $asset.name
Invoke-WebRequest $asset.browser_download_url -OutFile $zip
Expand-Archive -Path $zip -DestinationPath $dir -Force
Remove-Item $zip -Force

Say 'Registering runner and installing it as a service...'
Push-Location $dir
& .\config.cmd --unattended --url "https://github.com/$repo" --token $token `
    --name "alradhy-laptop-infinity" --labels "infinity" --work "_work" --runasservice --replace
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { Say "Runner registration failed (exit $code)." 'Red'; Done 1 }
Get-Service 'actions.runner.*' | Where-Object { $_.Name -like '*World-Window*' } | Format-Table Name, Status, StartType
Say 'Runner is online. The queued INFINITY build starts automatically - tell Claude.' 'Green'
Say "To remove later: cd $dir; .\config.cmd remove --token (gh api -X POST repos/$repo/actions/runners/remove-token --jq .token)" 'Yellow'
Done 0
