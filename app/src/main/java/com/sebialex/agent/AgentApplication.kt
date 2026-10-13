package com.sebialex.agent

import android.app.Application
import com.sebialex.agent.data.llm.ModelReasoning
import com.sebialex.agent.data.llm.OllamaBrain
import com.sebialex.agent.data.llm.OllamaCloud
import com.sebialex.agent.data.llm.OpenRouterBrain
import com.sebialex.agent.data.local.LocalBrain
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.LocalModels
import com.sebialex.agent.data.local.ModelStore
import com.sebialex.agent.data.llm.OpenRouterModels
import com.sebialex.agent.data.llm.ReasoningRules
import com.sebialex.agent.data.persistence.ConversationMemoryReader
import com.sebialex.agent.data.persistence.FileAuditLog
import com.sebialex.agent.data.persistence.SessionStore
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.data.shell.SandboxShell
import com.sebialex.agent.data.shell.TermuxBridge
import com.sebialex.agent.data.shell.WorkspaceMirror
import com.sebialex.agent.data.web.RoutingWebGateway
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.agent.AgentOrchestrator
import com.sebialex.agent.domain.agent.BrainRoute
import com.sebialex.agent.domain.agent.RoutingBrain
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.tools.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Everything that belongs to one conversation: its own workspace directory (and snapshots),
 * the mirror of that workspace that Termux works in, shell, tool executor (which carries the
 * shell's working directory) and audit log. Runtimes are never shared between conversations.
 */
class SessionRuntime(
    val sessionId: String,
    dir: java.io.File,
    web: RoutingWebGateway,
    termux: TermuxBridge,
    sessions: SessionStore,
) {
    val workspace = Workspace(dir)
    val mirror = WorkspaceMirror(
        local = workspace.root,
        shared = java.io.File(java.io.File(termux.sharedRoot, sessionId), "workspace"),
        manifestFile = java.io.File(dir, "mirror.manifest"),
    )
    val shell = SandboxShell(workspace, termux, mirror)
    val audit = FileAuditLog(dir)
    /** Read-only view of the other conversations, used only when memory is on for this one. */
    val memory = ConversationMemoryReader(sessions, sessionId)
    val tools = ToolExecutor(workspace, shell, web, memory)
}

/** Manual dependency container. */
class AppContainer(app: Application) {
    val settings = SecureSettings(app)
    /** The real on-device environment: Termux's bash, apt/dpkg, pkg, Python and pip. */
    val termux = TermuxBridge(app)
    val sessions = SessionStore(app.filesDir)
    private val web = RoutingWebGateway(keyProvider = { settings.tinyFishKey() })
    val policy = PermissionPolicy(askBeforeWebAccess = { settings.askBeforeWeb() })
    private val runtimes = HashMap<String, SessionRuntime>()

    /** Background work that outlives any screen: model downloads and model loading. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** On-device model files (downloads, imports) and the engine that runs them on the GPU. */
    val modelStore = ModelStore(app, appScope)
    val localEngine = LocalEngine(modelStore, settings, appScope)

    /** Reasoning support of an on-device model: an on/off switch when it has a thinking mode. */
    private fun localReasoning(id: String): ModelReasoning {
        val model = LocalModels.resolve(id)
        val thinking = when {
            model == null -> false
            model.imported -> localEngine.capabilities(id)?.thinking ?: false
            else -> model.thinking
        }
        return ModelReasoning.local(id, thinking)
    }

    /** Ollama Cloud: hosted models at ollama.com, a second cloud provider next to OpenRouter. */
    val ollama = OllamaCloud(apiKey = { settings.ollamaKey() })

    /** Model-written conversation titles. */
    val titles = com.sebialex.agent.data.llm.TitleWriter(settings, ollama)

    /**
     * Live model metadata, used for per-model reasoning support. On-device models are answered
     * locally, Ollama Cloud models from ollama.com; everything else from OpenRouter's catalog.
     */
    val models = OpenRouterModels(
        apiKey = { settings.openRouterKey() },
        other = { id ->
            when {
                LocalModels.isLocal(id) -> localReasoning(id)
                OllamaCloud.isOllama(id) -> ollama.reasoning(id)
                else -> null
            }
        },
    )

    private val brain = RoutingBrain(
        openRouterKey = { settings.openRouterKey() },
        model = { settings.model() },
        remote = { key, model ->
            OpenRouterBrain(key, model, reasoningFields = {
                // Read the model's live reasoning metadata before every chat request.
                ReasoningRules.requestFields(models.reasoning(model), settings.reasoning(model))
            })
        },
        other = { model ->
            when {
                LocalModels.isLocal(model) -> BrainRoute(
                    label = "on-device model",
                    brain = LocalBrain(localEngine, modelStore, model, thinking = {
                        ReasoningRules.resolve(localReasoning(model), settings.reasoning(model)).on
                    }),
                )
                OllamaCloud.isOllama(model) -> BrainRoute(
                    label = "Ollama Cloud request",
                    brain = settings.ollamaKey()?.let { key ->
                        OllamaBrain(ollama, key, model, think = {
                            // Native `think` field from the model's /api/show metadata and the user's choice.
                            OllamaCloud.thinkValue(models.reasoning(model), settings.reasoning(model))
                        })
                    },
                    notice = OLLAMA_NO_KEY_NOTICE,
                )
                else -> null
            }
        },
    )

    @Synchronized
    fun runtime(sessionId: String): SessionRuntime =
        runtimes.getOrPut(sessionId) { SessionRuntime(sessionId, sessions.dirFor(sessionId), web, termux, sessions) }

    @Synchronized
    fun forget(sessionId: String) {
        runtimes.remove(sessionId)
        settings.forgetConversation(sessionId)
        // The shared Termux copy of a deleted conversation goes with it.
        Thread { runCatching { java.io.File(termux.sharedRoot, sessionId).deleteRecursively() } }.start()
    }

    @Synchronized
    fun forgetAll() {
        runtimes.clear()
        Thread { runCatching { termux.sharedRoot.listFiles()?.forEach { it.deleteRecursively() } } }.start()
    }

    fun newOrchestrator(sessionId: String): AgentOrchestrator {
        val rt = runtime(sessionId)
        return AgentOrchestrator(
            brain = brain,
            tools = rt.tools,
            policy = policy,
            workspace = rt.workspace,
            audit = rt.audit,
            autoApprove = { settings.autoApprove() },
            memoryEnabled = { settings.conversationMemory(sessionId) },
        )
    }
}

private const val OLLAMA_NO_KEY_NOTICE =
    "Add an Ollama Cloud API key in Settings (create one at ollama.com/settings/keys) to use this model. Nothing was executed."

class AgentApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
