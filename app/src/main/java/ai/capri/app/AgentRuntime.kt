package ai.capri.app

import android.app.Application
import android.os.Looper

/** One process-owned controller. UI, headless service and authenticated clients share its authority. */
internal object AgentRuntime {
    private var model:CapriModel?=null
    fun get(app:Application):CapriModel {
        check(Looper.myLooper()==Looper.getMainLooper()) { "Agent runtime access requires the main thread." }
        return model?:CapriModel(app).also{model=it;DebugBridge.attach(it)}
    }
    fun current():CapriModel?=model
}
