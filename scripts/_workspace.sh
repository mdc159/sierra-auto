#!/usr/bin/env bash
# Canonical paths shared by workspace and device scripts. No device side
# effects. Source it; do not run it.
#
# Ported from _workspace.ps1 (git history keeps the original).

sierra_die() { printf '\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

SIERRA_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

# The canonical main checkout: the folder holding the .git directory that
# linked worktrees point back to.
sierra_root() {
    local common
    if ! common="$(git -C "$SIERRA_SCRIPT_DIR" rev-parse --path-format=absolute --git-common-dir)" || [[ -z "$common" ]]; then
        sierra_die 'Sierra scripts must run inside a Git checkout.'
    fi
    common="$(realpath -m -- "${common%%$'\n'*}")"
    if [[ "$(basename -- "$common")" != '.git' ]]; then
        sierra_die 'A regular main checkout with a .git directory is required.'
    fi
    dirname -- "$common"
}

# Lexically resolves PATH against PARENT and insists the result stays beneath
# PARENT. Lexical checks alone do not prevent symlink escapes, so every
# existing path component, including the boundary itself, is checked for
# being a symlink. Prints the resolved path; returns 1 (message on stderr)
# when the path escapes.
sierra_assert_child_path() {
    local path="$1" parent="$2"
    local parent_real full
    parent_real="$(realpath -m -- "$parent")"
    parent_real="${parent_real%/}"
    [[ "$parent_real" == /* ]] || parent_real="/$parent_real"

    local -a stack=() comps pcomps
    local comp p localIFS="$IFS"
    IFS='/'
    if [[ "$path" = /* ]]; then
        read -r -a comps <<< "${path#/}"
    else
        read -r -a comps <<< "$path"
    fi
    IFS="$localIFS"
    if [[ "$path" != /* ]]; then
        IFS='/'
        local -a pcomps
        read -r -a pcomps <<< "${parent_real#/}"
        IFS="$localIFS"
        for p in "${pcomps[@]}"; do stack+=("$p"); done
    fi
    for comp in "${comps[@]}"; do
        if [[ -z "$comp" || "$comp" == "." ]]; then continue; fi
        if [[ "$comp" == ".." ]]; then
            if [[ "${#stack[@]}" -eq 0 ]]; then
                printf 'ERROR: Cannot validate path (escapes root): %s\n' "$path" >&2
                return 1
            fi
            unset 'stack[${#stack[@]}-1]'
            continue
        fi
        stack+=("$comp")
    done
    if [[ "${#stack[@]}" -eq 0 ]]; then
        full="/"
    else
        full="/$(printf '%s/' "${stack[@]}")"
        full="${full%/}"
    fi

    if [[ "$full" != "$parent_real" && "$full" != "$parent_real"/* ]]; then
        printf 'ERROR: Path must be beneath %s : %s\n' "$parent_real" "$full" >&2
        return 1
    fi

    local cursor="$parent_real"
    while [[ "$cursor" != "$full" ]]; do
        # Strip "$cursor/" prefix and take the next component.
        local rest="${full#"$cursor"/}"
        local next="${rest%%/*}"
        cursor="$cursor/$next"
        if [[ -L "$cursor" ]]; then
            printf 'ERROR: Workspace paths cannot traverse a junction or symlink: %s\n' "$cursor" >&2
            return 1
        fi
    done
    printf '%s\n' "$full"
}

# A directory beneath the canonical root's .local, honouring an override that
# must itself stay beneath .local. Prints the path.
sierra_local_directory() {
    local name="$1" override="${2:-}"
    local root local_dir dir
    root="$(sierra_root)"
    if ! local_dir="$(sierra_assert_child_path .local "$root")"; then exit 1; fi
    if ! dir="$(sierra_assert_child_path "${override:-$name}" "$local_dir")"; then exit 1; fi
    mkdir -p -- "$dir"
    printf '%s\n' "$dir"
}

# An explicit file argument keeps shell-relative semantics, within .local.
# Prints the absolute path.
sierra_resolve_output_file() {
    local path="$1" root absolute
    root="$(sierra_root)"
    if [[ "$path" = /* ]]; then absolute="$path"; else absolute="$(pwd)/$path"; fi
    local full
    if ! full="$(sierra_assert_child_path "$absolute" "$root/.local")"; then exit 1; fi
    mkdir -p -- "$(dirname -- "$full")"
    printf '%s\n' "$full"
}

sierra_worktree_directory() {
    local root dir
    root="$(sierra_root)"
    if ! dir="$(sierra_assert_child_path .worktrees "$root")"; then exit 1; fi
    mkdir -p -- "$dir"
    printf '%s\n' "$dir"
}

# Registered worktrees, one 'path<TAB>branch<TAB>commit' line each.
sierra_worktrees() {
    local root
    root="$(sierra_root)"
    git -C "$root" -c core.quotePath=false worktree list --porcelain | awk '
        /^worktree / { path = substr($0, 10) }
        /^HEAD /     { commit = substr($0, 6) }
        /^branch /   { branch = substr($0, 8) }
        /^detached/  { branch = "(detached)" }
        /^bare/      { branch = "(bare)" }
        /^$/         { if (path != "") { printf "%s\t%s\t%s\n", path, (branch == "" ? "-" : branch), commit; path = commit = branch = "" } }
        END          { if (path != "") printf "%s\t%s\t%s\n", path, (branch == "" ? "-" : branch), commit }
    ' || sierra_die 'Cannot list worktrees.'
}

# new_sierra_worktree NAME [REF] [BRANCH] [DETACH(0|1)]
new_sierra_worktree() {
    local name="$1" ref="${2:-HEAD}" branch="${3:-}" detach="${4:-0}"
    if [[ ! "$name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]]; then
        sierra_die 'Worktree name must contain letters, numbers, hyphens or underscores.'
    fi
    if [[ "$detach" == 1 && -n "$branch" ]]; then
        sierra_die 'Choose either a new branch or a detached worktree.'
    fi
    if [[ "$ref" == -* || ( -n "$branch" && "$branch" == -* ) ]]; then
        sierra_die 'Ref and branch cannot start with a hyphen.'
    fi
    local wtdir path
    wtdir="$(sierra_worktree_directory)"
    if ! path="$(sierra_assert_child_path "$name" "$wtdir")"; then exit 1; fi
    if [[ -e "$path" ]]; then
        sierra_die "Worktree destination already exists; inspect it first: $path"
    fi
    local -a git_args=(worktree add)
    if [[ "$detach" == 1 ]]; then
        git_args+=(--detach)
    elif [[ -n "$branch" ]]; then
        git_args+=(-b "$branch")
    fi
    local output
    if ! output="$(git -C "$(sierra_root)" "${git_args[@]}" "$path" "$ref" 2>&1)"; then
        sierra_die "Worktree creation failed: $output"
    fi
    printf '%s\n' "$output"
    printf '%s\n' "$path"
}

# Removes a clean registered worktree after preserving its build evidence
# under .local and a recovery ref. Refuses changed/untracked worktrees and
# unfamiliar ignored files; uses only non-forced `git worktree remove`.
remove_sierra_worktree() {
    local path="$1"
    local wtdir full
    wtdir="$(sierra_worktree_directory)"
    if ! full="$(sierra_assert_child_path "$path" "$wtdir")"; then exit 1; fi

    local registered=0 tpath
    while IFS=$'\t' read -r tpath _ _; do
        [[ -n "$tpath" ]] || continue
        if [[ "$tpath" == "$full" ]]; then registered=$((registered + 1)); fi
    done < <(sierra_worktrees)
    if [[ "$registered" -ne 1 ]]; then
        sierra_die "Not a registered Sierra worktree: $full"
    fi

    local dirty
    if ! dirty="$(git -C "$full" status --porcelain --untracked-files=all)"; then
        sierra_die "Cannot read worktree status: $full"
    fi
    if [[ -n "$dirty" ]]; then
        sierra_die "Worktree has changes; preserve them before removal: $full"
    fi

    # Refuse unknown ignored files, including local settings and credentials.
    # Keep reports and distributables before discarding generated intermediates.
    local ignored unknown
    if ! ignored="$(git -C "$full" ls-files --others --ignored --exclude-standard)"; then
        sierra_die "Cannot inventory ignored files: $full"
    fi
    unknown="$(printf '%s\n' "$ignored" | grep -Ev '^(\.gradle/|\.kotlin/|app/build/|build/|bridge/(\.venv/|\.pytest_cache/|.*__pycache__/))' || true)"
    if [[ -n "$unknown" ]]; then
        sierra_die "Worktree has ignored local files that must be preserved first: $(printf '%s' "$unknown" | tr '\n' ',' | sed 's/,$//')"
    fi

    local name removal_id uid
    name="$(basename -- "$full")"
    uid="$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)"
    removal_id="$(date +%Y%m%d-%H%M%S)-${uid//-/}"
    local evidence
    evidence="$(sierra_local_directory "runs/worktree-$name-$removal_id")"

    local rel source file relative target hash target_hash link
    local manifest=''
    for rel in app/build/reports app/build/test-results app/build/outputs build/reports; do
        if ! source="$(sierra_assert_child_path "$rel" "$full")"; then exit 1; fi
        [[ -d "$source" ]] || continue
        if link="$(find "$source" -type l -print -quit)" && [[ -n "$link" ]]; then
            sierra_die "Build evidence contains a junction or symlink: $source"
        fi
        while IFS= read -r -d '' file; do
            relative="${file#"$full"/}"
            if ! target="$(sierra_assert_child_path "$relative" "$evidence")"; then exit 1; fi
            mkdir -p -- "$(dirname -- "$target")"
            hash="$(sha256sum -- "$file" | awk '{print $1}')"
            cp -- "$file" "$target"
            target_hash="$(sha256sum -- "$target" | awk '{print $1}')"
            if [[ "$hash" != "$target_hash" ]]; then
                sierra_die "Evidence copy failed: $target"
            fi
            manifest+=$(printf '{"Path":"%s","SHA256":"%s"},' "$(sierra_json_escape "$relative")" "$hash")$'\n'
        done < <(find "$source" -type f -print0)
    done

    local head
    if ! head="$(git -C "$full" rev-parse HEAD)"; then
        sierra_die 'Cannot resolve worktree HEAD.'
    fi
    head="${head%%$'\n'*}"
    local recovery_ref="refs/sierra/worktrees/$name/$removal_id"
    local zeros
    zeros="$(printf '%040d' 0)"
    if ! git -C "$(sierra_root)" update-ref "$recovery_ref" "$head" "$zeros"; then
        sierra_die 'Cannot preserve worktree commit.'
    fi

    local manifest_files
    manifest_files="$(printf '%s' "$manifest" | sed -e '/^[[:space:]]*$/d' -e 's/,$//' | paste -sd, -)"
    printf '{"Worktree":"%s","Commit":"%s","RecoveryRef":"%s","Files":[%s]}\n' \
        "$(sierra_json_escape "$full")" "$head" "$recovery_ref" "$manifest_files" \
        > "$evidence/manifest.json"

    if ! git -C "$(sierra_root)" worktree remove "$full"; then
        sierra_die "Git refused worktree removal: $full"
    fi
    printf 'Retained worktree commit and build evidence: %s\n' "$evidence"
}

sierra_json_escape() {
    local s="$1"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    printf '%s' "$s"
}

# Fails when any default workspace path is unsafe, a worktree lives outside
# .worktrees, an output override escapes .local, .local/.worktrees are not
# ignored, or a sibling folder mimics this checkout.
test_sierra_workspace() {
    local root boundary
    root="$(sierra_root)"
    boundary="$root/.worktrees"
    local -a problems=()
    local relative msg name override dir tpath sibling

    for relative in .worktrees .local .local/runs .local/state/dhu .local/downloads/releases; do
        if ! msg="$(sierra_assert_child_path "$relative" "$root" 2>&1 >/dev/null)"; then
            problems+=("Unsafe default workspace path: $relative ($msg)")
        fi
    done

    while IFS=$'\t' read -r tpath _ _; do
        [[ -n "$tpath" ]] || continue
        [[ "$tpath" == "$root" ]] && continue
        if ! sierra_assert_child_path "$tpath" "$boundary" >/dev/null 2>&1; then
            problems+=("External/unsafe worktree: $tpath")
        fi
    done < <(sierra_worktrees)

    for name in SIERRA_RUNS SIERRA_STATE; do
        override="$(printenv "$name" || true)"
        if [[ -n "$override" ]]; then
            if ! sierra_assert_child_path "$override" "$root/.local" >/dev/null 2>&1; then
                problems+=("$name must point beneath $root/.local")
            fi
        fi
    done

    for dir in .worktrees .local; do
        if ! git -C "$root" check-ignore --quiet -- "$dir/workspace-check"; then
            problems+=("$dir must be ignored by Git.")
        fi
    done

    prefix="$(basename -- "$root")-"
    while IFS= read -r sibling; do
        [[ -n "$sibling" ]] || continue
        problems+=("Project sibling folder needs consolidation: $sibling")
    done < <(find "$(dirname -- "$root")" -maxdepth 1 -mindepth 1 -type d -name "$prefix*" 2>/dev/null || true)

    if [[ "${#problems[@]}" -gt 0 ]]; then
        sierra_die "$(printf '%s\n' "${problems[@]}")"
    fi
    printf 'Workspace OK: %s; all linked worktrees are under .worktrees.\n' "$root"
}
