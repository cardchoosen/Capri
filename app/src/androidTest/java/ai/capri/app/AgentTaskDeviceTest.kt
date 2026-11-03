package ai.capri.app

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** No model calls or user-chat edits. Exercises actual foreground promotion, UI recreation and stop control. */
class AgentTaskDeviceTest {
    @Test fun activeTasksSurvivePageRecreationAndStopTogether() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm:CapriModel;scenario.onActivity{vm=AgentRuntime.get(it.application)}
        val ready=CountDownLatch(2);val finished=CountDownLatch(2);val tasks=mutableListOf<Job>()
        try {
            ins.runOnMainSync {
                assertTrue(vm.jobs.isEmpty())
                for(chat in listOf("foreground-fixture-a","foreground-fixture-b")) {
                    tasks+=vm.taskEngine.create(chat){
                        try { vm.taskEngine.promote(chat);ready.countDown();awaitCancellation() }
                        finally { finished.countDown() }
                    }.also{it.start()}
                }
            }
            assertTrue("Foreground promotion was not acknowledged",ready.await(12,TimeUnit.SECONDS))
            ins.runOnMainSync{assertTrue(vm.taskEngine.cpuLeaseHeld)}
            @Suppress("DEPRECATION")
            assertTrue(context.getSystemService(ActivityManager::class.java).getRunningServices(100).any{it.service.className==AgentTaskService::class.java.name&&it.foreground})
            scenario.recreate()
            scenario.onActivity{assertSame(vm,AgentRuntime.get(it.application));assertTrue(tasks.all{job->job.isActive})}
            // Destroy the page entirely; the process owner and task scope must remain alive.
            scenario.close()
            ins.runOnMainSync{assertTrue(tasks.all{it.isActive})}
            val notice=context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull{it.id==31_000_003}
            if(notice!=null)notice.notification.actions.first{it.title.toString()=="停止任务"}.actionIntent.send()
            else context.startService(Intent(context,AgentTaskService::class.java).setAction("ai.capri.app.agent.STOP"))
            assertTrue("Stop action did not cancel both tasks",finished.await(5,TimeUnit.SECONDS))
            runBlocking{tasks.forEach{it.join()}}
            ins.runOnMainSync{assertTrue(vm.jobs.isEmpty())}
            ins.runOnMainSync{assertTrue(vm.taskEngine.hostAvailable);assertFalse(vm.taskEngine.cpuLeaseHeld)}
        }finally{
            ins.runOnMainSync{tasks.forEach{it.cancel()}}
            runBlocking{tasks.forEach{it.join()}}
            scenario.close()
        }
    }
}
