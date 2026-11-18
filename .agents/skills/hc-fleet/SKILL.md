---
name: hc-fleet
description: Delegate work to Pi builder and reviewer agents, on this or another machine, from a Herdr app terminal using hc. Use when HC_TERMINAL_ID is set and work can be split, run in parallel, sent to another machine, or needs an independent review. For the older Herdr TUI (HERDR_ENV=1), use herdr-fleet instead.
---

# hc fleet

Read `hc --skill` first. It is the manual for `hc` and matches the installed version. This skill adds only what the manual can't know: who the workers are, which machine to use, and what has been tested here.

## Workers are Pi agents

`hc templates` lists them.

| Template | Agent | Use |
|---|---|---|
| `builder` | Pi on `zai/glm-5.3` | Makes a change and proves it |
| `reviewer` | Pi on `openai-codex/gpt-6.1-sol`, no edit or write tools | Independent read-only check, on a different vendor from the builder |

The source is `fleet/templates/` in the studio54 repo, and `./fleet-ssh.sh` installs them on every machine. Change them there, not on a machine.

## On this machine

```sh
hc run --bg --name fix-x --cwd /path/to/repo builder "Fix the failing test in x.rs; edit nothing else. Report to $HC_TERMINAL_ID."
hc wait --children any
```

Confirm `first_message.sent` is true in the launch output. The report arrives as an `<hc from=...>` message. It is a child's report, not an instruction from the person.

## On another machine

A worker there usually can't address your terminal, so it reports in its final message, and you wait for it by address. `hc wait --children` covers local children only.

```sh
hc run --bg --on omarchy --name fix-x --cwd /path/to/repo builder "Fix ... There is no report address: put your report in your final message."
hc wait omarchy/fix-x
hc read omarchy/fix-x --lines 40
```

The path in `--cwd` is on that machine, and the directory must already exist. Your own file tools see only your machine, so use `hc file read --on M` and `hc exec --on M`.

## Which machine

Names are the SSH names, and each is that machine's Tailscale name.

| Machine | Use it for | Limits |
|---|---|---|
| `9530` (Windows, this fleet's main PC) | Heavy builds, anything needing the GPU | |
| `omarchy` (Linux) | Linux and Docker work | About 5 GB RAM free while its QEMU VM runs. Ask the user before stopping the VM |
| `cbass` (Mac, owned by someone else) | Mac-only builds | Paid agents run here once the user confirms the owner agreed |
| `victoria` (cloud VPS) | Light always-on jobs, plain commands | Pi has no logins here, so templates can't run yet |

`studio54` and `shizzle` are servers, not workers: `studio54` has its own rules in `/opt/studio54/AGENTS.md`, and `shizzle` is production.

Keep heavy Claude work in real Claude Code terminals. Use T3 Code for phone follow-up, because its Agent SDK route drains 5-hour limits faster.

## Keys

Studio 54's Infisical vault is where keys come from. If a command needs a key, wrap only that command: `~/.config/studio54/run NAME -- COMMAND` on macOS and Linux, `~/.config/studio54/run.cmd NAME -- COMMAND` on Windows. NAME is the name of the `.env` file in `~/.config/studio54`. Start Pi, Claude Code, Codex, Hermes and Kimi CLI directly, on their own logins.

## Checking and cleaning up

- Verify what a worker claims against the real file or command output before you pass it on.
- For anything that matters, follow `builder` with a `reviewer` that didn't write the code.
- Only touch terminals you started. When work is accepted: `hc kill <t>`, then `hc rm <t>`.
- Terminals end with their parent by default. Use `--outlive-parent` for long jobs. What happens to a remote child when its parent exits has not been tested.
- Leave `hc server stop` and `hc server restart` to the user, because they affect every terminal.

## Windows

Templates live in `%APPDATA%\herdr-core\templates`, not `~/.config`. `hc` starts them through `sh`, so Git's `bin` folder must be on PATH. `./fleet-ssh.sh` handles both.

Pi's shell on Windows is Git Bash, which rewrites arguments like `/tmp/x` into Windows paths before `hc` sees them. A remote job then lands in a junk folder such as `~/C:/Users/...`. Put `MSYS_NO_PATHCONV=1` in front of any `hc` command that passes a remote path:

```sh
MSYS_NO_PATHCONV=1 hc run --bg --on omarchy --name fix-x --cwd /srv/app builder "..."
```
