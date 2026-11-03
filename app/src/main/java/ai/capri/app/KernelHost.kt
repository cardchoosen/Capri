package ai.capri.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Explicit host stop survives health polling; only a new user launch enables it again. */
internal object KernelHost {
    private fun prefs(context:Context)=context.getSharedPreferences("kernel-host",Context.MODE_PRIVATE)
    fun restoreAfterBoot(context:Context)=prefs(context).getBoolean("restore_after_boot",false)
    fun setRestoreAfterBoot(context:Context,enabled:Boolean){prefs(context).edit().putBoolean("restore_after_boot",enabled).apply()}
    fun enabled(context:Context)=prefs(context).getBoolean("enabled",true)
    fun startFromUi(context:Context){
        prefs(context).edit().putBoolean("enabled",true).apply()
        ContextCompat.startForegroundService(context,Intent(context,AgentTaskService::class.java))
        ai.capri.app.agent.reliability.ReliabilityAndroid.schedule(context,true)
    }
    fun disable(context:Context){prefs(context).edit().putBoolean("enabled",false).apply();ai.capri.app.agent.reliability.ReliabilityAndroid.cancelAll(context)}
}
