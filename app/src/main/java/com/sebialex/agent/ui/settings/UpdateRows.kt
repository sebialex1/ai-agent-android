package com.sebialex.agent.ui.settings

import com.sebialex.agent.data.update.InstalledApp
import com.sebialex.agent.data.update.UpdateResult
import com.sebialex.agent.data.update.UpdateState

/** The single action the Updates row may offer. */
enum class UpdateAction(val label: String) {
    CHECK("Check"),
    RETRY("Retry"),
    ALLOW_INSTALLS("Allow installs"),
    INSTALL("Install"),
    RELEASE_NOTES("Release notes"),
}

/** What the Updates row shows: a status line, a detail line, a progress bar while downloading, at most one action. */
data class UpdateRow(
    val status: String,
    val detail: String,
    /** 0..1 while downloading with a known size; [indeterminate] while the size is unknown. */
    val progress: Float? = null,
    val indeterminate: Boolean = false,
    val action: UpdateAction? = null,
    val warning: Boolean = false,
    /** For [UpdateAction.RELEASE_NOTES]: the page it opens. */
    val url: String? = null,
)

/** The text and action of every [UpdateState], kept out of Compose so it is unit-tested. */
object UpdateRows {
    fun row(state: UpdateState, installed: InstalledApp): UpdateRow {
        val thisBuild = "This install: ${installed.versionName} \u00b7 build ${installed.versionCode}"
        return when (state) {
            UpdateState.NotChecked -> UpdateRow("Not checked yet", thisBuild, action = UpdateAction.CHECK)
            UpdateState.Checking -> UpdateRow("Checking GitHub Releases\u2026", thisBuild)
            is UpdateState.UpToDate -> UpdateRow(
                "Up to date",
                thisBuild + (state.latest?.let { " \u00b7 latest release ${it.name}" } ?: ""),
                action = UpdateAction.CHECK,
            )
            UpdateState.Offline -> UpdateRow("No connection", "Check the network and try again.", action = UpdateAction.RETRY, warning = true)
            UpdateState.TimedOut -> UpdateRow(UpdateResult.TimedOut.MESSAGE, "Try again in a moment.", action = UpdateAction.RETRY, warning = true)
            is UpdateState.CheckFailed -> UpdateRow("Could not check", state.message, action = UpdateAction.RETRY, warning = true)
            is UpdateState.KeyChanged -> UpdateRow(
                "${state.release.name} is signed with a different key",
                "Android would refuse it as an update, so it was not downloaded. Back up first, uninstall, install it from the release page, then restore. Needed once.",
                action = UpdateAction.RELEASE_NOTES,
                warning = true,
                url = state.release.pageUrl,
            )
            is UpdateState.Downloading -> {
                val f = state.fraction
                UpdateRow(
                    "Downloading ${state.release.name}",
                    if (state.total > 0) "${mb(state.done)} of ${mb(state.total)}" else mb(state.done),
                    progress = f,
                    indeterminate = f == null,
                )
            }
            is UpdateState.DownloadFailed -> UpdateRow("Download failed", state.message, action = UpdateAction.RETRY, warning = true)
            is UpdateState.NeedsPermission -> UpdateRow(
                "${state.release.name} is downloaded",
                "Allow installs from this app; the installer then opens by itself.",
                action = UpdateAction.ALLOW_INSTALLS,
            )
            is UpdateState.Ready -> UpdateRow(
                "${state.release.name} is ready to install",
                if (state.handedOff) "Confirm in Android's installer. Closed it? Tap Install." else "Tap Install to open Android's installer.",
                action = UpdateAction.INSTALL,
            )
        }
    }

    private fun mb(bytes: Long) = "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
}
