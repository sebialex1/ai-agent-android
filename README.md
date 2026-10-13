# Agent for Android

A minimal, dark, local AI agent for Android. Give it a task in natural language; it searches and reads the web through **TinyFish**, runs real commands, package installs (`apt`, `pkg`, `pip`) and Python inside the Termux environment, and writes Markdown files. All the technical detail is kept in a collapsed audit trail above each answer.

Kotlin, Jetpack Compose (Material 3), Gradle Kotlin DSL, `compileSdk`/`targetSdk` 34, `minSdk` 26.

## API keys

Both keys go in **Settings** (overflow menu, top right). They are stored with `EncryptedSharedPreferences`, using an AES-256-GCM master key held in the **Android Keystore**. Keys never show up in the chat, the logs, or backups (`allowBackup=false`, and the data extraction rules exclude them).

| Key | Used for | Endpoint |
| --- | --- | --- |
| TinyFish | Web search and page retrieval | `GET https://api.search.tinyfish.ai?query=…`, `POST https://api.fetch.tinyfish.ai` (`X-API-Key` header) |
| OpenRouter | Model inference for the agent loop (tool calling) | `POST https://openrouter.ai/api/v1/chat/completions` (`Authorization: Bearer …`) |

The model is set in Settings and defaults to `openrouter/auto`; it is not hard-coded anywhere.

Nothing is canned or simulated. Every answer comes from OpenRouter's chat completions endpoint with tool calling, and every tool call is really executed: the exact exchange (assistant `tool_calls`, then one `tool` message per call with the real output) is replayed to the model on each step. Without an OpenRouter key the agent does not run at all; the turn ends with a short notice and nothing is executed. Without a TinyFish key, web access still happens for real, using a direct HTTPS GET for pages and DuckDuckGo's HTML results for search. Failures show up as failed steps and are reported back to the model.

The shell implements a jailed coreutils subset (`ls`, `cat`, `grep`, `find`, `mv`, `rm`, …) directly on the workspace. Everything else runs for real in the Termux environment described below, with a 60 s timeout (300 s for `apt`/`pkg`/`pip` installs) and an output cap. A program that is not installed reports `not found` with its real exit code.

## Sandbox: real packages and Python through Termux

The agent can run `apt install -y <pkg>`, `pkg install -y <pkg>`, `pip install <pkg>` and real Python (real imports, file I/O, `subprocess`). For example, it can `pip install yt-dlp` and then download a video into the conversation's workspace.

### Why Termux integration and not a bundled rootfs

Two approaches were considered:

- **(a) Termux app integration (chosen).** The app sends Termux's documented [`RUN_COMMAND` intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) to `com.termux/com.termux.app.RunCommandService`. Termux runs the command in the background with its own bash, APT/DPKG, `pkg`, Python and pip, and returns stdout, stderr and the exit code through a one-shot `PendingIntent`.
- **(b) A Termux bootstrap in the app's own private storage (rejected).** Every Termux package is compiled for the fixed prefix `/data/data/com.termux/files/usr`. Under another package name, the bootstrap's `apt`, `dpkg` and every package installed later would point at paths that don't exist. Making it work means rebuilding the whole package repository for this app's prefix, or shipping `proot` binaries in the APK. On top of that, apps that target SDK 29+ can't `exec()` files they download into their data directory (Android's W^X rule), and this app targets SDK 34.

Option (a) is the only one where `apt install` and `pip install` keep working against the official Termux repositories, so that is what the app uses. Nothing is bundled and the APK size doesn't change.

### One-time setup (Settings > Sandbox shows each item)

1. Install **Termux** from [F-Droid](https://f-droid.org/packages/com.termux/) or [GitHub releases](https://github.com/termux/termux-app/releases). The Play Store build is outdated. Open it once so it installs its own bootstrap.
2. In Termux, run (Settings has a copy button for this):
   ```
   mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties && termux-setup-storage
   ```
   Then grant Termux storage access when Android asks.
3. In Settings > Sandbox, tap **Allow** for *Run commands in Termux* (the `com.termux.permission.RUN_COMMAND` permission). If Android does not show a prompt, enable it under App info > Permissions > Additional permissions.
4. Tap **Allow** for *All files access*. Termux runs as a different Linux user and cannot see this app's private files. Shared storage is the only place both apps can read and write.
5. Tap **Check**. It runs a short command in Termux and prints the paths of `bash`, `apt`, `dpkg`, `pkg`, `python` and `pip` (`missing` for anything not installed yet). Python is installed on first use with `pkg install -y python`, which the agent can do itself.

If the device kills Termux in the background, exempt Termux from battery optimisation.

### How a command runs

```
files/conversations/<id>/workspace/          private workspace (source of truth, read by the app)
/storage/emulated/0/AgentSandbox/<id>/workspace/   shared mirror that Termux works in
```

Before each external command, changed files are copied from the private workspace to the shared mirror. The command then runs in Termux with the mirror as working directory, and `/workspace` paths in the command are rewritten to the mirror path. Afterwards, anything Termux created, changed or deleted is copied back. A small manifest (`mirror.manifest`, holding size and mtime per file) keeps the copies incremental and carries deletions in both directions. That's why the Workspace screen, the file viewer and the file pills show what `pip`, `python` or `yt-dlp` produced. Each conversation has its own mirror folder, and deleting a conversation deletes it too. Installed packages live in Termux's own `$PREFIX` and are shared by all conversations, like on any Termux install.

Note that files in shared storage can be read by other apps that have storage access. Don't keep secrets in the workspace.

### Failures are real failed steps

Nothing falls back to a canned message. Each of these shows up as a failed step (warning icon, exit code, raw output in the audit sheet) and is reported to the model:

| Situation | What you see |
| --- | --- |
| Termux not installed | `python: cannot run, the Termux environment is not available. Termux is not installed…` (exit 127) |
| RUN_COMMAND permission not granted | `permission denied: this app is not allowed to run commands in Termux…` |
| All files access off | `permission denied: shared storage access is off…` |
| `allow-external-apps` not set, Termux service killed | `termux error <code>: <Termux's message>` |
| Termux does not answer | `termux: Termux did not return a result within …s…` |
| No network, missing package, pip build error | The real `apt`/`pip` output and non-zero exit code |
| Over the time limit | `[timed out after 60s]` (exit 124) |

`apt`, `apt-get`, `dpkg`, `pkg`, `pip` and `python` never run on the device's `/system/bin/sh` instead. Without Termux they fail with the reason. Other external commands still use `/system/bin/sh` when Termux isn't set up.

### The python tool

Besides `shell`, the model has a `python` tool: `{code, packages?, filename?}`. The code is saved to the workspace (as `filename`, or under `/workspace/.scripts/`), any `packages` are installed with `python -m pip install`, and the script runs with Termux's Python from the current directory. In the steps row it looks like any command: terminal icon + `python script.py`. The audit sheet holds the full code and output, and has a link to open the script. If the pip install fails, the step fails with pip's output and the script does not run.

## Interaction model

Every assistant turn renders in this fixed order:

1. Execution row: `8 steps >`. Tap it to expand compact one-line actions (search icon + query, favicon + hostname, terminal icon + command, file icon + filename, shield icon for approvals). Long rows fade at the right edge. Tapping a row opens its raw audit sheet: input, output, URL/path, exit code, and timing.
2. The answer, rendered as Markdown.
3. File pills: document icon, filename, and "Updated now" or a `+n −m` diff count.
4. Source cluster: up to three favicon + domain chips, then `+N` for the rest. Each chip opens the exact page.

When you send a new message, all earlier step groups collapse. Answers, files, and sources stay visible. Only pages the agent actually fetched become website sources.

A newly received answer types out with a fast typewriter that speeds up as the answer grows. Each answer is revealed once. Reopening a conversation, restarting the app, or coming back from another screen shows the saved text immediately.

## Conversations

The X at the top left opens the conversations menu: settings, search, and new conversation across the top, recently updated conversations below, and an input at the bottom that starts a new conversation. Tap a row to reopen it, or use its options to delete it.

Conversations are fully independent. Each one has its own messages, model context, action history, and workspace:

```
files/conversations/<id>/session.json   messages, steps, files, sources
files/conversations/<id>/audit.jsonl    raw audit trail
files/conversations/<id>/workspace/     this conversation's /workspace
files/conversations/<id>/snapshots/     previous file versions for diffs
```

A run keeps going if you switch to another conversation, and it only ever writes into its own conversation.

Risky actions pause the loop and show an inline Allow/Deny card. This covers destructive commands, package installs (`apt`, `pkg`, `pip`, and `python` calls with `packages`), privilege escalation (always denied), network commands, writes outside `/workspace`, and, if enabled, web access.

**Auto-approve agent actions** (Settings, off by default) skips the card. Risky actions run immediately, but each one is still a step in the audit trail, marked with a small shield next to its text in the same muted color, and `approval: auto-approved` plus the risk in its audit sheet. Privilege escalation stays blocked even with auto-approve on. The Workspace screen always asks, because you type those commands yourself.

The Markdown reader renders headings, lists, task checkboxes (you can toggle them), tables, quotes, code blocks with copy, and links. It also has a raw mode, an edit mode, and a line diff against the previous snapshot whenever the agent changed an existing file.

The overflow menu also has **Workspace**, a user-controlled shell over the open conversation's workspace. It uses the same sandbox (built-ins, then Termux) and the same permission policy, and risky commands always ask there.

## Architecture

```
app/src/main/java/com/sebialex/agent
├── AgentApplication.kt         manual DI container
├── MainActivity.kt             edge-to-edge host, screen stack
├── domain/
│   ├── model/                  Turn, Step, ToolCall, ToolResult, FileAttachment, SourceRef
│   ├── agent/                  AgentOrchestrator (loop + approvals + audit), AgentBrain, RoutingBrain
│   ├── tools/ToolExecutor.kt   dispatches tool calls to workspace / shell / web
│   ├── permission/             PermissionPolicy (pure, unit tested)
│   └── markdown/               block + inline parser, LCS line diff
├── data/
│   ├── secure/SecureSettings   Keystore-backed EncryptedSharedPreferences (TinyFish, OpenRouter, model)
│   ├── workspace/Workspace     path-jailed file store with snapshots for diffs
│   ├── shell/                  SandboxShell (jailed built-ins, then Termux or /system/bin/sh),
│   │                           TermuxBridge (RUN_COMMAND + result receiver), WorkspaceMirror
│   ├── web/                    TinyFishClient, DirectWebClient, RoutingWebGateway
│   ├── llm/OpenRouterBrain     OpenAI-compatible tool-calling client
│   └── persistence/            per-conversation session store and JSONL audit log
└── ui/
    ├── theme/                  fixed dark palette, typography, soft surfaces
    ├── components/             icon buttons, fading single-line text, favicon loader
    ├── chat/                   timeline, turns, steps, approval card, input pill, sheets, conversations menu
    ├── markdown/               Compose Markdown renderer
    ├── files/                  reader / raw / diff / editor
    ├── settings/               key fields, model, reasoning, web approval, auto-approve, sandbox status
    └── terminal/               workspace shell
```

## Build

Pushing a `v*` tag (or a manual dispatch) runs `.github/workflows/build-apk.yml`. It sets up JDK 17 and the Android SDK, runs the unit tests, runs `assembleDebug`, and publishes the APK as a pre-release under that tag with `softprops/action-gh-release`.

Local build:

```
./gradlew assembleDebug
```
