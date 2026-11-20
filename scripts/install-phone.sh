#!/usr/bin/env bash
# Installs Sierra on the phone with the Play Store recorded as installer.
#
# Installs the latest GitHub Release, a given release tag, or a local APK with
# `adb install -r -i com.android.vending`, then prints the installed version
# and the installer of record.
#
# The -i is repeated on every install, updates included: `adb install -r`
# without it resets the installer of record to none, and it cannot be set
# afterwards.
#
# Play as installer of record is what the project's test setup uses. It is not
# by itself enough for a real head unit, which also looks at who started the
# install; see docs/TESTING.md and issue #36.
#
# Play Protect sometimes asks on the phone whether to send the app for a scan.
# The install waits for the answer, so the script says so and gives up after
# --timeout instead of hanging.
#
# --dry-run only reads adb state and never installs.
#
# Ported from install-phone.ps1 (git history keeps the original).
#
#   scripts/install-phone.sh
#   scripts/install-phone.sh --tag v0.1.19
#   scripts/install-phone.sh --apk app/build/outputs/apk/release/app-release.apk
#   scripts/install-phone.sh --dry-run
set -euo pipefail

# shellcheck source=/dev/null
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/_common.sh"

TAG=''
APK=''
SERIAL=''
REPO='mdc159/sierra-auto'
ALLOW_UNINSTALL=0
TIMEOUT_SEC=180
DRY_RUN=0

usage() {
    cat <<'EOF'
Usage: scripts/install-phone.sh [--tag TAG | --apk APK] [options]

  --tag TAG            install this GitHub Release of mdc159/sierra-auto
  --apk APK            install this local APK file
  --serial SERIAL      adb serial of the phone (default: the only device)
  --repo OWNER/NAME    GitHub repository to download releases from
  --allow-uninstall    uninstall first when signatures differ (wipes settings)
  --timeout SECONDS    wait this long for the phone, including a Play Protect
                       dialog (default 180)
  --dry-run            read-only: show what would be installed and what is on
                       the phone; never installs
  -h, --help           show this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag) TAG="${2:?}"; shift 2 ;;
        --apk) APK="${2:?}"; shift 2 ;;
        --serial) SERIAL="${2:?}"; shift 2 ;;
        --repo) REPO="${2:?}"; shift 2 ;;
        --allow-uninstall) ALLOW_UNINSTALL=1; shift ;;
        --timeout) TIMEOUT_SEC="${2:?}"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; sierra_die "Unknown argument: $1" ;;
    esac
done

SERIAL="$(sierra_phone_serial "$SERIAL")"

if [[ -n "$APK" ]]; then
    [[ -f "$APK" ]] || sierra_die "APK not found: $APK"
    apk_path="$(realpath -- "$APK")"
    source="local file $apk_path"
else
    apk_path=''
    if [[ -n "$TAG" ]]; then
        source="release $TAG"
    elif [[ $DRY_RUN -eq 1 ]]; then
        source='the latest release'
    else
        command -v gh >/dev/null 2>&1 || sierra_die 'The GitHub CLI (gh) is needed to download a release.'
        TAG="$(gh release view -R "$REPO" --json tagName -q .tagName)" || sierra_die "Could not read the latest release of $REPO."
        source="release $TAG"
    fi
fi

if sierra_get_install_record "$SERIAL"; then
    sierra_step "On the phone now: $RECORD_VERSION_NAME (code $RECORD_VERSION_CODE), installer $RECORD_INSTALLER"
else
    sierra_step 'Sierra is not installed on the phone yet'
fi

if [[ $DRY_RUN -eq 1 ]]; then
    if [[ -n "$APK" ]]; then
        sierra_note "Dry run: would install $source"
    else
        sierra_note "Dry run: would download sierra-auto.apk from $source and install it"
    fi
    sierra_note 'Dry run: nothing was downloaded or installed.'
    exit 0
fi

# Keep release downloads inside the canonical project, including from worktrees.
if [[ -z "$APK" ]]; then
    if [[ ! "$TAG" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]]; then
        sierra_die "Release tag is not safe as a folder name: $TAG"
    fi
    dir="$(sierra_local_directory "downloads/releases/$TAG")"
    mkdir -p -- "$dir"
    gh release download "$TAG" -R "$REPO" -p 'sierra-auto.apk' -D "$dir" --clobber \
        || sierra_die "Could not download sierra-auto.apk from release $TAG of $REPO."
    apk_path="$dir/sierra-auto.apk"
fi

sierra_step "Installing $source on $SERIAL with installer $PLAY_INSTALLER"
text="$(sierra_invoke_adb_install "$SERIAL" "$TIMEOUT_SEC" -r -i "$PLAY_INSTALLER" "$apk_path")" || text=''

if [[ "$text" != *Success* ]]; then
    if [[ "$text" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]]; then
        if [[ $ALLOW_UNINSTALL -eq 0 ]]; then
            sierra_die "The APK is signed with a different key from the Sierra already on the phone, so it cannot install over it. A debug build is the usual cause; build-pr.sh makes a release-signed build instead. To replace the app anyway and lose its settings, rerun with --allow-uninstall."
        fi
        sierra_step 'Signatures differ. Uninstalling the old Sierra (its settings are lost)'
        sierra_invoke_adb "$SERIAL" uninstall "$SIERRA_PACKAGE" >/dev/null
        text="$(sierra_invoke_adb_install "$SERIAL" "$TIMEOUT_SEC" -i "$PLAY_INSTALLER" "$apk_path")" || text=''
        [[ "$text" == *Success* ]] || sierra_die "Install failed after uninstall: $text"
    elif [[ "$text" == *INSTALL_FAILED_VERSION_DOWNGRADE* ]]; then
        sierra_die "The phone has a newer versionCode ($RECORD_VERSION_CODE) than this APK. Build with -PversionCode=$RECORD_VERSION_CODE or higher, or install a newer release."
    else
        sierra_die "Install failed: $text"
    fi
fi

sierra_get_install_record "$SERIAL" || sierra_die 'Install reported success but Sierra is not on the phone.'

printf '\n'
printf 'Installed version : %s (versionCode %s)\n' "$RECORD_VERSION_NAME" "$RECORD_VERSION_CODE"
printf 'Installer record  : %s\n' "$RECORD_INSTALLER"
printf 'Initiated by      : %s\n' "$RECORD_INITIATING"

if [[ "$RECORD_INSTALLER" != "$PLAY_INSTALLER" ]]; then
    sierra_die "The installer of record is '$RECORD_INSTALLER', not $PLAY_INSTALLER."
fi
