package com.sebialex.agent

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sebialex.agent.ui.chat.ChatScreen
import com.sebialex.agent.ui.chat.ChatViewModel
import com.sebialex.agent.ui.chat.ConversationsScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.ui.files.FileViewerScreen
import com.sebialex.agent.ui.settings.SettingsScreen
import com.sebialex.agent.ui.terminal.WorkspaceScreen
import com.sebialex.agent.ui.theme.AgentTheme
import com.sebialex.agent.ui.theme.Palette

sealed interface Screen {
    data object Chat : Screen
    data class File(val path: String, val baseSnapshot: String?) : Screen
    data object Settings : Screen
    data object Workspace : Screen
    data object Conversations : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as AgentApplication).container
        setContent {
            AgentTheme {
                AppRoot(container)
            }
        }
    }
}

@Composable
fun AppRoot(container: AppContainer) {
    val vm: ChatViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(container) as T
    })
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Chat)) }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun backToChat() { stack = listOf(Screen.Chat) }
    // Files and the workspace shell always belong to the conversation that is open.
    val chat by vm.state.collectAsStateWithLifecycle()
    val runtime = container.runtime(chat.session.id)

    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = {
                if (targetState is Screen.File) {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.98f, animationSpec = tween(180))) togetherWith fadeOut(tween(120))
                } else {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                }
            },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.Chat -> ChatScreen(
                    vm = vm,
                    onClose = { push(Screen.Conversations) },
                    onOpenFile = { path, base -> push(Screen.File(path, base)) },
                    onOpenWorkspace = { push(Screen.Workspace) },
                    onOpenSettings = { push(Screen.Settings) },
                )
                is Screen.File -> FileViewerScreen(runtime.workspace, screen.path, screen.baseSnapshot, onBack = ::pop)
                Screen.Settings -> SettingsScreen(
                    settings = container.settings,
                    models = container.models,
                    onBack = ::pop,
                    onClearConversations = { vm.clearAll(); backToChat() },
                )
                Screen.Workspace -> WorkspaceScreen(
                    shell = runtime.shell,
                    policy = container.policy,
                    onBack = ::pop,
                    onOpenFile = { push(Screen.File(it, null)) },
                )
                Screen.Conversations -> ConversationsScreen(
                    vm = vm,
                    onOpen = { vm.open(it); backToChat() },
                    onNew = { vm.newConversation(); backToChat() },
                    onStartWith = { text -> vm.newConversation(); vm.send(text); backToChat() },
                    onSettings = { push(Screen.Settings) },
                )
            }
        }
    }
}
