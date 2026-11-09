#requires -Version 7
# Integration checks use disposable local Git repositories and no phone/network.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '_workspace.ps1')
$realRoot = Get-SierraRoot
$testDir = Get-SierraLocalDirectory -Name ('tests/workspace-' + [guid]::NewGuid().ToString('N'))
$fixture = Join-Path $testDir 'fixture'
$scriptSource = $PSScriptRoot
$count = 0
function Assert([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
    $script:count++
    Write-Host "PASS: $Message"
}
function Expect-Refusal([scriptblock]$Action, [string]$Message) {
    $refused = $false
    try { & $Action | Out-Null } catch { $refused = $true }
    Assert $refused $Message
}
function Git-Fixture([string[]]$Arguments) {
    & git -C $fixture @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Fixture Git command failed: $($Arguments -join ' ')" }
}
New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts') -Force | Out-Null
& git init --quiet $fixture
if ($LASTEXITCODE -ne 0) { throw 'Cannot initialize fixture.' }
Copy-Item -LiteralPath (Join-Path $scriptSource '_workspace.ps1') -Destination (Join-Path $fixture 'scripts/_workspace.ps1')
Copy-Item -LiteralPath (Join-Path $scriptSource '_common.ps1') -Destination (Join-Path $fixture 'scripts/_common.ps1')
@('/.local/','/.worktrees/','app/build/','local.properties') | Set-Content -LiteralPath (Join-Path $fixture '.gitignore')
Git-Fixture @('add','.')
Git-Fixture @('-c','user.name=Workspace Test','-c','user.email=workspace-test@invalid','commit','--quiet','-m','fixture')
. (Join-Path $fixture 'scripts/_workspace.ps1')
Assert ((Get-SierraRoot) -eq $fixture) 'canonical root from main checkout'
Test-SierraWorkspace
Assert ((Get-SierraLocalDirectory -Name 'runs') -eq (Join-Path $fixture '.local/runs')) 'default outputs beneath .local'
Expect-Refusal { Get-SierraLocalDirectory -Name 'runs' -Override (Join-Path $testDir 'outside') } 'external output override rejected'
Expect-Refusal { Assert-SierraChildPath -Path '../outside' -Parent $fixture } 'parent traversal rejected'
Expect-Refusal { Assert-SierraChildPath -Path ($fixture + '-sibling') -Parent $fixture } 'similar-prefix sibling rejected'
Expect-Refusal { New-SierraWorktree -Name '../escape' -Detach } 'worktree traversal name rejected'
$tree = New-SierraWorktree -Name 'check' -Detach
Assert (Test-Path -LiteralPath (Join-Path $tree '.git')) 'worktree created beneath canonical root'
. (Join-Path $tree 'scripts/_workspace.ps1')
Assert ((Get-SierraRoot) -eq $fixture) 'linked worktree resolves main checkout root'
Assert ((Get-SierraLocalDirectory -Name 'runs') -eq (Join-Path $fixture '.local/runs')) 'linked worktree shares canonical artifact path'
Expect-Refusal { New-SierraWorktree -Name 'check' -Detach } 'existing worktree never overwritten'
Set-Content -LiteralPath (Join-Path $tree 'untracked.txt') -Value 'keep me'
Expect-Refusal { Remove-SierraWorktree -Path $tree } 'untracked work prevents removal'
Remove-Item -LiteralPath (Join-Path $tree 'untracked.txt')
Add-Content -LiteralPath (Join-Path $tree '.gitignore') -Value '# modified'
Expect-Refusal { Remove-SierraWorktree -Path $tree } 'tracked modification prevents removal'
& git -C $tree restore .gitignore
if ($LASTEXITCODE -ne 0) { throw 'Fixture restore failed.' }
Set-Content -LiteralPath (Join-Path $tree 'local.properties') -Value 'test-only'
Expect-Refusal { Remove-SierraWorktree -Path $tree } 'unfamiliar ignored settings prevent removal'
Remove-Item -LiteralPath (Join-Path $tree 'local.properties')
New-Item -ItemType Directory -Path (Join-Path $tree 'app/build/outputs') -Force | Out-Null
Set-Content -LiteralPath (Join-Path $tree 'app/build/outputs/sample.apk') -Value 'test-only fake artifact'
# A detached commit must survive removal, even if no branch points at it.
Set-Content -LiteralPath (Join-Path $tree 'unique.txt') -Value 'unique commit'
& git -C $tree add unique.txt
& git -C $tree -c user.name='Workspace Test' -c user.email='workspace-test@invalid' commit --quiet -m 'detached work'
if ($LASTEXITCODE -ne 0) { throw 'Fixture detached commit failed.' }
$unique = (& git -C $tree rev-parse HEAD).Trim()
. (Join-Path $fixture 'scripts/_workspace.ps1')
Remove-SierraWorktree -Path $tree
Assert (-not (Test-Path -LiteralPath $tree)) 'clean worktree removed safely'
$savedRefs = @(& git -C $fixture for-each-ref '--format=%(objectname)' refs/sierra/worktrees/check/)
Assert ($unique -in $savedRefs) 'detached commit retained by recovery ref'
$saved = @(Get-ChildItem -LiteralPath (Join-Path $fixture '.local/runs') -Recurse -Filter sample.apk)
Assert ($saved.Count -eq 1 -and (Get-Content -LiteralPath $saved[0].FullName) -eq 'test-only fake artifact') 'build artifact retained before worktree removal'
$tree2 = New-SierraWorktree -Name 'check' -Detach
Set-Content -LiteralPath (Join-Path $tree2 'second.txt') -Value 'second detached commit'
& git -C $tree2 add second.txt
& git -C $tree2 -c user.name='Workspace Test' -c user.email='workspace-test@invalid' commit --quiet -m 'second detached work'
if ($LASTEXITCODE -ne 0) { throw 'Second detached fixture commit failed.' }
$unique2 = (& git -C $tree2 rev-parse HEAD).Trim()
Remove-SierraWorktree -Path $tree2
$savedRefs = @(& git -C $fixture for-each-ref '--format=%(objectname)' refs/sierra/worktrees/check/)
Assert ($savedRefs.Count -eq 2 -and $unique -in $savedRefs -and $unique2 -in $savedRefs) 'reused name preserves both detached commits under unique refs'
$externalTree = Join-Path $testDir 'external'
Git-Fixture @('worktree','add','--detach',$externalTree,'HEAD')
Expect-Refusal { Test-SierraWorkspace } 'external registered worktree detected'
Git-Fixture @('worktree','remove',$externalTree)
$sibling = Join-Path $testDir 'fixture-stray'
New-Item -ItemType Directory -Path $sibling | Out-Null
Expect-Refusal { Test-SierraWorkspace } 'unregistered sibling folder detected'
Remove-Item -LiteralPath $sibling
if ($IsWindows) {
    $outside = Join-Path $testDir 'junction-target'
    New-Item -ItemType Directory -Path $outside | Out-Null
    $junction = Join-Path $fixture '.local/escape'
    New-Item -ItemType Junction -Path $junction -Target $outside | Out-Null
    Expect-Refusal { Get-SierraLocalDirectory -Name 'escape/new' } 'junction escape rejected'
    # Remove just the junction object, never recurse through its target.
    Remove-Item -LiteralPath $junction
    $runs = Join-Path $fixture '.local/runs'
    $runsSaved = Join-Path $fixture '.local/saved-runs'
    Move-Item -LiteralPath $runs -Destination $runsSaved
    New-Item -ItemType Junction -Path $runs -Target $outside | Out-Null
    Expect-Refusal { Test-SierraWorkspace } 'workspace check rejects default runs junction'
    Remove-Item -LiteralPath $runs
    Move-Item -LiteralPath $runsSaved -Destination $runs
    $state = Join-Path $fixture '.local/state'
    New-Item -ItemType Junction -Path $state -Target $outside | Out-Null
    Expect-Refusal { Test-SierraWorkspace } 'workspace check rejects default state ancestor junction'
    Remove-Item -LiteralPath $state
    $linkTree = New-SierraWorktree -Name 'linked-evidence' -Detach
    $build = Join-Path $linkTree 'app/build'
    New-Item -ItemType Directory -Path $build -Force | Out-Null
    $outputs = Join-Path $build 'outputs'
    Set-Content -LiteralPath (Join-Path $outside 'external.txt') -Value 'not project evidence'
    New-Item -ItemType Junction -Path $outputs -Target $outside | Out-Null
    Expect-Refusal { Remove-SierraWorktree -Path $linkTree } 'evidence root junction prevents removal/copy'
    Remove-Item -LiteralPath $outputs
    Remove-Item -LiteralPath $build
    New-Item -ItemType Directory -Path (Join-Path $outside 'outputs') | Out-Null
    New-Item -ItemType Junction -Path $build -Target $outside | Out-Null
    Expect-Refusal { Remove-SierraWorktree -Path $linkTree } 'evidence ancestor junction prevents removal/copy'
    Remove-Item -LiteralPath $build
    Remove-SierraWorktree -Path $linkTree
}
Expect-Refusal { Resolve-SierraOutputFile -Path (Join-Path $testDir 'external.log') } 'explicit external output filename rejected'
Assert ((Resolve-SierraOutputFile -Path (Join-Path $fixture '.local/runs/test.log')) -eq (Join-Path $fixture '.local/runs/test.log')) 'explicit local output filename accepted'
Test-SierraWorkspace
. (Join-Path $realRoot 'scripts/_workspace.ps1')
"$count workspace checks passed. Fixture evidence: $testDir" | Tee-Object -FilePath (Join-Path $testDir 'result.txt')
