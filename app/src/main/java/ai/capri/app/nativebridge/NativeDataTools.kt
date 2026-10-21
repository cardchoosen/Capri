package ai.capri.app.nativebridge

import android.app.Application
import android.content.ContentUris
import android.provider.CalendarContract
import android.provider.ContactsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal class NativeDataTools(private val app: Application, private val permissions: NativePermissions) {
    suspend fun contacts(params: JSONObject): JSONObject {
        permissions.requireGroup("contacts")
        val query = params.requiredText("query", 100)
        val limit = params.boundedInt("limit", 10, 1, 30)
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val rows = JSONArray()
        app.contentResolver.query(phone, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\' OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? ESCAPE '\\')",
            arrayOf("%$escaped%", "%$escaped%"), ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
            while (rows.length() < limit && cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                rows.put(JSONObject().put("name", cursor.getString(0)).put("number", cursor.getString(1)))
            }
        }
        return JSONObject().put("query", query).put("contacts", rows).put("limit", limit)
    }
    suspend fun calendar(params: JSONObject): JSONObject {
        permissions.requireGroup("calendar")
        val (start, end) = calendarRange(params)
        val limit = params.boundedInt("limit", 20, 1, 50)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, start); ContentUris.appendId(it, end) }.build()
        val rows = JSONArray()
        app.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
            null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
            while (rows.length() < limit && cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                rows.put(JSONObject().put("title", cursor.getString(0)).put("start_ms", cursor.getLong(1)).put("end_ms", cursor.getLong(2))
                    .put("location", cursor.getString(3) ?: "").put("all_day", cursor.getInt(4) == 1))
            }
        }
        return JSONObject().put("start_ms", start).put("end_ms", end).put("events", rows).put("limit", limit)
    }
}
