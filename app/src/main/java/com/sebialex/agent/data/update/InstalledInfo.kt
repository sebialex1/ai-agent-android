package com.sebialex.agent.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Version and signing certificate of this installed app, for the update check. */
object InstalledInfo {
    fun of(context: Context): InstalledApp {
        val pm = context.packageManager
        val pkg = context.packageName
        val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val code = when {
            info == null -> 0L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
            else -> @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        return InstalledApp(code, info?.versionName ?: "dev", certSha256(context))
    }

    /** SHA-256 of the signing certificate (lowercase hex), as apksigner prints it. */
    @Suppress("DEPRECATION")
    private fun certSha256(context: Context): String? = runCatching {
        val pm = context.packageManager
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            // The certificate that signed this APK (after any key rotation, the current one).
            signing?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        } ?: return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
