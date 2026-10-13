package com.sebialex.agent

import android.app.Application
import com.sebialex.agent.data.llm.OpenRouterBrain
import com.sebialex.agent.data.llm.OpenRouterModels
import com.sebialex.agent.data.llm.ReasoningRules
import com.sebialex.agent.data.persistence.FileAuditLog
import com.sebialex.agent.data.persistence.SessionStore
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.data.shell.SandboxShell
import com.sebialex.agent.data.web.RoutingWebGateway
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.agent.AgentOrchestrator
import com.sebialex.agent.domain.agent.RoutingBrain
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.tools.ToolExecutor

/**
 * Everything that belongs to one conversation: its own workspace directory (and snapshots),
 * shell, tool executor (which carries the shell's working directory) and audit log.
 * Runtimes are never shared between conversations.
 */
class SessionRuntime(val sessionId: String, dir: java.io.File, web: RoutingWebGateway) {
    val workspace = Workspace(dir)
    val shell = SandboxShell(workspace)
    val audit = FileAuditLog(dir)
    val tools = ToolExecutor(workspace, shell, web)
}

/** Manual dependency container. */
class AppContainer(app: Application) {
    val settings = SecureSettings(app)
    val sessions = SessionStore(app.filesDir)
    private val web = RoutingWebGateway(keyProvider = { settings.tinyFishKey() })
    val policy = PermissionPolicy(askBeforeWebAccess = { settings.askBeforeWeb() })
    private val runtimes = HashMap<String, SessionRuntime>()

    /** Live OpenRouter model metadata, used for per-model reasoning support. */
    val models = OpenRouterModels(apiKey = { settings.openRouterKey() })

    private val brain = RoutingBrain(
        openRouterKey = { settings.openRouterKey() },
        model = { settings.model() },
        remote = { key, model ->
            OpenRouterBrain(key, model, reasoningFields = {
                // Read the model's live reasoning metadata before every chat request.
                ReasoningRules.requestFields(models.reasoning(model), settings.reasoning(model))
            })
        },
    )

    @Synchronized
    fun runtime(sessionId: String): SessionRuntime =
        runtimes.getOrPut(sessionId) { SessionRuntime(sessionId, sessions.dirFor(sessionId), web) }

    @Synchronized
    fun forget(sessionId: String) {
        runtimes.remove(sessionId)
    }

    @Synchronized
    fun forgetAll() {
        runtimes.clear()
    }

    fun newOrchestrator(sessionId: String): AgentOrchestrator {
        val rt = runtime(sessionId)
        return AgentOrchestrator(
            brain = brain,
            tools = rt.tools,
            policy = policy,
            workspace = rt.workspace,
            audit = rt.audit,
        )
    }
}

class AgentApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
