package ai.capri.app

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Process-owned state shared by phone UI, web bridge and active task service. No Activity is retained. */
internal object AgentRuntime {
    private val owner=object:ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
    private var model:CapriModel?=null
    fun get(app:Application):CapriModel {
        check(Looper.myLooper()==Looper.getMainLooper()) { "Agent runtime access requires the main thread." }
        return model?:ViewModelProvider(owner,ViewModelProvider.AndroidViewModelFactory.getInstance(app))[CapriModel::class.java].also{model=it}
    }
    fun current():CapriModel?=model
}
