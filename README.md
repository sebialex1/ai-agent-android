# Agent for Android

A minimal, dark, local AI agent for Android. Give it a task in natural language; it searches and reads the web through **TinyFish**, runs real commands, package installs (`apt`, `pkg`, `pip`) and Python inside the Termux environment, and writes Markdown files. All the technical detail is kept in a collapsed audit trail above each answer.

Kotlin, Jetpack Compose (Material 3), Gradle Kotlin DSL, `compileSdk`/`targetSdk` 34, `minSdk` 26.

## API keys

All keys go in **Settings** (overflow menu, top right). They are stored with `EncryptedSharedPreferences`, using an AES-256-GCM master key held in the **Android Keystore**. Keys never show up in the chat, the logs, or backups (`allowBackup=false`, and the data extraction rules exclude them).

| Key | Used for | Endpoint |
| --- | --- | --- |
| TinyFish | Web search and page retrieval | `GET https://api.search.tinyfish.ai?query=…`, `POST https://api.fetch.tinyfish.ai` (`X-API-Key` header) |
| OpenRouter | Model inference for the agent loop (tool calling) | `POST https://openrouter.ai/api/v1/chat/completions` (`Authorization: Bearer …`) |
| Ollama Cloud (optional) | Hosted Ollama models, a second cloud provider | `POST https://ollama.com/v1/chat/completions`, `GET https://ollama.com/api/tags`, `POST https://ollama.com/api/show` (`Authorization: Bearer …`) |

The model is picked in Settings and defaults to `openrouter/auto`; it is not hard-coded anywhere. On-device models need no key at all (see below).

## Models: three separate sources

Settings > General has a **Model** row that shows the current model and where it runs. Tapping it opens a picker with a segmented switch, so the sources never mix in one list:

| Source | Runs on | Model ids | Needs |
| --- | --- | --- | --- |
| OpenRouter | OpenRouter's cloud | `openrouter/auto`, `anthropic/…`, any OpenRouter id | OpenRouter key |
| Ollama Cloud | ollama.com's cloud (not this phone) | `ollama/<name>`, e.g. `ollama/gpt-oss:120b`; the list is read live from `/api/tags` | Ollama Cloud key (create one at https://ollama.com/settings/keys) |
| On this phone | This phone's GPU (CPU fallback), offline | `local/gemma-4-e2b-it`, `local/gemma-4-e4b-it`, `local/lfm2.5-1.2b-instruct`, `local/file/<name>.litertlm` | A downloaded model file |

The **Cloud provider** switch (OpenRouter / Ollama Cloud) in General shows that provider's key field and makes its last used model active. Without an Ollama key the provider can still be selected; the picker then shows a short note instead of a model list, and a chat answers with a notice without running anything. Ollama Cloud uses the same streaming, tool-calling and reasoning code as OpenRouter through its OpenAI-compatible endpoint; tools are only offered to models whose `/api/show` capabilities include `tools`, and the Reasoning switch follows each model's `thinking` levels (`reasoning_effort`, `"none"` to turn it off).

**Custom Ollama model ID.** The Ollama tab of the picker always has a *Custom model ID* field, for models the live list does not show (or when `/api/tags` fails or comes back empty). Type the name as listed on ollama.com, e.g. `gpt-oss:120b`; an `ollama/` prefix and the local-app `-cloud` / `:cloud` suffix are stripped. Once saved it gets its own row ("Custom model ID") and is used as `ollama/<name>`. Clearing the field and saving removes it; with the field empty the picker behaves as before. Names unknown to `/api/show` simply get no Reasoning control.

## On-device models (Settings > On-device)

Local inference uses Google's **LiteRT-LM** runtime (`com.google.ai.edge.litertlm:litertlm-android:0.18.0`) on the GPU through OpenCL. Nothing leaves the phone; web tools still use the network when the model calls them.

| Model | File | Size | RAM | Notes |
| --- | --- | --- | --- | --- |
| Gemma 4 E2B (recommended) | `gemma-4-E2B-it.litertlm` | 2.58 GB | 8 GB | Tool calling, thinking, multi-token prediction, 32K context |
| Gemma 4 E4B | `gemma-4-E4B-it.litertlm` | 3.65 GB | 12 GB | Stronger, slower |
| LFM2.5 1.2B | `LFM2.5-1.2B-Instruct_int4_gpu.litertlm` | 736 MB | 6 GB | Smallest and fastest GPU build with tool calling, 4K context |

The "2B/4B" Gemma models are Gemma 4 E2B / E4B (effective 2B / 4B parameters), the current on-device Gemma generation. Files come from the ungated `litert-community` repositories on Hugging Face, pinned to exact commits, the same builds Google's AI Edge Gallery ships. You can also import any `.litertlm` file; its tool-calling and thinking support is read from the file.

- **Downloads** go through the system `DownloadManager` into `Download/AgentForAndroid/models/` in shared storage, so models survive clearing app data and reinstalling. The On-device tab shows the folder actually in use. Android 11+ needs no permission to create files there. Android 8-10 need the storage permission (an *Allow* button appears); without it, and whenever shared storage cannot be written, files go to the app-specific folder `Android/data/<app>/files/models` (deleted with the app). Models already in that old folder are moved to Downloads once, in the background, with progress on the card. After a reinstall on Android 11+, Android hides files the previous install created until **All files access** is granted (the same access the Termux sandbox uses; an *Allow* button is shown). LiteRT-LM's compiled caches stay app-specific (`Android/data/<app>/files/litert-cache`). Downloads continue in the background with a notification, survive the app being closed, and are checked against the exact byte size before use. Free space is checked first.
- **Loading**: one model is in memory at a time. It loads on first use (or on "Use"/"Load") on the GPU; if the GPU path is unavailable (no OpenCL, driver error) it falls back to the CPU and says why. Picking a cloud model unloads it to free memory.
- **Context window**: 2K to 32K tokens (default 4K), capped by each model. Long histories and tool outputs are trimmed to fit.
- **Faster decoding (MTP)**: optional multi-token prediction for models that include it (Gemma 4) on the GPU.
- **Agent behaviour** is the same as with cloud models: the same system prompt and six tools, tool calls returned to the app (LiteRT-LM never runs them), approvals and the audit log unchanged. Gemma 4's thinking mode is off by default and can be switched on with the Reasoning/Thinking toggle.

Build notes: LiteRT-LM 0.18.0 is compiled with a newer Kotlin than earlier versions of this project, so the build uses Kotlin 2.2.21, AGP 8.8.2 and Gradle 8.10.2, with `-Xskip-metadata-version-check` (as the AI Edge Gallery does). The manifest declares `libOpenCL.so` and `libvndksupport.so` as optional native libraries for GPU access. Native code ships for `arm64-v8a` (and `x86_64` emulators).

Nothing is canned or simulated. Every answer comes from OpenRouter's chat completions endpoint with tool calling, and every tool call is really executed: the exact exchange (assistant `tool_calls`, then one `tool` message per call with the real output) is replayed to the model on each step. Without a key for the selected cloud provider (and without an on-device model) the agent does not run at all; the turn ends with a short notice and nothing is executed. Without a TinyFish key, web access still happens for real, using a direct HTTPS GET for pages and DuckDuckGo's HTML results for search. Failures show up as failed steps and are reported back to the model.

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
   mkdir -p ~/.termux && (grep -qx 'allow-external-apps = true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps = true' >> ~/.termux/termux.properties) && termux-reload-settings; pkg install -y python; termux-setup-storage
   ```
   It is safe to run more than once. Then grant Termux storage access when Android asks.
3. In Settings > Sandbox, tap **Allow** for *Run commands in Termux* (the `com.termux.permission.RUN_COMMAND` permission). If Android does not show a prompt, enable it under App info > Permissions > Additional permissions.
4. Tap **Allow** for *All files access*. Termux runs as a different Linux user and cannot see this app's private files. Shared storage is the only place both apps can read and write.
5. Tap **Check**. It runs a short command in Termux and prints the paths of `bash`, `apt`, `dpkg`, `pkg`, `python` and `pip` (`missing` for anything not installed yet), then checks that Termux can write the shared workspace folder. Python is installed by the setup command; if it is missing, the agent can install it with `pkg install -y python`.

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
4. Source cluster: up to three favicon + domain chips, then `+N` for the rest. Tapping a chip opens a compact preview (favicon, title, domain, excerpt of what was read) with an **Open website** button; nothing opens until you tap it.

While the model thinks, its raw reasoning streams into the `thought` row as words, about one line tall, with older text fading out. When it finishes, the row keeps one line of the reasoning that fades at the tail; tap it for the full text.

The **+** in the input opens an attachment sheet: Camera, Photos and Files tiles, plus a quick **Thinking** switch for the selected model (the same per-model setting as in Settings). Picked items are copied byte-for-byte into `/workspace/imports/` and mentioned in the message.

The folder button in the chat top bar opens the workspace: a segmented switch for **Terminal**, **Sources** (every page read in the conversation) and **Files** (an **Artifacts** group with files the agent wrote, then all workspace files). Sources and Files only appear when they have content.

Settings live on the conversations screen. System Back walks up: file viewer, workspace or settings, then the chat, then the conversations list, and only leaves the app from there.

When you send a new message, all earlier step groups collapse. Answers, files, and sources stay visible. Only pages the agent actually fetched become website sources.

A newly received answer types out with a fast typewriter that speeds up as the answer grows. Each answer is revealed once. Reopening a conversation, restarting the app, or coming back from another screen shows the saved text immediately.

## Memory of past chats

The + sheet in a chat has a **Memory of past chats** switch. It is stored per conversation and off by default. When it is off the agent is not even told the tool exists. When on, the agent (OpenRouter, Ollama Cloud and on-device alike) gets one read-only tool, `conversation_memory`, with three actions:

- `list_conversations` (optional `query`): recent conversations, newest first, one line each (id, title, date, message count, first request). At most 25 rows / ~5 KB.
- `summarize_conversation` (`conversation_id`): requests, the last answer excerpt, tools used, files written, sources. ~2.5 KB at most, built from the saved messages without an extra model call.
- `read_conversation` (`conversation_id`, `offset`): the full messages, paged (~7 KB per page, 3 KB per message) with the next offset at the end.

The tool can only read `session.json` files; nothing in it writes. The current conversation is left out, since it is already in context.

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
│   ├── tools/ToolExecutor.kt   dispatches tool calls to workspace / shell / web / memory
│   ├── tools/ConversationMemory  read-only interface for the conversation_memory tool
│   ├── permission/             PermissionPolicy (pure, unit tested)
│   └── markdown/               block + inline parser, LCS line diff
├── data/
│   ├── secure/SecureSettings   Keystore-backed EncryptedSharedPreferences (keys, provider, model, on-device options)
│   ├── workspace/Workspace     path-jailed file store with snapshots for diffs
│   ├── shell/                  SandboxShell (jailed built-ins, then Termux or /system/bin/sh),
│   │                           TermuxBridge (RUN_COMMAND + result receiver), WorkspaceMirror
│   ├── web/                    TinyFishClient, DirectWebClient, RoutingWebGateway
│   ├── llm/OpenRouterBrain     OpenAI-compatible tool-calling client (OpenRouter and Ollama Cloud)
│   ├── llm/OllamaCloud         Ollama Cloud model list, capabilities, OllamaBrain
│   ├── local/                  LocalModels catalog, ModelStore (downloads/imports), LocalEngine (LiteRT-LM, GPU/CPU), LocalBrain
│   └── persistence/            per-conversation session store, JSONL audit log,
│                               ConversationMemoryReader (capped list/summary/paged read)
└── ui/
    ├── theme/                  fixed dark palette, typography, soft surfaces
    ├── components/             icon buttons, fading single-line text, favicon loader
    ├── chat/                   timeline, turns, steps, approval card, input pill, sheets, conversations menu
    ├── markdown/               Compose Markdown renderer
    ├── files/                  reader / raw / diff / editor
    ├── settings/               General / On-device tabs, model picker, provider switch, keys, reasoning, sandbox status
    └── terminal/               workspace shell
```

## Build

Pushing a `v*` tag (or a manual dispatch) runs `.github/workflows/build-apk.yml`. It sets up JDK 17 and the Android SDK, runs the unit tests, runs `assembleDebug`, and publishes the APK as a pre-release under that tag with `softprops/action-gh-release`.

Local build:

```
./gradlew assembleDebug
```
