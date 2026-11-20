#!/usr/bin/env bash
# Checks Sierra workspace boundaries, lists worktrees, or creates/removes a
# local worktree.
#
#   scripts/workspace.sh check
#   scripts/workspace.sh list
#   scripts/workspace.sh new voice-fix [BRANCH [REF]]   (also: --detach)
#   scripts/workspace.sh remove voice-fix
#
# Ported from workspace.ps1 (git history keeps the original).
set -euo pipefail

# shellcheck source=/dev/null
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/_workspace.sh"

usage() {
    cat <<'EOF'
Usage: scripts/workspace.sh check
       scripts/workspace.sh list
       scripts/workspace.sh new NAME [BRANCH [REF]] [--detach]
       scripts/workspace.sh remove NAME

  check   verify workspace boundaries, ignore rules and worktree safety
  list    show registered worktrees
  new     create a worktree .worktrees/NAME, on branch BRANCH (created at
          REF, default HEAD) or detached with --detach
  remove  remove a clean worktree after preserving evidence and a recovery ref
EOF
}

action="${1:-check}"
case "$action" in
    check)
        test_sierra_workspace
        ;;
    list)
        printf 'PATH\tBRANCH\tCOMMIT\n'
        sierra_worktrees
        ;;
    new)
        shift
        detach=0
        positional=()
        while [[ $# -gt 0 ]]; do
            case "$1" in
                --detach) detach=1 ;;
                --) shift; while [[ $# -gt 0 ]]; do positional+=("$1"); shift; done ;;
                -h|--help) usage; exit 0 ;;
                *) positional+=("$1") ;;
            esac
            shift
        done
        name="${positional[0]:-}"
        branch="${positional[1]:-}"
        ref="${positional[2]:-HEAD}"
        if [[ -z "$name" ]]; then
            usage >&2
            sierra_die 'A worktree NAME is required.'
        fi
        test_sierra_workspace
        new_sierra_worktree "$name" "$ref" "$branch" "$detach"
        ;;
    remove)
        shift
        name="${1:-}"
        if [[ -z "$name" || ! "$name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]]; then
            usage >&2
            sierra_die 'Supply a worktree NAME containing only letters, numbers, hyphens or underscores.'
        fi
        remove_sierra_worktree "$(sierra_worktree_directory)/$name"
        ;;
    -h|--help)
        usage
        ;;
    *)
        usage >&2
        sierra_die "Unknown action: $action (use check, list, new or remove)"
        ;;
esac
