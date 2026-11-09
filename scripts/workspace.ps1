#requires -Version 7
<#
.SYNOPSIS
Checks Sierra workspace boundaries, lists worktrees, or creates/removes a local worktree.
.EXAMPLE
pwsh -File scripts/workspace.ps1 check
.EXAMPLE
pwsh -File scripts/workspace.ps1 new -Name voice-fix -Branch fix/voice -Ref main
.EXAMPLE
pwsh -File scripts/workspace.ps1 remove -Name voice-fix
#>
[CmdletBinding()]
param(
    [Parameter(Position=0)][ValidateSet('check','list','new','remove')][string]$Action = 'check',
    [string]$Name,
    [string]$Ref = 'HEAD',
    [string]$Branch,
    [switch]$Detach
)
. (Join-Path $PSScriptRoot '_workspace.ps1')
switch ($Action) {
    'check' { Test-SierraWorkspace }
    'list' { Get-SierraWorktrees | Format-Table Path,Branch,Commit -AutoSize }
    'new' {
        Test-SierraWorkspace
        if (-not $Name) { throw '-Name is required.' }
        New-SierraWorktree -Name $Name -Ref $Ref -Branch $Branch -Detach:$Detach
    }
    'remove' {
        if (-not $Name -or $Name -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_-]*$') { throw 'Supply a worktree -Name containing only letters, numbers, hyphens or underscores.' }
        Remove-SierraWorktree -Path (Join-Path (Get-SierraWorktreeDirectory) $Name)
    }
}
