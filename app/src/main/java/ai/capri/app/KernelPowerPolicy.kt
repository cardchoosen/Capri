package ai.capri.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

/** System-reported policy only. Exemption requires user/system consent, never an automatic prompt. */
internal object KernelPowerPolicy {
    fun status(context:Context):JSONObject {
        val power=context.getSystemService(PowerManager::class.java)
        return JSONObject().put("background_restricted",context.getSystemService(ActivityManager::class.java).isBackgroundRestricted)
            .put("battery_optimization_exempt",power.isIgnoringBatteryOptimizations(context.packageName))
            .put("device_idle",power.isDeviceIdleMode).put("power_save_mode",power.isPowerSaveMode)
            .put("kernel_enabled",KernelHost.enabled(context))
            .put("host_available",AgentRuntime.current()?.taskEngine?.hostAvailable==true)
            .put("cpu_lease_held",AgentRuntime.current()?.taskEngine?.cpuLeaseHeld==true)
            .put("vendor_policy_verified",false)
            .put("note","Doze can suspend network and CPU without battery optimization exemption. OEM restrictions may still apply. Interrupted effects are not replayed; network retries remain bounded to 30 seconds.")
    }
    fun requestExemption(context:Context) {
        if(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) { openSettings(context);return }
        val request=Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(request) }
        catch(_:android.content.ActivityNotFoundException) { openSettings(context) }
        catch(_:SecurityException) { openSettings(context) }
    }
    fun openSettings(context:Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
