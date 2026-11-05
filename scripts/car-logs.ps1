#requires -Version 7
<#
.SYNOPSIS
Shows the phone's log filtered to Sierra and to Android Auto's car-app tags.

.DESCRIPTION
Keeps lines from Sierra's own process, lines that name the package, crashes,
and these Android Auto tags: CAR.VALIDATOR (package accepted or DENIED, with a
reason), GH.AppNotifier, GH.ConnLoggerV2 (session connect and disconnect),
CarApp.* and HostConfig (the template host binding to the app).

Follows new lines until Ctrl+C by default. -Dump prints what is in the buffer
and exits. -Since starts either mode at a given time.

Android Auto writes very little to logcat unless its tags are raised.
-VerboseTags raises them for this boot; -ResetTags puts them back. -HostDump
saves Android Auto's own state dump instead, which holds the last 500 lines of
its internal log and the validator state, needs no tag changes and works with
the phone locked.

.EXAMPLE
scripts\car-logs.ps1
.EXAMPLE
scripts\car-logs.ps1 -Dump -Since '11-04 18:00:00.000' -Out C:\tmp\car.log
.EXAMPLE
scripts\car-logs.ps1 -HostDump -Out C:\tmp\android-auto-dump.txt
#>
[CmdletBinding()]
param(
    [switch]$Dump,
    # logcat -T time, as printed by the log itself: 'MM-DD HH:MM:SS.mmm' (phone clock).
    [string]$Since,
    [string]$Out,
    [switch]$HostDump,
    [switch]$VerboseTags,
    [switch]$ResetTags,
    [string]$Serial
)

. (Join-Path $PSScriptRoot '_common.ps1')
trap { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }

$Serial = Get-PhoneSerial -Serial $Serial
$tags = @('CAR.VALIDATOR', 'GH.AppNotifier', 'GH.ConnLoggerV2')
$keep = [regex]'CAR\.VALIDATOR|GH\.AppNotifier|GH\.ConnLoggerV2|CarApp\.|HostConfig|com\.mdc159\.sierra|AndroidRuntime|FATAL EXCEPTION'
# Other apps' entries in Android Auto's lists say what else is on the phone.
# They are noise here and should not end up in an issue.
$drop = [regex]'Package DENIED; (Uses for \w+ not defined|package is disabled)|ActivityThread: Package \[|\s(adbd|artd)\s*:'

if ($VerboseTags -or $ResetTags) {
    $level = if ($ResetTags) { '' } else { 'VERBOSE' }
    foreach ($tag in $tags) {
        Invoke-Adb $Serial 'shell', 'setprop', "log.tag.$tag", "'$level'" | Out-Null
    }
    Write-Note "$(if ($ResetTags) { 'Reset' } else { 'Raised to VERBOSE' }): $($tags -join ', ')"
    if ($ResetTags -and -not $Dump -and -not $HostDump) { return }
}

if ($HostDump) {
    $hostLines = Invoke-Adb $Serial 'shell', 'dumpsys', 'activity', 'service', $GearheadPackage -AllowFail
    if (-not $Out) { $Out = Join-Path (Get-RunsDir) ("android-auto-dump-{0:yyyyMMdd-HHmmss}.txt" -f (Get-Date)) }
    Set-Content -Path $Out -Value $hostLines
    $state = Get-CarState -Serial $Serial
    Write-Note "Connected: $($state.Connected). On screen: $($state.Foreground). Unknown sources: $($state.UnknownSources)."
    Write-Note "Validator lines naming Sierra: $(@($hostLines | Where-Object { $_ -match 'CAR\.VALIDATOR' -and $_ -match 'mdc159' }).Count)"
    Write-Note "The full dump lists other apps on the phone. Do not post it whole."
    Write-Output $Out
    return
}

# Lines from Sierra's process are kept whatever their tag. The pid changes when
# the app restarts, so it is re-read from the 'Start proc' line.
$sierraPid = (Invoke-Adb $Serial 'shell', 'pidof', $SierraPackage -AllowFail | Select-Object -First 1)
$sierraPid = if ($sierraPid -match '^\d+') { $Matches[0] } else { $null }

function Select-CarLine {
    param([string]$Line)
    if ($Line -match "Start proc (\d+):$([regex]::Escape($SierraPackage))/") { $script:sierraPid = $Matches[1] }
    if ($drop.IsMatch($Line)) { return $false }
    if ($keep.IsMatch($Line)) { return $true }
    return ($script:sierraPid -and $Line -match "^\S+\s+\S+\s+$($script:sierraPid)\s")
}

$logcat = @('logcat', '-v', 'threadtime')
if ($Dump) { $logcat += '-d' }
if ($Since) { $logcat += @('-T', $Since) }
# When following, start at the newest line instead of replaying the whole buffer.
elseif (-not $Dump) { $logcat += @('-T', '1') }

$adb = Get-AdbPath
if ($Out) {
    $writer = [System.IO.StreamWriter]::new($Out, $false)
    try {
        & $adb -s $Serial @logcat | ForEach-Object { if (Select-CarLine "$_") { $writer.WriteLine("$_"); if (-not $Dump) { $writer.Flush(); Write-Host $_ } } }
    } finally { $writer.Dispose() }
    Write-Output $Out
} else {
    & $adb -s $Serial @logcat | ForEach-Object { if (Select-CarLine "$_") { Write-Output "$_" } }
}
