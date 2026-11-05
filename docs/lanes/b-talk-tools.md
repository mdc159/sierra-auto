LANE B: Talk tools. Branch `talk-tools`. Give the Talk agent tools so that speaking does things.
You own: agent/ (all files), car/TalkScreen.kt, new files under agent/tools/, a new IdeaSettingsActivity, app/src/main/res/xml/network_security_config.xml, and tests for all of it. You may read but not edit places/ and car/Place*.kt. Do not touch car/HomeScreen.kt, obd/, voice/, or .github/.
Shared-file edits allowed: AndroidManifest.xml (register your Activity; add android:networkSecurityConfig), MainActivity.kt (one button row to open IdeaSettingsActivity).
Build:
1. Tool calling in AgentClient using the OpenAI-compatible `tools` / `tool_calls` format. Loop at most 4 rounds, each with a timeout. If the endpoint rejects `tools`, fall back to plain chat. Unit-test the round trip against a fake server.
2. Tools:
   - find_places(category or free-text query): uses the existing places clients and the phone's location; returns up to 6 results with name, distance, lat, lng.
   - navigate_to(name, lat, lng): starts navigation with the same intent the Places detail screen uses.
   - file_idea(title, body): creates a GitHub issue on mdc159/sierra-auto with label `idea`, using a fine-grained token the owner enters in IdeaSettingsActivity (stored encrypted, the same way ConfigStore stores the agent key). The spoken reply confirms the issue number. This is the "speak a feature idea" loop described in docs/IDEA-LOOP.md.
   Keep a small Tool interface so a later lane can add read_vehicle (OBD). Do not implement read_vehicle.
3. System prompt: driving assistant, one to three short sentences, use tools when they fit, never read URLs aloud.
4. Network security config: allow plain http only for *.ts.net, localhost, 127.0.0.1 and 10.0.2.2. Everything else stays https-only.
5. TalkScreen shows a short status while a tool runs ("Finding fuel nearby...") without exceeding the template step limit described in the constraints doc.
