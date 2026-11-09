# Lane K — historical second-wave review-fixes scope

Historical branch/scope (2026-11-04): `review-fixes`; Talk/History, legacy voice, AgentClient/ConfigStore and regressions for issues #11/#28 (store), #10 (refused mic), #13 (permission fallback), #14 (focus), #16 (detector), #22 (truncation), #29 (null reply). Historical commit/issue-closing/session instructions are retired.

## As-is — 2026-11-08, main `210448b`

Completion is mixed. AgentClient null-content decoding (#29) is fixed and the detector no longer uses the old permanent RMS cap (#16). Diagnostics/refused/silent capture have improved. But several stores still delete data on open failure and Talk loads synchronously outside error handling; permission fallback and legacy focus remain applicable. Current `Conversation.forSpeech` still takes an 800-character window, not a universal sentence-boundary/continuation solution.

## Next steps

Use [STATUS.md](../STATUS.md) rather than assuming the assignment fixed every issue. Preserve credential data, report retryable setup errors, handle permission denial and stale utterance callbacks, and test long speech meaning/continuation plus lifecycle cancellation. None of those application fixes is claimed by this docs consolidation.
