#requires -Version 7
# Canonical paths shared by workspace and device scripts. No device side effects.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-SierraRoot {
    $common = & git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir
    if ($LASTEXITCODE -ne 0 -or -not $common) { throw 'Sierra scripts must run inside a Git checkout.' }
    $common = [IO.Path]::GetFullPath(($common -join '').Trim())
    if ((Split-Path -Leaf $common) -ne '.git') { throw 'A regular main checkout with a .git directory is required.' }
    return (Split-Path -Parent $common)
}

function Assert-SierraChildPath {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Parent)
    $parentPath = [IO.Path]::GetFullPath($Parent).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $full = [IO.Path]::GetFullPath($Path, $parentPath)
    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    if (-not $full.StartsWith($parentPath + [IO.Path]::DirectorySeparatorChar, $comparison)) {
        throw "Path must be beneath $parentPath : $full"
    }
    # Lexical checks alone do not prevent junction/symlink escapes. Reject links
    # in every existing path component, including the selected boundary.
    $cursor = $full
    while ($true) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Workspace paths cannot traverse a junction or symlink: $cursor"
            }
        }
        if ($cursor.Equals($parentPath, $comparison)) { break }
        $cursor = Split-Path -Parent $cursor
        if (-not $cursor) { throw "Cannot validate path: $full" }
    }
    return $full
}

function Get-SierraLocalDirectory {
    param([Parameter(Mandatory)][string]$Name, [string]$Override)
    $local = Assert-SierraChildPath -Path '.local' -Parent (Get-SierraRoot)
    $path = if ($Override) { $Override } else { $Name }
    $dir = Assert-SierraChildPath -Path $path -Parent $local
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    return $dir
}

function Resolve-SierraOutputFile {
    param([Parameter(Mandatory)][string]$Path)
    # Explicit file arguments keep shell-relative semantics, within .local.
    $absolute = [IO.Path]::GetFullPath($Path, (Get-Location).Path)
    $full = Assert-SierraChildPath -Path $absolute -Parent (Join-Path (Get-SierraRoot) '.local')
    New-Item -ItemType Directory -Path (Split-Path -Parent $full) -Force | Out-Null
    return $full
}

function Get-SierraWorktreeDirectory {
    $dir = Assert-SierraChildPath -Path '.worktrees' -Parent (Get-SierraRoot)
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    return $dir
}

function Get-SierraWorktrees {
    $lines = @(& git -C (Get-SierraRoot) -c core.quotePath=false worktree list --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot list worktrees.' }
    $entry = @{}
    foreach ($line in @($lines) + @('')) {
        if (-not $line) {
            if ($entry.ContainsKey('Path')) { [pscustomobject]$entry; $entry = @{} }
        } elseif ($line -match '^worktree (.*)$') { $entry.Path = [IO.Path]::GetFullPath($Matches[1]) }
        elseif ($line -match '^HEAD (.*)$') { $entry.Commit = $Matches[1] }
        elseif ($line -match '^branch (.*)$') { $entry.Branch = $Matches[1] }
        elseif ($line -eq 'detached') { $entry.Branch = '(detached)' }
    }
}

function New-SierraWorktree {
    param([Parameter(Mandatory)][string]$Name, [string]$Ref = 'HEAD', [string]$Branch, [switch]$Detach)
    if ($Name -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_-]*$') { throw 'Worktree name must contain letters, numbers, hyphens or underscores.' }
    if ($Detach -and $Branch) { throw 'Choose either a new branch or a detached worktree.' }
    if ($Ref.StartsWith('-') -or ($Branch -and $Branch.StartsWith('-'))) { throw 'Ref and branch cannot start with a hyphen.' }
    $path = Assert-SierraChildPath -Path $Name -Parent (Get-SierraWorktreeDirectory)
    if (Test-Path -LiteralPath $path) { throw "Worktree destination already exists; inspect it first: $path" }
    $gitArgs = @('worktree','add')
    if ($Detach) { $gitArgs += '--detach' }
    elseif ($Branch) { $gitArgs += @('-b',$Branch) }
    $output = & git -C (Get-SierraRoot) @gitArgs $path $Ref 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Worktree creation failed: $($output -join ' ')" }
    Write-Host ($output -join "`n")
    return $path
}

function Remove-SierraWorktree {
    param([Parameter(Mandatory)][string]$Path)
    $full = Assert-SierraChildPath -Path $Path -Parent (Get-SierraWorktreeDirectory)
    $registered = @(Get-SierraWorktrees | Where-Object { $_.Path -eq $full })
    if ($registered.Count -ne 1) { throw "Not a registered Sierra worktree: $full" }
    $dirty = @(& git -C $full status --porcelain --untracked-files=all)
    if ($LASTEXITCODE -ne 0 -or $dirty.Count) { throw "Worktree has changes; preserve them before removal: $full" }
    # Refuse unknown ignored files, including local settings and credentials.
    # Keep reports and distributables before discarding generated intermediates.
    $ignored = @(& git -C $full ls-files --others --ignored --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw "Cannot inventory ignored files: $full" }
    $unknown = @($ignored | Where-Object { $_ -notmatch '^(\.gradle/|\.kotlin/|app/build/|build/|bridge/(\.venv/|\.pytest_cache/|.*__pycache__/))' })
    if ($unknown.Count) { throw "Worktree has ignored local files that must be preserved first: $($unknown -join ', ')" }
    $name = Split-Path -Leaf $full
    $removalId = '{0:yyyyMMdd-HHmmss}-{1}' -f (Get-Date),[guid]::NewGuid().ToString('N')
    $evidence = Get-SierraLocalDirectory -Name "runs/worktree-$name-$removalId"
    $saved = [System.Collections.Generic.List[object]]::new()
    foreach ($rel in 'app/build/reports','app/build/test-results','app/build/outputs','build/reports') {
        $source = Assert-SierraChildPath -Path $rel -Parent $full
        if (-not (Test-Path -LiteralPath $source)) { continue }
        $items = @(Get-ChildItem -LiteralPath $source -Recurse -Force)
        if (@($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) {
            throw "Build evidence contains a junction or symlink: $source"
        }
        foreach ($file in $items | Where-Object { -not $_.PSIsContainer }) {
            $relative = [IO.Path]::GetRelativePath($full,$file.FullName)
            $target = Assert-SierraChildPath -Path $relative -Parent $evidence
            New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
            $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
            Copy-Item -LiteralPath $file.FullName -Destination $target
            if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $hash) { throw "Evidence copy failed: $target" }
            $saved.Add([pscustomobject]@{Path=$relative;SHA256=$hash})
        }
    }
    $head = (& git -C $full rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve worktree HEAD.' }
    $recoveryRef = "refs/sierra/worktrees/$name/$removalId"
    & git -C (Get-SierraRoot) update-ref $recoveryRef $head ('0' * $head.Length)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot preserve worktree commit.' }
    [pscustomobject]@{Worktree=$full;Commit=$head;RecoveryRef=$recoveryRef;Files=@($saved.ToArray())} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'manifest.json') -Encoding utf8
    & git -C (Get-SierraRoot) worktree remove $full
    if ($LASTEXITCODE -ne 0) { throw "Git refused worktree removal: $full" }
    Write-Host "Retained worktree commit and build evidence: $evidence"
}

function Test-SierraWorkspace {
    $root = Get-SierraRoot
    $boundary = Join-Path $root '.worktrees'
    $problems = [System.Collections.Generic.List[string]]::new()
    foreach ($relative in '.worktrees','.local','.local/runs','.local/state/dhu','.local/downloads/releases') {
        try { Assert-SierraChildPath -Path $relative -Parent $root | Out-Null }
        catch { $problems.Add("Unsafe default workspace path: $relative ($($_.Exception.Message))") }
    }
    foreach ($tree in @(Get-SierraWorktrees)) {
        if ($tree.Path -eq $root) { continue }
        try { Assert-SierraChildPath -Path $tree.Path -Parent $boundary | Out-Null }
        catch { $problems.Add("External/unsafe worktree: $($tree.Path)") }
    }
    foreach ($name in 'SIERRA_RUNS','SIERRA_STATE') {
        $override = [Environment]::GetEnvironmentVariable($name)
        if ($override) {
            try { Assert-SierraChildPath -Path $override -Parent (Join-Path $root '.local') | Out-Null }
            catch { $problems.Add("$name must point beneath $root\.local") }
        }
    }
    foreach ($dir in '.worktrees','.local') {
        & git -C $root check-ignore --quiet -- "$dir/workspace-check"
        if ($LASTEXITCODE -ne 0) { $problems.Add("$dir must be ignored by Git.") }
    }
    $prefix = (Split-Path -Leaf $root) + '-*'
    foreach ($sibling in @(Get-ChildItem -LiteralPath (Split-Path -Parent $root) -Directory -Filter $prefix)) {
        $problems.Add("Project sibling folder needs consolidation: $($sibling.FullName)")
    }
    if ($problems.Count) { throw ($problems -join "`n") }
    Write-Host "Workspace OK: $root; all linked worktrees are under .worktrees."
}
