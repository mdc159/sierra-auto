LANE A: independent reviewer. You change no code.
1. Review everything on main against docs/PLAN.md and docs/android-auto-constraints.md. Look hardest for:
   - car-host crashes: template constraint violations (action counts, list limits, rows without required fields, template stack depth, refresh rules);
   - lifecycle and threading: coroutines outliving a Screen, invalidate() after destroy, mic or TextToSpeech not released, audio focus not abandoned;
   - Talk: what happens on no network, timeout, empty transcript, the host ending the mic session, a very long reply;
   - security: key storage, anything secret-like in the public repo or CI logs, exported components, intent handling, the release signing workflow, the idea-loop workflow (.github/workflows/claude.yml);
   - OBD package: the safety allowlist really blocks every write or clear command.
2. File one GitHub issue per finding (label `review`; create the label if missing). The title starts with [P0], [P1] or [P2]. Body: file:line, a concrete failure scenario, and a suggested fix. No vague or style-only issues.
3. Then, until 11:00 PM PST, check `gh pr list` every few minutes. Review each open PR from the other sessions as it appears and whenever it gets new commits. Post specific review comments on the PR. Say clearly when a PR looks safe to merge.
4. End with one issue titled "Review summary" that ranks the top ten findings.
