package ai.capri.app

import ai.capri.app.extensions.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SkillLifecycleTest {
    @Test fun parserSeparatesIdentityDisplayBodyAndManualInvocation() {
        val root=Files.createTempDirectory("skills-parser").toFile()
        try {
            val dir=root.resolve("review").apply{mkdirs()};val file=dir.resolve("SKILL.md")
            val description="Use for repository review. "+"Complete trigger details. ".repeat(20)
            file.writeText("---\nname: Repository Review\ndescription: $description\ndisable-model-invocation: true\n---\n# Review\nRead references/check.md.")
            val skill=SkillParser.parse(dir);assertEquals("review",skill.id);assertEquals("Repository Review",skill.name)
            assertEquals(description.trim(),skill.description);assertFalse(skill.autoinvoke);assertTrue(skill.body.startsWith("# Review"));assertFalse(skill.body.contains("description:"))
            file.writeText(file.readText().replace("disable-model-invocation: true","disable-model-invocation: true\nmetadata:\n  opencode/autoinvoke: true"))
            assertTrue(SkillParser.parse(dir).autoinvoke)
            val flat=root.resolve("manual.md").apply{writeText("# Manual only")}
            assertEquals("manual",SkillParser.parseFile(flat).id);assertEquals("",SkillParser.parseFile(flat).description)
            assertTrue(SkillParser.resources(SkillParser.parseFile(flat)).isEmpty())
        } finally {root.deleteRecursively()}
    }
    @Test fun revisionIncludesResourceContentsAndRejectsSymlinkResources() {
        val root=Files.createTempDirectory("skills-resources").toFile()
        try {
            val dir=root.resolve("review").apply{mkdirs()};dir.resolve("SKILL.md").writeText("# Review")
            val resource=dir.resolve("check.txt").apply{writeText("first")};val skill=SkillParser.parse(dir);val first=skill.revision()
            resource.writeText("second");assertNotEquals(first,skill.revision())
            Files.createSymbolicLink(dir.resolve("escape").toPath(),root.toPath())
            assertTrue(runCatching{skill.revision()}.isFailure)
        } finally {root.deleteRecursively()}
    }
    @Test fun sourceDirectoryAliasesProduceSafeRelativeResourcePaths() {
        val root=Files.createTempDirectory("skills-alias").toFile()
        try {
            val actual=root.resolve("actual/review").apply{mkdirs()}
            actual.resolve("SKILL.md").writeText("# Review")
            actual.resolve("reference.txt").writeText("resource")
            Files.createSymbolicLink(root.resolve("alias").toPath(),root.resolve("actual").toPath())
            val aliased=SkillParser.parse(root.resolve("alias/review"));val resources=SkillParser.resources(aliased)
            assertEquals(listOf("reference.txt"),resources.map{it.relativeTo(aliased.directory.canonicalFile).invariantSeparatorsPath})
            assertEquals(SkillParser.parse(actual).revision(),aliased.revision())
        } finally {root.deleteRecursively()}
    }
    @Test fun sourceUpdatesAreAtomicAndFailuresKeepPreviousCompleteDefinition() {
        val root=Files.createTempDirectory("skills-remote").toFile()
        try {
            var version=1;var missing=false
            val source=SkillRemote(root) { address -> when {
                address.endsWith("index.json") -> JSONObject().put("skills",JSONArray().put(JSONObject().put("name","review").put("version",version.toString()).put("files",JSONArray(if(version==1)listOf("SKILL.md","old.txt") else listOf("SKILL.md","new.txt"))))).toString().toByteArray()
                address.endsWith("SKILL.md") -> "# Review v$version".toByteArray()
                missing -> error("fixture missing resource")
                else -> "resource".toByteArray()
            } }
            assertEquals("updated",source.refresh("team","https://example.test/catalog").getString("status"))
            val dir=source.directory("team").resolve("review");assertTrue(dir.resolve("old.txt").exists())
            version=2;missing=true;assertEquals("error",source.refresh("team","https://example.test/catalog").getString("status"))
            assertEquals("# Review v1",dir.resolve("SKILL.md").readText());assertTrue(dir.resolve("old.txt").exists())
            missing=false;assertEquals("updated",source.refresh("team","https://example.test/catalog").getString("status"))
            assertFalse(dir.resolve("old.txt").exists());assertTrue(dir.resolve("new.txt").exists())
            assertEquals("cached",source.refresh("team","https://example.test/catalog").getString("status"))
            dir.resolve("new.txt").delete()
            assertEquals("updated",source.refresh("team","https://example.test/catalog").getString("status"))
            assertTrue(dir.resolve("new.txt").exists())
        } finally {root.deleteRecursively()}
    }
    @Test fun remotePathsAndCancellationCannotPublishUnsafeOrPartialContent() {
        for(path in listOf("../x","/x","https://other.test/x","x/../../y","%2e%2e/x","x/%2fsecret","a\\b","a?b","a#b"))assertTrue(path,runCatching{SkillRemote.safePath(path)}.isFailure)
        assertEquals("references/check.md",SkillRemote.safePath("references/check.md"))
        for(url in listOf("http://example.test","https://user:secret@example.test","https://example.test/?key=a"))assertTrue(runCatching{SkillRemote.validateUrl(url)}.isFailure)
        val root=Files.createTempDirectory("skill-cancel").toFile()
        try {val source=SkillRemote(root){error("should not download")};assertTrue(runCatching{source.refresh("team","https://example.test"){throw kotlinx.coroutines.CancellationException()}}.exceptionOrNull() is kotlinx.coroutines.CancellationException);assertFalse(source.directory("team").exists())}finally{root.deleteRecursively()}
    }
    @Test fun snapshotsSurviveOrdinaryTurnsAndFollowHistoricalTimeline() {
        fun snapshot(revision:String)=JSONObject().put("id","review").put("source","user").put("revision",revision).put("body","Full review instructions").put("resource_base","/workspace/review")
        val entries=mutableListOf(Entry("user","First"));SkillContext.persist(entries,snapshot("one"));SkillContext.persist(entries,snapshot("one"))
        assertEquals(1,entries.count{it.role=="skill"})
        entries+=Entry("assistant","Done");entries+=Entry("user","Continue")
        val restored=SkillContext.freshEntries(entries);assertTrue(restored.any{it.text.contains("Full review instructions")});assertFalse(restored.any{it.role=="skill"})
        SkillContext.persist(entries,snapshot("two"));assertEquals(1,SkillContext.freshEntries(entries).count{it.text.startsWith(SkillLibrary.MARKER)})
        val session=Session(messages=entries);HistoryEditing.replaceFrom(session,entries.first().id)
        assertTrue(SkillContext.freshEntries(session.messages).isEmpty())
        val encoded=SessionCatalog.encode(listOf(Session(messages=mutableListOf(Entry("user","Use"),SkillLibrary.snapshotEntry(snapshot("one"))))))
        assertTrue(SessionCatalog.decode(encoded).first{it.kind==SessionKind.TASK}.messages.any{it.role=="skill"})
    }
    @Test fun oldLoadingActionsAreRemovedAndSkillContractIsEnglishAndStrict() {
        val old=ExtensionInstall.specification().getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").getJSONObject("action").getJSONArray("enum").toString()
        assertFalse(old.contains("load_skill"));assertFalse(old.contains("read_resource"))
        val spec=SkillTool.specification();assertFalse(spec.toString().any{it in '\u4e00'..'\u9fff'})
        assertEquals("load",SkillTool.validate(JSONObject().put("id","review")))
        assertTrue(runCatching{SkillTool.validate(JSONObject().put("id","review").put("command","run"))}.isFailure)
        assertTrue(runCatching{SkillTool.validate(JSONObject().put("action","read").put("id","review").put("path","a").put("offset",-1))}.isFailure)
    }
}
