package ai.capri.app.terminal

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

/** Public Android signals cannot certify OEM-specific background/freezing policies. */
internal object TerminalBackgroundPolicy {
    fun status(context: Context): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        return JSONObject().put("background_restricted", activity.isBackgroundRestricted)
            .put("battery_optimization_exempt", power.isIgnoringBatteryOptimizations(context.packageName))
            .put("power_save_mode", power.isPowerSaveMode)
            .put("vendor_policy_verified", false)
            .put("note", "Android/OEM policies may pause background tasks. Allow background activity in Capri battery settings; public APIs cannot verify vendor freeze policies.")
    }
    fun openSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
