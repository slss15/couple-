package com.qimingnan.couple.data.keepalive

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

/** Starts OEM settings screens; every attempt is wrapped in try/catch and the last resort is the app details page. */
object OemIntentLauncher {
    private const val TAG = "CoupleKeepAlive"

    /** @return the label of the screen that opened. */
    fun open(context: Context, targets: List<SettingsTarget>): String {
        for (t in targets) {
            try {
                context.startActivity(toIntent(context, t))
                return t.label
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "not found: ${t.packageName}/${t.className}")
            } catch (e: SecurityException) {
                Log.d(TAG, "not allowed: ${t.packageName}/${t.className}")
            } catch (e: Exception) {
                Log.d(TAG, "failed: ${t.packageName}/${t.className}: $e")
            }
        }
        openAppDetails(context)
        return "应用详情"
    }

    fun openAppDetails(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Tries [intents] in order (each in try/catch), then the app details page. */
    fun openFirst(context: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                Log.d(TAG, "failed: ${i.action}: $e")
            }
        }
        openAppDetails(context)
        return false
    }

    private fun toIntent(context: Context, t: SettingsTarget): Intent {
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        val i = if (t.action != null) Intent(t.action) else Intent()
        if (t.packageName != null && t.className != null) i.component = ComponentName(t.packageName, t.className)
        else if (t.packageName != null) i.setPackage(t.packageName)
        if (t.packageUri) i.data = Uri.parse("package:${context.packageName}")
        t.extras.forEach { (k, v) ->
            i.putExtra(k, v.replace(SettingsTarget.PKG, context.packageName).replace(SettingsTarget.LABEL, label))
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
