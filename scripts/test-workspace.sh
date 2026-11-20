#!/usr/bin/env bash
# Integration checks use disposable local Git repositories and no phone/network.
# They exercise _workspace.sh's functions and the workspace.sh CLI inside a
# temporary fixture repo, never the real repository's worktrees.
#
# Ported from test-workspace.ps1 (git history keeps the original).
set -euo pipefail

# shellcheck source=/dev/null
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/_workspace.sh"

real_scripts="$SIERRA_SCRIPT_DIR"
uid="$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)"
test_dir="$(sierra_local_directory "tests/workspace-${uid//-/}")"
fixture="$test_dir/fixture"
count=0

pass() { count=$((count + 1)); printf 'PASS: %s\n' "$1"; }

expect_refusal() {
    local desc="$1"
    shift
    # Subshell: helpers report refusals by exiting 1, which must not end the run.
    if ( "$@" ) >/dev/null 2>&1; then
        sierra_die "FAIL: $desc (it was allowed)"
    fi
    pass "$desc"
}

fixture_git() {
    git -C "$fixture" "$@" >/dev/null || sierra_die "Fixture Git command failed: git $*"
}

fixture_commit() {
    fixture_git -c user.name='Workspace Test' -c user.email=workspace-test@invalid commit --quiet -m "${1:-fixture}"
}

mkdir -p "$fixture/scripts"
git init --quiet "$fixture" || sierra_die 'Cannot initialize fixture.'
cp "$real_scripts/_workspace.sh" "$fixture/scripts/_workspace.sh"
cp "$real_scripts/_common.sh" "$fixture/scripts/_common.sh"
cp "$real_scripts/workspace.sh" "$fixture/scripts/workspace.sh"
printf '/.local/\n/.worktrees/\napp/build/\nlocal.properties\n' > "$fixture/.gitignore"
fixture_git add .
fixture_commit fixture

# shellcheck source=/dev/null
source "$fixture/scripts/_workspace.sh"

[[ "$(sierra_root)" == "$fixture" ]] || sierra_die 'FAIL: canonical root from main checkout'
pass 'canonical root from main checkout'
test_sierra_workspace
[[ "$(sierra_local_directory runs)" == "$fixture/.local/runs" ]] || sierra_die 'FAIL: default outputs beneath .local'
pass 'default outputs beneath .local'

expect_refusal 'external output override rejected' sierra_local_directory runs "$test_dir/outside"
expect_refusal 'parent traversal rejected' sierra_assert_child_path ../outside "$fixture"
expect_refusal 'similar-prefix sibling rejected' sierra_assert_child_path "$fixture-sibling" "$fixture"
expect_refusal 'worktree traversal name rejected' new_sierra_worktree ../escape HEAD '' 1

tree="$(new_sierra_worktree check HEAD '' 1 | tail -n 1)"
[[ -e "$tree/.git" ]] || sierra_die 'FAIL: worktree created beneath canonical root'
pass 'worktree created beneath canonical root'

# shellcheck source=/dev/null
source "$tree/scripts/_workspace.sh"
[[ "$(sierra_root)" == "$fixture" ]] || sierra_die 'FAIL: linked worktree resolves main checkout root'
pass 'linked worktree resolves main checkout root'
[[ "$(sierra_local_directory runs)" == "$fixture/.local/runs" ]] || sierra_die 'FAIL: linked worktree shares canonical artifact path'
pass 'linked worktree shares canonical artifact path'
expect_refusal 'existing worktree never overwritten' new_sierra_worktree check HEAD '' 1

echo 'keep me' > "$tree/untracked.txt"
expect_refusal 'untracked work prevents removal' remove_sierra_worktree "$tree"
rm "$tree/untracked.txt"
echo '# modified' >> "$tree/.gitignore"
expect_refusal 'tracked modification prevents removal' remove_sierra_worktree "$tree"
git -C "$tree" restore .gitignore || sierra_die 'Fixture restore failed.'
echo 'test-only' > "$tree/local.properties"
expect_refusal 'unfamiliar ignored settings prevent removal' remove_sierra_worktree "$tree"
rm "$tree/local.properties"

mkdir -p "$tree/app/build/outputs"
echo 'test-only fake artifact' > "$tree/app/build/outputs/sample.apk"
# A detached commit must survive removal, even if no branch points at it.
echo 'unique commit' > "$tree/unique.txt"
git -C "$tree" add unique.txt
git -C "$tree" -c user.name='Workspace Test' -c user.email=workspace-test@invalid commit --quiet -m 'detached work' || sierra_die 'Fixture detached commit failed.'
unique="$(git -C "$tree" rev-parse HEAD)"

# shellcheck source=/dev/null
source "$fixture/scripts/_workspace.sh"
remove_sierra_worktree "$tree"
[[ ! -e "$tree" ]] || sierra_die 'FAIL: clean worktree removed safely'
pass 'clean worktree removed safely'
saved_refs="$(git -C "$fixture" for-each-ref '--format=%(objectname)' refs/sierra/worktrees/check/)"
grep -qx "$unique" <<< "$saved_refs" || sierra_die 'FAIL: detached commit retained by recovery ref'
pass 'detached commit retained by recovery ref'
saved="$(find "$fixture/.local/runs" -name sample.apk -type f)"
[[ "$(printf '%s\n' "$saved" | grep -c .)" -eq 1 && "$(cat "$saved")" == 'test-only fake artifact' ]] || sierra_die 'FAIL: build artifact retained before worktree removal'
pass 'build artifact retained before worktree removal'

tree2="$(new_sierra_worktree check HEAD '' 1 | tail -n 1)"
echo 'second detached commit' > "$tree2/second.txt"
git -C "$tree2" add second.txt
git -C "$tree2" -c user.name='Workspace Test' -c user.email=workspace-test@invalid commit --quiet -m 'second detached work' || sierra_die 'Second detached fixture commit failed.'
unique2="$(git -C "$tree2" rev-parse HEAD)"
remove_sierra_worktree "$tree2"
saved_refs="$(git -C "$fixture" for-each-ref '--format=%(objectname)' refs/sierra/worktrees/check/)"
[[ "$(grep -c . <<< "$saved_refs")" -eq 2 && "$(grep -qx "$unique" <<< "$saved_refs" && echo yes)" == yes && "$(grep -qx "$unique2" <<< "$saved_refs" && echo yes)" == yes ]] || sierra_die 'FAIL: reused name preserves both detached commits under unique refs'
pass 'reused name preserves both detached commits under unique refs'

external="$test_dir/external"
fixture_git worktree add --detach "$external" HEAD
expect_refusal 'external registered worktree detected' test_sierra_workspace
fixture_git worktree remove "$external"
sibling="$test_dir/fixture-stray"
mkdir "$sibling"
expect_refusal 'unregistered sibling folder detected' test_sierra_workspace
rmdir "$sibling"

# Symlink escapes (the Windows junction cases, on Linux).
outside="$test_dir/symlink-target"
mkdir -p "$outside"
ln -s "$outside" "$fixture/.local/escape"
expect_refusal 'symlink escape rejected' sierra_local_directory escape/new
rm "$fixture/.local/escape"
runs="$fixture/.local/runs"
runs_saved="$fixture/.local/saved-runs"
mv "$runs" "$runs_saved"
ln -s "$outside" "$runs"
expect_refusal 'workspace check rejects default runs symlink' test_sierra_workspace
rm "$runs"
mv "$runs_saved" "$runs"
ln -s "$outside" "$fixture/.local/state"
expect_refusal 'workspace check rejects default state ancestor symlink' test_sierra_workspace
rm "$fixture/.local/state"

link_tree="$(new_sierra_worktree linked-evidence HEAD '' 1 | tail -n 1)"
mkdir -p "$link_tree/app/build"
ln -s "$outside" "$link_tree/app/build/outputs"
expect_refusal 'evidence root symlink prevents removal/copy' remove_sierra_worktree "$link_tree"
rm "$link_tree/app/build/outputs"
rmdir "$link_tree/app/build"
mkdir -p "$outside/outputs"
echo 'not project evidence' > "$outside/external.txt"
ln -s "$outside" "$link_tree/app/build"
expect_refusal 'evidence ancestor symlink prevents removal/copy' remove_sierra_worktree "$link_tree"
rm "$link_tree/app/build"
remove_sierra_worktree "$link_tree"
pass 'clean evidence-linked worktree removed'

expect_refusal 'explicit external output filename rejected' sierra_resolve_output_file "$test_dir/external.log"
[[ "$(sierra_resolve_output_file "$fixture/.local/runs/test.log")" == "$fixture/.local/runs/test.log" ]] || sierra_die 'FAIL: explicit local output filename accepted'
pass 'explicit local output filename accepted'
test_sierra_workspace

# The workspace.sh CLI against the same fixture.
bash "$fixture/scripts/workspace.sh" check >/dev/null || sierra_die 'FAIL: workspace.sh check CLI'
pass 'workspace.sh check CLI'
bash "$fixture/scripts/workspace.sh" list >/dev/null || sierra_die 'FAIL: workspace.sh list CLI'
pass 'workspace.sh list CLI'
bash "$fixture/scripts/workspace.sh" new cli-check --detach >/dev/null || sierra_die 'FAIL: workspace.sh new CLI'
[[ -e "$fixture/.worktrees/cli-check/.git" ]] || sierra_die 'FAIL: workspace.sh new created the worktree'
pass 'workspace.sh new CLI'
bash "$fixture/scripts/workspace.sh" remove cli-check >/dev/null || sierra_die 'FAIL: workspace.sh remove CLI'
[[ ! -e "$fixture/.worktrees/cli-check" ]] || sierra_die 'FAIL: workspace.sh remove removed the worktree'
pass 'workspace.sh remove CLI'
bash "$fixture/scripts/workspace.sh" new cli-branch fix/cli-test HEAD >/dev/null || sierra_die 'FAIL: workspace.sh new BRANCH CLI'
git -C "$fixture" rev-parse --verify --quiet refs/heads/fix/cli-test >/dev/null || sierra_die 'FAIL: workspace.sh new created the branch'
pass 'workspace.sh new BRANCH CLI'
bash "$fixture/scripts/workspace.sh" remove cli-branch >/dev/null || sierra_die 'FAIL: workspace.sh remove branch worktree CLI'
pass 'workspace.sh remove branch worktree CLI'
expect_refusal 'workspace.sh refuses an out-of-root worktree name' bash "$fixture/scripts/workspace.sh" remove ../evil

# shellcheck source=/dev/null
source "$real_scripts/_workspace.sh"
printf '%s workspace checks passed. Fixture evidence: %s\n' "$count" "$test_dir" | tee "$test_dir/result.txt"
