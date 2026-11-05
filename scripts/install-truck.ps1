#requires -Version 7
<#
.SYNOPSIS
Installs Sierra on the phone with the install record a real head unit is expected to accept.

.DESCRIPTION
Android Auto on a real head unit reads who STARTED the install. It trusts the
installer of record only when the starter was the system package installer.
The record wanted is therefore:

    installerPackageName  = com.android.vending
    initiatingPackageName = com.google.android.packageinstaller

`adb install -i com.android.vending` cannot produce it: the initiator is always
com.android.shell. Starting the system installer from the shell cannot either
on the Galaxy A54 (package installer 17): that installer drops the requested
installer name when the launcher holds INSTALL_PACKAGES, which the shell does.
Only an ordinary app can ask, so Sierra asks for itself.

NOT YET SEEN WORKING. On the owner's A54 a work profile forbids installs from
unknown sources for the whole phone, so the system installer refuses Sierra.
The script checks for that first and stops. Steps 1 to 3 (up to the download)
were run on that phone; steps 4 and 5 were not reached.

What the script does:

  1. Puts the build on the phone with `adb install -r -i com.android.vending`
     (a record the truck is expected to reject; this step only gets a Sierra
     with the "system installer" button onto the phone).
  2. Allows Sierra to request installs ("Install unknown apps" for Sierra).
  3. Opens Sierra -> Updates, taps Check, Download, and
     "Install through the system installer (Play record)".
  4. Taps Update in the system dialog and waits for the install to finish.
  5. Prints the record and fails unless it is the wanted pair.

Step 3 installs the LATEST GITHUB RELEASE, because that is what the Updates
screen downloads. -Apk and -Tag only choose what step 1 puts on the phone. A
local build with a higher versionCode than the latest release cannot be
replaced by it; build with a versionCode at or below the release.

The phone must be unlocked with the screen on, and needs internet for step 3.
See docs/TRUCK-INSTALL.md for what is measured and what is still inferred.

.EXAMPLE
scripts\install-truck.ps1
Latest release through adb, then again through the system installer.

.EXAMPLE
scripts\install-truck.ps1 -SkipAdbInstall
Sierra on the phone already has the button; only redo the system installer step.

.EXAMPLE
scripts\install-truck.ps1 -Apk app\build\outputs\apk\release\app-release.apk
#>
[CmdletBinding(DefaultParameterSetName = 'Latest')]
param(
    [Parameter(ParameterSetName = 'Tag', Mandatory)][string]$Tag,
    [Parameter(ParameterSetName = 'Apk', Mandatory)][string]$Apk,
    [string]$Serial,
    [string]$Repo = 'mdc159/sierra-auto',
    # Skip step 1 when the Sierra on the phone already has the system installer button.
    [switch]$SkipAdbInstall,
    # Seconds to wait for each step on the phone.
    [int]$TimeoutSec = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
trap { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }

$Package = 'com.mdc159.sierra'
$Play = 'com.android.vending'
$UiDump = '/data/local/tmp/sierra-ui.xml'

function Write-Step([string]$Text) { Write-Host "== $Text" -ForegroundColor Cyan }

function Get-AdbPath {
    $toolchain = if ($env:SIERRA_TOOLCHAIN) { $env:SIERRA_TOOLCHAIN } else { 'D:\Projects\android-toolchain' }
    $roots = @((Join-Path $toolchain 'android-sdk'), $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT) | Where-Object { $_ }
    foreach ($r in $roots) {
        $p = Join-Path $r 'platform-tools\adb.exe'
        if (Test-Path $p) { return $p }
    }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw "adb not found. Set SIERRA_TOOLCHAIN (folder with android-sdk inside) or ANDROID_HOME."
}
$Adb = Get-AdbPath

function Get-PhoneSerial([string]$Wanted) {
    if ($Wanted) { return $Wanted }
    if ($env:SIERRA_SERIAL) { return $env:SIERRA_SERIAL }
    $ready = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '^(\S+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($ready.Count -eq 1) { return $ready[0] }
    if ($ready.Count -eq 0) { throw "No phone is attached and authorised for adb. Plug it in, unlock it and accept the USB debugging prompt." }
    throw "More than one device is attached ($($ready -join ', ')). Pass -Serial or set SIERRA_SERIAL."
}

# Runs one command line in the phone's shell and returns its output as one string.
function Invoke-Shell([string]$Command, [switch]$AllowFail) {
    $out = (& $Adb -s $Serial shell $Command 2>&1 | ForEach-Object { "$_" }) -join "`n"
    if ($LASTEXITCODE -ne 0 -and -not $AllowFail) { throw "adb shell '$Command' failed ($LASTEXITCODE): $out" }
    return $out
}

function Get-InstallRecord {
    $dump = Invoke-Shell "dumpsys package $Package" -AllowFail
    if ($dump -notmatch 'versionName=') { return $null }
    $field = { param($name) if ($dump -match "(?m)$name=(\S+)") { $Matches[1] } else { $null } }
    [pscustomobject]@{
        VersionName = & $field 'versionName'
        VersionCode = & $field 'versionCode'
        Installer   = & $field 'installerPackageName'
        Initiating  = & $field 'initiatingPackageName'
        Originating = & $field 'originatingPackageName'
        Source      = & $field 'packageSource'
        Updated     = if ($dump -match '(?m)lastUpdateTime=(.+)$') { $Matches[1].Trim() } else { $null }
    }
}

# Every node on screen that has text: text, package, enabled, and the centre of its bounds.
function Get-UiNodes {
    $text = Invoke-Shell "uiautomator dump $UiDump >/dev/null 2>&1; cat $UiDump" -AllowFail
    $start = $text.IndexOf('<?xml')
    if ($start -lt 0) { return @() }
    try { $doc = [xml]$text.Substring($start) } catch { return @() }
    foreach ($n in $doc.SelectNodes('//node[@text!=""]')) {
        if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            [pscustomobject]@{
                Text    = $n.text
                Package = $n.package
                Enabled = $n.enabled -eq 'true'
                X       = ([int]$Matches[1] + [int]$Matches[3]) / 2
                Y       = ([int]$Matches[2] + [int]$Matches[4]) / 2
                Top     = [int]$Matches[2]
                Bottom  = [int]$Matches[4]
            }
        }
    }
}

function Find-UiNode([string]$Pattern, [string]$PackagePattern = '.') {
    Get-UiNodes | Where-Object { $_.Text -match $Pattern -and $_.Package -match $PackagePattern } | Select-Object -First 1
}

# Waits for a node whose text matches, scrolling Sierra's screen down while it is not visible.
function Wait-UiNode([string]$Pattern, [string]$What, [string]$PackagePattern = '.', [switch]$Enabled, [switch]$NoScroll, [string]$FailPattern, [int]$Seconds = $TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    $scrolls = 0
    while ((Get-Date) -lt $deadline) {
        $nodes = @(Get-UiNodes)
        if ($FailPattern) {
            $bad = $nodes | Where-Object { $_.Text -match $FailPattern } | Select-Object -First 1
            if ($bad) { throw "$What failed. The phone says: $($bad.Text)" }
        }
        $hit = $nodes | Where-Object { $_.Text -match $Pattern -and $_.Package -match $PackagePattern } | Select-Object -First 1
        if ($hit -and (-not $Enabled -or $hit.Enabled)) { return $hit }
        if (-not $hit -and -not $NoScroll -and $scrolls -lt 8) {
            Invoke-Shell 'input swipe 540 1700 540 900 250' | Out-Null
            $scrolls++
        }
        Start-Sleep -Milliseconds 700
    }
    throw "$What did not appear on the phone within $Seconds s (looked for '$Pattern')."
}

# Top edge of the navigation bar. Sierra's screens draw under it, so the centre of the last button
# on a screen can lie inside the bar, where a tap does not reach the button.
function Get-NavBarTop {
    $window = Invoke-Shell 'dumpsys window' -AllowFail
    if ($window -match 'type=navigationBars frame=\[\d+,(\d+)\]') { return [int]$Matches[1] }
    if ((Invoke-Shell 'wm size' -AllowFail) -match '(\d+)x(\d+)') { return [int]([int]$Matches[2] * 0.93) }
    return 100000
}

function Invoke-Tap($Node) {
    $y = [int]$Node.Y
    $limit = $script:NavBarTop - 12
    if ($y -gt $limit -and $Node.Top -lt $limit) { $y = [int](($Node.Top + $limit) / 2) }
    Invoke-Shell "input tap $([int]$Node.X) $y" | Out-Null
}

$Serial = Get-PhoneSerial $Serial
$NavBarTop = Get-NavBarTop

# --- 1. get a build with the system installer button onto the phone -----------------------------
if (-not $SkipAdbInstall) {
    if ($PSCmdlet.ParameterSetName -eq 'Apk') {
        if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
        $apkPath = (Resolve-Path $Apk).Path
        $source = "local file $apkPath"
    } else {
        if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "The GitHub CLI (gh) is needed to download a release." }
        if (-not $Tag) {
            $Tag = (& gh release view -R $Repo --json tagName -q .tagName)
            if ($LASTEXITCODE -ne 0 -or -not $Tag) { throw "Could not read the latest release of $Repo." }
        }
        $dir = Join-Path ([IO.Path]::GetTempPath()) "sierra-auto-apk\$Tag"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        & gh release download $Tag -R $Repo -p 'sierra-auto.apk' -D $dir --clobber
        if ($LASTEXITCODE -ne 0) { throw "Could not download sierra-auto.apk from release $Tag of $Repo." }
        $apkPath = Join-Path $dir 'sierra-auto.apk'
        $source = "release $Tag"
    }
    Write-Step "Step 1: adb install of $source (initiator will be com.android.shell; fixed in step 3)"
    $text = (& $Adb -s $Serial install -r -i $Play $apkPath 2>&1 | ForEach-Object { "$_" }) -join "`n"
    if ($text -notmatch 'Success') { throw "adb install failed: $text" }
}

$before = Get-InstallRecord
if (-not $before) { throw "Sierra is not installed on the phone." }
Write-Host "On the phone: $($before.VersionName), installer $($before.Installer), initiator $($before.Initiating)"

# --- 2. let Sierra ask the system installer ------------------------------------------------------
# Only the main user's block of the dump counts: a work profile may carry its own restriction.
$users = Invoke-Shell 'dumpsys user' -AllowFail
$mainUser = ($users -split 'UserInfo\{' | Where-Object { $_ -match '^0:' } | Select-Object -First 1)
if ($mainUser -match 'no_install_unknown_sources') {
    throw "A device policy on this phone (usually a work profile) forbids installs from unknown sources, so the system installer refuses Sierra. Only adb (scripts\install-phone.ps1) or Google Play can install here. See docs/TRUCK-INSTALL.md, 'Where this stands'."
}
Write-Step "Step 2: allowing Sierra to request installs (Install unknown apps)"
Invoke-Shell "appops set $Package REQUEST_INSTALL_PACKAGES allow" | Out-Null

# --- 3. drive Sierra's Updates screen ------------------------------------------------------------
if ((Invoke-Shell 'dumpsys power') -notmatch 'mWakefulness=Awake') { throw "The phone screen is off. Wake and unlock it, then run again." }
if ((Invoke-Shell 'dumpsys window') -match 'isKeyguardShowing=true') { throw "The phone is locked. Unlock it, then run again." }

Write-Step "Step 3: Sierra -> Updates -> Check -> Download -> Install through the system installer"
Invoke-Shell "am force-stop $Package" | Out-Null
Invoke-Shell "am start -n $Package/.MainActivity" | Out-Null
Invoke-Tap (Wait-UiNode '(?i)^updates and install record$' 'The Updates button' $Package)
Invoke-Tap (Wait-UiNode '(?i)^check for updates$' 'The Check for updates button' $Package)
$found = Wait-UiNode '(?i)(newer than this build|the same build as this one|older than this build)' 'The release check' $Package -FailPattern '(?i)^Check failed'
Write-Host "Latest release: $(($found.Text -split "`n")[0])"
if ($found.Text -match '(?i)older than this build') {
    throw "The latest release is older than the build on the phone, so it cannot replace it. Install a build whose versionCode is not above the release (see -Apk in the help)."
}
Invoke-Tap (Wait-UiNode '(?i)^download and verify signer$' 'The Download button' $Package -Enabled)
Wait-UiNode '(?i)Signer matches the installed app' 'The download' $Package -FailPattern '(?i)^(Refusing|Download failed)' -Seconds ([Math]::Max($TimeoutSec, 600)) | Out-Null
$button = Find-UiNode '(?i)^install through the system installer' $Package
if (-not $button) {
    try { $button = Wait-UiNode '(?i)^install through the system installer' 'The system installer button' $Package -Enabled }
    catch { throw "This Sierra build has no 'Install through the system installer' button. Install a release that has it (step 1), then run again." }
}
Invoke-Tap $button

# --- 4. the system installer's own dialog --------------------------------------------------------
Write-Step "Step 4: confirming the system installer dialog"
$confirm = Wait-UiNode '(?i)^(update|install|reinstall)$' 'The system installer dialog' 'packageinstaller' -NoScroll
Invoke-Tap $confirm
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$after = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $after = Get-InstallRecord
    if ($after -and $after.Updated -ne $before.Updated) { break }
    # Play Protect sometimes asks before the install goes on.
    $ask = Find-UiNode '(?i)^(install anyway|install without scanning|update anyway|continue)$'
    if ($ask) { Invoke-Tap $ask }
}
if (-not $after -or $after.Updated -eq $before.Updated) {
    throw "The install did not finish within $TimeoutSec s. Look at the phone: a dialog may be waiting."
}
Start-Sleep -Seconds 2
$done = Find-UiNode '(?i)^done$' 'packageinstaller'
if ($done) { Invoke-Tap $done }

# --- 5. the record -------------------------------------------------------------------------------
Write-Host ""
Write-Host "Installed version : $($after.VersionName) (versionCode $($after.VersionCode))"
Write-Host "Installer record  : $($after.Installer)"
Write-Host "Initiated by      : $($after.Initiating)"
Write-Host "Originating       : $($after.Originating)"
Write-Host "Package source    : $($after.Source)"

if ($after.Installer -ne $Play) {
    throw "The installer of record is '$($after.Installer)', not $Play. This phone's installer dropped the name; see docs/TRUCK-INSTALL.md."
}
if ($after.Initiating -ne $Play -and $after.Initiating -notmatch 'packageinstaller') {
    throw "The initiating package is '$($after.Initiating)'. A real head unit is expected to reject that."
}
Write-Host "Record OK: this is the pair Android Auto's validator accepts by its code. Not yet confirmed on the truck." -ForegroundColor Green
