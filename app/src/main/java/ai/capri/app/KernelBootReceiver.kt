package ai.capri.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Unlocked boot only; restore an opted-in idle host, never replay interrupted tasks. */
class KernelBootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED)return
        try {
            if(KernelHost.enabled(context) && KernelHost.restoreAfterBoot(context)) {
                ContextCompat.startForegroundService(context,Intent(context,AgentTaskService::class.java))
                ai.capri.app.agent.reliability.ReliabilityAndroid.schedule(context,true)
            }
            if(Store(context).remoteDeviceEnabled() && Store(context).remoteDeviceConfig()!=null)
                ContextCompat.startForegroundService(context,Intent(context,ai.capri.app.remote.DeviceNodeService::class.java))
        }catch(error:Exception) {
            android.util.Log.w("CapriKernel","boot_host_start_failed type=${error.javaClass.simpleName}")
        }
    }
}
