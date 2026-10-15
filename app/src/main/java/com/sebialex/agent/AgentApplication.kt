package com.sebialex.agent

import android.app.Application
import com.sebialex.agent.data.llm.CustomProviders
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
    images: com.sebialex.agent.domain.tools.ImageGenerator? = null,
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
    /** One terminal for the agent and the user: same shell, working directory and history. */
    val terminal = com.sebialex.agent.data.shell.SharedTerminal(shell)
    val tools = ToolExecutor(workspace, shell, terminal, web, memory, images)
}

/** Manual dependency container. */
class AppContainer(app: Application) {
    val settings = SecureSettings(app)
    /** Keeps the process in the foreground (service + notification) while any agent run is active. */
    val runKeeper = com.sebialex.agent.data.background.RunKeeper(app)
    /** The real on-device environment: Termux's bash, apt/dpkg, pkg, Python and pip. */
    val termux = TermuxBridge(app)
    val sessions = SessionStore(app.filesDir)
    private val web = RoutingWebGateway(
        keyProvider = { settings.tinyFishKey() },
        rotate = { failed, reason -> settings.rotateKey(SecureSettings.PROVIDER_TINYFISH, failed, reason) },
    )
    val policy = PermissionPolicy(askBeforeWebAccess = { settings.askBeforeWeb() })
    private val runtimes = HashMap<String, SessionRuntime>()

    /** Background work that outlives any screen: model downloads and model loading. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** On-device model files (downloads, imports) and the engine that runs them on the GPU. */
    val modelStore = ModelStore(app, appScope)

    /** Backup folder + Auto Backup copy; restores the Auto Backup copy on the first start of a reinstall. */
    val backup = com.sebialex.agent.data.backup.BackupManager(app, settings, sessions, appScope)
    val localEngine = LocalEngine(modelStore, settings, appScope)

    /** Reasoning support of an on-device model: an on/off switch when it has a thinking mode. */
    private fun localReasoning(id: String): ModelReasoning {
        val model = LocalModels.resolve(id) ?: return ModelReasoning.unknown(id, "not an installed on-device model")
        if (!model.imported) return ModelReasoning.local(id, model.thinking, "on-device catalog (chat template)")
        // Imported .litertlm files: LiteRT-LM reports thinking support once the model is loaded.
        val thinking = localEngine.capabilities(id)?.thinking
            ?: return ModelReasoning.unknown(id, "load the model once to read its thinking support").copy(wire = com.sebialex.agent.data.llm.ReasoningWire.LOCAL)
        return ModelReasoning.local(id, thinking, "LiteRT-LM model metadata")
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
                // Manual override, saved model-list metadata, documented families, else unknown.
                CustomProviders.isCustom(id) -> com.sebialex.agent.data.llm.CustomReasoning.detect(
                    id, CustomProviders.parse(id)?.first?.let { settings.customProvider(it) }, settings.reasoningOverride(id),
                )
                else -> null
            }
        },
    )

    private val brain = routingBrain { settings.model() }

    /**
     * Sends each request to the provider of [model]'s current value: the chat model for the main
     * agent, a fixed model for a subagent. Same providers, keys and reasoning settings for both.
     */
    private fun routingBrain(model: () -> String) = RoutingBrain(
        keys = settings,
        model = model,
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
                    brain = null,
                    notice = OLLAMA_NO_KEY_NOTICE,
                    provider = SecureSettings.PROVIDER_OLLAMA,
                    brainFor = { key ->
                        OllamaBrain(ollama, key, model, think = {
                            // Native `think` field from the model's /api/show metadata and the user's choice.
                            OllamaCloud.thinkValue(models.reasoning(model), settings.reasoning(model))
                        })
                    },
                )
                CustomProviders.isCustom(model) -> customRoute(model)
                else -> null
            }
        },
    )

    /**
     * A model of a user-added OpenAI-compatible provider: the shared OpenAI-style brain pointed at
     * the provider's /chat/completions (no OpenRouter-only fields), with its own key ring. Local
     * servers that need no key (LM Studio, Ollama, llama.cpp) run without one.
     */
    private fun customRoute(model: String): BrainRoute {
        val (providerId, wireModel) = CustomProviders.parse(model)
            ?: return BrainRoute("custom provider request", null, notice = "This model id is not valid. Pick a model again in the + sheet.")
        val provider = settings.customProvider(providerId)
            ?: return BrainRoute("custom provider request", null, notice = "That provider was removed in Settings. Pick another model in the + sheet. Nothing was executed.")
        fun brain(key: String) = OpenRouterBrain(
            apiKey = key,
            model = wireModel,
            endpoint = CustomProviders.chatEndpoint(provider.baseUrl),
            provider = provider.name,
            openRouterExtras = false,
            replayReasoning = false,
            // Per-provider reasoning fields (reasoning_effort, Anthropic thinking); empty when unknown/unsupported.
            reasoningFields = { ReasoningRules.requestFields(models.reasoning(model), settings.reasoning(model)) },
        )
        val ring = CustomProviders.keyRing(providerId)
        if (settings.keyCount(ring) == 0) {
            val local = CustomProviders.isLocalAddress(provider.baseUrl)
            return BrainRoute(
                label = "${provider.name} request",
                brain = if (local) brain("sk-no-key") else null,
                notice = "Add an API key for ${provider.name} in Settings > Models to use this model. Nothing was executed.",
            )
        }
        return BrainRoute(
            label = "${provider.name} request",
            brain = null,
            notice = "Add an API key for ${provider.name} in Settings > Models. Nothing was executed.",
            provider = ring,
            brainFor = ::brain,
        )
    }

    @Synchronized
    fun runtime(sessionId: String): SessionRuntime =
        runtimes.getOrPut(sessionId) { SessionRuntime(sessionId, sessions.dirFor(sessionId), web, termux, sessions, images) }

    /**
     * generate_image: the provider and model picked in Settings > Models > Image generation
     * (OpenRouter's Image API or a custom provider's /images/generations), never a silent fallback.
     */
    private val images = com.sebialex.agent.data.llm.RoutedImageGenerator(settings)

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
        // Subagents share this conversation's workspace, tools, policy and audit log; each runs
        // on its own model and keeps its own transcript (see Subagents).
        val subagents = object : com.sebialex.agent.domain.agent.Subagents {
            override fun models() = settings.effectiveSubagentModels()
            override fun orchestrator(model: String) = AgentOrchestrator(
                brain = routingBrain { model },
                tools = rt.tools,
                policy = policy,
                workspace = rt.workspace,
                audit = rt.audit,
                autoApprove = { settings.autoApprove() },
                maxSteps = com.sebialex.agent.domain.agent.SubagentRuns.CHILD_MAX_STEPS,
            )
        }
        return AgentOrchestrator(
            brain = brain,
            tools = rt.tools,
            policy = policy,
            workspace = rt.workspace,
            audit = rt.audit,
            autoApprove = { settings.autoApprove() },
            memoryEnabled = { settings.conversationMemory(sessionId) },
            subagents = subagents,
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
