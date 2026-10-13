# Agent for Android

A minimal, dark, local AI agent for Android. Give it a task in natural language; it searches and reads the web through **TinyFish**, runs commands in a sandboxed Termux-like workspace, and writes Markdown files. All the technical detail is kept in a collapsed audit trail above each answer.

Kotlin, Jetpack Compose (Material 3), Gradle Kotlin DSL, `compileSdk`/`targetSdk` 34, `minSdk` 26.

## API keys

Both keys go in **Settings** (overflow menu, top right). They are stored with `EncryptedSharedPreferences`, using an AES-256-GCM master key held in the **Android Keystore**. Keys never show up in the chat, the logs, or backups (`allowBackup=false`, and the data extraction rules exclude them).

| Key | Used for | Endpoint |
| --- | --- | --- |
| TinyFish | Web search and page retrieval | `GET https://api.search.tinyfish.ai?query=…`, `POST https://api.fetch.tinyfish.ai` (`X-API-Key` header) |
| OpenRouter | Model inference for the agent loop (tool calling) | `POST https://openrouter.ai/api/v1/chat/completions` (`Authorization: Bearer …`) |

The model is set in Settings and defaults to `openrouter/auto`; it is not hard-coded anywhere. If no OpenRouter key is set, the app uses a deterministic `ScriptedBrain`. If no TinyFish key is set, it uses an offline web corpus (`SampleWeb`). This means the whole interaction model works offline with real, reproducible data.

## Interaction model

Every assistant turn renders in this fixed order:

1. Execution row: `8 steps >`. Tap it to expand compact one-line actions (search icon + query, favicon + hostname, terminal icon + command, file icon + filename, shield icon for approvals). Long rows fade at the right edge. Tapping a row opens its raw audit sheet: input, output, URL/path, exit code, and timing.
2. The answer, rendered as Markdown.
3. File pills: document icon, filename, and "Updated now" or a `+n −m` diff count.
4. Source cluster: up to three favicon + domain chips, then `+N` for the rest. Each chip opens the exact page.

When you send a new message, all earlier step groups collapse. Answers, files, and sources stay visible.

Risky actions pause the loop and show an inline Allow/Deny card. This covers destructive commands, package installs, privilege escalation (always denied), network commands, writes outside `/workspace`, and, if enabled, web access.

The Markdown reader renders headings, lists, task checkboxes (you can toggle them), tables, quotes, code blocks with copy, and links. It also has a raw mode, an edit mode, and a line diff against the previous snapshot whenever the agent changed an existing file.

The overflow menu also has **Workspace**, a user-controlled shell over the same sandbox with the same permission policy.

## Architecture

```
app/src/main/java/com/sebialex/agent
├── AgentApplication.kt         manual DI container
├── MainActivity.kt             edge-to-edge host, screen stack
├── domain/
│   ├── model/                  Turn, Step, ToolCall, ToolResult, FileAttachment, SourceRef
│   ├── agent/                  AgentOrchestrator (loop + approvals + audit), AgentBrain, ScriptedBrain, RoutingBrain
│   ├── tools/ToolExecutor.kt   dispatches tool calls to workspace / shell / web
│   ├── permission/             PermissionPolicy (pure, unit tested)
│   └── markdown/               block + inline parser, LCS line diff
├── data/
│   ├── secure/SecureSettings   Keystore-backed EncryptedSharedPreferences (TinyFish, OpenRouter, model)
│   ├── workspace/Workspace     path-jailed file store with snapshots for diffs
│   ├── shell/SandboxShell      Termux-like interpreter (no real processes)
│   ├── web/                    TinyFishClient, SampleWeb, RoutingWebGateway
│   ├── llm/OpenRouterBrain     OpenAI-compatible tool-calling client
│   └── persistence/            session JSON store, JSONL audit log
└── ui/
    ├── theme/                  fixed dark palette, typography, soft surfaces
    ├── components/             icon buttons, fading single-line text, favicon loader
    ├── chat/                   timeline, turns, steps, approval card, input pill, sheets
    ├── markdown/               Compose Markdown renderer
    ├── files/                  reader / raw / diff / editor
    ├── settings/               two key fields, model, web approval toggle
    └── terminal/               workspace shell
```

To use real backends, swap in different implementations behind `AgentBrain`, `WebGateway`, and `Shell` (for example, a Termux bootstrap that runs real processes in `$PREFIX`).

## Build

Each push to `main` runs `.github/workflows/build-apk.yml`. It sets up JDK 17 and the Android SDK, runs the unit tests, runs `assembleDebug`, and publishes the APK as a pre-release (`v0.1.<run>`) with `softprops/action-gh-release`.

Local build:

```
./gradlew assembleDebug
```
