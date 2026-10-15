package com.sebialex.agent.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * The Android side of [UpdateFlow]: hands a downloaded APK to the system package installer
 * through a FileProvider content URI (read access granted to the installer only). The user
 * confirms the install there; this app never installs anything silently.
 */
class AndroidUpdateHost(private val context: Context) : UpdateHost {
    override fun installed(): InstalledApp = InstalledInfo.of(context)

    /** `cache/updates/`: the only folder `res/xml/update_paths.xml` shares. */
    override val apkDir: File = File(context.cacheDir, "updates")

    // minSdk 26: the per-app "Install unknown apps" permission always exists.
    override fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openInstaller(apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, authority(context), apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    override fun openInstallSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        /** Matches the provider's `android:authorities` in the manifest. */
        fun authority(context: Context) = "${context.packageName}.updates"
    }
}
