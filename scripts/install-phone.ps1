#requires -Version 7
<#
.SYNOPSIS
Installs Sierra on the phone with the Play Store recorded as installer.

.DESCRIPTION
Installs the latest GitHub Release, a given release tag, or a local APK with
`adb install -r -i com.android.vending`, then prints the installed version and
the installer of record.

The `-i` is repeated on every install, updates included: `adb install -r`
without it resets the installer of record to none, and it cannot be set
afterwards.

Play as installer of record is what the project's test setup uses. It is not
by itself enough for a real head unit, which also looks at who started the
install; see docs/TESTING.md and issue #36.

Play Protect sometimes asks on the phone whether to send the app for a scan.
The install waits for the answer, so the script says so and gives up after
-TimeoutSec instead of hanging.

.EXAMPLE
scripts\install-phone.ps1
Installs the latest release.

.EXAMPLE
scripts\install-phone.ps1 -Tag v0.1.19

.EXAMPLE
scripts\install-phone.ps1 -Apk app\build\outputs\apk\release\app-release.apk
#>
[CmdletBinding(DefaultParameterSetName = 'Latest')]
param(
    [Parameter(ParameterSetName = 'Tag', Mandatory)][string]$Tag,
    [Parameter(ParameterSetName = 'Apk', Mandatory)][string]$Apk,
    [string]$Serial,
    [string]$Repo = 'mdc159/sierra-auto',
    # Uninstall first when the signatures differ. This wipes Sierra's settings.
    [switch]$AllowUninstall,
    # Seconds to wait for the phone, including for a Play Protect dialog.
    [int]$TimeoutSec = 180
)

. (Join-Path $PSScriptRoot '_common.ps1')
trap { Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }

$Serial = Get-PhoneSerial -Serial $Serial

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
    # A fresh folder per tag, outside the repo.
    $dir = Join-Path ([IO.Path]::GetTempPath()) "sierra-auto-apk\$Tag"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    & gh release download $Tag -R $Repo -p 'sierra-auto.apk' -D $dir --clobber
    if ($LASTEXITCODE -ne 0) { throw "Could not download sierra-auto.apk from release $Tag of $Repo." }
    $apkPath = Join-Path $dir 'sierra-auto.apk'
    $source = "release $Tag"
}

$before = Get-InstallRecord -Serial $Serial
if ($before) {
    Write-Step "On the phone now: $($before.VersionName) (code $($before.VersionCode)), installer $($before.Installer)"
} else {
    Write-Step "Sierra is not installed on the phone yet"
}

Write-Step "Installing $source on $Serial with installer $PlayInstaller"
$text = Invoke-AdbInstall -Serial $Serial -InstallArgs '-r', '-i', $PlayInstaller, $apkPath -TimeoutSec $TimeoutSec

if ($text -notmatch 'Success') {
    if ($text -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        if (-not $AllowUninstall) {
            throw "The APK is signed with a different key from the Sierra already on the phone, so it cannot install over it. A debug build is the usual cause; build-pr.ps1 makes a release-signed build instead. To replace the app anyway and lose its settings, rerun with -AllowUninstall."
        }
        Write-Step "Signatures differ. Uninstalling the old Sierra (its settings are lost)"
        Invoke-Adb $Serial 'uninstall', $SierraPackage | Out-Null
        $text = Invoke-AdbInstall -Serial $Serial -InstallArgs '-i', $PlayInstaller, $apkPath -TimeoutSec $TimeoutSec
        if ($text -notmatch 'Success') { throw "Install failed after uninstall: $text" }
    } elseif ($text -match 'INSTALL_FAILED_VERSION_DOWNGRADE') {
        throw "The phone has a newer versionCode ($($before.VersionCode)) than this APK. Build with -PversionCode=$($before.VersionCode) or higher, or install a newer release."
    } else {
        throw "Install failed: $text"
    }
}

$after = Get-InstallRecord -Serial $Serial
if (-not $after) { throw "Install reported success but Sierra is not on the phone." }

Write-Host ""
Write-Host "Installed version : $($after.VersionName) (versionCode $($after.VersionCode))"
Write-Host "Installer record  : $($after.Installer)"
Write-Host "Initiated by      : $($after.Initiating)"

if ($after.Installer -ne $PlayInstaller) {
    throw "The installer of record is '$($after.Installer)', not $PlayInstaller."
}
