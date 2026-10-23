package ai.capri.app
import android.content.Context
import java.io.File
internal object NativePythonPackages {
    fun prepare(context: Context): File = ManagedBundle.prepare(context, ManagedBundle.science)
}
