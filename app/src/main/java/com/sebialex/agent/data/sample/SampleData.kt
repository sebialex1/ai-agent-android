package com.sebialex.agent.data.sample

import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.markdown.LineDiff
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.SourceRef
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn

/** Deterministic first-launch conversation and workspace so the full interaction model is visible. */
object SampleData {

    const val PLAN_V1 = """# Integration Plan

> Plan the integration of an embedded terminal sandbox with TinyFish web access on Android.

## Goals

- Run controlled commands inside an app-private workspace
- Search and read the web through TinyFish
- Keep every action auditable without cluttering the chat

## Architecture

| Layer | Responsibility | Notes |
| --- | --- | --- |
| UI | Compose chat timeline | Steps collapse above each answer |
| Domain | Agent loop, permission policy | Deterministic and testable |
| Data | Workspace, shell, TinyFish client | Keys in Android Keystore |

## Milestones

- [x] Scaffold the workspace sandbox
- [x] Store the TinyFish key with EncryptedSharedPreferences
- [ ] Wire the shell behind the permission policy
- [ ] Ship the debug build

## Shell contract

```kotlin
interface Shell {
    fun execute(command: String, cwd: String = "/"): ShellResult
}
```

Package installs and destructive commands always require approval. See the
[Termux app](https://github.com/termux/termux-app) for how packages are isolated under `${'$'}PREFIX`.
"""

    val PLAN_V2 = PLAN_V1.trimEnd('\n') + """

## Rollout

1. Internal dogfood with the offline corpus
2. Enable live TinyFish search for testers
3. Collect audit logs and tune the permission policy

- [ ] Add a kill switch for network tools
- [ ] Document the approval flow
"""

    const val MAKE_PLAN = """# Generates integration-plan.md from collected notes
CONTENT = ""${'"'}
# Integration Plan
""${'"'}

with open("integration-plan.md", "w") as f:
    f.write(CONTENT)
print("wrote integration-plan.md")
"""

    fun seed(ws: Workspace): Session {
        val now = System.currentTimeMillis()
        ws.mkdir("notes")
        ws.write("make_plan.py", MAKE_PLAN)
        ws.write("notes/tinyfish.md", "# TinyFish\n\n- Search: `GET https://api.search.tinyfish.ai`\n- Fetch: `POST https://api.fetch.tinyfish.ai`\n- Header: `X-API-Key`\n")
        ws.write("integration-plan.md", PLAN_V1)
        val v2 = ws.write("integration-plan.md", PLAN_V2)
        val (added, removed) = LineDiff.counts(PLAN_V1, PLAN_V2)

        fun step(i: Int, kind: StepKind, payload: String, tool: String, input: String, output: String, url: String? = null, path: String? = null, t: Long) =
            Step("seed-$i", kind, payload, tool, input, output, url, path, StepStatus.DONE, null, t, t + 400 + i * 37L, if (kind == StepKind.SHELL) 0 else null)

        val t1 = now - 1000L * 60 * 14
        val steps1 = listOf(
            step(1, StepKind.SEARCH, "android terminal sandbox tinyfish", "web_search", "{\"query\": \"android terminal sandbox tinyfish\"}",
                "termux/termux-app - GitHub\nhttps://github.com/termux/termux-app\n\nSearch API - TinyFish\nhttps://docs.tinyfish.ai/search-api", t = t1),
            step(2, StepKind.SEARCH, "Android terminal sandbox architecture app-private storage isolation", "web_search",
                "{\"query\": \"Android terminal sandbox architecture app-private storage isolation\"}",
                "Application Sandbox | Android Open Source Project\nhttps://source.android.com/docs/security/app-sandbox", t = t1 + 900),
            step(3, StepKind.FETCH, "github.com", "web_fetch", "{\"url\": \"https://github.com/termux/termux-app\"}",
                "# Termux\nTermux combines terminal emulation with a Linux package collection.", url = "https://github.com/termux/termux-app", t = t1 + 1800),
            step(4, StepKind.FETCH, "docs.tinyfish.ai", "web_fetch", "{\"url\": \"https://docs.tinyfish.ai/search-api\"}",
                "# Search API\nGET https://api.search.tinyfish.ai?query=...", url = "https://docs.tinyfish.ai/search-api", t = t1 + 2600),
            step(5, StepKind.FETCH, "developer.android.com", "web_fetch", "{\"url\": \"https://developer.android.com/privacy-and-security/keystore\"}",
                "# Android Keystore system\nKey material never enters the application process.", url = "https://developer.android.com/privacy-and-security/keystore", t = t1 + 3400),
            step(6, StepKind.FETCH, "source.android.com", "web_fetch", "{\"url\": \"https://source.android.com/docs/security/app-sandbox\"}",
                "# Application Sandbox\nEach app gets a unique Linux UID.", url = "https://source.android.com/docs/security/app-sandbox", t = t1 + 4100),
            step(7, StepKind.SHELL, "python make_plan.py", "shell", "python make_plan.py", "wrote integration-plan.md", t = t1 + 4900),
            step(8, StepKind.FILE_WRITE, "integration-plan.md", "write_file", "{\"path\": \"integration-plan.md\"}\n\n$PLAN_V1",
                "wrote ${PLAN_V1.length} bytes to /integration-plan.md", path = "/integration-plan.md", t = t1 + 5600),
        )
        val sources1 = listOf(
            SourceRef("https://github.com/termux/termux-app", "github.com", "termux/termux-app"),
            SourceRef("https://docs.tinyfish.ai/search-api", "docs.tinyfish.ai", "Search API - TinyFish"),
            SourceRef("https://developer.android.com/privacy-and-security/keystore", "developer.android.com", "Android Keystore system"),
            SourceRef("https://source.android.com/docs/security/app-sandbox", "source.android.com", "Application Sandbox"),
            SourceRef("https://docs.tinyfish.ai/fetch-api", "docs.tinyfish.ai", "Fetch API - TinyFish"),
        )
        val t2 = now - 1000L * 60 * 6
        val steps2 = listOf(
            step(9, StepKind.FILE_READ, "integration-plan.md", "read_file", "{\"path\": \"integration-plan.md\"}", PLAN_V1, path = "/integration-plan.md", t = t2),
            step(10, StepKind.PERMISSION, "pip install markdown-it-py", "permission", "pip install markdown-it-py", "allowed by user", t = t2 + 700)
                .copy(risk = "package install"),
            step(11, StepKind.SHELL, "pip install markdown-it-py", "shell", "pip install markdown-it-py",
                "Collecting markdown-it-py\nInstalling markdown-it-py into /workspace/.usr\nSuccessfully installed markdown-it-py", t = t2 + 1500),
            step(12, StepKind.FILE_WRITE, "integration-plan.md", "write_file", "{\"path\": \"integration-plan.md\"}\n\n$PLAN_V2",
                "wrote ${PLAN_V2.length} bytes to /integration-plan.md", path = "/integration-plan.md", t = t2 + 2300),
        )
        val turns = listOf(
            Turn("seed-u1", Role.USER, "Draft an integration plan for an Android terminal sandbox with TinyFish web access", createdAt = t1 - 2000),
            Turn(
                "seed-a1", Role.ASSISTANT, "Here's your file.", steps1,
                listOf(FileAttachment("/integration-plan.md", "integration-plan.md", null, PLAN_V1.trimEnd('\n').count { it == '\n' } + 1, 0, t1 + 5600)),
                sources1, t1 + 6000,
            ),
            Turn("seed-u2", Role.USER, "Add a rollout section", createdAt = t2 - 2000),
            Turn(
                "seed-a2", Role.ASSISTANT, "Added a **Rollout** section with three phases and two open tasks.", steps2,
                listOf(FileAttachment("/integration-plan.md", "integration-plan.md", v2.snapshotId, added, removed, t2 + 2300)),
                emptyList(), t2 + 2600,
            ),
        )
        return Session("seed", "Integration plan", turns, t1 - 2000)
    }
}
