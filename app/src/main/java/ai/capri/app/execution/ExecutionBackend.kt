package ai.capri.app.execution

/** Backends must describe what an exit actually proves. Local ownership cannot prove remote completion. */
internal interface ExecutionBackend {
    val id: String
    val durableRemoteReceipt: Boolean
    fun start(launch: () -> Process): Process
}
internal object LocalExecutionBackend : ExecutionBackend {
    override val id = "local_proot"
    override val durableRemoteReceipt = false
    override fun start(launch: () -> Process) = launch()
}
internal object LocalHostExecutionBackend : ExecutionBackend {
    override val id = "local_host"
    override val durableRemoteReceipt = false
    override fun start(launch: () -> Process) = launch()
}
internal object ExecutionBackends {
    fun resolve(id: String): ExecutionBackend = when (id) {
        "local_proot" -> LocalExecutionBackend
        "local_host" -> LocalHostExecutionBackend
        else -> throw IllegalArgumentException("Backend unavailable. SSH requires an explicit destination, independent remote receipts and remote cancellation; a disconnected transport is not completion.")
    }
}
