package ai.capri.app.execution

import ai.capri.app.terminal.TerminalOutput
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** One byte cursor protocol for terminal, scripts, probes and installation logs. Never consume pipes. */
internal object OutputPage {
    fun read(file: File?, offset: Long, limit: Int, complete: Boolean): JSONObject {
        require(offset >= 0 && limit in 4..32000) { "Invalid output cursor or limit." }
        val available = file?.isFile == true
        val size = if (available) file!!.length() else 0L
        require(offset <= size) { "Output cursor exceeds retained log length; inspect output availability before restarting at 0." }
        val bytes = if (available) RandomAccessFile(file!!, "r").use { stream ->
            stream.seek(offset)
            ByteArray(minOf(limit.toLong(), size-offset).toInt()).also { stream.readFully(it) }
        } else ByteArray(0)
        val chunk = TerminalOutput.bounded(bytes, complete && offset + bytes.size == size)
        return JSONObject().put("output", chunk.text).put("offset", offset).put("next_offset", offset + chunk.consumed)
            .put("total_bytes", size).put("has_more", offset + chunk.consumed < size).put("complete", complete)
            .put("output_available", available).put("output_limit_bytes", 2_000_000)
    }
}
