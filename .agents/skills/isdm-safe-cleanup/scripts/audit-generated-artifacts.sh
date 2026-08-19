#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  printf '%s\n' 'Usage: audit-generated-artifacts.sh'
  printf '%s\n' 'Read-only Git, ignored-output, size, and integrity audit.'
  exit 0
fi
if (($#)); then
  printf 'Unknown argument: %s\n' "$1" >&2
  exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
cd "$repo_root"

printf 'Repository: %s\n\n' "$repo_root"
git status --short --branch

printf '\nWorktrees\n'
git worktree list --porcelain

printf '\nUntracked preview\n'
git clean -nd

printf '\nIgnored preview\n'
git clean -ndX

printf '\nKnown generated paths\n'
known_paths=(
  "android/.gradle"
  "android/.kotlin"
  "android/app/build"
  "android/build"
  "beta-dashboard/.next"
  "beta-dashboard/dist"
  "beta-dashboard/.wrangler"
  "beta-dashboard/node_modules"
  "node_modules"
)

for path in "${known_paths[@]}"; do
  if [[ -e "$path" ]]; then
    du -sh -- "$path"
  fi
done

printf '\nIntegrity checks\n'
git diff --check
git fsck --connectivity-only --no-progress

printf '\nRead-only audit complete. No files were removed.\n'
