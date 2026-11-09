# Lane D — historical off-PC-updates scope

Historical branch/scope (2026-11-04): `updates`; research install records, signer-checked GitHub updater, optional Shizuku path, Play-internal workflow and tests. An ADB/DHU appearance was background evidence only. This is not a current installation instruction.

## As-is — 2026-11-08, main `210448b`

Play internal testing is operational (reviewed upload 100005); signed GitHub APKs remain. Play builds hide self-update UI and remove installer permissions. Non-Play PackageInstaller/Shizuku paths and install-record heuristic exist but do not prove host acceptance. Inspected phone had installer Play and initiator shell, not demonstrated Play/Play provenance.

## Next steps

Confirm genuine Play update provenance and real-host launch. Treat `.worktrees/install-record` / draft #44 as an old, selectively reassessed experiment, not shipped main or a proven fix. Keep release-name length/version/signing checks; do not infer future policy acceptance or silent-install guarantees. [UPDATES.md](../UPDATES.md).
