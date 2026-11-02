package ai.capri.app.execution

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ai.capri.app.environment.EnvironmentStamp
import java.io.File
import java.nio.file.Files
import java.util.UUID

class ProcessLedgerTest {
    private fun folder() = Files.createTempDirectory("capri-process-test").toFile()
    private suspend fun await(ledger: ProcessLedger,chat: String): JSONObject = withTimeout(3000) {
        while (true) {
            val seen = ledger.updates.value
            val records = ledger.list(chat)
            val result = records.getJSONObject(records.length()-1)
            if (result.optString("state") !in setOf("starting","running","cancel_requested")) return@withTimeout result
            ledger.awaitChange(seen,1000)
        }
        @Suppress("UNREACHABLE_CODE") JSONObject()
    }
    @Test fun actualExitPublishesIndependentReceiptAndBoundedUnicodeOutput() = runBlocking {
        val root=folder()
        try {
            val output=File(root,"log")
            val ledger=ProcessLedger(File(root,"ledger"),stop={ it.destroyForcibly() })
            ledger.launch("chat","build",listOf("/bin/sh"),output) { ProcessBuilder("/bin/sh","-c","printf '你好\\n'; exit 7").redirectErrorStream(true).redirectOutput(output).start() }
            val result=await(ledger,"chat")
            assertEquals("failed",result.getString("state")); assertEquals(7,result.getInt("exit_code"))
            val id=result.getString("process_id")
            output.writeText("overwritten") // Receipts retain the output of this execution, not a reused log path.
            val recovered=ProcessLedger(File(root,"ledger"))
            assertEquals("failed",recovered.list("chat").getJSONObject(0).getString("state"))
            val first=recovered.read("chat",id,0,4)
            assertEquals("你",first.getString("output")); assertEquals(3,first.getInt("next_offset")); assertTrue(first.getBoolean("has_more"))
            assertEquals("好\n",recovered.read("chat",id,3,4).getString("output"))
            assertFalse(first.getJSONObject("process").has("output_path"))
            try { recovered.read("other",id,0,4); fail("cross-chat receipt") } catch (_: IllegalStateException) { }
            assertEquals(0,recovered.list("other").length())
        } finally { root.deleteRecursively() }
    }
    @Test fun restartMarksUnknownWithoutPidReattachmentOrReplay() {
        val root=folder(); val id=UUID.randomUUID().toString()
        try {
            File(root,"chat").mkdirs()
            File(root,"chat/$id.json").writeText(JSONObject().put("process_id",id).put("chat_id","chat").put("state","running").put("pid",12345).toString())
            val ledger=ProcessLedger(root,stop={ fail("Reattached an unowned process") })
            val receipt=ledger.list("chat").getJSONObject(0)
            assertEquals("interrupted",receipt.getString("state")); assertEquals("unknown",receipt.getString("effect"))
            assertEquals("interrupted",ledger.cancel("chat",id).getString("state"))
            assertTrue(File(root,"chat/$id.json").readText().contains("unknown"))
        } finally { root.deleteRecursively() }
    }
    @Test fun waitIsEventDrivenAndCancellationDoesNotCancelUnderlyingWork() = runBlocking {
        val root=folder(); val ledger=ProcessLedger(root,stop={it.destroyForcibly()}); var process:Process?=null
        try {
            process=ledger.launch("chat","script",listOf("/bin/sleep"),null) { ProcessBuilder("/bin/sleep","10").start() }
            val seen=ledger.updates.value
            val waiter=launch { ledger.awaitChange(seen,20000) }
            yield(); waiter.cancelAndJoin()
            assertTrue(process.isAlive)
            val id=ledger.list("chat").getJSONObject(0).getString("process_id")
            ledger.cancel("chat",id)
            assertEquals("cancelled",await(ledger,"chat").getString("state")); assertFalse(process.isAlive)
            val page=ledger.read("chat",id,0,4)
            assertFalse(page.getBoolean("output_available")); assertTrue(page.getBoolean("complete"))
        } finally { process?.destroyForcibly(); root.deleteRecursively() }
    }
    @Test fun stoppedProcessPublishesTimeoutBeforeTemporaryOutputCleanup() = runBlocking {
        val root=folder(); val output=File(root,"transient")
        val ledger=ProcessLedger(File(root,"ledger"),stop={it.destroyForcibly()})
        var process:Process?=null
        try {
            process=ledger.launch("chat","build",listOf("sleep"),output) { ProcessBuilder("/bin/sleep","10").redirectOutput(output).start() }
            ProcessLedger.markStopping(process,"timeout")
            process.destroyForcibly().waitFor()
            ProcessLedger.processStopped(process)
            output.delete()
            val receipt=await(ledger,"chat")
            assertEquals("timeout",receipt.getString("state"))
            assertTrue(ledger.read("chat",receipt.getString("process_id"),0,4).getBoolean("output_available"))
        } finally { process?.destroyForcibly();root.deleteRecursively() }
    }
    @Test fun launchFailureIsDurableAndDoesNotStoreInlineSecrets() {
        val root=folder(); val ledger=ProcessLedger(root)
        try {
            try { ledger.launch("chat","check",listOf("python","secret-token"),null) { error("failed") }; fail("accepted") } catch (_: IllegalStateException) { }
            val result=ProcessLedger(root).list("chat").getJSONObject(0)
            assertEquals("launch_failed",result.getString("state"))
            assertFalse(root.walkTopDown().filter { it.isFile }.joinToString { it.readText() }.contains("secret-token"))
        } finally { root.deleteRecursively() }
    }
    @Test fun opaqueMutationsInvalidateBeforeLaunchAndAfterCompletion() = runBlocking {
        val root=folder(); val invalidations=java.util.concurrent.atomic.AtomicInteger()
        val ledger=ProcessLedger(root,invalidate={invalidations.incrementAndGet()})
        try {
            ledger.launch("chat","script",listOf("true"),null,changesEnvironment=true) {
                assertEquals(1,invalidations.get()); ProcessBuilder("/usr/bin/true").start()
            }
            await(ledger,"chat"); assertEquals(2,invalidations.get())
        } finally { root.deleteRecursively() }
    }
    @Test fun overflowingFileOutputCannotBeSuccessfulEvidence() = runBlocking {
        val root=folder(); val output=File(root,"output")
        try {
            val ledger=ProcessLedger(File(root,"ledger"),stop={it.destroyForcibly()})
            ledger.launch("chat","build",listOf("sh"),output) { ProcessBuilder("/bin/sh","-c","head -c 2000100 /dev/zero").redirectOutput(output).start() }
            val receipt=await(ledger,"chat")
            assertEquals("output_limit",receipt.getString("state"))
            val result=JSONObject().put("status","ok").put("data",JSONObject().put("process",receipt))
            assertTrue(ai.capri.app.agent.CompletionEvidence.unsuccessful(result.toString()))
        } finally { root.deleteRecursively() }
    }
    @Test fun pendingAndInterruptedReceiptsAreNotCompletionEvidence() {
        for (state in listOf("starting","running","interrupted","cancel_requested","launch_failed","output_limit")) {
            val result=JSONObject().put("status","ok").put("data",JSONObject().put("process",JSONObject().put("state",state)))
            assertTrue(state,ai.capri.app.agent.CompletionEvidence.unsuccessful(result.toString()))
        }
    }
    @Test fun backendSelectionCannotSilentlyFallBackOrClaimRemoteCompletion() {
        assertSame(LocalExecutionBackend,ExecutionBackends.resolve("local_proot"))
        try { ExecutionBackends.resolve("ssh"); fail("silent remote fallback") } catch (_: IllegalArgumentException) { }
    }
    @Test fun watchedFilesInvalidateWhenSizeAndTimestampArePreserved() {
        val root=folder(); val file=File(root,"requirements.txt")
        try {
            file.writeText("a==1"); val before=EnvironmentStamp.of(file); val at=file.lastModified()
            file.writeText("a==2"); file.setLastModified(at)
            assertNotEquals(before,EnvironmentStamp.of(file))
            assertEquals(EnvironmentStamp.of(file),EnvironmentStamp.of(file))
        } finally { root.deleteRecursively() }
    }
    @Test fun paginationEscapingAndMissingLogsAreExplicit() {
        val root=folder(); val log=File(root,"log")
        try {
            log.writeText("\u0001".repeat(10000))
            val page=OutputPage.read(log,0,32000,true)
            assertTrue(page.getBoolean("has_more")); assertTrue(page.getLong("next_offset")>0)
            assertTrue(JSONObject.quote(page.getString("output")).length<=24000)
            try { OutputPage.read(log,10001,4,true); fail("past end") } catch (_: IllegalArgumentException) { }
            log.delete(); assertFalse(OutputPage.read(log,0,4,true).getBoolean("output_available"))
        } finally { root.deleteRecursively() }
    }
}
