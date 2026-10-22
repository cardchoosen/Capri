package ai.capri.app

import android.content.Context
import android.system.Os
import java.io.File

/** Global client configuration; no sshd and no user-data resets. */
internal object SshEnvironment {
    @Synchronized fun prepare(context: Context): File {
        val directory = File(context.filesDir, "runtime/ssh").apply { mkdirs() }
        Os.chmod(directory.path, 448) // 0700
        for (name in listOf("config", "known_hosts")) {
            val file = File(directory, name)
            if (file.createNewFile()) Os.chmod(file.path, 384) // 0600
        }
        return directory
    }
    suspend fun identities(runtime: PythonRuntime, chat: String, name: String = "", pending: String = ""): String {
        val request = org.json.JSONObject().put("action", if (pending.isEmpty()) "list" else "import")
            .put("name", name).put("pending", pending).toString()
        // Only metadata enters the runtime script and persisted run record.
        return runtime.execute(chat, "import subprocess\np=subprocess.run(['/usr/bin/python3','/opt/capri/bootstrap/ssh_identity.py'],input=" +
            org.json.JSONObject.quote(request) + ",text=True,capture_output=True)\nprint(p.stdout,end='')\nraise SystemExit(p.returncode)")
    }
    fun inspection(generate: Boolean, root: String = "/opt/capri/ssh"): String {
        require(root.matches(Regex("[a-zA-Z0-9/_-]+")))
        return """
        import pathlib,subprocess,json,os,fcntl
        root=pathlib.Path('$root')
        private=root/'id_ed25519';public=root/'id_ed25519.pub'
        with (root/'.keygen.lock').open('a') as lock:
            fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
            if private.is_symlink() or public.is_symlink(): raise RuntimeError('SSH密钥路径异常，请先检查，不自动覆盖')
            if ${if (generate) "True" else "False"} and not private.exists():
                if public.exists(): raise RuntimeError('公钥已存在但私钥缺失；不自动覆盖，请先检查')
                result=subprocess.run(['/opt/capri/tools/usr/bin/ssh-keygen','-q','-t','ed25519','-N','','-C','capri-device','-f',str(private)],capture_output=True,text=True,timeout=20)
                if result.returncode: raise RuntimeError('SSH密钥生成失败：'+result.stderr[-1000:])
                os.chmod(private,0o600);os.chmod(public,0o644)
            if private.exists() and not public.exists(): raise RuntimeError('私钥存在但公钥缺失；请先检查，不重新生成')
            if private.exists():
                derived=subprocess.run(['/opt/capri/tools/usr/bin/ssh-keygen','-y','-P','','-f',str(private)],capture_output=True,text=True,stdin=subprocess.DEVNULL,timeout=5)
                if derived.returncode or derived.stdout.split()[:2] != public.read_text().split()[:2]:
                    raise RuntimeError('设备SSH公私钥无法核验，请先检查，不自动覆盖')
            result={'scope':'application; shared by all chats','key_exists':private.exists(),'public_key':public.read_text().strip() if public.exists() else '',
                    'config':'/opt/capri/ssh/config','known_hosts':'/opt/capri/ssh/known_hosts'}
            if public.exists():
                fingerprint=subprocess.run(['/opt/capri/tools/usr/bin/ssh-keygen','-lf',str(public)],capture_output=True,text=True,timeout=5)
                if fingerprint.returncode: raise RuntimeError('SSH公钥检查失败')
                result['fingerprint']=fingerprint.stdout.strip()
            print(json.dumps(result,ensure_ascii=False,indent=2))
    """.trimIndent()
    }
}
