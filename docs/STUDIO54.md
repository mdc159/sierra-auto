# Studio 54 sign-in

**Added:** 2026-11-04 (lane I)
**Code:** `app/src/main/java/com/mdc159/sierra/studio54/`, `Studio54Activity`
**Server side:** the owner's private "Studio 54" server on a Tailscale network: an Infisical vault that holds every key, and a LiteLLM gateway in front of the model providers. Each device is a named Infisical machine identity ("agent") with its own project.

The phone works like the computer launcher: it signs in to the vault once with its machine identity, then pulls the keys it needs at run time. Nobody types or copies a model key onto the phone.

## What the phone holds

Stored on the phone, encrypted with the same scheme `ConfigStore` uses for the agent key (EncryptedSharedPreferences, AES-256-GCM master key in the Android Keystore, file `sierra_studio54`):

| Value | Why |
| --- | --- |
| `INFISICAL_API_URL` | Vault address, plain http on a private `*.ts.net` name |
| `INFISICAL_CLIENT_ID` | Universal-auth client id of this phone's agent |
| `INFISICAL_CLIENT_SECRET` | Universal-auth client secret of this phone's agent |
| `INFISICAL_PROJECT_ID` | The agent's own project |
| `S54_KEYS_PROJECT_ID` | Optional. The shared keys project |
| secret cache | The last successful read: names, values and the fetch time |

The access token the vault issues is kept in memory only; each process start logs in again.

The screen never shows a secret value. It shows the vault host, the project ids, the secret **names** that arrived and **when**. Nothing is logged either: `Studio54Credentials` and `SecretCache` have redacting `toString()`s, and vault error messages carry the server's `message` field only.

## What the phone can read

Exactly what the launcher reads:

1. Every secret in environment `prod`, path `/`, of the agent's own project. This is where `OPENAI_BASE_URL`, `OPENAI_API_KEY` (the gateway address and this agent's budgeted gateway key) and `LANGFUSE_*` live.
2. If `S54_KEYS_PROJECT_ID` is set, every secret in `prod` `/` of that project too (service keys such as `ELEVENLABS_API_KEY`).

The agent's own project wins when a name appears in both. Within a project, a secret the project defines wins over one it imports.

Infisical endpoints used (verified against Infisical's server source and official SDKs, not guessed):

| Call | Endpoint |
| --- | --- |
| Login | `POST /api/v1/auth/universal-auth/login` with `{clientId, clientSecret}`; returns `accessToken`, `expiresIn` (s), `accessTokenMaxTTL` (s) |
| Renew | `POST /api/v1/auth/token/renew` with `{accessToken}`; same reply shape. Used when under a minute of life is left; a refused renewal or a reached max TTL means a fresh login |
| List | `GET /api/v3/secrets/raw?workspaceId=…&environment=prod&secretPath=/&viewSecretValue=true&expandSecretReferences=true&include_imports=true` |

The list endpoint is the one the official SDKs call today. The server marks it deprecated in favour of `GET /api/v4/secrets?projectId=…` (same reply shape). `InfisicalClient.LIST_PATH` is the single place to change if the vault is ever upgraded past v3.

### Refresh and the cache

- **At start.** `Studio54.start(context)` reads the vault once and starts a 30-minute timer. It is called when Android Auto creates the car session (`SierraSession.onCreateScreen`) and when the Studio 54 screen opens.
- **On a timer.** Every 30 minutes while the process lives.
- **On 401 from the gateway.** When a Talk exchange fails with "error 401" while the vault supplies the key, `Studio54.onAgentError` reads the vault again (at most once a minute) because the agent's gateway key may have been rotated.
- **Refresh now.** The button on the screen.

When the vault is unreachable (the phone is off the Tailscale network) the last good read is served from the encrypted cache for up to **3 days** (`VaultSecretSource.DEFAULT_CACHE_MAX_AGE_MILLIS`). After that the names disappear and the Talk screen falls back to whatever is typed in the phone settings. The status screen says "serving cached copy" while this is happening.

### Using it from code

```kotlin
val secrets: SecretSource = Studio54.source(context)   // process-wide
secrets.get(SecretNames.OPENAI_API_KEY)                // null when not signed in or cache expired
secrets.get(SecretNames.ELEVENLABS_API_KEY)            // for the voice lanes
secrets.names()                                        // for a status screen; never the values
secrets.lastFetched                                    // epoch millis, or null
```

### How Talk uses it (phase 2)

`TalkScreen` builds its agent settings with `Studio54.agentConfig(carContext, ConfigStore.load(carContext))`. When the vault has both `OPENAI_BASE_URL` and `OPENAI_API_KEY`, they replace the base URL and key typed in the phone settings (`AgentOverlay.apply`). Everything else stays as typed: the **owner still chooses the model**, and the transcription settings are untouched (a blank transcription key falls back to the vault key, as it always fell back to the agent key). When the vault has nothing, or only one of the two, the typed settings are used unchanged.

The "Test connection" button on the main phone screen tests the **typed** settings only. Use **Refresh now** on the Studio 54 screen to prove the vault, and a Talk exchange on the car screen to prove the gateway key.

## Signing in

### By hand

Phone settings → **Studio 54 sign-in** → fill the five fields → **Sign in**. Client id and secret fields are password fields and are cleared after saving.

### From the PC over USB (nothing typed, nothing shown)

The Activity accepts an explicit intent. Replace the placeholders; the values travel over the USB debug link, not the screen. The phone then shows a confirmation that names the vault host, the agent project and the shared keys project. **Nothing is saved until "Save and sign in" is tapped.** An import is never accepted silently.

```sh
adb shell am start \
  -n com.mdc159.sierra/.studio54.Studio54Activity \
  -a com.mdc159.sierra.action.IMPORT_STUDIO54 \
  --es INFISICAL_API_URL "http://<vault-host>.ts.net:<port>" \
  --es INFISICAL_CLIENT_ID "<client-id>" \
  --es INFISICAL_CLIENT_SECRET "<client-secret>" \
  --es INFISICAL_PROJECT_ID "<agent-project-id>" \
  --es S54_KEYS_PROJECT_ID "<shared-keys-project-id>"
```

Leave the last line off if the phone has no shared keys project. On Windows `cmd.exe`, drop the backslashes and put everything on one line.

Hygiene for the PC side: the command line, and therefore the client secret, lands in the PC shell history. Run it from the launcher (which already holds the values) or clear the history line afterwards. The Activity drops the extras from its activity record as soon as it has read them.

The Activity is exported (it must be, for `adb shell am start`) but guarded with `android:permission="android.permission.DUMP"`. That permission is `signature|privileged|development`: the adb shell holds it, ordinary apps cannot obtain it, and Sierra's own settings screen starts the Activity with an explicit intent, which same-app checks always allow. So another app on the phone cannot push a sign-in pointing at its own vault. The confirmation screen is the second line of defence: if the host is not the owner's `*.ts.net` name, tap **Cancel**.

If a Samsung build ever refuses the documented `am start` with a permission denial, the fallback is `android:exported="false"` on a debuggable build (then `adb shell am start --user 0 …` still works for debug APKs) and manual entry on release builds.

### Signing out

**Sign out** on the screen asks once, then wipes the sign-in and the cache from the phone (a synchronous `clear().commit()`). Signing out does not revoke anything on the server.

## Revoking a lost phone

The phone holds a client secret, so losing the phone means that secret is loose. On the server, **remove the phone's agent** (its machine identity) in Infisical, or at least revoke its universal-auth client secret. From that moment every login and renewal from the phone fails with 401, and the phone's cached copy expires on its own within 3 days. Also rotate the phone's gateway key on LiteLLM if the cache might still be live.

Then, on the next phone: create a new agent, enroll it, push the sign-in with the command above.

## Recommendation on the shared keys project

Grant the phone `S54_KEYS_PROJECT_ID` **only if it truly needs those keys**. Today the phone's own project already gives it the gateway address and its budgeted gateway key, which is everything Talk needs. The shared project holds service keys (`ELEVENLABS_API_KEY` and others) that are not budgeted per device; a lost phone with the shared project would expose all of them, not just one capped gateway key. Add the shared project when a voice lane actually reads `ELEVENLABS_API_KEY` from `SecretSource`, and consider a dedicated, smaller project for the phone instead of the full shared one.

## Dependencies

- **Plain http to `*.ts.net`.** Android blocks cleartext by default. Lane B (`talk-tools`, merged) added `res/xml/network_security_config.xml`, which permits plain http for `*.ts.net`, `localhost`, `127.0.0.1` and `10.0.2.2` and is registered in the manifest. If that rule were ever removed, a refresh would fail with "Plain http to <host> is blocked by this build's network security rules" and the status screen would show it; the sign-in itself still saves.
- **Voice lanes.** Read `ELEVENLABS_API_KEY` with `Studio54.source(context).get(SecretNames.ELEVENLABS_API_KEY)`. It is null until the phone is granted the shared keys project (see the recommendation above).
