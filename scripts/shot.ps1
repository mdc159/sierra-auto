#requires -Version 7
<#
.SYNOPSIS
Saves a PNG of the car screen shown by the Desktop Head Unit.

.DESCRIPTION
Uses the DHU's own `screenshot` console command when the DHU was started by
dhu.ps1. Otherwise, or with -Window, it captures the DHU window's client area.
The window capture reads only the DHU window, never the rest of the desktop.

.EXAMPLE
scripts\shot.ps1
Saves to the runs folder with a timestamped name and prints the path.

.EXAMPLE
scripts\shot.ps1 -Out C:\tmp\home.png
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$Out,
    # Capture the DHU window even when the console command is available.
    [switch]$Window
)

. (Join-Path $PSScriptRoot '_common.ps1')
. (Join-Path $PSScriptRoot '_window.ps1')
trap { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }

if (-not $Out) { $Out = Join-Path (Get-RunsDir) ("shot-{0:yyyyMMdd-HHmmss}.png" -f (Get-Date)) }
$parent = Split-Path -Parent ([System.IO.Path]::GetFullPath($Out))
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$Out = Join-Path (Resolve-Path $parent).Path (Split-Path -Leaf $Out)
Remove-Item $Out -ErrorAction SilentlyContinue

$managed = Get-ManagedDhu
$how = $null

if ($managed -and -not $Window) {
    $before = (Get-Item $managed.log).Length
    [System.IO.File]::AppendAllText($managed.commands, "screenshot $Out`n")
    $deadline = (Get-Date).AddSeconds(5)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 200
        if ((Test-Path $Out) -and (Get-Item $Out).Length -gt 0) { $how = 'DHU screenshot command'; break }
    }
    if (-not $how) {
        $fs = [System.IO.File]::Open($managed.log, 'Open', 'Read', 'ReadWrite')
        try { $fs.Position = $before; $said = ([System.IO.StreamReader]::new($fs)).ReadToEnd().Trim() } finally { $fs.Dispose() }
        Write-Note "The DHU screenshot command wrote no file$(if ($said) { ": $said" }). Capturing the window instead."
    }
}

if (-not $how) {
    $dhuWindow = if ($managed) { Get-DhuWindow -ProcessId $managed.dhuPid } else { Get-DhuWindow }
    $mode = Save-DhuWindow -Handle $dhuWindow.MainWindowHandle -Path $Out
    $how = if ($mode -eq 'printwindow') { "window capture of DHU pid $($dhuWindow.Id)" } else { "screen copy of the DHU window rectangle (pid $($dhuWindow.Id)); anything covering the window is in the picture" }
}

Write-Note "Saved by $how"
Write-Output $Out
