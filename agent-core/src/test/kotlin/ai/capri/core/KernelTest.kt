package ai.capri.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class KernelTest {
    @Test fun ownershipCancellationAndLazyCleanupNeedNoUi()=runBlocking {
        val finished=mutableListOf<String>();val tasks=KernelTasks(Dispatchers.Unconfined){finished+=it}
        val stopped=CompletableDeferred<Unit>()
        val first=tasks.create("chat"){try{awaitCancellation()}finally{stopped.complete(Unit)}}
        first.start()
        try{tasks.create("chat"){fail("Duplicate task ran")};fail()}catch(_:IllegalStateException){}
        val lazy=tasks.create("scheduled"){fail("Cancelled lazy task ran")};lazy.cancel();lazy.join()
        assertFalse(tasks.jobs.containsKey("scheduled"))
        tasks.cancelAll();first.join();stopped.await()
        assertTrue(tasks.jobs.isEmpty());assertEquals(setOf("scheduled","chat"),finished.toSet())
        val closing=tasks.create("closing"){awaitCancellation()};closing.start()
        tasks.close();closing.join();assertTrue(tasks.jobs.isEmpty());assertTrue("closing" in finished)
    }
    @Test fun scheduleSkipsMissedPeriodsAndBoundsWakeDelay() {
        assertEquals(0L,KernelSchedule.wakeDelay(10,20))
        assertEquals(60L,KernelSchedule.wakeDelay(10,20,60))
        assertEquals(100L,KernelSchedule.wakeDelay(120,20))
        assertEquals(1010L,KernelSchedule.nextInterval(1000,10))
        try{KernelSchedule.nextInterval(Long.MAX_VALUE,1);fail()}catch(_:IllegalArgumentException){}
    }
}
