package ai.capri.core

/** Platform clocks and wakeups are supplied by the host; missed periods never cause catch-up bursts. */
object KernelSchedule {
    fun wakeDelay(dueAt:Long,now:Long,minimum:Long=0):Long {
        require(minimum>=0) { "Minimum wake delay cannot be negative." }
        return if(dueAt<=now)minimum else (dueAt-now).takeIf{it>=0}?.coerceAtLeast(minimum)?:Long.MAX_VALUE
    }
    fun nextInterval(finishedAt:Long,interval:Long):Long {
        require(finishedAt>=0 && interval>0 && finishedAt<=Long.MAX_VALUE-interval) { "Invalid recurring schedule bounds." }
        return finishedAt+interval
    }
}
