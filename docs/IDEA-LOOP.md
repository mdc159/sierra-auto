# Idea loop (M5)

Pull over, speak a feature idea, and get a new APK. The flow:

```
GitHub issue labeled `idea`  →  .github/workflows/claude.yml (Claude Code agent)
  →  branch claude/issue-N-…  +  pull request  →  build.yml builds and tests the PR
  →  you merge on your phone  →  build.yml publishes a signed APK release from main
```

The agent reads `docs/PLAN.md` and `docs/android-auto-constraints.md` first. It also runs the feature idea checklist. If an idea can't work in Android Auto, it explains why in the issue instead of writing code. It never pushes to `main`. It cannot edit `.github/workflows/` (the Claude GitHub App has no workflow permission).

## Use it today (GitHub mobile app)

1. Open **GitHub** → `mdc159/sierra-auto` → **Issues** → **+** (new issue).
2. Title: a few words. Body: tap the keyboard's **mic** and dictate the idea.
3. **Labels** → `idea` → **Create**.
4. Within a minute Claude comments on the issue and updates that one comment as it works. When it finishes, the comment links the PR.
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

Until step 3 is done, `claude.yml` logs **"Idea loop skipped"** as a warning and exits green. The workflow deliberately has no `ANTHROPIC_API_KEY` (metered API) path. Runs use your Claude subscription's usage limits. Actions minutes are free on a public repo.

Only accounts with **write access** to the repo can trigger the agent; the action enforces this. Leave `allowed_non_write_users` unset, because the repo is public.

## Later: an "Idea" button in the app

The Talk screen already records with `CarAudioRecord` and transcribes. An Idea action can reuse that:

1. **Idea** on the action strip → record → transcribe. This is voice only, so it is allowed while driving.
2. Show a `MessageTemplate` with the transcript (2 lines max) and two buttons: **File idea** / **Cancel**. That's a single refresh-friendly step, which keeps the flow well under the 5-template limit.
3. **File idea** → `POST https://api.github.com/repos/mdc159/sierra-auto/issues` with
   `{"title": "<first words>", "body": "<transcript>", "labels": ["idea"]}`
   authenticated with a **fine-grained PAT** limited to this repo with *Issues: read and write*. Store the PAT in the phone Settings screen (like the agent API key), never in the repo.
4. Speak a short TTS confirmation ("Idea filed"). The issue is created as you, so you have write access, and the `idea` label starts the loop exactly as above. Confirm on the first real run that an issue created with a label fires the `labeled` trigger. GitHub normally sends `opened` followed by `labeled`.
