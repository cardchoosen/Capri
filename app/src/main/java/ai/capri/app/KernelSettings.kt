package ai.capri.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable internal fun KernelSettings(onBack:()->Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var status by remember { mutableStateOf(KernelPowerPolicy.status(context)) }
    var restore by remember { mutableStateOf(KernelHost.restoreAfterBoot(context)) }
    DisposableEffect(owner,context) {
        fun refresh(){status=KernelPowerPolicy.status(context);restore=KernelHost.restoreAfterBoot(context)}
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME)refresh() }
        val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){refresh()}}
        val filter=IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED).apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        owner.lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context,receiver,filter,ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { owner.lifecycle.removeObserver(observer);context.unregisterReceiver(receiver) }
    }
    ScrollDetail("内核与后台运行",onBack) {
        ConfigIntro("进程级内核 · 手机与 Web 共用", "页面关闭后仍可接收任务；只有活跃任务持有 CPU 唤醒锁，空闲时释放。")
        ConfigGroup("当前状态") {
            ConfigFact("内核宿主",if(status.optBoolean("host_available")) "已启动" else "未就绪")
            ConfigFact("CPU 唤醒锁",if(status.optBoolean("cpu_lease_held")) "活跃任务持有" else "已释放")
            ConfigFact("电池优化",if(status.optBoolean("battery_optimization_exempt")) "已允许深度省电期间继续运行" else "未豁免 · 深度省电可能暂停 CPU 和网络")
            ConfigFact("系统后台限制",if(status.optBoolean("background_restricted")) "受限制，请在系统设置允许后台运行" else "未检测到限制")
            ConfigFact("设备状态",if(status.optBoolean("device_idle")) "深度待机" else if(status.optBoolean("power_save_mode")) "省电模式" else "正常")
        }
        ConfigGroup("后台运行权限", "长时间黑屏运行需要系统允许电池优化豁免。点击后由系统确认，不在应用启动时自动弹出。") {
            Button(onClick={KernelPowerPolicy.requestExemption(context)}) { Text(if(status.optBoolean("battery_optimization_exempt")) "管理系统电池设置" else "允许长时间后台运行") }
            TextButton(onClick={KernelPowerPolicy.openSettings(context)}) { Text("打开应用系统设置") }
        }
        ConfigGroup("设备重启后") {
            ConfigToggle("解锁后恢复内核待命", "默认关闭。启用后，设备重启并解锁可恢复待命与现有调度；中断任务仍需显式继续，不自动重放工具。",restore) {
                KernelHost.setRestoreAfterBoot(context,it);restore=it
            }
        }
        ConfigHelp("运行与恢复边界") {
            Text("模型网络失败最多重试 30 秒，超时保留检查点。恢复网络不会自动重放可能已经产生效果的操作。")
            Text("强行停止应用后需重新打开。首次解锁前不启动；部分厂商还需要允许自启动或关闭后台冻结。系统状态不能证明所有厂商策略均已解除。")
            Text("通知中的“停止任务”保留内核待命；“关闭内核”会停止接入和调度，直到重新打开 Capri。")
        }
    }
}
