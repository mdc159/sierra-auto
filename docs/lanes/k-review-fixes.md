LANE K: fix the reviewer's open findings in Talk, voice and config. Branch `review-fixes`.
Second wave, started 7:20 PM PST. All ten first-wave lanes are merged; work from current main. An integrator on the owner's PC builds and merges ready PRs automatically, so post your closing comment when you are done and it will be picked up. The owner is away: do not ask the owner questions; decide, record the decision in the PR, and continue. Commit with the git identity already configured in your environment, and do not put any email address on a command line.

You own for this wave: car/TalkScreen.kt, car/HistoryScreen.kt, the existing files directly under voice/ (not voice/duplex/, voice/realtime/, voice/tts/, voice/stt/, voice/convo/), agent/AgentClient.kt, agent/ConfigStore.kt, and their tests. Lane L owns obd/ and car/Truck*.kt; lane M owns new files under agent/profiles/; lane N owns README.md and docs/. Do not edit those.

Fix these GitHub issues, one commit per issue, each with a test that fails before the fix, and write "Fixes #N" in the commit message:
- #28 and #11: the Talk screen crashes the car app when the encrypted settings store cannot be opened, and the first failure silently wipes settings. The car must show a clear message instead, and nothing may be wiped without the owner choosing it on the phone.
- #10: a car microphone that the host refuses looks like a user cancel, so Talk silently disappears. Tell the driver what happened.
- #13: the phone speech-recognizer path never checks the microphone permission.
- #14: audio focus is dropped mid-sentence on Repeat and never released when speaking fails.
- #16: the end-of-speech detector's threshold cap treats road noise as speech. Make the threshold adapt to the measured noise floor, with tests on synthetic noise.
- #22: long replies are cut at 800 characters mid-word with no cue. Cut at a sentence boundary and say that there is more.
- #29: the app shows and speaks the word "null" when the agent returns no content.
Then read the first-wave PRs' closing comments (#24, #27, #33, #37) for anything they listed as a known bug in the files you own, and fix what is small.
