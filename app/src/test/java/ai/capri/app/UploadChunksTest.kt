package ai.capri.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class UploadChunksTest {
    @get:Rule val temp=TemporaryFolder()
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Test fun repeatedAcknowledgementsDoNotDuplicateBytesAndInvalidWritesPreserveTheFile() {
        val file=temp.newFile();val first=ByteArray(UploadChunks.MAX_CHUNK){(it%251).toByte()}
        assertEquals(first.size.toLong(),UploadChunks.append(file,first.size+20L,0,first,hash(first)))
        assertEquals(first.size.toLong(),UploadChunks.append(file,first.size+20L,0,first,hash(first)))
        val tail=byteArrayOf(1,2,3)
        for(write in listOf<()->Unit>(
            {UploadChunks.append(file,first.size+20L,first.size+1L,tail)},
            {UploadChunks.append(file,first.size+20L,0,tail,"0".repeat(64))},
            {UploadChunks.append(file,first.size+20L,0,tail)},
            {UploadChunks.append(file,first.size+20L,first.size-1L,tail)})) {
            try{write();fail()}catch(_:IllegalArgumentException){}
            assertArrayEquals(first,file.readBytes())
        }
        UploadChunks.append(file,first.size+20L,first.size.toLong(),tail,hash(tail));assertEquals(first.size+3L,file.length())
    }
}
