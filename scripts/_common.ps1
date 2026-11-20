# Shared helpers for the device test scripts. Dot-source it; do not run it.
#
# Paths come from the environment so nothing machine-specific is hard-coded:
#   SIERRA_TOOLCHAIN  folder holding jdk-17, android-sdk and gradle-user-home
#                     (default D:\Projects\android-toolchain)
#   SIERRA_SERIAL     adb serial of the phone (default: the only device attached)
#   SIERRA_RUNS       where screenshots, logs and built APKs go
#                     (default %USERPROFILE%\sierra-auto-runs, outside the repo)
#   SIERRA_STATE      where the DHU session state lives
#                     (default %LOCALAPPDATA%\sierra-auto\dhu)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SierraPackage = 'com.mdc159.sierra'
$SierraService = 'com.mdc159.sierra/com.mdc159.sierra.car.SierraCarAppService'
$GearheadPackage = 'com.google.android.projection.gearhead'
$GearheadLauncherService = 'GhAppLauncherService'
$PlayInstaller = 'com.android.vending'
$DhuPort = 5277

function Get-SierraToolchain {
    if ($env:SIERRA_TOOLCHAIN) { return $env:SIERRA_TOOLCHAIN }
    return 'D:\Projects\android-toolchain'
}

function Get-AndroidSdk {
    $candidates = @(
        (Join-Path (Get-SierraToolchain) 'android-sdk'),
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT
    ) | Where-Object { $_ }
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c 'platform-tools')) { return $c }
    }
    throw "Android SDK not found. Set SIERRA_TOOLCHAIN (folder with android-sdk inside) or ANDROID_HOME."
}

function Get-AdbPath {
    $adb = Join-Path (Get-AndroidSdk) 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
    return $adb
}

function Get-DhuPath {
    $dhu = Join-Path (Get-AndroidSdk) 'extras\google\auto\desktop-head-unit.exe'
    if (-not (Test-Path $dhu)) {
        throw "Desktop Head Unit not found at $dhu. Install it with: sdkmanager `"extras;google;auto`""
    }
    return $dhu
}

function Get-PhoneSerial {
    param([string]$Serial)
    if ($Serial) { return $Serial }
    if ($env:SIERRA_SERIAL) { return $env:SIERRA_SERIAL }
    $lines = & (Get-AdbPath) devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
    $ready = @($lines | Where-Object { $_ -match '^(\S+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($ready.Count -eq 1) { return $ready[0] }
    if ($ready.Count -eq 0) {
        $other = ($lines -join '; ')
        throw "No phone is attached and authorised for adb. adb devices says: [$other]. Plug the phone in, unlock it and accept the USB debugging prompt."
    }
    throw "More than one device is attached ($($ready -join ', ')). Pass -Serial or set SIERRA_SERIAL."
}

# Runs adb against the phone and returns its output lines. Pass the adb
# arguments as one array: Invoke-Adb $Serial 'shell', 'getprop'. Throws on a
# non-zero exit unless -AllowFail is given; then the caller reads $LASTEXITCODE.
function Invoke-Adb {
    param(
        [Parameter(Mandatory, Position = 0)][string]$Serial,
        [Parameter(Mandatory, Position = 1)][string[]]$AdbArgs,
        [switch]$AllowFail
    )
    $out = & (Get-AdbPath) -s $Serial @AdbArgs 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0 -and -not $AllowFail) {
        throw "adb $($AdbArgs -join ' ') failed ($LASTEXITCODE): $($out -join "`n")"
    }
    return $out
}

# Version and install-source record of Sierra on the phone, or $null when it is
# not installed.
function Get-InstallRecord {
    param([Parameter(Mandatory)][string]$Serial)
    $dump = Invoke-Adb $Serial 'shell', 'dumpsys', 'package', $SierraPackage -AllowFail
    $text = $dump -join "`n"
    if ($text -notmatch 'versionName=') { return $null }
    $get = { param($name) if ($text -match "(?m)^\s*$name=(\S+)") { $Matches[1] } else { $null } }
    return [pscustomobject]@{
        VersionName = (& $get 'versionName')
        VersionCode = [int](& $get 'versionCode')
        Installer   = (& $get 'installerPackageName')
        Initiating  = (& $get 'initiatingPackageName')
    }
}

function Get-RunsDir {
    $dir = if ($env:SIERRA_RUNS) { $env:SIERRA_RUNS } else { Join-Path $HOME 'sierra-auto-runs' }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    return (Resolve-Path $dir).Path
}

function Get-StateDir {
    $dir = if ($env:SIERRA_STATE) { $env:SIERRA_STATE } else { Join-Path $env:LOCALAPPDATA 'sierra-auto\dhu' }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    return (Resolve-Path $dir).Path
}

# The DHU session that dhu.ps1 started, if its processes are still alive.
function Get-ManagedDhu {
    $file = Join-Path (Get-StateDir) 'state.json'
    if (-not (Test-Path $file)) { return $null }
    try { $state = Get-Content $file -Raw | ConvertFrom-Json } catch { return $null }
    $dhu = Get-Process -Id $state.dhuPid -ErrorAction SilentlyContinue
    if (-not $dhu -or $dhu.ProcessName -ne 'desktop-head-unit') { return $null }
    # A recycled pid would have a different start time.
    if ([math]::Abs(($dhu.StartTime.ToUniversalTime() - [datetime]$state.dhuStartUtc).TotalSeconds) -gt 5) { return $null }
    return $state
}

# Any DHU process on this PC that dhu.ps1 did not start.
function Get-UnmanagedDhu {
    $managed = Get-ManagedDhu
    $managedPid = if ($managed) { $managed.dhuPid } else { -1 }
    return @(Get-Process desktop-head-unit -ErrorAction SilentlyContinue | Where-Object { $_.Id -ne $managedPid })
}

# Android Auto's own view of the session, read from its service dump. This
# works with the phone locked and needs no log settings.
function Get-CarState {
    param([Parameter(Mandatory)][string]$Serial)
    $dump = Invoke-Adb $Serial 'shell', 'dumpsys', 'activity', 'service', $GearheadPackage -AllowFail
    $text = $dump -join "`n"
    # Each Android Auto process dumps its own connection history; the session
    # is up when one of them ends in CONNECTED.
    $connected = $false
    $histories = [regex]::Matches($text, '(?s)Dumping history of connection state for Car Module \(human readable timestamps\):(.*?)\n\s*\n')
    foreach ($history in $histories) {
        $states = @($history.Groups[1].Value -split "`n" | Where-Object { $_ -match ':(\w+)\s*$' } | ForEach-Object { ($_ -split ':')[-1].Trim() })
        if ($states.Count -gt 0 -and $states[-1] -eq 'CONNECTED') { $connected = $true }
    }
    $foreground = if ($text -match 'primaryRegionAppPackageName:\s*([\w.]+)') { $Matches[1] } else { $null }
    $launcherShown = $text -match "Current app ProjectionApp\(appKey=AppKey\(componentName=ComponentInfo\{[^}]*$GearheadLauncherService\}"
    $unknownSources = if ($text -match 'unknownSourcesEnabled=(\w+)') { $Matches[1] } else { $null }
    $carInfo = if ($text -match '(?m)^\s*CarInfo:\s*(.+)$') { $Matches[1].Trim() } else { $null }
    $denied = @($dump | Where-Object { $_ -match 'Package DENIED' -and $_ -match [regex]::Escape($SierraPackage) })
    return [pscustomobject]@{
        Connected      = $connected
        Foreground     = $foreground
        LauncherShown  = [bool]$launcherShown
        UnknownSources = $unknownSources
        CarInfo        = $carInfo
        SierraDenied   = $denied
        Raw            = $dump
    }
}

function Test-PhoneLocked {
    param([Parameter(Mandatory)][string]$Serial)
    $dump = Invoke-Adb $Serial 'shell', 'dumpsys', 'window' -AllowFail
    return [bool]($dump | Where-Object { $_ -match 'isKeyguardShowing=true' })
}

# True while Play Protect is showing a dialog on the phone. An install waits
# behind it until someone answers.
function Test-PlayProtectPrompt {
    param([Parameter(Mandatory)][string]$Serial)
    $dump = Invoke-Adb $Serial 'shell', 'dumpsys', 'activity', 'activities' -AllowFail
    return [bool]($dump | Where-Object { $_ -match 'PlayProtectDialogsActivity' })
}

# adb install with a time limit. A plain `adb install` blocks for ever when
# Play Protect asks a question on the phone, which nobody sees if the phone is
# locked. Returns adb's output text; throws on timeout.
function Invoke-AdbInstall {
    param(
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string[]]$InstallArgs,
        [int]$TimeoutSec = 180
    )
    $psi = [System.Diagnostics.ProcessStartInfo]::new((Get-AdbPath))
    foreach ($a in (@('-s', $Serial, 'install') + $InstallArgs)) { $psi.ArgumentList.Add($a) }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $startedAt = (Invoke-Adb $Serial 'shell', "date '+%m-%d %H:%M:%S.000'" -AllowFail | Select-Object -First 1)
    $p = [System.Diagnostics.Process]::Start($psi)
    $stdout = $p.StandardOutput.ReadToEndAsync()
    $stderr = $p.StandardError.ReadToEndAsync()
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $told = $false
    while (-not $p.WaitForExit(5000)) {
        if (-not $told -and (Test-PlayProtectPrompt -Serial $Serial)) {
            $told = $true
            Write-Host ""
            Write-Host "Play Protect is asking a question on the phone and the install is waiting for the answer." -ForegroundColor Yellow
            if (Test-PhoneLocked -Serial $Serial) { Write-Host "The phone is locked. Unlock it to see the dialog." -ForegroundColor Yellow }
            Write-Host "Answer the dialog on the phone. Waiting up to $TimeoutSec s in total."
        }
        if ((Get-Date) -gt $deadline) {
            $p.Kill()
            $why = if ($told) { "Play Protect's dialog on the phone was not answered" } else { "the phone did not finish the install" }
            # The session is still waiting on the phone. Cancel it so it cannot
            # install by surprise later. Its id is only in the system log.
            $commits = Invoke-Adb $Serial 'logcat', '-d', '-T', "$startedAt", '-s', 'PackageInstallerSession:I' -AllowFail
            $ids = @($commits | ForEach-Object { if ($_ -match 'START COMMIT SESSION: id\{(\d+)\}') { $Matches[1] } })
            $cancelled = $false
            if ($ids.Count -gt 0) {
                $answer = Invoke-Adb $Serial 'shell', 'pm', 'install-abandon', $ids[-1] -AllowFail
                $cancelled = [bool]($answer -match 'Success')
            }
            $after = if ($cancelled) { "The waiting install was cancelled, so nothing was changed. Answer or dismiss the dialog on the phone, then run this again." } else { "The install may still be waiting on the phone and can complete once the dialog is answered; run this again afterwards to check." }
            throw "adb install gave up after $TimeoutSec s: $why. $after"
        }
    }
    return ($stdout.Result + "`n" + $stderr.Result).Trim()
}

function Write-Step { param([string]$Message) Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Note { param([string]$Message) Write-Host "    $Message" }
