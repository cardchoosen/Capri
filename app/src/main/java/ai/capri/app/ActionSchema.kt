package ai.capri.app

import org.json.JSONArray
import org.json.JSONObject

/** Describe fields once, while keeping each action closed and independently required. */
internal object ActionSchema {
    fun build(properties: JSONObject, fields: Map<String, Set<String>>, required: (String) -> List<String>, overrides: (String) -> Map<String, JSONObject> = { emptyMap() }): JSONObject {
        val branches = fields.map { (action, allowed) ->
            val branch = JSONObject().put("action", JSONObject().put("enum", JSONArray(listOf(action))))
            allowed.forEach { branch.put(it, overrides(action)[it] ?: JSONObject()) }
            JSONObject().put("type", "object").put("additionalProperties", false).put("properties", branch)
                .put("required", JSONArray(listOf("action") + required(action)))
        }
        properties.put("action", JSONObject().put("type", "string").put("enum", JSONArray(fields.keys)))
        return JSONObject().put("type", "object").put("additionalProperties", false).put("properties", properties)
            .put("required", JSONArray(listOf("action"))).put("oneOf", JSONArray(branches))
    }
}
