package ai.capri.host

import org.json.JSONArray
import org.json.JSONObject

internal class TurnEvents(private val id: String) {
    private val buffer = ArrayDeque<Pair<Long, String>>()
    private var characters = 0
    private var sequence = 0L

    @Synchronized fun publish(event: JSONObject): JSONObject {
        event.put("turnId", id).put("seq", ++sequence)
        val encoded = event.toString()
        buffer.addLast(sequence to encoded)
        characters += encoded.length
        while (characters > 256 * 1024 || buffer.size > 512) characters -= buffer.removeFirst().second.length
        return event
    }

    @Synchronized fun after(cursor: Long): JSONObject = JSONObject()
        .put("events", JSONArray(buffer.filter { it.first > cursor }.map { JSONObject(it.second) }))
        .put("gap", cursor < (buffer.firstOrNull()?.first ?: (sequence + 1)) - 1)
        .put("cursor", sequence)
}
