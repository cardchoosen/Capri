package ai.capri.app

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Bounded, offset-scoped writes. Repeating an acknowledged chunk never appends it twice. */
internal object UploadChunks {
    const val MAX_CHUNK = 1024 * 1024
    fun append(file: File, size: Long, offset: Long, bytes: ByteArray, sha256: String? = null): Long {
        require(bytes.isNotEmpty() && bytes.size <= MAX_CHUNK && offset >= 0 && offset <= size - bytes.size) { "Invalid upload chunk range." }
        if(sha256 != null) require(sha256.matches(Regex("[a-f0-9]{64}")) && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)} == sha256) { "Upload chunk checksum mismatch." }
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())) { "Upload file unavailable." }
        RandomAccessFile(file,"rw").use { output ->
            val length=output.length()
            require(length <= size && offset <= length) { "Upload offset mismatch; read status before retrying." }
            output.seek(offset)
            if(offset < length) {
                require(offset + bytes.size <= length) { "Upload chunk overlaps an incomplete write; read status before retrying." }
                val previous=ByteArray(bytes.size);output.readFully(previous)
                require(MessageDigest.isEqual(previous,bytes)) { "Repeated upload chunk contains different bytes." }
            }else output.write(bytes)
            return output.length()
        }
    }
}
