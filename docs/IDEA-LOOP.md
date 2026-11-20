# Idea loop (M5)

As of **2026-11-20, `dev/phone-voice-checkpoint`**: issue filing and issue-to-PR automation exist; approval, CI and publication are separate. `claude.yml` now starts from `staging` and opens its PRs against `staging`, matching the [delivery workflow](DEVELOPMENT-WORKFLOW.md). A newly opened `idea-voice` issue no longer triggers the agent even if its model-written text contains `@claude`; voice ideas run only after the owner adds `idea`. `FileIdeaTool` still preserves model-supplied title/body. Source change only; not yet exercised by filing an issue. History: until this change (main `210448b`) the `opened` path could bypass the owner gate.

Intended human-reviewed flow (set up/use only while parked):

```
GitHub issue labeled `idea`  →  .github/workflows/claude.yml (Claude Code agent)
  →  branch claude/issue-N-…  +  pull request  →  build.yml builds and tests the PR
  →  owner reviews/merges into staging  →  manual play.yml uploads a Play-internal AAB
  →  phone/truck acceptance  →  staging promoted to main
```

The agent reads `docs/PLAN.md` and `docs/android-auto-constraints.md` first. It also runs the feature idea checklist. If an idea can't work in Android Auto, it explains why in the issue instead of writing code. It never pushes to `main` or `staging`. It cannot edit `.github/workflows/` (the Claude GitHub App has no workflow permission).

## Use it today (GitHub mobile app)

1. Open **GitHub** → `mdc159/sierra-auto` → **Issues** → **+** (new issue).
2. Title: a few words. Body: tap the keyboard's **mic** and dictate the idea.
3. **Labels** → `idea` → **Create**.
4. When correctly configured and admitted by the action, Claude comments on the issue and updates that comment as it works; there is no timing/completion guarantee. When it finishes, the comment links the PR.
5. Review the PR. To ask for changes, comment `@claude …` on it. When the `build` check is green, merge. The release with the new APK appears under **Releases**.

You can also write `@claude …` in any issue or PR comment, or in a new issue's title or body, to start the agent without the label.

## One-time setup (owner)

1. **Install the Claude GitHub App** on this repo: https://github.com/apps/claude → Configure → *Only select repositories* → `sierra-auto`. The action's default sign-in needs it. Commits and PRs made through the app also trigger `build.yml`. A plain `GITHUB_TOKEN` would not trigger it.
2. **Make a subscription token** on a computer where you're logged in to Claude (Pro or Max):
   ```
   claude setup-token
   ```
3. **Add it as a repo secret** named `CLAUDE_CODE_OAUTH_TOKEN`:
   ```
   gh secret set CLAUDE_CODE_OAUTH_TOKEN -R mdc159/sierra-auto
   ```
   Paste the token when prompted. Never put it in a file or a commit.
4. The `idea` label already exists in the repo.

Until step 3 is done, `claude.yml` logs **"Idea loop skipped"** as a warning and exits green. The workflow deliberately has no `ANTHROPIC_API_KEY` (metered API) path. Runs use your Claude subscription's usage limits. Check current account usage/Actions terms before running; this documentation makes no billing guarantee.

Only accounts with **write access** to the repo can trigger the agent; the action enforces this. Leave `allowed_non_write_users` unset, because the repo is public. The workflow also refuses to run on PRs from forks, because the agent runs Gradle from the checked-out branch.

## From the truck: say it to Talk

The Talk agent has a `file_idea` tool (`agent/tools/FileIdeaTool.kt`). Tap **Talk** and say something like "file an idea: remind me to turn off the heated seats when it warms up". The agent turns that into a title and body, the tool does `POST https://api.github.com/repos/mdc159/sierra-auto/issues` with `labels: ["idea-voice"]` (JSON built with a serializer, never by string interpolation), and the spoken reply confirms the issue number.

**It is labelled `idea-voice`, not `idea`, on purpose.** The model that calls the tool also reads text from OpenStreetMap through `find_places` (place names, addresses), so the intended design is that an issue it files must not start the cloud agent by itself. **Current mention-trigger behavior violates that design**. The intended owner action is to read the issue and add `idea`; repair the opened-issue mention bypass before treating this as mandatory review. (GitHub creates the `idea-voice` label on first use if it does not exist.)

Setup on the phone: **Sierra → Idea settings**. Paste a **fine-grained PAT** limited to this repo with *Issues: read and write* and tap **Test token** (it only reads the repo). The token is stored encrypted next to the agent key, the same way `ConfigStore` keeps the agent settings, and never in the repo. Without a token the tool answers that it is not set up and the agent says so.

## Next steps and evidence limits

Require a separate trusted review action or explicitly exclude `idea-voice` creation from mention triggers; test both ordinary labeled ideas and adversarial mentions with an isolated event fixture before any live automation. The owner's PAT makes voice-created issues a writer's action, so the action's actor check does not restore the missing human boundary. Preserve manual PR review; a green job may mean a skipped run when OAuth secret is absent. This docs session created no issue, sent no external message and triggered no workflow. See [STATUS.md](STATUS.md), [UPDATES.md](UPDATES.md) for Play distribution and acceptance limits.
