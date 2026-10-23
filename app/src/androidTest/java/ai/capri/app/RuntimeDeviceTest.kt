package ai.capri.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeDeviceTest {
    @Test fun pythonRunsOnPhoneWithDistinctSessionWorkspaces() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store(context)
        val runtime = PythonRuntime(context, store.workspace)
        val first = "device-test-first"
        val second = "device-test-second"
        try {
            val setup = runtime.install()
            assertTrue(setup, setup.contains("Capri Ubuntu ready"))
            val write = runtime.execute(first, "from pathlib import Path\nPath('proof.txt').write_text('Capri ARM64')\nprint(Path.cwd())\nprint(Path('proof.txt').read_text())")
            assertTrue(write, write.contains("exit=0"))
            assertEquals("Capri ARM64", store.workspace.file(first, "proof.txt").readText())
            val other = runtime.execute(second, "from pathlib import Path\nprint('isolated:', not Path('proof.txt').exists())")
            assertTrue(other, other.contains("isolated: True"))
            val canceled = withTimeoutOrNull(500) { runtime.execute(first, "import time\ntime.sleep(30)"); "finished" }
            assertNull(canceled)
        } finally {
            store.workspace.root(first).deleteRecursively()
            store.workspace.root(second).deleteRecursively()
        }
    }
}
