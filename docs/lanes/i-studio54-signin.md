LANE I: Studio 54 sign-in. Branch `studio54-signin`.
The owner runs a private server ("Studio 54") on a Tailscale network: an Infisical vault that holds every key, and a LiteLLM gateway that fronts the model providers. Each device is enrolled as a named agent with its own machine identity. On computers, a launcher signs in to the vault and hands programs their keys at run time, so nobody types or copies a key. The phone app should work the same way: sign in once, then pull what it needs.

How the computer launcher does it (the phone must match this):
- It holds four values: INFISICAL_API_URL, INFISICAL_CLIENT_ID, INFISICAL_CLIENT_SECRET, INFISICAL_PROJECT_ID, and optionally S54_KEYS_PROJECT_ID.
- It logs in with Infisical universal auth (client id and client secret) to get a short-lived access token.
- It reads every secret in environment `prod`, path `/` of the agent's own project. Those include OPENAI_BASE_URL and OPENAI_API_KEY (the gateway address and this agent's budgeted gateway key) and LANGFUSE_* values.
- If the shared keys project id is present, it reads that project too (service keys such as ELEVENLABS_API_KEY). The agent's own project wins when a name appears in both.

You own: a new package studio54/, a new Studio54Activity, tests, and docs/STUDIO54.md. In phase 1 do not edit existing files under agent/, voice/ or car/.
Shared-file edits allowed: AndroidManifest.xml (your Activity and its intent filter), MainActivity.kt (one button row to open Studio54Activity).

Phase 1:
1. An Infisical REST client. Read Infisical's current API documentation for the universal-auth login call and for listing a project's secrets with their values; do not guess paths or field names. Handle token expiry and renewal, timeouts, and a vault that is unreachable (the phone may be off the private network).
2. A SecretSource interface (`get(name)`, `names()`, `lastFetched`) backed by the vault, with an encrypted on-device cache so the app still works for a while when the vault is unreachable. Refresh at app start, on a timer, and when the gateway answers 401. Never log or display a secret value; the UI shows names and the fetch time only.
3. Studio54Activity: sign in by manual entry, show status (agent project, which secret names arrived, when), "Refresh now", and "Sign out" which wipes the sign-in and the cache.
4. Import by intent, so the coordinator can push the sign-in from the PC over USB without the values appearing on any screen: an explicit action such as `com.mdc159.sierra.action.IMPORT_STUDIO54` with string extras for the values above. The Activity must always show a confirmation screen naming the vault host before saving, and must never accept an import silently. Document the exact `adb shell am start` command with placeholders in docs/STUDIO54.md.
5. The sign-in values are stored with the same encrypted storage ConfigStore uses.
6. The vault address is plain http on a private `*.ts.net` name. Lane B is adding the network security rule that permits this; do not create or edit that file. Note the dependency in your PR.
7. Unit tests against a fake Infisical server: login, listing, precedence between the two projects, token renewal, unreachable vault with a warm cache, and sign-out.
8. docs/STUDIO54.md: what the phone holds, what it can read, how to revoke a lost phone (the owner removes the agent on the server), and the recommendation to grant the phone the shared keys project only if it truly needs those keys.

Phase 2, only after the `talk-tools` PR is merged into main: merge main and add the smallest add-only hook so that, when signed in, the agent base URL and key come from SecretSource (the owner still chooses the model). Expose SecretSource so the voice lanes can read ELEVENLABS_API_KEY from it. If talk-tools is not merged by 10:15 PM PST, describe the exact hook in your PR and stop.
