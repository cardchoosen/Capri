package ai.capri.app

import ai.capri.app.files.FileToolContract
import ai.capri.app.files.WorkspaceFileTools
import ai.capri.app.extensions.ExtensionStore
import ai.capri.app.extensions.SkillParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceFilesDeviceTest {
    @Test fun androidAtomicEditPagingAndIsolation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = File(context.cacheDir, "file-tools-${UUID.randomUUID()}")
        val workspace = Workspace(base); val tools = WorkspaceFileTools(workspace)
        fun call(name: String, vararg args: Pair<String, Any>) = JSONObject(tools.execute("fixture", name, JSONObject(mapOf(*args))))
        try {
            val text = "\uFEFF😀 line\r\n".repeat(16000)
            assertEquals("ok", call("write_file", "path" to "src/任务.txt", "content" to text).getString("status"))
            val file = workspace.file("fixture", "src/任务.txt")
            val permissions = PosixFilePermissions.fromString("rwx------"); Files.setPosixFilePermissions(file.toPath(), permissions)
            val first = call("read_file", "path" to "/workspace/src/任务.txt", "max_lines" to 2)
            assertEquals("\uFEFF😀 line\r\n".repeat(2), first.getString("content")); assertEquals(3, first.getInt("next_line"))
            val edit = call("edit_file", "path" to "src/任务.txt", "old_text" to "line", "new_text" to "updated", "replace_all" to true, "expected_sha256" to first.getString("sha256"))
            assertEquals("ok", edit.getString("status")); assertEquals(16000, edit.getInt("replacements"))
            assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
            assertEquals(2, call("search_files", "query" to "updated", "pattern" to "**/*.txt", "limit" to 2).getJSONArray("matches").length())
            assertEquals("not_found", JSONObject(tools.execute("other", "read_file", JSONObject().put("path", "src/任务.txt"))).getJSONObject("error").getString("code"))
            Files.createSymbolicLink(File(file.parentFile, "link").toPath(), file.toPath())
            assertEquals("unsafe_path", call("read_file", "path" to "src/link").getJSONObject("error").getString("code"))
            assertFalse(file.parentFile!!.listFiles()!!.any { it.name.startsWith(".capri-edit-") })
            assertEquals(5, FileToolContract.specifications().size)
        } finally { RuntimeFiles.delete(base) }
    }
    @Test fun builtInEnglishSkillsRefreshWithoutTouchingUserSkills() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "skill-refresh-${UUID.randomUUID()}").apply { mkdirs() }
        val fixture = object : android.content.ContextWrapper(context) { override fun getFilesDir() = root }
        try {
            val user = File(root, "extensions/skills/user-fixture/SKILL.md").apply { parentFile!!.mkdirs(); writeText("---\nname: user-fixture\ndescription: 用户自建内容\n---\nKeep user content. 用户原文保持不变。") }
            val userBytes = user.readBytes()
            File(root, "extensions/skills/capri-browser/SKILL.md").apply { parentFile!!.mkdirs(); writeText("旧版内置指引") }
            val store = ExtensionStore(fixture)
            val names = context.assets.list("extensions/skills").orEmpty()
            assertEquals(6, names.size)
            for (name in names) {
                val directory = File(root, "extensions/skills/$name")
                val info = SkillParser.parse(directory, true)
                assertEquals(name, info.name)
                assertTrue(info.description.isNotBlank())
                for (file in directory.walkTopDown().filter { it.isFile && it.extension == "md" }) {
                    assertFalse(file.relativeTo(root).path, file.readText().any { it in '\u4e00'..'\u9fff' })
                }
            }
            assertArrayEquals(userBytes, user.readBytes())
            assertTrue(store.skills().any { it.id == "user-fixture" && !it.builtin })
        } finally { RuntimeFiles.delete(root) }
    }
}
