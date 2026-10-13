package com.sebialex.agent

import android.app.Application
import com.sebialex.agent.data.llm.OpenRouterBrain
import com.sebialex.agent.data.persistence.FileAuditLog
import com.sebialex.agent.data.persistence.SessionStore
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.data.shell.SandboxShell
import com.sebialex.agent.data.web.RoutingWebGateway
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.agent.AgentOrchestrator
import com.sebialex.agent.domain.agent.RoutingBrain
import com.sebialex.agent.domain.agent.ScriptedBrain
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.tools.ToolExecutor

/** Manual dependency container. */
class AppContainer(app: Application) {
    val settings = SecureSettings(app)
    val workspace = Workspace(app.filesDir)
    val shell = SandboxShell(workspace)
    val sessions = SessionStore(app.filesDir)
    private val audit = FileAuditLog(app.filesDir)
    private val web = RoutingWebGateway(keyProvider = { settings.tinyFishKey() })
    val policy = PermissionPolicy(askBeforeWebAccess = { settings.askBeforeWeb() })

    private val brain = RoutingBrain(
        openRouterKey = { settings.openRouterKey() },
        model = { settings.model() },
        remote = { key, model -> OpenRouterBrain(key, model) },
        local = ScriptedBrain(),
    )

    fun newOrchestrator() = AgentOrchestrator(
        brain = brain,
        tools = ToolExecutor(workspace, shell, web),
        policy = policy,
        workspace = workspace,
        audit = audit,
    )
}

class AgentApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
