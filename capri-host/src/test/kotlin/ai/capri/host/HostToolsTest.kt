package ai.capri.host

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HostToolsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun toolResultsReachModelWithTheirCallIds() = runBlocking {
        HostSessions(temporary.root.toPath(), null).use { sessions ->
            val input = JSONArray().put(JSONObject().put("role", "user").put("content", "Inspect the host."))
            var requests = 0
            val result = runTurn("Inspect the host.", input, HostTools(sessions, HostSessions.DEFAULT)) { request ->
                assertEquals(22, request.tools.length())
                val message = if (requests++ == 0) {
                    JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                        .put("tool_calls", JSONArray()
                            .put(call("one", "host_environment", "{}"))
                            .put(call("two", "session_artifacts", """{"path":"unexpected"}"""))
                            .put(call("three", "session_artifacts", "{}"))
                            .put(call("four", "context_history", """{"limit":2}""")))
                } else {
                    val first = request.messages.getJSONObject(2)
                    val invalid = request.messages.getJSONObject(3)
                    val artifacts = request.messages.getJSONObject(4)
                    assertEquals("one", first.getString("tool_call_id"))
                    assertEquals("Capri JVM host", JSONObject(first.getString("content")).getString("runtime"))
                    assertEquals("two", invalid.getString("tool_call_id"))
                    assertEquals("invalid_tool_call", JSONObject(invalid.getString("content")).getString("error"))
                    assertEquals("three", artifacts.getString("tool_call_id"))
                    assertEquals(0, JSONObject(artifacts.getString("content")).getJSONArray("files").length())
                    val context = JSONObject(request.messages.getJSONObject(5).getString("content"))
                    assertEquals(5, context.getInt("total"))
                    assertEquals("three", context.getJSONArray("entries").getJSONObject(1).getString("toolCallId"))
                    JSONObject().put("role", "assistant").put("content", "Host environment inspected.")
                }
                JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message)))
            }
            assertEquals("complete", result.getString("outcome"))
            assertEquals(4, result.getJSONArray("tool_receipts").length())
            assertEquals(2, requests)
        }
    }

    private fun call(id: String, name: String, arguments: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", arguments))

    @Test
    fun workspaceEditsAndDeliveryStayInsideTheirSession() = runBlocking {
        val root = temporary.root.toPath()
        HostSessions(root, null).use { sessions ->
            val tools = HostTools(sessions, HostSessions.DEFAULT)
            val other = sessions.create("Other").getString("id")
            suspend fun execute(name: String, args: JSONObject) = JSONObject(tools.execute(call("test", name, args.toString())))
            fun path(name: String = "notes/report.md") = JSONObject().put("path", name)
            val inventory = execute("tool_search", JSONObject())
            assertEquals(tools.specifications().length(), inventory.getInt("count"))
            assertEquals("read", execute("tool_describe", JSONObject().put("name", "read"))
                .getJSONObject("tool").getString("name"))
            assertEquals("unknown_tool", execute("tool_describe", JSONObject().put("name", "unavailable")).getString("error"))
            assertEquals("invalid_tool_target", execute("tool_invoke",
                JSONObject().put("name", "tool_invoke").put("arguments", JSONObject())).getString("error"))
            assertEquals("invalid_tool_call", execute("tool_invoke",
                JSONObject().put("name", "write").put("arguments", JSONObject())).getString("error"))
            assertEquals("ok", execute("write", path().put("content", "one\ntwo\nthree")).getString("status"))
            val read = execute("read", path().put("offset", 2).put("limit", 1))
            assertEquals("two", read.getString("content"))
            assertEquals(3, read.getInt("nextOffset"))
            assertEquals(1, execute("ls", path("notes")).getJSONArray("entries").length())
            assertEquals(1, execute("grep", JSONObject().put("pattern", "three")).getJSONArray("matches").length())
            assertEquals("edit_conflict", execute("edit", path().put("oldText", "missing").put("newText", "")).getString("error"))
            assertEquals("ok", execute("edit", path().put("oldText", "two").put("newText", "revised")).getString("status"))
            val delivery = JSONObject().put("action", "send").put("media", "notes/report.md")
            val attachment = execute("message", delivery).getJSONObject("attachment")
            val artifact = attachment.getString("id")
            assertEquals("notes/report.md", attachment.getString("path"))
            assertEquals(artifact, execute("message", delivery).getJSONObject("attachment").getString("id"))
            execute("write", path().put("content", "later"))
            assertEquals("later", sessions.artifact(HostSessions.DEFAULT, artifact)?.second?.toString(Charsets.UTF_8))
            assertEquals(5L, sessions.artifacts(HostSessions.DEFAULT).getJSONObject(0).getLong("size"))
            assertEquals(null, sessions.artifact(other, artifact))
            sessions.export(HostSessions.DEFAULT)
            assertEquals(false, Files.exists(root.resolve("sessions/default/artifacts")))
            val original = root.resolve("sessions/default/workspace/notes/report.md")
            Files.delete(original)
            Files.createSymbolicLink(original, root.resolve("sessions/$other/messages.json"))
            assertEquals(null, sessions.artifact(HostSessions.DEFAULT, artifact))
            assertEquals(0, sessions.artifacts(HostSessions.DEFAULT).length())
            assertEquals("invalid_path", execute("read", path("../messages.json")).getString("error"))
            val link = root.resolve("sessions/default/workspace/escape.md")
            Files.createSymbolicLink(link, root.resolve("sessions/$other/messages.json"))
            assertEquals("invalid_path", execute("read", path("escape.md")).getString("error"))
            assertEquals("invalid_path", execute("write", path("escape.md").put("content", "blocked")).getString("error"))
        }
    }

    @Test
    fun olderAttachmentsRemainDownloadable() {
        val root = temporary.root.toPath()
        HostSessions(root, null).use { sessions ->
            val id = "68a4c976-4bb7-4b15-a34d-70b2d905b867"
            val legacy = root.resolve("sessions/default/artifacts/$id")
            Files.createDirectories(legacy)
            Files.writeString(legacy.resolve("content"), "legacy")
            Files.writeString(legacy.resolve("metadata.json"), JSONObject().put("id", id).put("name", "old.md")
                .put("size", 6).put("createdAt", 1).toString())
            assertEquals(id, sessions.artifacts(HostSessions.DEFAULT).getJSONObject(0).getString("id"))
            assertEquals("legacy", sessions.artifact(HostSessions.DEFAULT, id)?.second?.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun revisionsAndRecordScopeAreEnforced() = runBlocking {
        HostSessions(temporary.root.toPath(), null).use { sessions ->
            val other = sessions.create("Other").getString("id")
            suspend fun execute(name: String, args: JSONObject, session: String = HostSessions.DEFAULT) =
                JSONObject(HostTools(sessions, session).execute(call("test", name, args.toString())))
            val file = JSONObject().put("path", "package.json")
            execute("write", JSONObject(file.toString()).put("content", "original"))
            val invalid = JSONObject().put("path", "invalid.json")
            execute("write", JSONObject(invalid.toString()).put("content", """{"key":"""))
            assertEquals("json_parse_error", execute("coding_diagnostics", invalid)
                .getJSONArray("diagnostics").getJSONObject(0).getString("code"))
            assertEquals("node", execute("project", JSONObject()).getJSONArray("projectTypes").getString(0))
            val revision = execute("coding_reference", JSONObject(file.toString()).put("line", 1)).getString("revision")
            val patch = JSONObject(file.toString()).put("revision", revision).put("changes",
                JSONArray().put(JSONObject().put("oldText", "original").put("newText", "changed")))
            val patched = execute("apply_patch", patch)
            assertEquals("ok", patched.getString("status"))
            assertEquals("revision_conflict", execute("apply_patch", patch).getString("error"))
            val restore = JSONObject(file.toString()).put("revision", patched.getString("revision")).put("restoreRevision", revision)
            assertEquals("ok", execute("coding_restore", restore).getString("status"))
            assertEquals("original", execute("read", file).getString("content"))
            val record = JSONObject().put("action", "save").put("title", "Preference")
                .put("source", "User instruction").put("content", "Use Markdown.")
            val search = JSONObject().put("action", "search").put("query", "Markdown")
            assertEquals(true, execute("knowledge", record).getBoolean("created"))
            assertEquals(false, execute("knowledge", record).getBoolean("created"))
            assertEquals(0, execute("knowledge", search, other).getJSONArray("records").length())
            assertEquals(true, execute("personal_memory", record).getBoolean("created"))
            assertEquals(1, execute("personal_memory", search, other).getJSONArray("records").length())
        }
    }

    @Test
    fun workspaceDocumentsAndBatchPatchesAreBounded() = runBlocking {
        HostSessions(temporary.root.toPath(), null).use { sessions ->
            val tools = HostTools(sessions, HostSessions.DEFAULT)
            suspend fun execute(name: String, args: JSONObject) =
                JSONObject(tools.execute(call("test", name, args.toString())))
            fun docx(xml: String): JSONObject {
                val bytes = ByteArrayOutputStream()
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("word/document.xml"))
                    zip.write(xml.toByteArray())
                    zip.closeEntry()
                }
                val file = sessions.workspace(HostSessions.DEFAULT).importFile("sample.docx", bytes.toByteArray())
                return JSONObject().put("path", file.getString("path"))
            }
            val ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            val document = docx("""<w:document xmlns:w="$ns"><w:body><w:p><w:r><w:t>Capri document</w:t></w:r></w:p></w:body></w:document>""")
            assertEquals("Capri document", execute("document_extract", document).getString("content"))
            val unsafe = docx("""<!DOCTYPE x [<!ENTITY x SYSTEM "file:///private.txt">]><w:p xmlns:w="$ns"><w:t>&x;</w:t></w:p>""")
            assertEquals("invalid_document", execute("document_extract", unsafe).getString("error"))
            suspend fun prepare(path: String): JSONObject {
                execute("write", JSONObject().put("path", path).put("content", "before"))
                val file = JSONObject().put("path", path)
                val revision = execute("read", file).getString("revision")
                return file.put("revision", revision).put("changes", JSONArray()
                    .put(JSONObject().put("oldText", "before").put("newText", "after")))
            }
            val first = prepare("first.txt")
            val second = prepare("second.txt")
            val stale = JSONObject(second.toString()).put("revision", "0".repeat(64))
            fun batch(last: JSONObject) = JSONObject().put("files", JSONArray().put(first).put(last))
            assertEquals("revision_conflict", execute("apply_patch", batch(stale)).getString("error"))
            assertEquals("before", execute("read", JSONObject().put("path", "first.txt")).getString("content"))
            assertEquals(2, execute("apply_patch", batch(second)).getJSONArray("files").length())
            assertEquals("after", execute("read", JSONObject().put("path", "second.txt")).getString("content"))
        }
    }

    @Test
    fun webSearchUsesTheConfiguredProviderAndKeepsCitations() = runBlocking {
        HostSessions(temporary.root.toPath(), null).use { sessions ->
            val tools = HostTools(sessions, HostSessions.DEFAULT, webSearch = { args ->
                assertEquals("public library", args.getString("query"))
                webSearchResults(JSONObject("""{"results":[{"url":"https://example.org/library","title":"Library","snippet":"Public hours"},{"url":"javascript:alert(1)"}]}"""), 2)
            })
            val reply = JSONObject(tools.execute(call("test", "web_search", """{"query":"public library","count":2}""")))
            assertEquals("deepseek", reply.getString("provider"))
            val sources = reply.getJSONArray("results")
            assertEquals(1, sources.length())
            assertEquals("https://example.org/library", sources.getJSONObject(0).getString("url"))
            assertEquals("https://example.org/library", publicPageUrl("https://example.org/library#hours").toString())
            for (url in listOf("http://127.0.0.1", "http://host.local", "https://user@example.org/", "https://example.org/?key=secret")) {
                assertEquals("invalid_public_url", (runCatching { publicPageUrl(url) }.exceptionOrNull() as WorkspaceFailure).code)
            }
        }
    }

    @Test
    fun skillResourceAndCatalogReferencesStayBoundToTheirRoots() = runBlocking {
        val root = temporary.root.toPath()
        val catalogRoot = Files.createDirectories(root.resolve("catalog"))
        val release = Files.createDirectories(catalogRoot.resolve("reports/1.0"))
        Files.writeString(release.resolve("SKILL.md"), "# Reports")
        val catalog = HostSkillCatalog(catalogRoot)
        val item = catalog.execute(JSONObject("""{"action":"inspect","name":"reports","version":"1.0"}"""))
        assertEquals("# Reports", item.getString("content"))
        assertEquals(64, item.getString("revision").length)
        val outside = Files.createDirectories(root.resolve("outside/1.0"))
        Files.writeString(outside.resolve("SKILL.md"), "outside")
        Files.createSymbolicLink(catalogRoot.resolve("alias"), outside.parent)
        val unavailable = runCatching { catalog.execute(JSONObject("""{"action":"inspect","name":"alias","version":"1.0"}""")) }.exceptionOrNull()
        assertEquals("skill_not_found", (unavailable as WorkspaceFailure).code)

        HostSessions(root.resolve("host"), null).use { sessions ->
            val workspace = sessions.workspace(HostSessions.DEFAULT)
            workspace.execute("write", JSONObject("""{"path":"skills/reports/SKILL.md","content":"# Reports"}"""))
            workspace.execute("write", JSONObject("""{"path":"skills/reports/resources/checklist.md","content":"Checklist"}"""))
            val skill = JSONObject("""{"name":"reports"}""")
            val descriptor = workspace.execute("skill", skill)
            assertEquals("checklist.md", descriptor.getJSONArray("resources").getString(0))
            skill.put("revision", descriptor.getString("revision")).put("resource", "checklist.md")
            assertEquals("Checklist", workspace.execute("skill", skill).getString("content"))
            val escaped = runCatching { workspace.execute("skill", skill.put("resource", "../SKILL.md")) }.exceptionOrNull()
            assertEquals("invalid_path", (escaped as WorkspaceFailure).code)
        }
    }

    @Test
    fun projectedResultsKeepTheirOriginalAndDiscoveryLoadsRequestedSchema() = runBlocking {
        HostSessions(temporary.root.toPath(), null).use { sessions ->
            val tools = HostTools(sessions, HostSessions.DEFAULT)
            fun names() = jsonObjects(tools.modelSpecifications()).map { it.getJSONObject("function").getString("name") }
            assertEquals(false, "knowledge" in names())
            val describe = JSONObject(tools.execute(call("describe", "tool_describe", """{"name":"knowledge"}""")))
            assertEquals("ok", describe.getString("status"))
            assertEquals(true, "knowledge" in names())
            val source = "evidence ".repeat(4000)
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "Operator constraints."))
                .put(JSONObject().put("role", "user").put("content", "Keep the original requirement."))
                .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(call("read-1", "read", "{}"))))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "read-1").put("content", source))
            tools.trackContext(messages)
            val projected = projectHostContext(messages, tools.modelSpecifications(), 8000)
            assertEquals("Keep the original requirement.", projected.getJSONObject(1).getString("content"))
            assertEquals("read-1", projected.getJSONObject(3).getString("tool_call_id"))
            assertEquals(true, JSONObject(projected.getJSONObject(3).getString("content")).getBoolean("truncated"))
            assertEquals(source, messages.getJSONObject(3).getString("content"))
            val result = JSONObject(tools.execute(call("recover", "context_history", """{"toolCallId":"read-1","offset":1}""")))
            assertEquals(source.take(4000), result.getString("content"))
        }
    }

    @Test
    fun documentExtractReadsPdfPagesAndReportsMissingTextLayer() = runBlocking {
        HostSessions(temporary.root.toPath(), null).use { sessions ->
            val data = ByteArrayOutputStream()
            PDDocument().use { pdf ->
                val page = PDPage()
                pdf.addPage(page)
                pdf.addPage(PDPage())
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    stream.newLineAtOffset(40f, 700f)
                    stream.showText("Capri PDF fixture")
                    stream.endText()
                }
                pdf.save(data)
            }
            val workspace = sessions.workspace(HostSessions.DEFAULT)
            val path = workspace.importFile("fixture.pdf", data.toByteArray()).getString("path")
            val params = JSONObject().put("path", path).put("pageLimit", 1)
            val page = workspace.execute("document_extract", params)
            assertEquals("Capri PDF fixture", page.getString("content"))
            assertEquals(2, page.getInt("nextPage"))
            assertEquals(false, workspace.execute("document_extract", params.put("firstPage", 2)).getBoolean("hasText"))
        }
    }

    @Test
    fun clarificationStopsTheRestOfTheBatch() = runBlocking {
        HostSessions(temporary.root.toPath(), null).use { sessions ->
            val tools = HostTools(sessions, HostSessions.DEFAULT)
            var requests = 0
            val result = runTurn("Draft the report.", JSONArray(), tools) {
                requests++
                val response = JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                    .put("tool_calls", JSONArray()
                        .put(call("question", "clarify", """{"question":"Who is the audience?"}"""))
                        .put(call("write", "write", """{"path":"report.md","content":"Premature"}""")))
                JSONObject().put("choices", JSONArray().put(JSONObject().put("message", response)))
            }
            assertEquals(1, requests)
            assertEquals("waiting", result.getString("outcome"))
            assertEquals("Who is the audience?", result.getString("reply"))
            assertEquals("not_executed", result.getJSONArray("tool_receipts").getJSONObject(1).getString("status"))
        }
    }
}
