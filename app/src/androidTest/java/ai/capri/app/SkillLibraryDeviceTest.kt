package ai.capri.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import ai.capri.app.extensions.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SkillLibraryDeviceTest {
    @Test fun nativePickerInsertsAnExplicitReferenceWithoutSending() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val scenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        lateinit var vm:CapriModel;scenario.onActivity{vm=AgentRuntime.get(it.application)}
        val selected=vm.selected;val chat=Session(title="Skill picker fixture")
        fun find(node:android.view.accessibility.AccessibilityNodeInfo?,predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo? {
            if(node==null)return null;if(predicate(node))return node
            for(i in 0 until node.childCount)find(node.getChild(i),predicate)?.let{return it};return null
        }
        fun waitFor(predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo {
            repeat(60){ins.uiAutomation.clearCache();find(ins.uiAutomation.rootInActiveWindow,predicate)?.let{return it};Thread.sleep(50)};error("Skill picker UI unavailable")
        }
        fun click(node:android.view.accessibility.AccessibilityNodeInfo){var n=node;while(!n.isClickable&&n.parent!=null)n=n.parent;check(n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))}
        try {
            ins.runOnMainSync{vm.sessions.add(chat);vm.select(chat.id);vm.activeTab=0;vm.persist()}
            click(waitFor{it.text?.toString()=="@"})
            click(waitFor{it.text?.toString()=="capri-development · @capri-development"})
            assertTrue(waitFor{it.isEditable&&it.text?.toString()?.contains("@capri-development")==true}.text.toString().contains("@capri-development"))
            assertTrue(chat.messages.isEmpty());assertFalse(vm.jobs.containsKey(chat.id))
        } finally {
            ins.runOnMainSync{vm.sessions.removeAll{it.id==chat.id};vm.select(selected);vm.persist()}
            SessionDeletion(ins.targetContext.filesDir).cleanup(chat.id);scenario.close()
        }
    }
    @Test fun unifiedDiscoverySnapshotsPermissionsUpdatesAndProvidersUseOneLibrary() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"skill-library-${UUID.randomUUID()}").apply{mkdirs()};val prefix="skill-fixture-${UUID.randomUUID()}-";val prefNames=mutableSetOf<String>()
        val context=object:ContextWrapper(app) {
            override fun getFilesDir()=root
            override fun getSharedPreferences(name:String,mode:Int)=app.getSharedPreferences(prefix+name,mode).also{prefNames+=prefix+name}
        }
        val store=ExtensionStore(context);val workspace=Workspace(File(root,"sessions"));val library=SkillLibrary(store,workspace);val chat="alpha"
        fun projectSkill(id:String,body:String):File {
            val dir=workspace.file(chat,"repo/.agents/skills/$id").apply{mkdirs()}
            dir.resolve("SKILL.md").writeText("---\nname: ${id.uppercase()}\ndescription: "+"Full trigger description. ".repeat(20)+"\n---\n$body")
            dir.resolve("references").mkdirs();dir.resolve("references/check.txt").writeText("reference")
            return dir
        }
        try {
            workspace.file(chat,"repo/.git").mkdirs()
            val dir=projectSkill("review","Review instructions")
            val catalog=library.catalog(chat);val skill=catalog.skills.first{it.id=="review"}
            assertTrue(skill.source.startsWith("project:"));assertEquals("REVIEW",skill.name);assertTrue(skill.description.length>200)
            assertTrue(catalog.skills.any{it.id=="capri-development"});assertTrue(catalog.skills.any{it.id=="capri-diagnostics"})
            val loaded=library.load(chat,"review");assertEquals("Review instructions",loaded.getString("body"));assertFalse(loaded.getString("body").contains("description:"))
            val base=loaded.getString("resource_base").removePrefix("/workspace/");val snapshot=workspace.file(chat,base)
            assertEquals("reference",File(snapshot,"references/check.txt").readText())
            File(snapshot,"generated.txt").writeText("keep")
            assertEquals(loaded.getString("revision"),library.load(chat,"review").getString("revision"));assertEquals("keep",File(snapshot,"generated.txt").readText())
            assertEquals(listOf("review"),library.mentions(chat,"Use @review and @review; user@example.test. ```@capri-diagnostics```"))
            val entries=mutableListOf(Entry("user","@review"));SkillContext.persist(entries,loaded)
            val messages=JSONArray(SkillContext.freshEntries(entries).map{JSONObject().put("role",it.role).put("content",it.text)})
            val turn=SkillContext.Turn();turn.refresh(library,chat,messages)
            assertTrue(messages.toString().contains(skill.description));assertTrue(SkillContext.contains(messages,loaded))
            val before=messages.length();turn.refresh(library,chat,messages);assertEquals(before,messages.length())
            val settings=library.settings().put("manual",JSONArray().put("review"));library.configure(settings)
            turn.refresh(library,chat,messages);assertTrue(messages.getJSONObject(messages.length()-1).getString("content").contains("removed"))
            assertFalse(library.advertised(chat).any{it.getString("id")=="review"});assertEquals("review",library.load(chat,"review",true).getString("id"))
            library.configure(library.settings().put("permissions",JSONArray().put(JSONObject().put("pattern","review").put("effect","ask"))))
            assertTrue(runCatching{library.load(chat,"review")}.isFailure);assertEquals("review",library.load(chat,"review",true).getString("id"))
            library.configure(library.settings().put("permissions",JSONArray().put(JSONObject().put("pattern","review").put("effect","deny"))))
            assertTrue(runCatching{library.load(chat,"review",true)}.isFailure);turn.refresh(library,chat,messages);assertFalse(SkillContext.contains(messages,loaded))
            library.configure(library.settings().put("permissions",JSONArray()).put("manual",JSONArray()))
            dir.resolve("SKILL.md").appendText("\nUpdated steps")
            val updated=library.load(chat,"review");assertNotEquals(loaded.getString("revision"),updated.getString("revision"));assertEquals("keep",File(snapshot,"generated.txt").readText())
            turn.refresh(library,chat,messages);assertTrue(messages.toString().contains("skill_context_unavailable"))
            val flat=workspace.file(chat,"repo/.opencode/skills/flat.md").apply{parentFile!!.mkdirs();writeText("---\nname: Flat\ndescription: Read one file\n---\nFlat guidance")}
            assertTrue(library.catalog(chat).skills.any{it.id=="flat"});assertEquals(0,library.load(chat,"flat").getInt("file_count"))
            library.register("fixture",SkillProvider { listOf(SkillParser.parseFile(flat).copy(id="provided")) })
            assertEquals("plugin:fixture",library.catalog(chat).skills.first{it.id=="provided"}.source)
            library.unregister("fixture");assertFalse(library.catalog(chat).skills.any{it.id=="provided"})
            // Another chat neither discovers the repository nor reuses its loaded snapshot.
            assertFalse(library.catalog("beta").skills.any{it.id=="review"})
            assertTrue(runCatching{library.project(chat,"../beta")}.isFailure)
            library.project(chat,"repo");assertEquals("repo",library.settings().getJSONObject("projects").getString(chat));library.clearChat(chat);assertFalse(library.settings().getJSONObject("projects").has(chat))
        } finally {root.deleteRecursively();prefNames.forEach{app.deleteSharedPreferences(it)}}
    }
}
