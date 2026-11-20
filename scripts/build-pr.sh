#!/usr/bin/env bash
# Builds a pull request, runs its unit tests and installs it on the phone over
# the existing Sierra.
#
# Fetches the PR head into a temporary worktree under the canonical repo's
# .worktrees folder, then:
#
#   1. assembleDebug testDebugUnitTest      (no signing keys in the environment)
#   2. assembleRelease                       (signed with the local release key)
#   3. install on the phone with Play recorded as installer
#
# A debug build is signed with a different key from the release on the phone, so
# installing it would mean uninstalling Sierra and losing its settings. Step 2
# builds the PR as a release instead, signed with the same key as the GitHub
# Releases, using the environment variables app/build.gradle.kts already reads:
# SIGNING_KEYSTORE_PATH, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS and
# SIGNING_KEY_PASSWORD. They come from ~/.config/sierra-auto (release.jks and
# signing.env) and exist only inside the Gradle process for step 2. They are
# never printed.
#
# The build uses the versionCode already on the phone. Installation also requires
# a compatible signing certificate. Returning to a release requires a compatible
# certificate and a versionCode at least this high: the GitHub APK and Play tracks
# use different sequences, so the latest GitHub APK may be a downgrade. Prefer
# the enrolled Play channel for normal updates; do not uninstall to bypass a
# downgrade.
#
# SECURITY: a build runs the PR's own Gradle scripts on this machine, and step 2
# runs them with the signing passwords in the environment. The script refuses a
# PR whose branch lives in a fork unless --trust-fork is given. Read the diff of
# *.gradle.kts, gradle/ and gradlew before building a PR you did not write.
#
#   scripts/build-pr.sh 24
#   scripts/build-pr.sh 24 --no-install --keep
#
# Ported from build-pr.ps1 (git history keeps the original).
set -euo pipefail

# shellcheck source=/dev/null
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/_common.sh"

NUMBER=''
SERIAL=''
NO_INSTALL=0
KEEP=0
TRUST_FORK=0
REPO='mdc159/sierra-auto'
SIGNING_DIR="$HOME/.config/sierra-auto"

usage() {
    cat <<'EOF'
Usage: scripts/build-pr.sh NUMBER [options]

  NUMBER            pull request number to build
  --serial SERIAL   adb serial of the phone (default: the only device)
  --no-install      build and test only; leave the phone alone
  --keep            keep the worktree afterwards (its path is printed)
  --trust-fork      allow a PR whose head branch is in a fork (see the
                    SECURITY note in the header)
  --repo OWNER/NAME GitHub repository (default mdc159/sierra-auto)
  --signing-dir DIR release key folder (default ~/.config/sierra-auto)
  -h, --help        show this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) SERIAL="${2:?}"; shift 2 ;;
        --no-install) NO_INSTALL=1; shift ;;
        --keep) KEEP=1; shift ;;
        --trust-fork) TRUST_FORK=1; shift ;;
        --repo) REPO="${2:?}"; shift 2 ;;
        --signing-dir) SIGNING_DIR="${2:?}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        -*) usage >&2; sierra_die "Unknown argument: $1" ;;
        *)
            [[ -z "$NUMBER" ]] || sierra_die 'Only one PR number can be given.'
            [[ "$1" =~ ^[0-9]+$ ]] || sierra_die "PR number expected, got: $1"
            NUMBER="$1"
            shift
            ;;
    esac
done
[[ -n "$NUMBER" ]] || { usage >&2; sierra_die 'A PR number is required.'; }

signing_names=(SIGNING_KEYSTORE_PATH SIGNING_STORE_PASSWORD SIGNING_KEY_ALIAS SIGNING_KEY_PASSWORD)

run_gradle() {
    # run_gradle DIR LOGFILE GRADLE-ARGS...
    local dir="$1" logfile="$2"
    shift 2
    local rc=0
    (
        cd -- "$dir"
        ./gradlew --no-daemon "$@" > >(tee -a -- "$logfile" | grep -E '^(> Task .*(FAILED)|BUILD |FAILURE|\* What went wrong|e: |w: .*error|.*Test.*FAILED|[0-9]+ tests completed)' || true) 2>&1
    ) || rc=$?
    return "$rc"
}

repo_root="$(sierra_root)"
test_sierra_workspace

# 1. What is this PR?
gh_field() { gh pr view "$NUMBER" -R "$REPO" --json "$1" -q ".$1"; }
pr_title="$(gh_field title)" || sierra_die "Could not read PR #$NUMBER of $REPO."
pr_number="$(gh_field number)"
pr_state="$(gh_field state)"
pr_branch="$(gh_field headRefName)"
pr_head="$(gh_field headRefOid)"
pr_fork="$(gh_field isCrossRepository)"
pr_author="$(gh pr view "$NUMBER" -R "$REPO" --json author -q .author.login)"
sierra_step "PR #$pr_number: $pr_title"
sierra_note "branch $pr_branch, head ${pr_head:0:7}, by $pr_author, $pr_state"
if [[ "$pr_fork" == 'true' && $TRUST_FORK -eq 0 ]]; then
    sierra_die "PR #$NUMBER comes from a fork. Building it would run its Gradle scripts here with the release signing passwords in the environment. Review it first, then rerun with --trust-fork if you accept that."
fi

# 2. Environment for Gradle.
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/java" ]]; then
    sierra_die 'JDK 17 not found. Set SIERRA_TOOLCHAIN or JAVA_HOME.'
fi
export ANDROID_HOME ANDROID_SDK_ROOT
# Make sure no signing value leaks in from the caller's shell into step 1.
unset "${signing_names[@]}" 2>/dev/null || true

# 3. versionCode: the one on the phone, else the latest release number.
if [[ $NO_INSTALL -eq 1 ]] && ! "$(sierra_adb)" devices | grep -qE '[[:space:]]device$'; then
    SERIAL=''
else
    SERIAL="$(sierra_phone_serial "$SERIAL")"
fi
version_code=''
if [[ -n "$SERIAL" ]] && sierra_get_install_record "$SERIAL"; then
    version_code="$RECORD_VERSION_CODE"
    sierra_note "Phone has $RECORD_VERSION_NAME (code $version_code); building the PR with the same versionCode."
else
    tag="$(gh release view -R "$REPO" --json tagName -q .tagName)" || sierra_die 'Could not read the latest release.'
    version_code="$(grep -oE '[0-9]+$' <<< "$tag" || true)"
    [[ -n "$version_code" ]] || version_code=1
    sierra_note "Sierra is not on the phone; building with versionCode $version_code (latest release $tag)."
fi

# 4. Unique temporary worktree beneath the canonical repository.
short="${pr_head:0:7}"
uid8="$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid | tr -d '-')"
uid8="$(printf '%s' "${uid8//-/}" | cut -c1-8)"
work_name="pr-$NUMBER-$short-$uid8"
out_dir="$(sierra_runs_dir)/pr-$NUMBER-$short-$(date +%Y%m%d-%H%M%S)"
mkdir -p -- "$out_dir"
log="$out_dir/gradle.log"

sierra_step "Fetching PR #$NUMBER for .worktrees/$work_name"
git -C "$repo_root" fetch --quiet origin "pull/$NUMBER/head" || sierra_die "git fetch of pull/$NUMBER/head failed."
fetched="$(git -C "$repo_root" rev-parse FETCH_HEAD)"
fetched="${fetched%%$'\n'*}"
if [[ "$fetched" != "$pr_head" ]]; then
    sierra_note "Note: fetched ${fetched:0:7}, GitHub reported $short. Building what was fetched."
fi
work="$(new_sierra_worktree "$work_name" "$fetched" '' 1 | tail -n 1)"

ok=0
cleanup_worktree() {
    if [[ $KEEP -eq 1 || $ok -eq 0 ]]; then
        sierra_note "Worktree kept at $work (after preserving evidence, remove with: scripts/workspace.sh remove $work_name)"
    else
        if ! remove_sierra_worktree "$work"; then
            printf '\033[33mBuild completed; worktree retained for inspection\033[0m\n' >&2
        fi
    fi
}
trap cleanup_worktree EXIT

# 5. Debug build and unit tests, with no signing values in the environment.
sierra_step "Step 1 of 3: assembleDebug testDebugUnitTest (log: $log)"
code=0
run_gradle "$work" "$log" assembleDebug testDebugUnitTest "-PversionCode=$version_code" || code=$?
if [[ -d "$work/app/build/reports/tests" ]]; then
    mkdir -p -- "$out_dir/test-reports"
    cp -R -- "$work/app/build/reports/tests/." "$out_dir/test-reports/"
fi
if [[ $code -ne 0 ]]; then
    sierra_die "Debug build or unit tests failed (exit $code). See $log and $out_dir/test-reports."
fi
sierra_note 'Debug build and unit tests passed.'

# 6. Release build signed with the local key.
keystore="$SIGNING_DIR/release.jks"
env_file="$SIGNING_DIR/signing.env"
if [[ ! -f "$keystore" || ! -f "$env_file" ]]; then
    sierra_die "Release signing files not found in $SIGNING_DIR (release.jks and signing.env). Without them the PR can only be built debug-signed, which will not install over the release on the phone."
fi
sierra_step 'Step 2 of 3: assembleRelease, signed with the local release key'
export SIGNING_KEYSTORE_PATH="$keystore"
while IFS= read -r line; do
    [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?(SIGNING_[A-Z_]+)[[:space:]]=[[:space:]]*(.*[^[:space:]])[[:space:]]*$ ]] || continue
    value="${BASH_REMATCH[3]}"
    value="${value%\"}"; value="${value#\"}"
    value="${value%\'}"; value="${value#\'}"
    export "${BASH_REMATCH[2]}=$value"
done < "$env_file"
missing=''
for name in "${signing_names[@]}"; do
    if [[ -z "${!name:-}" ]]; then missing="$missing $name"; fi
done
if [[ -n "$missing" ]]; then
    unset "${signing_names[@]}" 2>/dev/null || true
    sierra_die "signing.env does not define:$missing"
fi
code=0
run_gradle "$work" "$log" assembleRelease "-PversionCode=$version_code" || code=$?
unset "${signing_names[@]}" 2>/dev/null || true
if [[ $code -ne 0 ]]; then
    sierra_die "Release build failed (exit $code). See $log."
fi

built="$work/app/build/outputs/apk/release/app-release.apk"
[[ -f "$built" ]] || sierra_die "Release APK not found at $built."
apk="$out_dir/sierra-auto-pr$NUMBER-$short.apk"
cp -- "$built" "$apk"

# Prove it carries the release certificate, as CI does.
apksigner="$(find "$(sierra_android_sdk)/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -1 || true)"
[[ -n "$apksigner" ]] || sierra_die "apksigner not found under $(sierra_android_sdk)/build-tools."
certs="$("$apksigner" verify --print-certs "$apk" 2>&1)" || sierra_die "apksigner could not verify $apk: $certs"
if grep -q 'CN=Android Debug' <<< "$certs"; then
    sierra_die 'The release APK is debug-signed, so it will not install over the release on the phone. Check signing.env.'
fi
digest="$(grep -m1 'SHA-256 digest' <<< "$certs" || true)"
sierra_note "Signed with the release certificate. $digest"
sierra_note "APK: $apk"

# 7. Install.
if [[ $NO_INSTALL -eq 1 ]]; then
    sierra_step 'Step 3 of 3 skipped (--no-install)'
else
    sierra_step 'Step 3 of 3: installing on the phone'
    bash "$SIERRA_SCRIPT_DIR/install-phone.sh" --apk "$apk" --serial "$SERIAL" || sierra_die 'Install failed.'
    sierra_note "The phone now runs PR #$NUMBER ($short) under version name 0.1.$version_code. Return through the appropriate release channel with a compatible signer and versionCode >= $version_code; the latest GitHub APK may be older than this Play-derived version. See docs/UPDATES.md."
fi
ok=1

printf '\nDone. Output folder: %s\n' "$out_dir"
