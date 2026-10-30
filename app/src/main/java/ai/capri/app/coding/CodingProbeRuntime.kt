package ai.capri.app.coding

import ai.capri.app.*
import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class CodingProbeRuntime(private val context:Context,private val runtime:PythonRuntime,private val fingerprint:(String)->String) {
    suspend fun probe(chat:String,input:JSONObject):JSONObject=withContext(Dispatchers.IO) {
            runtime.ensureSession(chat)
            val stamp=fingerprint(chat)
            require(chat.matches(Regex("[a-zA-Z0-9-]+"))){"Invalid coding analysis scope."}
            val run=File(context.filesDir,"coding-jobs/$chat/runtime/${UUID.randomUUID()}").apply{mkdirs()}
            context.assets.open("runtime/coding_probe.py").use { source->File(run,"probe.py").outputStream().use{source.copyTo(it)} }
            File(run,"input.json").writeText(input.toString());val output=File(run,"output.txt")
            val process=runtime.startProcess(chat,run,listOf("/usr/bin/python3","/run/capri/probe.py"),output,"Coding diagnostics")
            try {
                while(!process.waitFor(50,TimeUnit.MILLISECONDS)){currentCoroutineContext().ensureActive();check(output.length()<1_000_000){"Diagnostic output budget exceeded."}}
                check(process.exitValue()==0 && output.length()<1_000_000){"Diagnostic process failed; no clean analysis is established."}
                JSONObject(output.readText()).put("environment_fingerprint",stamp)
            }finally{try{OwnedProcesses.stop(process)}finally{if(!process.isAlive)RuntimeFiles.delete(run)}}
    }
}
