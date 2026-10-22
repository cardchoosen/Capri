package ai.capri.app

import ai.capri.app.terminal.TerminalManager
import android.os.ParcelFileDescriptor
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NodeCompilerDeviceTest {
    @Test fun versionLayerInstallsFromPartsWithoutAndroidHardLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "development-layer-test-${java.util.UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        try {
            val layer = DevelopmentTools.prepare(isolated)
            assertTrue(File(layer, "capri-bin/gcc").isFile)
            assertTrue(File(layer, "usr/bin/npm").isFile)
            assertEquals(layer, DevelopmentTools.prepare(isolated))
        } finally { directory.deleteRecursively() }
    }
    @Test fun nodePackagesAndNativeBuildsWorkAcrossIsolatedChats() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ai.capri.app/.MainActivity")).use { it.readBytes() }
        val store = Store(context); val runtime = PythonRuntime(context, store.workspace)
        val manager = TerminalManager.get(context)
        val first = "node-compiler-test-a"; val second = "node-compiler-test-b"
        suspend fun call(chat: String, action: String, params: JSONObject = JSONObject()): JSONObject {
            val result = JSONObject(manager.execute(chat, JSONObject().put("action", action).put("params", params)))
            assertEquals(result.toString(), "ok", result.getString("status"))
            return result.getJSONObject("data")
        }
        suspend fun run(chat: String, id: String, code: String, exit: Int = 0): String = withTimeout(240_000) {
            var data = call(chat, "run", JSONObject().put("terminal_id", id).put("command", code)
                .put("wait_ms", 10000).put("timeout_seconds", 180))
            val command = data.getString("command_id"); val output = StringBuilder(data.getString("output"))
            while (!data.getBoolean("complete") || data.getBoolean("has_more")) {
                data = call(chat, "read", JSONObject().put("terminal_id", id).put("command_id", command)
                    .put("offset", data.getInt("next_offset")).put("wait_ms", 20000))
                output.append(data.getString("output"))
            }
            assertEquals(output.toString(), exit, data.getJSONObject("command").getInt("exit_code"))
            output.toString()
        }
        fun file(chat: String, path: String, text: String): File = store.workspace.folder(chat, "workspace").resolve(path)
            .apply { parentFile!!.mkdirs(); writeText(text) }
        val marker = File(context.filesDir, "runtime/rootfs/capri-node-preserve-${java.util.UUID.randomUUID()}")
        try {
            marker.writeText("keep rootfs")
            store.workspace.folder(first, "home").resolve("keep.txt").writeText("keep home")
            file(first, "keep.txt", "keep workspace")
            runtime.install()
            for ((chat, version) in listOf(first to "1.0.0", second to "2.0.0")) {
                file(chat, "fixture/package.json", JSONObject().put("name", "capri-local-fixture").put("version", version)
                    .put("main", "index.js").put("bin", JSONObject().put("capri-fixture", "cli.js")).toString())
                file(chat, "fixture/index.js", "module.exports = '$version';\n")
                file(chat, "fixture/cli.js", "#!/usr/bin/env node\nconsole.log('fixture-$version');\n").setExecutable(true, true)
            }
            val a = call(first, "open").getString("terminal_id")
            val b = call(second, "open").getString("terminal_id")
            val diagnostic = runtime.execute(first, "exec(open('/opt/capri/bootstrap/environment_probe.py').read())")
            assertTrue(diagnostic, diagnostic.startsWith("exit=0\n"))
            val environment = JSONObject(diagnostic.substringAfter('\n'))
            for (name in listOf("node", "npm", "npx", "gcc", "g++", "make", "pkg-config", "git", "curl", "ssh"))
                assertTrue("$name: $diagnostic", environment.getJSONObject("tools").getJSONObject(name).getBoolean("available"))
            assertTrue(environment.getJSONObject("development").getBoolean("python_headers"))
            assertTrue(run(first, a, "node -e \"console.log(new Intl.DateTimeFormat('en').format(new Date()));console.log(new URL('https://例子.com').hostname)\"").contains("xn--fsqu00a.com"))
            for ((chat, id, version) in listOf(Triple(first, a, "1.0.0"), Triple(second, b, "2.0.0"))) {
                val installed = run(chat, id, """
                    cd /workspace && npm install --offline --ignore-scripts ./fixture &&
                    node -e "if(require('capri-local-fixture')!=='$version')process.exit(1);console.log('local-$version')" &&
                    npm install -g --offline --ignore-scripts ./fixture && capri-fixture &&
                    npx --offline --no-install capri-fixture && npm config get prefix && npm config get cache
                """.trimIndent())
                assertTrue(installed, installed.contains("local-$version")); assertTrue(installed, installed.contains("fixture-$version"))
                assertTrue(installed, installed.contains("/root/.local/npm")); assertTrue(installed, installed.contains("/root/.cache/npm"))
            }
            assertTrue(run(first, a, "node -e \"if(require('capri-local-fixture')!=='1.0.0')process.exit(1)\" && capri-fixture")
                .contains("fixture-1.0.0"))
            file(first, "hello.c", """
                #include <stdio.h>
                #include <math.h>
                int main(int argc, char **argv) { volatile double x = argc + 3; printf("C %.0f\n", sqrt(x)); return 0; }
            """.trimIndent())
            file(first, "hello.cpp", """
                #include <algorithm>
                #include <iostream>
                #include <vector>
                #include <thread>
                int main() { std::vector<int> a{3,1,2}; std::thread t([&]{std::sort(a.begin(),a.end());}); t.join();
                  for(auto v:a) std::cout<<v; std::cout<<" C++ OK\n"; }
            """.trimIndent())
            file(first, "Makefile", "all: hello-c hello-cpp\nhello-c: hello.c\n\t${'$'}(CC) hello.c -lm -o hello-c\nhello-cpp: hello.cpp\n\t${'$'}(CXX) -std=c++17 hello.cpp -pthread -o hello-cpp\n")
            val compiled = run(first, a, "cd /workspace && make -j2 && ./hello-c && ./hello-cpp && file hello-c && readelf -h hello-c")
            assertTrue(compiled, compiled.contains("C 2")); assertTrue(compiled, compiled.contains("123 C++ OK"))
            assertTrue(compiled, compiled.contains("AArch64"))
            file(first, "native.c", """
                #include <Python.h>
                static PyObject *answer(PyObject *self, PyObject *args) { return PyLong_FromLong(42); }
                static PyMethodDef methods[] = {{"answer", answer, METH_NOARGS, "answer"}, {NULL,NULL,0,NULL}};
                static struct PyModuleDef module = {PyModuleDef_HEAD_INIT, "capri_native", NULL, -1, methods};
                PyMODINIT_FUNC PyInit_capri_native(void) { return PyModule_Create(&module); }
            """.trimIndent())
            run(first, a, "cd /workspace && cc -shared -fPIC native.c -o capri_native.so")
            val extension = runtime.execute(first, "import sys;sys.path.insert(0,'/workspace');import capri_native;assert capri_native.answer()==42;print('native-extension-ok')")
            assertTrue(extension, extension.contains("exit=0") && extension.contains("native-extension-ok"))
            file(first, "broken.c", "int main( { broken }")
            assertTrue(run(first, a, "cd /workspace && gcc broken.c -o should-not-exist", 1).contains("error:"))
            run(second, b, "test ! -e /workspace/hello-c && test ! -e /workspace/capri_native.so && test ! -e /root/keep.txt")
            val registry = run(first, a, "cd /workspace && npm install --ignore-scripts --fetch-retries=0 --fetch-timeout=20000 is-number@7.0.0 && node -e \"if(!require('is-number')(42))process.exit(1);console.log('registry-https-ok')\"")
            assertTrue(registry, registry.contains("registry-https-ok"))
            assertEquals("keep rootfs", marker.readText())
            assertEquals("keep home", store.workspace.folder(first, "home").resolve("keep.txt").readText())
            assertEquals("keep workspace", store.workspace.folder(first, "workspace").resolve("keep.txt").readText())
        } finally {
            marker.delete()
            for (chat in listOf(first, second)) {
                manager.list(chat).forEach { record ->
                    val params = JSONObject().put("terminal_id", record.getString("terminal_id"))
                    call(chat, "close", params); call(chat, "delete", params)
                }
                store.workspace.root(chat).deleteRecursively()
            }
        }
    }
}
