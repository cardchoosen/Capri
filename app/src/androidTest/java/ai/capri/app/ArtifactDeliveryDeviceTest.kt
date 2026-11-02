package ai.capri.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ArtifactDeliveryDeviceTest {
    @Test fun onlyLastCheckedRevisionIsPublishedAsAnImmutableSnapshot() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"artifact-delivery-fixture").apply{mkdirs()}
        try {
            val workspace=Workspace(root);val file=workspace.file("fixture","report.md").apply{parentFile!!.mkdirs();writeText("# Draft\n")}
            val messages=JSONArray().put(JSONObject().put("role","system").put("content","rules"))
            val entries=mutableListOf(Entry("user","Create a report"))
            ArtifactDelivery.stage(messages,workspace,"fixture","report.md","Report")
            assertTrue(entries.none{it.role=="attachment"})
            file.writeText("# Final report\nChecked facts.\n")
            assertNotNull(ArtifactDelivery.issue(messages,workspace,"fixture"))
            try{ArtifactDelivery.prepare(messages,workspace,"fixture");fail()}catch(_:ArtifactDelivery.Changed){}
            ArtifactDelivery.stage(messages,workspace,"fixture","/workspace/report.md","Final report")
            assertEquals(1,ArtifactDelivery.pending(messages).size)
            val prepared=ArtifactDelivery.prepare(messages,workspace,"fixture")
            assertTrue(entries.none{it.role=="attachment"})
            ArtifactDelivery.publish(entries,prepared);ArtifactDelivery.publish(entries,prepared)
            assertEquals(1,entries.count{it.role=="attachment"})
            val snapshot=workspace.file("fixture",prepared.single().getString("path"))
            file.writeText("Later task revision")
            assertEquals("# Final report\nChecked facts.\n",snapshot.readText())
        }finally{root.deleteRecursively()}
    }
}
