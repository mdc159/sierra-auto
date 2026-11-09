# Lane I — historical Studio54-sign-in scope

Historical branch/scope (2026-11-04): `studio54-signin`; Infisical universal auth, token renewal, per-device/shared-project precedence, encrypted cache, refresh/401 hooks, confirmed explicit-intent import, sign-out/revocation, UI and fake-vault tests. The phase/session gates are retired.

## As-is — 2026-11-08, main `210448b`

Vault client/cache and phone enrollment UI are implemented. Complete gateway and bridge URL/key pairs overlay typed settings independently; main Test connection now tests the effective route. Realtime overlay uses the gateway model default; chat selection does not redirect speech keys. Cached secrets last up to three days. Studio54Store still deletes preferences on any open failure; vault refresh identity races remain a specialist test gap.

## Next steps

Preserve stores on transient failures and test sign-out/identity change racing refresh. Test offline/expired-cache, key rotation and typed fallback deliberately. Revoke identity plus cached downstream gateway/bridge keys for a lost phone; sign-out alone is not server revocation. Keep shared-project access minimal. [STUDIO54.md](../STUDIO54.md).
