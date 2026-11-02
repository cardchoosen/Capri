package ai.capri.app.environment

import java.io.File
import java.security.MessageDigest

/** Small watched files include content: same-size edits and preserved timestamps must invalidate evidence. */
internal object EnvironmentStamp {
    fun of(file: File): String = try {
        val metadata = "${file.canonicalPath}:${file.exists()}:${file.length()}:${file.lastModified()}"
        if (!file.isFile || file.length() > 262144) metadata
        else {
            val bytes = file.inputStream().use { input ->
                val buffer=ByteArray(262145); var count=0
                while(count<buffer.size) { val n=input.read(buffer,count,buffer.size-count); if(n<0)break; count+=n }
                buffer.copyOf(count)
            }
            metadata + ":" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    } catch (_: Exception) { "${file.path}:unavailable" }
}
