---
name: isdm-safe-cleanup
description: Audit and recoverably clean generated artifacts in the ISDM Companion repository. Use for repository cleanup, disk-space reduction, ignored build-output review, pre-release hygiene, or requests to remove unnecessary files while preserving active and uncommitted work.
---

# ISDM Safe Cleanup

Separate reproducible artifacts from active work, move only exact disposable paths to Trash, and verify repository integrity afterward.

## Workflow

1. Run `scripts/audit-generated-artifacts.sh`. Inspect Git status, worktrees, ignored/untracked previews, sizes, and the exact known build directories.
2. Classify each candidate:
   - **Generated and reproducible**: Gradle, Kotlin, Android build, dashboard build, or cache output.
   - **Active or ambiguous**: source, tests, migrations, documents, signing files, environment files, evidence, or any untracked work whose origin is unknown.
3. Show the exact generated paths selected for cleanup. Keep active and ambiguous files.
4. Use `gio trash -- <exact-path>...` so cleanup remains recoverable. Avoid globs and broad directory targets.
5. Re-run the audit, then run `git diff --check` and `git fsck --full --no-progress`.
6. Report what moved to Trash, whether it is recoverable, space reclaimed, and the unchanged active-work state. Finish only when every removed path was preclassified and Git integrity passes.

Do not rebuild merely to prove deletion. A later build may recreate the same generated directories.

## Script

Resolve `scripts/audit-generated-artifacts.sh` relative to this skill directory. It is read-only and never deletes or trashes files.
