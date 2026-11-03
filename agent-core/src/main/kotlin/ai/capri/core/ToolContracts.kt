package ai.capri.core

import org.json.JSONArray
import org.json.JSONObject

/** Shared contract construction. Hosts still own effect dispatch and authorization. */
object ToolContracts {
    fun spec(name: String, description: String, properties: JSONObject, required: List<String>): JSONObject = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", description).put("parameters", JSONObject().put("type", "object").put("additionalProperties", false).put("properties", properties).put("required", JSONArray(required))))
}
