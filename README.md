# Agent for Android

A minimal, dark, local AI agent for Android. Give it a task in natural language; it searches and reads the web through **TinyFish**, runs commands in a sandboxed Termux-like workspace, and writes Markdown files. All the technical detail is kept in a collapsed audit trail above each answer.

Kotlin, Jetpack Compose (Material 3), Gradle Kotlin DSL, `compileSdk`/`targetSdk` 34, `minSdk` 26.

## API keys

Both keys go in **Settings** (overflow menu, top right). They are stored with `EncryptedSharedPreferences`, using an AES-256-GCM master key held in the **Android Keystore**. Keys never show up in the chat, the logs, or backups (`allowBackup=false`, and the data extraction rules exclude them).

| Key | Used for | Endpoint |
| --- | --- | --- |
| TinyFish | Web search and page retrieval | `GET https://api.search.tinyfish.ai?query=…`, `POST https://api.fetch.tinyfish.ai` (`X-API-Key` header) |
| OpenRouter | Model inference for the agent loop (tool calling) | `POST https://openrouter.ai/api/v1/chat/completions` (`Authorization: Bearer …`) |

The model is set in Settings and defaults to `openrouter/auto`; it is not hard-coded anywhere.

Nothing is canned or simulated. Every answer comes from OpenRouter's chat completions endpoint with tool calling, and every tool call is really executed: the exact exchange (assistant `tool_calls`, then one `tool` message per call with the real output) is replayed to the model on each step. Without an OpenRouter key the agent does not run at all; the turn ends with a short notice and nothing is executed. Without a TinyFish key, web access still happens for real, using a direct HTTPS GET for pages and DuckDuckGo's HTML results for search. Failures show up as failed steps and are reported back to the model.

The shell implements a jailed coreutils subset (`ls`, `cat`, `grep`, `find`, `mv`, `rm`, …) directly on the workspace. Anything else runs on the device's `/system/bin/sh` inside the conversation's workspace directory, with a 60 s timeout and an output cap. A program that is not installed reports `not found` with its real exit code.

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

Risky actions pause the loop and show an inline Allow/Deny card. This covers destructive commands, package installs, privilege escalation (always denied), network commands, writes outside `/workspace`, and, if enabled, web access.

The Markdown reader renders headings, lists, task checkboxes (you can toggle them), tables, quotes, code blocks with copy, and links. It also has a raw mode, an edit mode, and a line diff against the previous snapshot whenever the agent changed an existing file.

The overflow menu also has **Workspace**, a user-controlled shell over the open conversation's workspace, with the same permission policy.

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
│   ├── shell/SandboxShell      jailed built-ins + real /system/bin/sh for everything else
│   ├── web/                    TinyFishClient, DirectWebClient, RoutingWebGateway
│   ├── llm/OpenRouterBrain     OpenAI-compatible tool-calling client
│   └── persistence/            per-conversation session store and JSONL audit log
└── ui/
    ├── theme/                  fixed dark palette, typography, soft surfaces
    ├── components/             icon buttons, fading single-line text, favicon loader
    ├── chat/                   timeline, turns, steps, approval card, input pill, sheets, conversations menu
    ├── markdown/               Compose Markdown renderer
    ├── files/                  reader / raw / diff / editor
    ├── settings/               two key fields, model, web approval toggle
    └── terminal/               workspace shell
```

To use real backends, swap in different implementations behind `AgentBrain`, `WebGateway`, and `Shell` (for example, a Termux bootstrap that runs real processes in `$PREFIX`).

## Build

Pushing a `v*` tag (or a manual dispatch) runs `.github/workflows/build-apk.yml`. It sets up JDK 17 and the Android SDK, runs the unit tests, runs `assembleDebug`, and publishes the APK as a pre-release under that tag with `softprops/action-gh-release`.

Local build:

```
./gradlew assembleDebug
```
