package com.sebialex.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.R
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppSheet
import com.sebialex.agent.ui.components.SheetConfirm
import com.sebialex.agent.ui.components.SheetDivider
import com.sebialex.agent.ui.components.SheetOption
import com.sebialex.agent.ui.components.SheetSwitch
import com.sebialex.agent.ui.components.SheetTextField

private enum class OptionsMode { MENU, RENAME, CONFIRM_DELETE }

/**
 * Options of one conversation, in the app's own sheet style (no plain system menu): pin,
 * rename, the per-conversation "Memory of past chats" switch and delete. Opened only from the
 * "..." of a row in the conversations list (the chat's top bar has no second copy).
 *
 * Memory is bound to [SecureSettings.memoryChats], a flow that changes on every switch, so the
 * switch always moves (the old one in the "+" sheet read a snapshot that never changed).
 *
 * This switch is the only memory control in the app and only changes this chat; new chats
 * always start with memory on. Settings has no copy of it.
 */
@Composable
fun ChatOptionsSheet(
    sessionId: String,
    title: String,
    pinned: Boolean,
    settings: SecureSettings,
    onPin: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** Hidden for a chat that has no messages yet (nothing to pin, rename or delete). */
    saved: Boolean = true,
) {
    val memoryChats by settings.memoryChats.collectAsStateWithLifecycle()
    val memory = sessionId in memoryChats
    var mode by remember(sessionId) { mutableStateOf(OptionsMode.MENU) }
    AppSheet(
        onDismiss = onDismiss,
        title = title.ifBlank { "New conversation" },
        subtitle = if (pinned) "Pinned" else null,
    ) {
        when (mode) {
            OptionsMode.MENU -> {
                if (saved) {
                    SheetOption(
                        icon = R.drawable.ic_pin,
                        title = if (pinned) "Unpin" else "Pin",
                        onClick = { onPin(!pinned); onDismiss() },
                    )
                    SheetOption(R.drawable.ic_edit, "Rename", onClick = { mode = OptionsMode.RENAME })
                    SheetDivider()
                }
                SheetSwitch(
                    icon = R.drawable.ic_history,
                    title = "Memory of past chats",
                    checked = memory,
                    onChange = { settings.setConversationMemory(sessionId, it) },
                )
                if (saved) {
                    SheetDivider()
                    SheetOption(R.drawable.ic_trash, "Delete", onClick = { mode = OptionsMode.CONFIRM_DELETE }, danger = true)
                }
            }
            OptionsMode.RENAME -> SheetTextField(
                initial = title,
                placeholder = "Conversation title",
                onSubmit = { onRename(it); onDismiss() },
            )
            OptionsMode.CONFIRM_DELETE -> SheetConfirm(
                message = "Delete this conversation, its workspace files and its history? This cannot be undone.",
                confirm = "Delete",
                onConfirm = { onDelete(); onDismiss() },
                onCancel = { mode = OptionsMode.MENU },
            )
        }
    }
}
