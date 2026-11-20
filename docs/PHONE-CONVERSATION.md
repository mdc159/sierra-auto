# Phone conversation and connectivity

## As is — 2026-11-09

The launcher is `phone.PhoneActivity`. It uses the existing agent settings and
Studio54 enrollment, the phone microphone, transcription and selected speech output.
The default Hermes route is Victoria's Sierra bridge. Typed and voice turns share
screen-local context and a stable `X-Sierra-Conversation` ID. Car Talk also supplies
its own stable ID. The bridge's legacy reply-text matching remains for older clients;
this change does not remove that server-side fallback.

Signed version **100009** is installed on the A54. Its release build and 106 focused
voice/phone/vault tests pass; the full-suite/debug-lint baseline is 100008 in
[STATUS](STATUS.md). The afternoon recheck confirmed working server-only OAuth credentials and
phone TCP access to Victoria 8443. Sierra's connection check and two typed turns
now pass on the actual A54, including follow-up context. On 100007 the user's
spoken question was transcribed and Victoria's reply appeared in the transcript.
The phone-specific detector accepts the A54's lower speech levels and retains an
unconfirmed first utterance; it still filters silent follow-ups and digital silence.
The configured Jana/Eleven v3 phone sample completed in 5.5 seconds on 100009 after
fixing a PCM chunk-boundary playback stall. Human voice comparison and truck routing
require separate checks. The actual phone UI, typed-input
Stop and microphone open/Stop/silence paths were checked; see [STATUS](STATUS.md).

Talk runs the capture → transcription → agent → speech controller with follow-up
listening. This is turn-taking conversation; the separate full-duplex settings flow
is still available. Stopping, leaving the screen or destroying the activity cancels
the phone conversation. Chat cancellation closes its HTTP call. Conversation context
does not persist across activity/process recreation. Phone Home does not implement
the car's Places or OBD screens or its local tool registry.

## Victoria's voice

Victoria's Hermes configuration selects ElevenLabs **Jana**, voice ID
`jXJ93ZaMvKJrPorQEQlr`, with model **`eleven_v3`**. These were read from the
running agent's configuration and checked against the authenticated ElevenLabs
voice and PCM speech endpoints on November 9. The phone's Voice settings use
those same explicit provider/voice/model choices; changing them on Victoria
does not automatically synchronize these phone preferences.

Sierra now resolves `ELEVENLABS_API_KEY` from Studio54 at runtime for phone Talk,
typed replies, the car capture/transcription controller, microphone transcription
diagnostics and Test voice. A nonblank vault key takes precedence over a typed
fallback. The resolved key is not copied into the editable field or saved in the
voice-settings store. Studio54's existing encrypted secret cache and refresh loop
remain responsible for delivery. Changed resolved settings rebuild cached output
on the next lookup; an ElevenLabs 401 does not yet trigger an immediate refresh.

The phone-specific vault entry was repaired using Victoria's existing working
credential. The shared entry had been rejected as `api_key_id_used_as_api_key`;
it was left unchanged for infrastructure follow-up. No key was rotated or printed.
The separate full-duplex configuration is unchanged. Car system-recognizer and
Repeat responses still use Android speech; the ElevenLabs selection applies to
the car's capture/transcription controller. Truck acceptance remains pending.

## The private connection

On November 9 the A54 had general Internet access but Tailscale was not running.
Opening Tailscale restored private DNS and connectivity to Studio54. Always-on VPN
was enabled for Tailscale, with lockdown off. A separate tailnet policy omission
still blocked the phone from Victoria's TCP 8443 bridge. The same saved phone agent
credentials successfully reached Hermes from the PC; no backend restart was needed.
The saved Tailscale administrator API key returned HTTP 401 (`API token invalid`).
Those were the morning findings. The afternoon check found the network grant
already applied and the phone's TCP 8443 connection successful.

The earlier proposed narrow grant for these enrolled devices was:

```json
{"src":["100.92.69.24"],"dst":["100.112.150.24"],"ip":["tcp:8443"]}
```

Studio54 now maintains a covering grant instead: `autogroup:member` → `tag:mdc`,
`tcp:8443`, declared in its `tailnet-grants.json`. The deployed check confirms it
is present, so do not add a duplicate single-phone rule. These are private tailnet
routes; Victoria's unrelated HTTPS service on port 443 remains in place.

`bridge/deploy/ensure_phone_access.py` remains a tested standalone alternative
for an authorized automation environment. It is not the current server's policy
owner. Default is
a read-only preview. `--apply` validates first, requires the policy ETag, uses
If-Match, and checks the saved policy. Local evidence retains the original policy
and candidate; serialization preserves policy fields but removes HuJSON comments.

For current operations, use Studio54's maintained `tailnet.py --check` on its
server and its declared-grants deployment workflow. The phone's `sierra-a54`
launcher intentionally has no access to the new policy-management OAuth pair.
The rejected legacy shared API key was subsequently removed by the infrastructure
session. Never paste keys into chat, source,
command-line arguments or diagnostic reports.

## Renewable automation credentials

The November 9 morning follow-up read the authoritative `s54-keys` vault record using the
Studio54 controller. Its `TAILSCALE_API_KEY` is version 1, last changed September
27; its fingerprint matches the key delivered by the phone enrollment. Clearing
the inherited environment did not change the result: Tailscale returned HTTP 401
`API token invalid`. The shared project contains no Tailscale OAuth credential.
This establishes a rejected upstream credential, not an Infisical delivery failure;
the rejection does not distinguish expiration from revocation.

The afternoon recheck confirms a working OAuth pair in the **server-only
`s54-server`** vault project. Deployed Studio54 code exchanges it for an access
token in memory and reads the tailnet policy. Agent identities do not receive
this pair. Studio54's documented setup uses `policy_file` write access and its
required `devices:posture_attributes` and `devices:core:read` scopes. This credential can
edit the tailnet policy, so its authority covers more than this single phone grant;
the repair helper itself still adds only the requested device/port grant and
preserves other policy entries. Device enrollment (`auth_keys`) is not required.
See [Tailscale scope definitions](https://tailscale.com/docs/reference/trust-credentials)
and [OAuth setup](https://tailscale.com/docs/features/oauth-clients).

The server stores the pair under these names:

- `TAILSCALE_OAUTH_CLIENT_ID`
- `TAILSCALE_OAUTH_CLIENT_SECRET`

The repair helper now prefers this pair, obtains an access token in memory, renews
it before expiry, and retries a rejected access token once with a new token. It
does not log or write tokens, follow redirects, retry permission/conflict failures,
or fall back to an old static key after an OAuth failure. An incomplete OAuth pair
fails explicitly. A static API key is supported only when OAuth is not configured.
Fifteen standalone-helper tests pass, including renewal, rejected tokens, ETag
preservation and refusal to loop after client revocation. Its own live OAuth
path has not been exercised; **live OAuth issuance is now verified through
Studio54's separate deployed consumer**. Do not infer identical retry behavior
between the two implementations.

The initial administrator bootstrap is complete. Studio54 obtains a fresh access
token when its policy command runs; normal token expiry does not require manually
replacing the client credentials. Revoking the OAuth client removes that ability.
No Tailscale policy was changed during the afternoon recheck because the deployed
consumer already reported the declared grants present.

## Next steps and acceptance

Use the current result in [STATUS](STATUS.md). A passing TCP connection or PC request
is not a phone voice pass. Check connection, a typed reply and typed follow-up
context now pass on the connected phone, as does a spoken question reaching
Victoria and returning a displayed reply on 100007. On 100009 both the Jana sample
and a normal agent reply complete playback with consumed audio frames. Next obtain
the user's voice comparison and check spoken follow-up and Stop on this build.
Then repeat the parked
[truck test plan](TRUCK-TEST-PLAN.md), including input/output routing, echo and
interruption. Keep phone and truck results separate.
