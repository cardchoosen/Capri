package ai.capri.app

import ai.capri.app.coding.*
import ai.capri.app.files.WorkspaceFileTools
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CodingIntelligenceTest {
    private fun test(block:(Workspace,CodingStore,WorkspaceFileTools)->Unit) {
        val root=Files.createTempDirectory("coding-intelligence").toFile();val w=Workspace(File(root,"sessions"));val s=CodingStore(File(root,"history"));val tools=WorkspaceFileTools(w,s)
        try{block(w,s,tools)}finally{root.deleteRecursively()}
    }
    private fun call(tools:WorkspaceFileTools,name:String,args:JSONObject,scope:String="chat")=JSONObject(tools.executeScoped("chat",scope,name,args,"operation"))
    @Test fun strictJsonRejectsLooseSyntaxAndDoesNotRunXmlEntities() {
        for(text in listOf("{'a':1}","[1,]","{a:1}","01","1١","true false","\"x\n\""))assertEquals(text,1,QuickDiagnostics.inspect("a.json",text).getJSONArray("diagnostics").length())
        assertEquals(0,QuickDiagnostics.inspect("a.json","{\"ok\":[true,null,1.2e3]}").getJSONArray("diagnostics").length())
        assertNotEquals("checked",QuickDiagnostics.inspect("a.xml","<x>".repeat(1000)+"</x>".repeat(1000)).getString("status"))
        assertEquals(1,QuickDiagnostics.inspect("a.xml","<!DOCTYPE foo [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><foo>&x;</foo>").getJSONArray("diagnostics").length())
    }
    @Test fun shiftedExistingDiagnosticIsNotReportedAsNew() {
        fun report(line:Int,anchor:String)=JSONObject().put("status","checked").put("diagnostics",JSONArray().put(JSONObject().put("source","pylsp").put("code","F821").put("severity","error").put("message","Undefined name").put("line",line).put("anchor",anchor)))
        val old=report(1,"print(missing)");val shifted=report(3,"print(missing)")
        val delta=QuickDiagnostics.delta(old,shifted);assertEquals(0,delta.getJSONArray("new").length());assertEquals(1,delta.getInt("existing_count"))
        val unknown=QuickDiagnostics.delta(JSONObject().put("status","unavailable"),shifted)
        assertFalse(unknown.getBoolean("baseline_available"));assertEquals(1,unknown.getJSONArray("unclassified").length())
    }
    @Test fun realLspFixtureOnlyReportsTheIntroducedName() {
        val source=listOf(File("../tools/fixtures/coding-lsp-findings.json"),File("tools/fixtures/coding-lsp-findings.json")).first{it.isFile}
        val fixture=JSONObject(source.readText());val delta=QuickDiagnostics.delta(fixture.getJSONObject("before"),fixture.getJSONObject("after"))
        assertEquals(1,delta.getInt("existing_count"));assertEquals(1,delta.getJSONArray("new").length());assertTrue(delta.getJSONArray("new").getJSONObject(0).getString("message").contains("new_missing"))
    }
    @Test fun writeReturnsSnapshotAndOnlyIntroducedSyntaxErrors()=test { w,s,t ->
        w.file("chat","a.json").writeText("{\"x\":1}")
        val result=call(t,"write_file",JSONObject().put("path","a.json").put("content","{\"x\":}"))
        assertEquals("ok",result.getString("status"));assertTrue(result.getBoolean("result_incomplete"))
        val snapshot=result.getJSONArray("coding_snapshots").getJSONObject(0)
        assertEquals("committed",snapshot.getString("state"));assertEquals(1,snapshot.getJSONObject("quick_diagnostics").getInt("new_errors"))
        assertEquals("{\"x\":1}",s.content("chat",snapshot,"before")!!.toString(Charsets.UTF_8))
        assertEquals("{\"x\":}",w.file("chat","a.json").readText())
    }
    @Test fun repeatedUnchangedWritesDoNotCreateSnapshotsOrQueueWork()=test { w,s,t ->
        val old="{\"value\":\""+"x".repeat(16000)+"\"}";w.file("chat","a.json").writeText(old)
        val start=System.nanoTime();val same=call(t,"write_file",JSONObject().put("path","a.json").put("content",old));val unchangedMs=(System.nanoTime()-start)/1_000_000.0
        assertFalse(same.getBoolean("changed"));assertEquals(0,s.list("chat").length())
        val changedStart=System.nanoTime();val update=call(t,"write_file",JSONObject().put("path","a.json").put("content",old.replace('x','y')));val changedMs=(System.nanoTime()-changedStart)/1_000_000.0
        assertTrue(update.getBoolean("changed"));assertEquals(1,s.list("chat").length())
        println("CODING_FIXTURE "+JSONObject().put("source_bytes",old.toByteArray().size).put("unchanged_ms",unchangedMs).put("snapshot_write_ms",changedMs).put("device_used",false))
    }
    @Test fun restoreIsVersionGuardedAndPreservesLaterUserChanges()=test { w,s,t ->
        w.file("chat","a.txt").writeText("before")
        val result=call(t,"edit_file",JSONObject().put("path","a.txt").put("old_text","before").put("new_text","after"))
        val id=result.getJSONArray("coding_snapshots").getJSONObject(0).getString("id")
        w.file("chat","a.txt").writeText("user edit")
        val rejected=call(t,"coding_restore",JSONObject().put("snapshot_id",id).put("expected_sha256",CodingStore.hash("user edit".toByteArray())))
        assertEquals("error",rejected.getString("status"));assertEquals("user edit",w.file("chat","a.txt").readText())
        w.file("chat","a.txt").writeText("after")
        val restored=call(t,"coding_restore",JSONObject().put("snapshot_id",id).put("expected_sha256",result.getString("sha256")))
        assertEquals("ok",restored.getString("status"));assertEquals("before",w.file("chat","a.txt").readText());assertEquals(2,s.list("chat").length())
    }
    @Test fun patchSnapshotsCoverAdditionDeletionAndDryRunDoesNotCreateVersions()=test { w,s,t ->
        w.file("chat","old.txt").writeText("preserve me\n")
        val patch="*** Begin Patch\n*** Delete File: old.txt\n*** Add File: new.txt\n+new\n*** End Patch"
        call(t,"apply_patch",JSONObject().put("patch",patch).put("dry_run",true));assertEquals(0,s.list("chat").length())
        val result=call(t,"apply_patch",JSONObject().put("patch",patch));assertEquals("ok",result.getString("status"));assertEquals(2,result.getJSONArray("coding_snapshots").length())
        val deleted=(0 until s.list("chat").length()).map{s.list("chat").getJSONObject(it)}.first{it.getString("path")=="old.txt"}
        assertEquals("absent",deleted.getString("after_sha256"))
        assertEquals("ok",call(t,"coding_restore",JSONObject().put("snapshot_id",deleted.getString("id")).put("expected_sha256","absent")).getString("status"))
        assertEquals("preserve me\n",w.file("chat","old.txt").readText())
    }
    @Test fun fileAndDiffReferencesAreExactAndScopeBound()=test { w,s,t ->
        w.file("chat","a.txt").writeText("one\ntwo\n")
        val reference=call(t,"coding_reference",JSONObject().put("kind","file").put("path","a.txt").put("start_line",2))
        assertEquals("two\n",call(t,"read_file",reference.getJSONObject("read_args")).getString("content"))
        val edited=call(t,"edit_file",JSONObject().put("path","a.txt").put("old_text","two").put("new_text","three"))
        assertEquals("error",call(t,"read_file",reference.getJSONObject("read_args")).getString("status"))
        val id=edited.getJSONArray("coding_snapshots").getJSONObject(0).getString("id")
        val diff=call(t,"coding_reference",JSONObject().put("kind","diff").put("snapshot_id",id));assertTrue(diff.getString("text").contains("-two"));assertTrue(diff.getString("text").contains("+three"))
        val dir=call(t,"coding_reference",JSONObject().put("kind","directory"));assertTrue(dir.has("metadata_fingerprint"))
        val branch="chat:history:12345678-1234-1234-1234-123456789abc"
        assertEquals("error",call(t,"coding_restore",JSONObject().put("snapshot_id",id).put("expected_sha256",edited.getString("sha256")),branch).getString("status"))
    }
    @Test fun pendingSnapshotSurvivesBudgetPressureAndCorruptBytesCannotRestore()=test { w,s,_ ->
        val record=s.stage("chat","x.txt","before".toByteArray(),"after".toByteArray(),null,"operation")
        try{s.reserve("chat",65L*1024*1024,1);fail()}catch(_:IllegalStateException){}
        assertEquals("pending",s.get("chat",record.getString("id")).getString("state"))
        assertEquals("before",s.content("chat",record,"before")!!.toString(Charsets.UTF_8))
        File(w.sessionDirectory("chat").parentFile!!.parentFile,"history/chat/revisions/main/${record.getString("id")}.before").writeText("corrupt")
        try{s.content("chat",record,"before");fail()}catch(_:IllegalStateException){}
    }
    @Test fun unmanagedChangeDuringSnapshotIsPreserved()=test { w,s,t ->
        w.file("chat","a.txt").writeText("before");var altered=false
        val raw=t.executeScoped("chat","chat","write_file",JSONObject().put("path","a.txt").put("content","planned"),"operation") {
            if(!altered && s.list("chat").length()>0){altered=true;w.file("chat","a.txt").writeText("user edit")}
        }
        assertTrue(altered);assertEquals("conflict",JSONObject(raw).getJSONObject("error").getString("code"));assertEquals("user edit",w.file("chat","a.txt").readText())
        assertEquals("pending",s.list("chat").getJSONObject(0).getString("state"))
    }
    @Test fun snapshotRestoreCannotFollowLinksOrIgnoreProjectRules()=test { w,_,t ->
        w.file("chat","a.txt").writeText("old");val first=call(t,"write_file",JSONObject().put("path","a.txt").put("content","new"));val id=first.getJSONArray("coding_snapshots").getJSONObject(0).getString("id")
        w.file("chat","AGENTS.md").writeText("Preserve signatures.")
        val restore=JSONObject().put("snapshot_id",id).put("expected_sha256",first.getString("sha256"))
        assertEquals("project_rules_required",call(t,"coding_restore",restore).getString("status"));assertEquals("new",w.file("chat","a.txt").readText())
        w.file("chat","a.txt").delete();Files.createSymbolicLink(w.file("chat","a.txt").toPath(),w.file("chat","AGENTS.md").toPath())
        assertEquals("error",call(t,"coding_restore",restore).getString("status"))
    }
    @Test fun diagnosticsQueueDoesNotBlockWritesAndRejectsStaleResults()=runBlocking<Unit> {
        val root=Files.createTempDirectory("coding-async").toFile();val w=Workspace(File(root,"sessions"));val s=CodingStore(File(root,"history"));lateinit var t:WorkspaceFileTools
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val service=CodingDiagnostics(File(root,"jobs"),s,{_,_->started.complete(Unit);release.await();JSONObject().put("syntax_before",QuickDiagnostics.inspect("a.json","{}")).put("syntax_after",QuickDiagnostics.inspect("a.json","{}"))},
            {c,p->t.currentVersion(c,p)},{"fixture"},save={f,text->f.parentFile!!.mkdirs();f.writeText(text)},load={it.readText()})
        t=WorkspaceFileTools(w,s){scope,record->service.enqueue(scope,record)}
        try {
            val written=call(t,"write_file",JSONObject().put("path","a.py").put("content","x=1\n"))
            val id=written.getJSONArray("coding_snapshots").getJSONObject(0).getString("diagnostic_job")
            withTimeout(3000){started.await()};assertEquals("x=1\n",w.file("chat","a.py").readText())
            w.file("chat","a.py").writeText("x=2\n");release.complete(Unit)
            val result=JSONObject(service.execute("chat",JSONObject().put("action","result").put("job_id",id).put("wait_ms",3000)))
            assertEquals("stale",result.getJSONObject("analysis").getString("state"));assertTrue(result.getBoolean("result_incomplete"))
            try{service.result("other",id);fail()}catch(_:java.io.FileNotFoundException){}
        } finally {service.dispose();root.deleteRecursively()}
    }
    @Test fun manualChecksDoNotModifyFilesOrInventBaselines()=runBlocking<Unit> {
        val root=Files.createTempDirectory("coding-manual").toFile();val w=Workspace(File(root,"sessions"));val s=CodingStore(File(root,"history"));val t=WorkspaceFileTools(w,s)
        w.file("chat","a.py").writeText("x=1\n")
        val service=CodingDiagnostics(File(root,"jobs"),s,{_,input->assertFalse(input.getBoolean("before_known"));JSONObject().put("syntax_before",JSONObject().put("status","unavailable")).put("syntax_after",QuickDiagnostics.inspect("a.json","{}"))},
            t::currentVersion,{"fixture"},save={f,text->f.parentFile!!.mkdirs();f.writeText(text)},load={it.readText()},source=t::diagnosticSource)
        try {
            val queued=JSONObject(service.execute("chat",JSONObject().put("action","check").put("path","a.py")))
            val result=JSONObject(service.execute("chat",JSONObject().put("action","result").put("job_id",queued.getString("job_id")).put("wait_ms",3000)))
            assertEquals("ready",result.getJSONObject("analysis").getString("state"));assertFalse(result.getJSONObject("analysis").getJSONObject("syntax").getBoolean("baseline_available"))
            assertEquals("x=1\n",w.file("chat","a.py").readText());assertEquals(0,s.list("chat").length())
            w.file("chat","a.json").writeText("{bad}")
            val quick=JSONObject(service.execute("chat",JSONObject().put("action","check").put("path","a.json")))
            assertTrue(quick.getBoolean("result_incomplete"));assertEquals(1,quick.getJSONObject("analysis").getJSONObject("syntax").getJSONArray("unclassified").length())
            assertEquals("{bad}",w.file("chat","a.json").readText());assertEquals(0,s.list("chat").length())
        }finally{service.dispose();root.deleteRecursively()}
    }
    @Test fun missingCheckerIsNotReportedAsClean() {
        val report=QuickDiagnostics.delta(JSONObject().put("status","unavailable").put("reason","pylsp_not_installed"),JSONObject().put("status","unavailable").put("reason","pylsp_not_installed"))
        assertEquals("unavailable",report.getString("status"));assertEquals("pylsp_not_installed",report.getString("reason"));assertFalse(report.getBoolean("baseline_available"))
    }
}
