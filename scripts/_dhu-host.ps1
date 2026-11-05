#requires -Version 7
# Background host for one Desktop Head Unit session. dhu.ps1 starts it; do not
# run it by hand.
#
# The DHU quits at once when its stdin is closed, so this process owns the DHU
# and keeps a pipe to its stdin open. Lines appended to commands.txt in the
# state folder are forwarded to the DHU console. The DHU's output goes to
# dhu.log.
param(
    [Parameter(Mandatory)][string]$Dhu,
    [Parameter(Mandatory)][string]$StateDir,
    [int]$Port = 5277,
    [string]$Config
)

$ErrorActionPreference = 'Stop'

Add-Type @'
using System;
using System.Diagnostics;
using System.IO;
public static class DhuPump {
    // Appends every stdout and stderr line of the process to a log file.
    public static void Attach(Process p, string logPath) {
        var w = new StreamWriter(new FileStream(logPath, FileMode.Append, FileAccess.Write, FileShare.ReadWrite));
        w.AutoFlush = true;
        DataReceivedEventHandler h = (s, e) => { if (e.Data != null) lock (w) { w.WriteLine(e.Data); } };
        p.OutputDataReceived += h;
        p.ErrorDataReceived += h;
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
    }
}
'@

$commands = Join-Path $StateDir 'commands.txt'
$log = Join-Path $StateDir 'dhu.log'
$stateFile = Join-Path $StateDir 'state.json'
Set-Content -Path $commands -Value '' -NoNewline
Set-Content -Path $log -Value '' -NoNewline

$psi = [System.Diagnostics.ProcessStartInfo]::new($Dhu)
$psi.WorkingDirectory = Split-Path $Dhu
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true
if ($Port -ne 5277) { $psi.ArgumentList.Add("--adb=$Port") }
if ($Config) { $psi.ArgumentList.Add("--config=$Config") }

$p = [System.Diagnostics.Process]::Start($psi)
[DhuPump]::Attach($p, $log)

[ordered]@{
    dhuPid      = $p.Id
    dhuStartUtc = $p.StartTime.ToUniversalTime().ToString('o')
    hostPid     = $PID
    port        = $Port
    log         = $log
    commands    = $commands
} | ConvertTo-Json | Set-Content -Path $stateFile

$offset = 0L
try {
    while (-not $p.HasExited) {
        $fs = [System.IO.File]::Open($commands, 'Open', 'Read', 'ReadWrite')
        try {
            if ($fs.Length -gt $offset) {
                $fs.Position = $offset
                $reader = [System.IO.StreamReader]::new($fs)
                $text = $reader.ReadToEnd()
                # Only take whole lines; a writer may be mid-append.
                $end = $text.LastIndexOf("`n")
                if ($end -ge 0) {
                    $chunk = $text.Substring(0, $end + 1)
                    $offset += [System.Text.Encoding]::UTF8.GetByteCount($chunk)
                    foreach ($line in ($chunk -split "`r?`n")) {
                        if ($line.Trim()) {
                            $p.StandardInput.WriteLine($line.Trim())
                            $p.StandardInput.Flush()
                        }
                    }
                }
            }
        } finally { $fs.Dispose() }
        Start-Sleep -Milliseconds 150
    }
} finally {
    if (-not $p.HasExited) {
        try { $p.StandardInput.WriteLine('quit'); $p.StandardInput.Flush() } catch { }
        if (-not $p.WaitForExit(3000)) { $p.Kill() }
    }
    Add-Content -Path $log -Value "[host] DHU exited with code $($p.ExitCode)"
    Remove-Item $stateFile -ErrorAction SilentlyContinue
}
