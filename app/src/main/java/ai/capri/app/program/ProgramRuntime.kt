package ai.capri.app.program

import ai.capri.app.PythonRuntime
import ai.capri.app.Workspace
import android.content.Context
import java.io.File
import java.util.UUID

/** Uses the existing session venv, owned process tracking and RuntimeGate; no additional OS authority. */
internal class ProgramRuntime(private val context:Context,private val runtime:PythonRuntime,private val workspace:Workspace) {
    fun fingerprint(chat:String):String {
        val profile=ai.capri.app.environment.InstallationEnvironment.get(context)
        val home=workspace.folder(chat,"home")
        val paths=mutableListOf(File(home,".venv/pyvenv.cfg"),File(home,".venv/bin"))
        File(home,".venv/lib").listFiles().orEmpty().forEach { paths+=File(it,"site-packages") }
        val evidence=profile.fingerprint("ubuntu_shared","environment")+paths.joinToString { "${it.path}:${it.length()}:${it.lastModified()}" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(evidence.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    suspend fun launch(chat:String):Process {
        runtime.ensureSession(chat)
        val run=File(workspace.folder(chat,"runs"),"kernel-${UUID.randomUUID()}").apply { mkdirs() }
        context.assets.open("runtime/agent_kernel.py").use { input -> File(run,"kernel.py").outputStream().use { input.copyTo(it) } }
        return runtime.startProcess(chat,run,listOf("/usr/bin/python3","-u","/run/capri/kernel.py"),kind="Program kernel")
    }
}
