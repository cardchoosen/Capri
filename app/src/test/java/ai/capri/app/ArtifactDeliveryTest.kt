package ai.capri.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtifactDeliveryTest {
    @Test fun stagedDeliverySurvivesRecoveryButUserCorrectionClearsIt() {
        val messages=JSONArray().put(JSONObject().put("role","system").put("content","rules"))
        ai.capri.app.agent.TaskState.ensure(messages,"Create a report",false)
        messages.getJSONObject(0).put(ArtifactDelivery.KEY,JSONArray().put(JSONObject().put("path","report.md").put("delivery_id","version")))
        val restored=RecoveryMessages.forResume(RecoveryMessages.sanitized(messages))
        assertEquals(1,ArtifactDelivery.pending(restored).size)
        ai.capri.app.agent.TaskState.userChanges(restored,listOf("Do not send the report yet"))
        assertTrue(ArtifactDelivery.pending(restored).isEmpty())
    }
    @Test fun recoveryCannotPublishTheSameSnapshotTwice() {
        val entries=mutableListOf(Entry("user","Report"))
        val item=JSONObject().put("path","deliveries/a/report.md").put("delivery_id","a")
        ArtifactDelivery.publish(entries,listOf(item));ArtifactDelivery.publish(entries,listOf(item))
        assertEquals(1,entries.count{it.role=="attachment"})
    }
}
