#requires -Version 7
<#
.SYNOPSIS
Starts and drives the Android Auto Desktop Head Unit (DHU) against the phone.

.DESCRIPTION
  dhu.ps1 start            forward the port, check the phone, start the DHU with
                           its console kept open, wait until Sierra is offered
  dhu.ps1 send <command>   send one DHU console command, print what it answered
  dhu.ps1 tap <x> <y>      tap a point on the car screen
  dhu.ps1 status           say what is running and what the car screen shows
  dhu.ps1 stop             quit the DHU that this script started

The DHU exits at once if its stdin is closed, so `start` runs it under a small
background host that holds its stdin open and forwards commands. If a DHU that
this script did not start is already running, the script leaves it alone and
works with it through its window instead: `tap` and shot.ps1 still work,
`send` does not.

DHU console commands that matter (from the DHU's own `help`):
  tap <x> <y>                 touch; (0, 0) is the top-left corner
  screenshot <file.png>       save the car screen
  mic play <file.wav>         play a WAV file as the car microphone
  mic repeat                  play the last WAV again
  mic begin                   press the voice button
  keycode back | home         hardware keys
  day | night                 day/night sensor
  restrict all | none         driving restrictions
  sleep <seconds>             pause, for scripted sequences
  help [command]              list commands, or explain one

A pass on the DHU does not prove the app shows in the truck: Android Auto skips
its install-source checks for the DHU. See docs/TESTING.md.

.EXAMPLE
scripts\dhu.ps1 start
.EXAMPLE
scripts\dhu.ps1 send mic play C:\audio\question.wav
.EXAMPLE
scripts\dhu.ps1 tap 240 344
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][ValidateSet('start', 'send', 'tap', 'status', 'stop')][string]$Action = 'start',
    [Parameter(Position = 1, ValueFromRemainingArguments)][string[]]$Rest,
    [string]$Serial,
    # Seconds to wait for the session to connect and for Sierra to be offered.
    [int]$TimeoutSec = 60,
    # DHU .ini file (see <sdk>\extras\google\auto\config). Default: 800x480 touch.
    [string]$Config,
    # Test hooks: a different local port, and no phone at all. With -NoPhone
    # the script only starts the DHU and its console; point -Port at something
    # that accepts a TCP connection.
    [int]$Port = 5277,
    [switch]$NoPhone
)

. (Join-Path $PSScriptRoot '_common.ps1')
. (Join-Path $PSScriptRoot '_window.ps1')
trap { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }

function Read-LogFrom {
    param([string]$Path, [long]$Offset)
    $fs = [System.IO.File]::Open($Path, 'Open', 'Read', 'ReadWrite')
    try {
        if ($fs.Length -le $Offset) { return '' }
        $fs.Position = $Offset
        return ([System.IO.StreamReader]::new($fs)).ReadToEnd()
    } finally { $fs.Dispose() }
}

function Send-DhuCommand {
    param([Parameter(Mandatory)]$State, [Parameter(Mandatory)][string]$Command, [int]$WaitMs = 800)
    $before = (Get-Item $State.log).Length
    [System.IO.File]::AppendAllText($State.commands, $Command + "`n")
    Start-Sleep -Milliseconds $WaitMs
    return (Read-LogFrom -Path $State.log -Offset $before).TrimEnd()
}

function Test-HeadUnitServer {
    param([string]$Serial)
    # ss works for the shell user on Android 16; netstat is the fallback.
    $listen = Invoke-Adb $Serial 'shell', "ss -ltn 2>/dev/null; netstat -ltn 2>/dev/null" -AllowFail
    return [bool]($listen | Where-Object { $_ -match "[:.]$DhuPort\s" -and $_ -match 'LISTEN' })
}

function Show-HeadUnitServerHelp {
    param([string]$Serial)
    $locked = Test-PhoneLocked -Serial $Serial
    Write-Host ""
    Write-Host "The phone's head unit server is not listening on port $DhuPort." -ForegroundColor Yellow
    Write-Host "On the phone:"
    if ($locked) { Write-Host "  0. Unlock the phone." }
    Write-Host "  1. Open Settings > Connected devices > Android Auto."
    Write-Host "     (This script has just asked the phone to open that screen.)"
    Write-Host "  2. Tap the three-dot menu at the top right."
    Write-Host "  3. Tap 'Start head unit server'. If the item says 'Stop head unit server',"
    Write-Host "     tap it, open the menu again and tap 'Start head unit server'."
    Write-Host "     If the menu has no such item, scroll to the bottom of the settings,"
    Write-Host "     tap 'Version' ten times to turn on developer mode, and try again."
    Write-Host "Then run this script again."
    Invoke-Adb $Serial 'shell', 'am', 'start', '-a', "$GearheadPackage.SETTINGS" -AllowFail | Out-Null
}

function Wait-SierraOffered {
    param([string]$Serial, [int]$TimeoutSec)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $state = $null
    do {
        $state = Get-CarState -Serial $Serial
        if ($state.Connected) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    if (-not $state.Connected) {
        throw "Android Auto on the phone did not reach CONNECTED within $TimeoutSec s. If the DHU window says 'Waiting for phone', the head unit server is stuck: on the phone, Android Auto settings > three-dot menu > Stop head unit server, then Start head unit server, and run this again."
    }
    Write-Note "Session connected. Head unit: $($state.CarInfo)"

    if (-not (Get-InstallRecord -Serial $Serial)) {
        throw "Sierra is not installed on the phone. Run scripts\install-phone.ps1 first."
    }
    $services = Invoke-Adb $Serial 'shell', 'cmd', 'package', 'query-services', '--brief', '-a', 'androidx.car.app.CarAppService' -AllowFail
    if (-not ($services | Where-Object { $_ -match [regex]::Escape($SierraPackage) })) {
        throw "Sierra is installed but exposes no androidx.car.app.CarAppService, so Android Auto has nothing to offer."
    }
    if ($state.SierraDenied.Count -gt 0) {
        $state.SierraDenied | ForEach-Object { Write-Host $_.Trim() -ForegroundColor Red }
        throw "Android Auto denied Sierra (lines above are from its validator)."
    }
    Write-Note "Sierra is offered: car app service present, no validator denial."
    if ($state.CarInfo -match 'Desktop Head Unit') {
        Write-Note "Note: Android Auto skips install-source checks on the DHU. This does not show the truck will offer it."
    }
    return $state
}

if ($NoPhone) { $Serial = '' } else { $Serial = Get-PhoneSerial -Serial $Serial }

switch ($Action) {
    'start' {
        $managed = Get-ManagedDhu
        # -NoPhone is a console test on another port; a DHU on the phone's port is not in its way.
        $unmanaged = @(if (-not $NoPhone) { Get-UnmanagedDhu })
        if ($managed) {
            Write-Step "A DHU started by this script is already running (pid $($managed.dhuPid))"
        } elseif ($unmanaged.Count -gt 0) {
            Write-Step "A DHU that this script did not start is already running (pid $($unmanaged[0].Id))"
            Write-Note "Leaving it alone and using it through its window: tap and shot.ps1 work, send does not."
            Write-Note "For a console session, close that DHU window yourself and run this again."
        } else {
            $dhu = Get-DhuPath
            if (-not $NoPhone) {
                Write-Step "Forwarding tcp:$DhuPort to the phone"
                Invoke-Adb $Serial 'forward', "tcp:$Port", "tcp:$DhuPort" | Out-Null
                if (-not (Test-HeadUnitServer -Serial $Serial)) {
                    Show-HeadUnitServerHelp -Serial $Serial
                    exit 2
                }
                Write-Note "The phone is listening on $DhuPort."
            }
            Write-Step "Starting the Desktop Head Unit"
            $stateDir = Get-StateDir
            Remove-Item (Join-Path $stateDir 'state.json') -ErrorAction SilentlyContinue
            $hostArgs = @('-NoProfile', '-File', (Join-Path $PSScriptRoot '_dhu-host.ps1'), '-Dhu', $dhu, '-StateDir', $stateDir, '-Port', $Port)
            if ($Config) { $hostArgs += @('-Config', (Resolve-Path $Config).Path) }
            Start-Process pwsh -ArgumentList $hostArgs -WindowStyle Hidden | Out-Null
            $deadline = (Get-Date).AddSeconds(15)
            do { Start-Sleep -Milliseconds 300; $managed = Get-ManagedDhu } while (-not $managed -and (Get-Date) -lt $deadline)
            if (-not $managed) {
                $log = Join-Path $stateDir 'dhu.log'
                $tail = if (Test-Path $log) { (Get-Content $log -Tail 12) -join "`n" } else { '(no output)' }
                throw "The DHU did not stay up. Its last output:`n$tail"
            }
            Write-Note "DHU pid $($managed.dhuPid); output in $($managed.log)"
        }
        if (-not $NoPhone) {
            Write-Step "Waiting for the session and for Sierra to be offered"
            Wait-SierraOffered -Serial $Serial -TimeoutSec $TimeoutSec | Out-Null
        }
    }

    'send' {
        if (-not $Rest) { throw "Give a DHU console command, for example: dhu.ps1 send help" }
        $managed = Get-ManagedDhu
        if (-not $managed) {
            if (@(Get-UnmanagedDhu).Count -gt 0) {
                throw "The running DHU was not started by this script, so it has no console to send to. Use 'dhu.ps1 tap' and shot.ps1, or close that DHU and run 'dhu.ps1 start'."
            }
            throw "No DHU is running. Run 'dhu.ps1 start' first."
        }
        $command = $Rest -join ' '
        $answer = Send-DhuCommand -State $managed -Command $command
        if ($answer) { Write-Host $answer }
    }

    'tap' {
        if (-not $Rest -or $Rest.Count -ne 2) { throw "Usage: dhu.ps1 tap <x> <y>" }
        $x = [int]$Rest[0]; $y = [int]$Rest[1]
        $managed = Get-ManagedDhu
        if ($managed) {
            $answer = Send-DhuCommand -State $managed -Command "tap $x $y" -WaitMs 300
            if ($answer) { Write-Host $answer }
        } else {
            $window = Get-DhuWindow
            [SierraDhuWindow]::Tap($window.MainWindowHandle, $x, $y)
            Write-Note "Tapped $x,$y through the window of DHU pid $($window.Id)."
        }
    }

    'status' {
        $managed = Get-ManagedDhu
        $unmanaged = @(Get-UnmanagedDhu)
        if ($managed) { Write-Host "DHU            : running, started by this script (pid $($managed.dhuPid)), console available" }
        elseif ($unmanaged.Count -gt 0) { Write-Host "DHU            : running, not started by this script (pid $($unmanaged[0].Id)), window access only" }
        else { Write-Host "DHU            : not running" }
        if (-not $NoPhone) {
            $record = Get-InstallRecord -Serial $Serial
            if ($record) { Write-Host "Sierra         : $($record.VersionName) (code $($record.VersionCode)), installer $($record.Installer)" }
            else { Write-Host "Sierra         : not installed" }
            Write-Host "Head unit server: $(if (Test-HeadUnitServer -Serial $Serial) { "listening on $DhuPort" } else { 'not listening' })"
            Write-Host "Phone          : $(if (Test-PhoneLocked -Serial $Serial) { 'locked' } else { 'unlocked' })"
            $car = Get-CarState -Serial $Serial
            Write-Host "Session        : $(if ($car.Connected) { 'connected' } else { 'not connected' })"
            Write-Host "On screen      : $(if ($car.LauncherShown) { 'app launcher' } elseif ($car.Foreground) { $car.Foreground } else { 'unknown' })"
            Write-Host "Unknown sources: $($car.UnknownSources)"
        }
    }

    'stop' {
        $managed = Get-ManagedDhu
        if (-not $managed) {
            if (@(Get-UnmanagedDhu).Count -gt 0) { Write-Note "A DHU is running but this script did not start it, so it is left alone." }
            else { Write-Note "No DHU is running." }
            break
        }
        Write-Step "Quitting the DHU (pid $($managed.dhuPid))"
        [System.IO.File]::AppendAllText($managed.commands, "quit`n")
        $dhuProcess = Get-Process -Id $managed.dhuPid -ErrorAction SilentlyContinue
        if ($dhuProcess -and -not $dhuProcess.WaitForExit(5000)) {
            Write-Note "It did not quit by itself; ending the process."
            Stop-Process -Id $managed.dhuPid -Force
        }
        Start-Sleep -Milliseconds 500
        $hostProcess = Get-Process -Id $managed.hostPid -ErrorAction SilentlyContinue
        if ($hostProcess -and $hostProcess.ProcessName -eq 'pwsh') { $hostProcess.WaitForExit(3000) | Out-Null }
        Write-Note "Stopped."
    }
}
