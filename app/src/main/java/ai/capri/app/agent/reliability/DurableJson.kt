package ai.capri.app.agent.reliability

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** One process-wide lock per store, atomic publication and explicit corruption failures. */
internal class DurableJson(private val file: File) {
    companion object { private val locks=ConcurrentHashMap<String,Any>() }
    private val lock=locks.getOrPut(file.canonicalPath) { Any() }
    fun <T> access(block: (JSONObject)->T): T = synchronized(lock) {
        var part: File?=file
        repeat(3) { val current=part;require(current==null || !Files.isSymbolicLink(current.toPath())) { "Store path cannot contain symbolic links." };part=current?.parentFile }
        val data=if(file.exists())JSONObject(file.readText())else JSONObject()
        val before=data.toString();val result=block(data)
        if(data.toString()!=before || !file.exists()) {
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            val staged=File(file.parentFile,file.name+".new")
            require(!Files.isSymbolicLink(staged.toPath())) { "Staged store cannot be a symbolic link." }
            java.io.FileOutputStream(staged).use { it.write(data.toString().toByteArray(Charsets.UTF_8));it.fd.sync() }
            Files.move(staged.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }
        result
    }
}
