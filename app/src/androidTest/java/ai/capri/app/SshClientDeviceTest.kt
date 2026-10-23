package ai.capri.app

import ai.capri.app.terminal.TerminalManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SshClientDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val chat = "ssh-client-test"
    private val manager get() = TerminalManager.get(context)
    private val store get() = Store(context)
    private val runtime get() = PythonRuntime(context, store.workspace)
    private fun foreground() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    @Test fun importedIdentitiesStayGlobalAndNeverExposePrivateBytes() = runBlocking<Unit> {
        foreground()
        val output = runtime.execute(chat, """
            import runpy,pathlib,subprocess,shutil,json,os
            root=pathlib.Path('/workspace/import-fixture')
            shutil.rmtree(root,ignore_errors=True);root.mkdir()
            manage=runpy.run_path('/opt/capri/bootstrap/ssh_identity.py')['manage']
            keygen='/usr/bin/ssh-keygen'
            try:
                subprocess.run([keygen,'-q','-t','ed25519','-N','','-f',str(root/'source')],check=True)
                shutil.copyfile(root/'source',root/'import-123')
                first=manage(root,'import','server','import-123')
                assert first['identities'][0]['name']=='server'
                assert 'PRIVATE KEY' not in json.dumps(first)
                assert not (root/'import-123').exists()
                assert (root/'identities/server').stat().st_mode & 0o777 == 0o600
                original=(root/'identities/server').read_bytes()
                shutil.copyfile(root/'source',root/'import-456')
                try: manage(root,'import','server','import-456');raise AssertionError('overwritten')
                except ValueError: pass
                assert (root/'identities/server').read_bytes()==original
                assert not (root/'import-456').exists()
                (root/'import-789').write_text('invalid-private-key')
                try: manage(root,'import','invalid','import-789');raise AssertionError('accepted invalid')
                except ValueError: pass
                assert not (root/'identities/invalid').exists()
                subprocess.run([keygen,'-q','-t','ed25519','-N','fixture-passphrase','-f',str(root/'encrypted')],check=True)
                shutil.copyfile(root/'encrypted',root/'import-abc')
                try: manage(root,'import','locked','import-abc');raise AssertionError('accepted encrypted')
                except ValueError: pass
                assert not (root/'identities/locked').exists()
                assert len(manage(root,'list')['identities'])==1
                assert 'PRIVATE KEY' not in (root/'identities.json').read_text()
                print('identity import, validation, no overwrite, permissions and public-only inventory passed')
            finally: shutil.rmtree(root)
        """.trimIndent())
        assertTrue(output, output.startsWith("exit=0\n"))
        assertTrue(output, output.contains("public-only inventory passed"))
    }
    @Test fun prepareFixture() = runBlocking<Unit> {
        assumeTrue(arguments.getString("ssh_fixture") == "prepare")
        foreground(); runtime.install()
        store.workspace.folder(chat, "workspace").resolve("ssh-fixture").mkdirs()
        val probe = runtime.execute(chat, "import subprocess,os;print('uid',os.getuid());p=subprocess.run(['ssh','-G','fixture.invalid'],capture_output=True,text=True);print(p.stderr);assert p.returncode==0;print('ssh-config-ok')")
        assertTrue(probe, probe.contains("exit=0"))
        val created = runtime.execute(chat, SshEnvironment.inspection(true, "/workspace/ssh-fixture"))
        assertTrue(created, created.contains("exit=0"))
        val publicKey = File(store.workspace.folder(chat,"workspace"), "ssh-fixture/id_ed25519.pub").readText()
        assertTrue(publicKey.startsWith("ssh-ed25519 "))
        val again = runtime.execute(chat, SshEnvironment.inspection(true, "/workspace/ssh-fixture"))
        assertTrue(again, again.contains("exit=0"))
        assertEquals(publicKey, File(store.workspace.folder(chat,"workspace"), "ssh-fixture/id_ed25519.pub").readText())
    }
    private suspend fun invoke(action: String, params: JSONObject): JSONObject {
        val r = JSONObject(manager.execute(chat, JSONObject().put("action", action).put("params", params)))
        assertEquals(r.toString(), "ok", r.getString("status")); return r.getJSONObject("data")
    }
    private suspend fun command(terminal: String, text: String, expected: Int? = 0, timeout: Int = 60): JSONObject = withTimeout(120_000) {
        val job = invoke("run", JSONObject().put("terminal_id", terminal).put("command", text).put("timeout_seconds", timeout).put("wait_ms", 1000))
        var offset = 0; val output = StringBuilder()
        while (true) {
            val read = invoke("read", JSONObject().put("terminal_id", terminal).put("command_id", job.getString("command_id")).put("offset", offset).put("wait_ms", 20000))
            output.append(read.getString("output")); offset = read.getInt("next_offset")
            if (read.getBoolean("complete")) {
                val result = read.getJSONObject("command")
                if (expected != null) assertEquals(output.toString(), expected, result.getInt("exit_code"))
                return@withTimeout result.put("output", output.toString())
            }
        }
        @Suppress("UNREACHABLE_CODE") JSONObject()
    }
    @Test fun sshSftpScpAndGitUseKeysAndRejectUnknownOrChangedHosts() = runBlocking<Unit> {
        val port = arguments.getString("ssh_fixture_port")?.toInt()
        assumeTrue(port != null)
        foreground()
        val work = store.workspace.folder(chat, "workspace")
        val folder = File(work, "ssh-fixture")
        val known = File(folder, "known_hosts")
        val key = arguments.getString("ssh_host_key")!!
        val changed = arguments.getString("ssh_changed_key")!!
        val origin = "[127.0.0.1]:$port"
        val options = "-o UserKnownHostsFile=/workspace/ssh-fixture/known_hosts -o StrictHostKeyChecking=yes -i /workspace/ssh-fixture/id_ed25519"
        try {
            val id = invoke("open", JSONObject().put("title", "SSH client acceptance")).getString("terminal_id")
            known.writeText("")
            val unknown = command(id, "ssh $options -p $port fixture-user@127.0.0.1 capri-echo", expected = 255)
            assertTrue(unknown.toString(), unknown.getString("output").contains("Host key verification failed"))
            command(id, "ssh-keyscan -T 5 -p $port 127.0.0.1 > /workspace/candidate-hosts && ssh-keygen -lf /workspace/candidate-hosts")
            assertTrue(File(work, "candidate-hosts").readText().contains(key))
            known.writeText("$origin ssh-rsa $key\n")
            command(id, "ssh-keygen -q -t ed25519 -N '' -f /workspace/ssh-fixture/wrong_key")
            val refused = command(id, "ssh -o UserKnownHostsFile=/workspace/ssh-fixture/known_hosts -o StrictHostKeyChecking=yes -i /workspace/ssh-fixture/wrong_key -p $port fixture-user@127.0.0.1 capri-echo", expected = 255)
            assertTrue(refused.toString(), refused.getString("output").contains("Permission denied"))
            val remote = command(id, "ssh $options -p $port fixture-user@127.0.0.1 capri-echo")
            assertTrue(remote.toString(), remote.getString("output").contains("fixture-remote-ok"))
            command(id, "ssh $options -p $port fixture-user@127.0.0.1 capri-fail", expected = 17)
            File(work, "upload.txt").writeText("Capri SSH transfer 中文\n")
            File(work, "transfer.batch").writeText("put /workspace/upload.txt /upload.txt\nget /upload.txt /workspace/download.txt\n")
            command(id, "sftp $options -P $port -b /workspace/transfer.batch fixture-user@127.0.0.1")
            assertEquals(File(work, "upload.txt").readText(), File(work, "download.txt").readText())
            command(id, "scp $options -P $port /workspace/upload.txt fixture-user@127.0.0.1:/scp.txt && scp $options -P $port fixture-user@127.0.0.1:/scp.txt /workspace/scp-download.txt")
            assertEquals(File(work, "upload.txt").readText(), File(work, "scp-download.txt").readText())
            command(id, "GIT_SSH_COMMAND='ssh $options' git clone ssh://fixture-user@127.0.0.1:$port/repo ssh-repo && test -f ssh-repo/README")
            known.writeText("$origin ssh-rsa $changed\n")
            val mismatch = command(id, "ssh $options -p $port fixture-user@127.0.0.1 capri-echo", expected = 255)
            assertTrue(mismatch.toString(), mismatch.getString("output").contains("REMOTE HOST IDENTIFICATION HAS CHANGED"))
            known.writeText("$origin ssh-rsa $key\n")
            val timed = command(id, "ssh $options -p $port fixture-user@127.0.0.1 capri-sleep", expected = null, timeout = 2)
            assertEquals(timed.toString(), "timeout", timed.getString("state"))
            command(id, "echo ssh-shell-still-ready")
            val defaults = command(id, "ssh -G fixture.invalid | grep -E '^(batchmode|stricthostkeychecking|userknownhostsfile|identityfile|forwardagent) '")
            assertTrue(defaults.toString(), defaults.getString("output").contains("/opt/capri/ssh/known_hosts"))
            val diagnostic = runtime.execute(chat, "exec(open('/opt/capri/bootstrap/environment_probe.py').read())")
            assertTrue(diagnostic, diagnostic.contains("OpenSSH_"))
        } finally {
            for (record in manager.list(chat)) {
                val params = JSONObject().put("terminal_id", record.getString("terminal_id"))
                manager.execute(chat, JSONObject().put("action", "close").put("params", params))
                manager.execute(chat, JSONObject().put("action", "delete").put("params", params))
            }
            store.workspace.root(chat).deleteRecursively()
        }
    }
}
