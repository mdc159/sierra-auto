#requires -Version 7
<#
.SYNOPSIS
End-to-end smoke test on the real phone: install, connect the DHU, open
Sierra, and save screenshots of Home, Places and Talk with the log.

.DESCRIPTION
Runs install-phone.ps1, dhu.ps1 start, opens Sierra on the car screen, then
captures Home, Places and Talk. Everything goes to a timestamped folder under
the runs folder (outside the repo):

  home.png, places.png, talk.png   car screen
  launcher.png                     the Android Auto app grid, if it was used
  car.log                          filtered logcat for the run
  android-auto-dump.txt            Android Auto's own state dump
  smoke.log                        output of the scripts it ran
  summary.txt                      what passed and what did not

The script checks what it can without reading the pictures: the session is
connected, Android Auto reports Sierra in the foreground, and the template
host logs a new template from the app after each tap. Look at the PNGs for
the rest.

Tap points come from scripts\screens.json.

.EXAMPLE
scripts\smoke.ps1
.EXAMPLE
scripts\smoke.ps1 -SkipInstall
.EXAMPLE
scripts\smoke.ps1 -Apk C:\path\sierra-auto.apk -MicWav C:\audio\question.wav
#>
[CmdletBinding()]
param(
    [string]$Tag,
    [string]$Apk,
    # Test what is on the phone now.
    [switch]$SkipInstall,
    # WAV file to play into the car microphone on the Talk screen. Needs a DHU
    # started by dhu.ps1.
    [string]$MicWav,
    [int]$MicSeconds = 12,
    [string]$Points = (Join-Path $PSScriptRoot 'screens.json'),
    [int]$SettleSec = 3,
    [string]$Serial
)

. (Join-Path $PSScriptRoot '_common.ps1')

$Serial = Get-PhoneSerial -Serial $Serial
$tapPoints = Get-Content $Points -Raw | ConvertFrom-Json
$out = Join-Path (Get-RunsDir) ("smoke-{0:yyyyMMdd-HHmmss}" -f (Get-Date))
New-Item -ItemType Directory -Force -Path $out | Out-Null
$results = [System.Collections.Generic.List[string]]::new()
$failed = $false

function Add-Result {
    param([string]$Name, [ValidateSet('PASS', 'FAIL', 'SKIP')][string]$Status, [string]$Note)
    $line = "{0,-5} {1}{2}" -f $Status, $Name, $(if ($Note) { ": $Note" })
    $results.Add($line)
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Gray' } }
    Write-Host $line -ForegroundColor $color
    if ($Status -eq 'FAIL') { $script:failed = $true }
}

# Runs one of the sibling scripts in its own process, so its exit code is
# reliable, and copies its output to smoke.log.
function Invoke-Script {
    param([string]$Name, [string[]]$ScriptArgs)
    & pwsh -NoProfile -File (Join-Path $PSScriptRoot $Name) @ScriptArgs 2>&1 |
        Tee-Object -FilePath (Join-Path $out 'smoke.log') -Append |
        ForEach-Object { Write-Host "    $_" }
    return ($LASTEXITCODE -eq 0)
}

function Get-PhoneTime {
    return (Invoke-Adb $Serial 'shell', "date '+%m-%d %H:%M:%S.000'" | Select-Object -First 1)
}

function Invoke-Tap {
    param([string]$Point)
    $xy = $tapPoints.$Point
    if (-not $xy) { throw "screens.json has no point named '$Point'." }
    Invoke-Script 'dhu.ps1' @('tap', "$($xy[0])", "$($xy[1])", '-Serial', $Serial) | Out-Null
    Start-Sleep -Seconds $SettleSec
}

function Save-Shot {
    param([string]$Name)
    $path = Join-Path $out "$Name.png"
    Invoke-Script 'shot.ps1' @('-Out', $path) | Out-Null
    if (Test-Path $path) { Add-Result "$Name.png" 'PASS' }
    else { Add-Result "$Name.png" 'FAIL' 'no screenshot; see smoke.log' }
}

# Template types the host received from the app since a phone time, read from
# the template host's log lines.
function Get-NewTemplates {
    param([string]$Since)
    $lines = Invoke-Adb $Serial 'logcat', '-d', '-T', $Since, '-s', 'CarApp.H.Tem:I' -AllowFail
    return @($lines | ForEach-Object { if ("$_" -match 'Host received new template \(type: (?:[\w.]*\.)?(\w+)\)') { $Matches[1] } })
}

# Taps a point on Home and records whether the app answered with a new screen.
function Open-Screen {
    param([string]$Name, [string]$Point)
    $since = Get-PhoneTime
    Invoke-Tap $Point
    $templates = @(Get-NewTemplates -Since $since)
    if ($templates.Count -gt 0) { Add-Result "$Name screen" 'PASS' "after the tap the host received $($templates -join ', ')" }
    else { Add-Result "$Name screen" 'FAIL' "no new template after the tap; check the $Point point in screens.json" }
}

$startedAt = Get-PhoneTime
Write-Step "Smoke run in $out"

try {
    # 1. Install.
    if ($SkipInstall) {
        Add-Result 'install' 'SKIP' 'testing what is on the phone'
    } else {
        $installArgs = @('-Serial', $Serial)
        if ($Apk) { $installArgs += @('-Apk', $Apk) } elseif ($Tag) { $installArgs += @('-Tag', $Tag) }
        if (Invoke-Script 'install-phone.ps1' $installArgs) { Add-Result 'install' 'PASS' }
        else { Add-Result 'install' 'FAIL' 'see smoke.log'; throw 'stop' }
    }
    $record = Get-InstallRecord -Serial $Serial
    if (-not $record) { Add-Result 'installed' 'FAIL' 'Sierra is not on the phone'; throw 'stop' }
    $installerOk = $record.Installer -eq $PlayInstaller
    Add-Result 'installed' $(if ($installerOk) { 'PASS' } else { 'FAIL' }) "$($record.VersionName) (code $($record.VersionCode)), installer $($record.Installer)"

    # 2. DHU session.
    if (Invoke-Script 'dhu.ps1' @('start', '-Serial', $Serial)) { Add-Result 'dhu session, Sierra offered' 'PASS' }
    else { Add-Result 'dhu session, Sierra offered' 'FAIL' 'see smoke.log'; throw 'stop' }

    # 3. Bring Sierra to the front.
    $opened = $false
    $car = $null
    for ($try = 1; $try -le 4 -and -not $opened; $try++) {
        $car = Get-CarState -Serial $Serial
        if ($car.Foreground -eq $SierraPackage -and -not $car.LauncherShown) { $opened = $true; break }
        if ($car.LauncherShown) {
            Save-Shot 'launcher'
            Invoke-Tap 'sierraIcon'
        } else {
            Invoke-Tap 'launcherButton'
        }
    }
    if (-not $opened) {
        Save-Shot 'not-opened'
        Add-Result 'open Sierra' 'FAIL' "Android Auto shows '$($car.Foreground)'. Look at launcher.png and set sierraIcon in screens.json to where the Sierra icon is."
        throw 'stop'
    }
    Add-Result 'open Sierra' 'PASS' "Android Auto reports $SierraPackage in the foreground"

    # Leave any sub-screen. On Home the header has no back arrow and the tap does nothing.
    Invoke-Tap 'back'; Invoke-Tap 'back'

    # 4. Screens.
    Save-Shot 'home'

    Open-Screen -Name 'places' -Point 'places'
    Save-Shot 'places'
    Invoke-Tap 'back'

    Open-Screen -Name 'talk' -Point 'talk'
    if ($MicWav) {
        if (Get-ManagedDhu) {
            $wav = (Resolve-Path $MicWav).Path
            Invoke-Script 'dhu.ps1' @('send', 'mic', 'play', $wav, '-Serial', $Serial) | Out-Null
            Start-Sleep -Seconds $MicSeconds
            Add-Result 'mic play' 'PASS' "sent $wav; read talk.png for the result"
        } else {
            Add-Result 'mic play' 'SKIP' 'the DHU was not started by dhu.ps1, so there is no console to send mic commands to'
        }
    }
    Save-Shot 'talk'
    Invoke-Tap 'back'

    $car = Get-CarState -Serial $Serial
    $alive = $car.Connected -and $car.Foreground -eq $SierraPackage
    Add-Result 'still running' $(if ($alive) { 'PASS' } else { 'FAIL' }) "connected=$($car.Connected), foreground=$($car.Foreground)"
} catch {
    if ($_.Exception.Message -ne 'stop') { Add-Result 'smoke script' 'FAIL' $_.Exception.Message }
} finally {
    # 5. Logs, whatever happened.
    $carLog = Join-Path $out 'car.log'
    Invoke-Script 'car-logs.ps1' @('-Dump', '-Since', "$startedAt", '-Out', $carLog, '-Serial', $Serial) | Out-Null
    Invoke-Script 'car-logs.ps1' @('-HostDump', '-Out', (Join-Path $out 'android-auto-dump.txt'), '-Serial', $Serial) | Out-Null
    $bad = 0
    if (Test-Path $carLog) {
        $bad = @(Select-String -Path $carLog -Pattern 'FATAL EXCEPTION|CarApp\.H\.Tem: Error').Count
    }
    Add-Result 'no crash or host error in car.log' $(if ($bad -eq 0) { 'PASS' } else { 'FAIL' }) $(if ($bad) { "$bad matching lines" })
    @(("Sierra smoke run {0:yyyy-MM-dd HH:mm:ss}" -f (Get-Date)), "Phone $Serial", '') + $results | Set-Content (Join-Path $out 'summary.txt')
}

Write-Host ""
Write-Host "Output folder: $out"
if ($failed) { exit 1 }
