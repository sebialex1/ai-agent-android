# Agent for Android

A minimal, dark, local AI agent for Android. Give it a task in natural language; it searches and reads the web through **TinyFish**, runs real commands, package installs (`apt`, `pkg`, `pip`) and Python inside the Termux environment, and writes Markdown files. All the technical detail is kept in a collapsed audit trail above each answer.

Kotlin, Jetpack Compose (Material 3), Gradle Kotlin DSL, `compileSdk`/`targetSdk` 34, `minSdk` 26.

## Agent tools

`web_search`, `web_fetch`, `shell` (Termux: bash, apt, pkg, pip), `python`, `read_file`, `write_file`, `edit_file` (exact-text replace), `list_files`, `search_files` (grep across the workspace), `move_file`, `delete_file` (asks first), `download_file` (any URL into the workspace), `http_request` (API calls; non-GET asks first), `device_info` (date, time zone, locale, Android version), `check_tools` (is yt-dlp / ffmpeg / git … installed, and how to install what is missing), `attach_files` (attach produced files to the answer as artifacts), `view_image` (look at a workspace image or video frames), plus the read-only `conversation_memory` when memory of past chats is on for that conversation.

## Chats, settings and files

- Conversations can be **pinned** (own section on top) and renamed from the themed options sheet (the ⋯ next to a chat in the conversations list; the chat's own top bar only has back, new chat and workspace). After the first answer the model writes a short **title** from the conversation (on by default); a renamed chat keeps its title.
- **Memory of past chats** is per conversation, switched in that chat's options sheet (the only switch for it). New chats start with it on until you switch it off somewhere; from then on they start with the last choice made there (an explicit off is never overridden). Settings has no second copy.
- **Settings** opens on a vertical list of sections: General (permissions, Termux sandbox), Models (every API key: OpenRouter, Ollama Cloud and the TinyFish web search key; the active model and its reasoning are picked only in the chat's + sheet), On-device, Chats (auto titles, delete all) and About. Tapping a section opens its own page with its name next to Back; Back (or system Back) returns to the list.
- The workspace **Files** tab is a small file manager: breadcrumbs, folders, sizes and ages; open, download to `Download/AgentForAndroid`, rename and delete per item; download a folder as zip, clear it, or show hidden files.
- The **file viewer** shows type and size under the name. Images (png, jpg, gif, webp, bmp) open in-app with Coil (pinch or double-tap to zoom, GIFs animate). Video (mp4, webm, mkv, mov, m4v) and audio (mp3, ogg, wav, m4a) play in a small built-in player on the platform `MediaPlayer` (play/pause, seek, time; no ExoPlayer). Everything else opens as text or rendered Markdown. In landscape, images and videos go full-screen: the header shrinks to a slim translucent overlay (and the video controls float over the bottom edge), a tap hides or shows them, and they fade out on their own while a video plays.
- **Rotation keeps your place.** The activity handles orientation and size changes itself, and the screen stack (chat, workspace, Settings section, an open file viewer) is saved with the instance state, so rotating never drops you back to the chat and a playing video keeps playing.

## API keys

All keys go in **Settings > Models** (gear on the conversations screen). Each provider (OpenRouter, Ollama Cloud, TinyFish and every custom provider) holds a **stack of keys**, shown as a small card: a numbered row per key (masked to its last four characters, optional label such as *Work* or *Free tier*), an **Active** badge on the key in use, a **Failing** badge on one that just failed, **Use** to switch by hand, remove, and **Add key**. When a request fails because of the key (HTTP 401/402/403/429, rate limit, quota, out of credit, invalid key) the next key that has not failed in the last 10 minutes becomes active and the request is retried, until one works or all have been tried; other errors are reported as before. Keys saved by older versions are migrated into the stack. They are stored with `EncryptedSharedPreferences`, using an AES-256-GCM master key held in the **Android Keystore**. Keys never show up in the chat, the logs, or backups (`allowBackup=false`, and the data extraction rules exclude them).

| Key | Used for | Endpoint |
| --- | --- | --- |
| TinyFish | Web search and page retrieval | `GET https://api.search.tinyfish.ai?query=…`, `POST https://api.fetch.tinyfish.ai` (`X-API-Key` header) |
| OpenRouter | Model inference for the agent loop (tool calling) | `POST https://openrouter.ai/api/v1/chat/completions` (`Authorization: Bearer …`) |
| Ollama Cloud (optional) | Hosted Ollama models, a second cloud provider | `POST https://ollama.com/api/chat` (native API), `GET https://ollama.com/api/tags`, `POST https://ollama.com/api/show` (`Authorization: Bearer …`) |

### Custom providers

Settings > Models > **Custom providers** adds any OpenAI-compatible API, the way SillyTavern's *Custom (OpenAI-compatible)* source, LibreChat's custom endpoints, Open WebUI's connections and Jan's custom providers do: a base URL (the API root, normally ending in `/v1`, never `/chat/completions`), keys, and the models its `/models` lists. **Add provider** opens a sheet with a dropdown of popular providers (OpenAI, Anthropic, Google Gemini, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, Cerebras, DeepInfra, Perplexity, Hugging Face, NVIDIA NIM, Moonshot, Z.ai; local LM Studio, Ollama, llama.cpp, vLLM, LiteLLM, Jan) and **Custom / auto-detect**. **Detect** cleans the URL (adds the scheme, drops a pasted `/chat/completions` or `/models`), then probes `<root>/models` for the URL as typed and with `/v1`, `/api/v1`, `/openai/v1`, `/v1beta/openai`; then Ollama's native `/api/tags` (switching to its `/v1`); then `POST <root>/chat/completions` with an empty body (400/401/422 means the route exists, even without a model list). It names the provider from a known host, the models' `owned_by` or the host, and shows what it found (e.g. *OpenAI-compatible · 42 models*). Each custom provider gets its own key stack with rotation; local servers work without a key. Its models appear in the + sheet's picker under the provider's name (refreshed from `/models`, or a typed model id when there is no list), as `custom/<provider>/<model>`; requests go to `<root>/chat/completions` with streaming and tools, without OpenRouter-only fields. Plain `http://` is allowed only for servers on the phone itself (`localhost`, `127.0.0.1`, the emulator's `10.0.2.2`, e.g. a llama.cpp or Ollama server started in Termux); anything else, including other machines on your network, needs `https://` (put a TLS reverse proxy or tunnel in front of a LAN server).

The model is picked in the chat's + sheet and defaults to `openrouter/auto`; it is not hard-coded anywhere. On-device models need no key at all (see below).

## Models: three separate sources

The model is chosen from the chat's **+** sheet (one row showing the current model; tapping it opens a clean searchable list); that is the only model picker, Settings > Models only holds the keys. The list shows downloaded on-device models, then Ollama Cloud and OpenRouter models, each pulled live from the provider (`/api/tags`, `/api/v1/models`). A manual model-id field only appears for a provider whose list failed or came back empty:

| Source | Runs on | Model ids | Needs |
| --- | --- | --- | --- |
| OpenRouter | OpenRouter's cloud | `openrouter/auto`, `anthropic/…`, any OpenRouter id | OpenRouter key |
| Ollama Cloud | ollama.com's cloud (not this phone) | `ollama/<name>`, e.g. `ollama/gpt-oss:120b`; the list is read live from `/api/tags` | Ollama Cloud key (create one at https://ollama.com/settings/keys) |
| On this phone | This phone's GPU (CPU fallback), offline | `local/gemma-4-e2b-it`, `local/gemma-4-e4b-it`, `local/lfm2.5-1.2b-instruct`, `local/file/<name>.litertlm` | A downloaded model file |

Both provider keys (and the web search key) live in Settings > Models; whichever provider the picked model belongs to answers. Without an Ollama key the provider can still be selected; the picker then shows a short note instead of a model list, and a chat answers with a notice without running anything. Ollama Cloud uses Ollama's native `/api/chat` endpoint as documented at https://docs.ollama.com/cloud: NDJSON streaming, native tool calls, and the `think` field (`true`/`false` or a named level). Model names are the exact tags from `/api/tags` (e.g. `gemma4:31b`; `-cloud` names only work through the local Ollama app). Tools are only offered to models whose `/api/show` capabilities include `tools`; Gemma 4 and other known thinking families always get the Reasoning switch. Errors show the server's own message, e.g. a 403 "this model requires a subscription" or an invalid key.

**Custom Ollama model ID.** The Ollama tab of the picker always has a *Custom model ID* field, for models the live list does not show (or when `/api/tags` fails or comes back empty). Type the name as listed on ollama.com, e.g. `gpt-oss:120b`; an `ollama/` prefix and the local-app `-cloud` / `:cloud` suffix are stripped. Once saved it gets its own row ("Custom model ID") and is used as `ollama/<name>`. Clearing the field and saving removes it; with the field empty the picker behaves as before. Names unknown to `/api/show` simply get no Reasoning control.

## On-device models (Settings > On-device)

Local inference uses Google's **LiteRT-LM** runtime (`com.google.ai.edge.litertlm:litertlm-android:0.18.0`) on the GPU through OpenCL. Nothing leaves the phone; web tools still use the network when the model calls them.

| Model | File | Size | RAM | Notes |
| --- | --- | --- | --- | --- |
| Gemma 4 E2B (recommended) | `gemma-4-E2B-it.litertlm` | 2.58 GB | 8 GB | Tool calling, thinking, multi-token prediction, 32K context |
| Gemma 4 E4B | `gemma-4-E4B-it.litertlm` | 3.65 GB | 12 GB | Stronger, slower |
| LFM2.5 1.2B | `LFM2.5-1.2B-Instruct_int4_gpu.litertlm` | 736 MB | 6 GB | Smallest and fastest GPU build with tool calling, 4K context |

The "2B/4B" Gemma models are Gemma 4 E2B / E4B (effective 2B / 4B parameters), the current on-device Gemma generation. Files come from the ungated `litert-community` repositories on Hugging Face, pinned to exact commits, the same builds Google's AI Edge Gallery ships. You can also import any `.litertlm` file; its tool-calling and thinking support is read from the file.

- **Downloads** go through the system `DownloadManager` into `Download/AgentForAndroid/models/` in shared storage, so models survive clearing app data and reinstalling. The On-device page shows the folder actually in use. Android 11+ needs no permission to create files there. Android 8-10 need the storage permission (an *Allow* button appears); without it, and whenever shared storage cannot be written, files go to the app-specific folder `Android/data/<app>/files/models` (deleted with the app). Models already in that old folder are moved to Downloads once, in the background, with progress on the card. After a reinstall on Android 11+, Android hides files the previous install created until **All files access** is granted (the same access the Termux sandbox uses; an *Allow* button is shown). LiteRT-LM's compiled caches stay app-specific (`Android/data/<app>/files/litert-cache`). Downloads continue in the background with a notification, survive the app being closed, and are checked against the exact byte size before use. Free space is checked first.
- **Loading**: one model is in memory at a time. It loads on first use (or on "Use"/"Load") on the GPU; if the GPU path is unavailable (no OpenCL, driver error) it falls back to the CPU and says why. Picking a cloud model unloads it to free memory.
- **Context window**: 2K to 32K tokens (default 4K), capped by each model. Long histories and tool outputs are trimmed to fit.
- **Faster decoding (MTP)**: optional multi-token prediction for models that include it (Gemma 4) on the GPU.
- **Agent behaviour** is the same as with cloud models: the same system prompt and a compact tool set (web, shell, python, read/write/edit/list files, device info), tool calls returned to the app (LiteRT-LM never runs them), approvals and the audit log unchanged. Gemma 4's thinking mode is off by default and can be switched on with the Reasoning/Thinking toggle.

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

### Programs are checked before a command runs

Before the agent's `shell` command runs, every program it uses (the first word of each `&&`, `||`, `;`, `|` part, quote-aware; built-ins and shell keywords skipped) is looked up with one `command -v` probe in Termux (or the device shell). If one is missing, the command is **not run**: the step fails with exit 127 and a readable note under the row, e.g. `yt-dlp — not installed · pip install -U yt-dlp`, and the model gets the install command so it can offer to install it (or install it when the task clearly needs it). Commands that install something themselves are never pre-checked, found programs are cached until something is uninstalled, and if the probe cannot answer the command simply runs. The `check_tools` tool runs the same lookup on demand.

### The python tool

Besides `shell`, the model has a `python` tool: `{code, packages?, filename?}`. The code is saved to the workspace (as `filename`, or under `/workspace/.scripts/`), any `packages` are installed with `python -m pip install`, and the script runs with Termux's Python from the current directory. In the steps row it looks like any command: terminal icon + `python script.py`. The audit sheet holds the full code and output, and has a link to open the script. If the pip install fails, the step fails with pip's output and the script does not run.

## Interaction model

Every assistant turn renders in this fixed order:

1. Execution row: `8 steps >`. Tap it to expand compact one-line actions (search icon + query, favicon + hostname, terminal icon + command, file icon + filename, shield icon for approvals). Long rows fade at the right edge. Tapping a row opens its raw audit sheet: input, output, URL/path, exit code, and timing.
2. The answer, rendered as Markdown.
3. Artifacts: files the agent attached on purpose with `attach_files` (a downloaded video, a converted image, a report), as compact chips with a tiny thumbnail or type icon, the name and size. No big inline preview; tapping one opens the file viewer (image viewer, media player, text). Several files can be attached; they are only references to workspace files, and an ugly auto-generated name is renamed to the friendly `name` the model gives. They also appear in the workspace's Artifacts group.
4. File pills: document icon, filename, and "Updated now" or a `+n −m` diff count.
5. Source cluster: up to three favicon + domain chips, then `+N` for the rest. Tapping a chip opens a compact preview (favicon, title, domain, excerpt of what was read) with an **Open website** button; nothing opens until you tap it.

When a turn has both thinking and tool calls, the thinking is not mixed into the steps: the tool calls keep their `8 steps >` group and the reasoning gets its own collapsible **Thinking / Thought for 12s** section right below it (live one-line window while it streams, full text when expanded, tap the text for the raw audit sheet). A turn with thinking only, or plain text with tool calls and no thinking, renders as before.

While the model thinks, its raw reasoning streams into the `thought` row as words, about one line tall, with older text fading out. When it finishes, the row keeps one line of the reasoning that fades at the tail; tap it for the full text.

The **+** in the input opens an attachment sheet: Camera, Photos and Files tiles, the model row and the **Thinking** switch with its effort levels for the selected model (the only place for them; stored per model). Picked items (Photos takes images and videos) are copied byte-for-byte into `/workspace/imports/<id>/` and wait above the input as small thumbnails with a remove button; a message can be just the imports. In the sent message they show as previews instead of a file name: images and videos as rounded, aspect-fit cards (videos with their first frame, a play badge and the length), other files as a compact chip with name and size. Tapping one opens it in the file viewer and its built-in player. The model still receives them as `@imports/<id>/<name>` mentions: every import gets its own folder, so re-importing `photo.jpg` later never overwrites the earlier one (old messages keep their preview and what the agent saw), while the file keeps its original name.

**@-mentions.** Typing `@` (at the start or after a space, so e-mail addresses never trigger it) opens a floating card that rises from the input over the bottom of the chat: the conversation's workspace files, artifacts and files the agent wrote first (tagged *Artifact*), then the rest by last change, filtered as you type (name matches first, the typed part highlighted). Images and videos show a small thumbnail to the left of the name, other files a type tile; each row shows its folder and size. Picking one inserts `@path` (or `@"path with spaces"`). In the sent message the mention stays highlighted in the text and a compact chip under the bubble opens the file; the model gets the path (and sees a mentioned image as it would an import).

The folder button in the chat top bar opens the workspace: a segmented switch for **Terminal**, **Sources** (every page read in the conversation) and **Files** (an **Artifacts** group with files the agent wrote, then all workspace files). Sources and Files only appear when they have content.

Settings live on the conversations screen. System Back walks up: file viewer, workspace or settings, then the chat, then the conversations list, and only leaves the app from there.

**The input is never blocked.** While an answer is running you can keep typing and send: the message is **queued**, shown dimmed at the bottom of the chat with a small *Queued* badge and a remove button, and sent automatically as soon as the running turn finishes (several queue up in order; stopping the run sends the next one right away). The send button is the only button there: it queues when there is text and turns into Stop when the input is empty.

**Long-press one of your messages** for a themed sheet with a quoted preview and **Copy message**, **Edit message** and **Delete message**. Edit puts the text back into the input (its imports back above it) with a slim *Editing* strip and a cancel button; sending replaces that message and everything after it in the history, then runs it again. Delete removes the message and its reply. Edit and Delete wait while an answer is running; Copy always works.

When you send a new message, all earlier step groups collapse. Answers, files, and sources stay visible. Only pages the agent actually fetched become website sources.

A newly received answer types out with a fast typewriter that speeds up as the answer grows. Each answer is revealed once. Reopening a conversation, restarting the app, or coming back from another screen shows the saved text immediately.

## Images and vision

Whether the agent looks at an imported image is decided from the message it came with:

- **Looks**: the message is only the import, or it asks to look ("what's this", "describe", "read the text", "who is this", a question about it, Romanian "uită-te", "ce e" and similar), or it is any other request about the picture. The image is sent with that message as an `image_url` part (scaled to at most 1568 px, EXIF rotation applied, JPEG or PNG); a video is sent as three frames (start, middle, end). Up to four imports per message.
- **Does not look, use only** (`USE_ONLY`): the message only says what to do with the file ("use this as the background", "upload it", "convert to png", "rename it"). The picture is not inspected automatically; the model is told the file exists, works with it by path, and can still call `view_image` on demand if the task turns out to need it.
- **Does not look, refused** (`REFUSED`): the message says not to look ("don't look", "don't describe it", "ignore the image"). Nothing is shown and every `view_image` call in that turn is refused. This is the only case that blocks `view_image`.

Each decision is written to the conversation's audit log as a `vision_intent` entry (`only_media`, `asked_to_look`, `implicit`, `use_only` or `refused`); it adds no row to the chat.

If the selected model has no image input, the request is retried once without the picture and the model says it could not see it. Ollama Cloud gets the same pictures in its native `images` field. On-device models take text only and are told so.

## Memory of past chats

A chat's options sheet (the ⋯ next to it in the conversations list) has the **Memory of past chats** switch, the only control for it. It is stored per conversation; new chats start with it on until you explicitly turn it off, then with the last choice made there. When it is off the agent is not even told the tool exists. When on, the agent (OpenRouter, Ollama Cloud and on-device alike) gets one read-only tool, `conversation_memory`, with three actions:

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
    ├── settings/               section list and pages (General, Models, On-device, Chats, About), model picker, keys, reasoning, sandbox status
    └── terminal/               workspace shell
```

## Build

Pushing a `v*` tag (or a manual dispatch) runs `.github/workflows/build-apk.yml`. It sets up JDK 17 and the Android SDK, runs the unit tests, runs `assembleDebug`, and publishes the APK as a pre-release under that tag with `softprops/action-gh-release`.

Local build:

```
./gradlew assembleDebug
```
