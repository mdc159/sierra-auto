#requires -Version 7
<#
.SYNOPSIS
Builds a pull request, runs its unit tests and installs it on the phone over
the existing Sierra.

.DESCRIPTION
Fetches the PR head into a throwaway git worktree outside the repo, then:

  1. assembleDebug testDebugUnitTest      (no signing keys in the environment)
  2. assembleRelease                       (signed with the local release key)
  3. install on the phone with Play recorded as installer

A debug build is signed with a different key from the release on the phone, so
installing it would mean uninstalling Sierra and losing its settings. Step 2
builds the PR as a release instead, signed with the same key as the GitHub
Releases, using the environment variables app/build.gradle.kts already reads:
SIGNING_KEYSTORE_PATH, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS and
SIGNING_KEY_PASSWORD. They come from %USERPROFILE%\.config\sierra-auto
(release.jks and signing.env) and exist only inside the Gradle process for
step 2. They are never printed.

The build uses the versionCode already on the phone, so the PR build installs
over it and the next real release still installs over the PR build.

SECURITY: a build runs the PR's own Gradle scripts on this PC, and step 2 runs
them with the signing passwords in the environment. The script refuses a PR
whose branch lives in a fork unless -TrustFork is given. Read the diff of
*.gradle.kts, gradle/ and gradlew before building a PR you did not write.

.EXAMPLE
scripts\build-pr.ps1 24
.EXAMPLE
scripts\build-pr.ps1 24 -NoInstall -Keep
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)][int]$Number,
    [string]$Serial,
    # Build and test only; leave the phone alone.
    [switch]$NoInstall,
    # Keep the worktree afterwards (its path is printed).
    [switch]$Keep,
    # Allow a PR whose head branch is in a fork. See SECURITY above.
    [switch]$TrustFork,
    [string]$Repo = 'mdc159/sierra-auto',
    [string]$SigningDir = (Join-Path $HOME '.config\sierra-auto')
)

. (Join-Path $PSScriptRoot '_common.ps1')
trap { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }

$signingNames = @('SIGNING_KEYSTORE_PATH', 'SIGNING_STORE_PASSWORD', 'SIGNING_KEY_ALIAS', 'SIGNING_KEY_PASSWORD')

function Invoke-Gradle {
    param([string]$Dir, [string[]]$GradleArgs, [string]$LogFile)
    Push-Location $Dir
    try {
        & (Join-Path $Dir 'gradlew.bat') --no-daemon @GradleArgs 2>&1 | Tee-Object -FilePath $LogFile -Append | ForEach-Object {
            # Progress only; the full output is in the log file.
            if ("$_" -match '^(> Task .*(FAILED)|BUILD |FAILURE|\* What went wrong|e: |w: .*error|.*Test.*FAILED|\d+ tests completed)') { Write-Host "    $_" }
        }
        return $LASTEXITCODE
    } finally { Pop-Location }
}

$repoRoot = (& git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
if (-not $repoRoot) { throw "scripts\ is not inside a git checkout." }

# 1. What is this PR?
$pr = & gh pr view $Number -R $Repo --json number,title,state,headRefName,headRefOid,isCrossRepository,author | ConvertFrom-Json
if (-not $pr) { throw "Could not read PR #$Number of $Repo." }
Write-Step "PR #$($pr.number): $($pr.title)"
Write-Note "branch $($pr.headRefName), head $($pr.headRefOid.Substring(0, 7)), by $($pr.author.login), $($pr.state)"
if ($pr.isCrossRepository -and -not $TrustFork) {
    throw "PR #$Number comes from a fork. Building it would run its Gradle scripts here with the release signing passwords in the environment. Review it first, then rerun with -TrustFork if you accept that."
}

# 2. Environment for Gradle.
$toolchain = Get-SierraToolchain
$sdk = Get-AndroidSdk
$jdk = Join-Path $toolchain 'jdk-17'
if (Test-Path $jdk) { $env:JAVA_HOME = $jdk }
if (-not $env:JAVA_HOME) { throw "JDK 17 not found. Set SIERRA_TOOLCHAIN or JAVA_HOME." }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$gradleHome = Join-Path $toolchain 'gradle-user-home'
if (Test-Path $gradleHome) { $env:GRADLE_USER_HOME = $gradleHome }
# Make sure no signing value leaks in from the caller's shell into step 1.
foreach ($name in $signingNames) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }

# 3. versionCode: the one on the phone, else the latest release number.
$Serial = if ($NoInstall -and -not (& (Get-AdbPath) devices | Select-String '\sdevice$')) { $null } else { Get-PhoneSerial -Serial $Serial }
$record = if ($Serial) { Get-InstallRecord -Serial $Serial } else { $null }
if ($record) {
    $versionCode = $record.VersionCode
    Write-Note "Phone has $($record.VersionName) (code $versionCode); building the PR with the same versionCode."
} else {
    $tag = (& gh release view -R $Repo --json tagName -q .tagName)
    $versionCode = if ($tag -match '(\d+)$') { [int]$Matches[1] } else { 1 }
    Write-Note "Sierra is not on the phone; building with versionCode $versionCode (latest release $tag)."
}

# 4. Throwaway worktree, outside the repo.
$short = $pr.headRefOid.Substring(0, 7)
$workRoot = Join-Path ([IO.Path]::GetTempPath()) 'sierra-auto-pr'
$work = Join-Path $workRoot "pr-$Number-$short"
$outDir = Join-Path (Get-RunsDir) ("pr-$Number-$short-{0:yyyyMMdd-HHmmss}" -f (Get-Date))
New-Item -ItemType Directory -Force -Path $workRoot, $outDir | Out-Null
$log = Join-Path $outDir 'gradle.log'

Write-Step "Fetching PR #$Number into $work"
& git -C $repoRoot fetch --quiet origin "pull/$Number/head"
if ($LASTEXITCODE -ne 0) { throw "git fetch of pull/$Number/head failed." }
$fetched = (& git -C $repoRoot rev-parse FETCH_HEAD).Trim()
if ($fetched -ne $pr.headRefOid) { Write-Note "Note: fetched $($fetched.Substring(0, 7)), GitHub reported $short. Building what was fetched." }
if (Test-Path $work) { & git -C $repoRoot worktree remove --force $work 2>$null; Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }
& git -C $repoRoot worktree add --quiet --detach $work $fetched
if ($LASTEXITCODE -ne 0) { throw "git worktree add failed." }

$ok = $false
try {
    # 5. Debug build and unit tests, with no signing values in the environment.
    Write-Step "Step 1 of 3: assembleDebug testDebugUnitTest (log: $log)"
    $code = Invoke-Gradle -Dir $work -GradleArgs @('assembleDebug', 'testDebugUnitTest', "-PversionCode=$versionCode") -LogFile $log
    $reports = Join-Path $work 'app\build\reports\tests'
    if (Test-Path $reports) { Copy-Item $reports (Join-Path $outDir 'test-reports') -Recurse -Force }
    if ($code -ne 0) { throw "Debug build or unit tests failed (exit $code). See $log and $outDir\test-reports." }
    Write-Note "Debug build and unit tests passed."

    # 6. Release build signed with the local key.
    $keystore = Join-Path $SigningDir 'release.jks'
    $envFile = Join-Path $SigningDir 'signing.env'
    if (-not (Test-Path $keystore) -or -not (Test-Path $envFile)) {
        throw "Release signing files not found in $SigningDir (release.jks and signing.env). Without them the PR can only be built debug-signed, which will not install over the release on the phone."
    }
    Write-Step "Step 2 of 3: assembleRelease, signed with the local release key"
    try {
        $env:SIGNING_KEYSTORE_PATH = $keystore
        foreach ($line in Get-Content $envFile) {
            if ($line -match '^\s*(?:export\s+)?(SIGNING_[A-Z_]+)\s*=\s*(.*?)\s*$') {
                $value = $Matches[2].Trim('"').Trim("'")
                Set-Item -Path "Env:$($Matches[1])" -Value $value
            }
        }
        $missing = @($signingNames | Where-Object { -not (Test-Path "Env:$_") -or -not (Get-Item "Env:$_").Value })
        if ($missing.Count -gt 0) { throw "signing.env does not define: $($missing -join ', ')" }
        $code = Invoke-Gradle -Dir $work -GradleArgs @('assembleRelease', "-PversionCode=$versionCode") -LogFile $log
    } finally {
        foreach ($name in $signingNames) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
    }
    if ($code -ne 0) { throw "Release build failed (exit $code). See $log." }

    $built = Join-Path $work 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path $built)) { throw "Release APK not found at $built." }
    $apk = Join-Path $outDir "sierra-auto-pr$Number-$short.apk"
    Copy-Item $built $apk

    # Prove it carries the release certificate, as CI does.
    $apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Recurse -Filter 'apksigner.bat' | Sort-Object { [version]($_.Directory.Name -replace '[^\d.]', '') } | Select-Object -Last 1
    if (-not $apksigner) { throw "apksigner not found under $sdk\build-tools." }
    $certs = & $apksigner.FullName verify --print-certs $apk 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "apksigner could not verify $apk`: $($certs -join ' ')" }
    if ($certs -match 'CN=Android Debug') { throw "The release APK is debug-signed, so it will not install over the release on the phone. Check signing.env." }
    $digest = ($certs | Where-Object { $_ -match 'SHA-256 digest' } | Select-Object -First 1)
    Write-Note "Signed with the release certificate. $("$digest".Trim())"
    Write-Note "APK: $apk"

    # 7. Install.
    if ($NoInstall) {
        Write-Step "Step 3 of 3 skipped (-NoInstall)"
    } else {
        Write-Step "Step 3 of 3: installing on the phone"
        & (Join-Path $PSScriptRoot 'install-phone.ps1') -Apk $apk -Serial $Serial
        if ($LASTEXITCODE -ne 0) { throw "Install failed." }
        Write-Note "The phone now runs PR #$Number ($short) under version name 0.1.$versionCode. Run install-phone.ps1 to go back to the latest release."
    }
    $ok = $true
} finally {
    if ($Keep) {
        Write-Note "Worktree kept at $work (remove with: git worktree remove --force `"$work`")"
    } else {
        & git -C $repoRoot worktree remove --force $work 2>$null
        Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
        & git -C $repoRoot worktree prune
    }
}
if ($ok) { Write-Host ""; Write-Host "Done. Output folder: $outDir" }
