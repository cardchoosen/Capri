package ai.capri.app.terminal

import android.content.Context
import ai.capri.app.KernelPowerPolicy

/** Terminal and agent diagnostics share the same system policy snapshot. */
internal object TerminalBackgroundPolicy {
    fun status(context:Context)=KernelPowerPolicy.status(context)
    fun openSettings(context:Context)=KernelPowerPolicy.openSettings(context)
}
