package ai.capri.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import android.system.Os
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable internal fun SshPanel(runtime: PythonRuntime, chat: String, onDismiss: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var publicKey by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var identityName by remember { mutableStateOf("") }
    var identities by remember { mutableStateOf(emptyList<JSONObject>()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun applyIdentities(output: String): Boolean {
        val data = runCatching { JSONObject(output.substringAfter('\n')) }.getOrNull()
        if (!output.startsWith("exit=0\n") || data == null) {
            status = data?.optString("error")?.takeIf { it.isNotEmpty() } ?: "SSH 密钥操作失败"
            return false
        }
        val items = data.getJSONArray("identities")
        identities = (0 until items.length()).map { items.getJSONObject(it) }
        return true
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            var pending: File? = null
            try {
                val selectedName = identityName.trim()
                require(selectedName.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,39}")))
                val token = "import-${UUID.randomUUID()}"
                withContext(Dispatchers.IO) {
                    val file = File(SshEnvironment.prepare(context), token)
                    pending = file
                    check(file.createNewFile())
                    Os.chmod(file.path, 384)
                    context.contentResolver.openInputStream(uri).use { input ->
                        checkNotNull(input)
                        file.outputStream().use { output ->
                            val buffer = java.io.ByteArrayOutputStream()
                            val block = ByteArray(4096)
                            while (buffer.size() <= 65536) {
                                val count = input.read(block, 0, minOf(block.size, 65537 - buffer.size()))
                                if (count < 0) break
                                buffer.write(block, 0, count)
                            }
                            val bytes = buffer.toByteArray()
                            require(bytes.isNotEmpty() && bytes.size <= 65536)
                            output.write(bytes)
                        }
                    }
                }
                if (applyIdentities(SshEnvironment.identities(runtime, chat, selectedName, token))) {
                    identityName = ""; status = "私钥已导入，所有会话可按名称使用"
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { status = "导入失败：请检查文件是否可读、非空且不超过 64 KiB" }
            finally { pending?.delete(); busy = false }
        }
    }
    val clipboard = LocalClipboardManager.current
    suspend fun inspect(generate: Boolean) {
        busy = true
        try {
            val output = runtime.execute(chat, SshEnvironment.inspection(generate))
            if (!output.startsWith("exit=0\n")) { status = output.take(2000); return }
            val data = JSONObject(output.substringAfter('\n'))
            publicKey = data.getString("public_key"); fingerprint = data.optString("fingerprint")
            status = if (publicKey.isEmpty()) "尚未生成设备密钥" else "设备公钥已就绪，所有会话共用"
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { status = "SSH 环境操作失败，请查看环境诊断" }
        finally { busy = false }
    }
    LaunchedEffect(Unit) {
        inspect(false)
        busy = true
        try { applyIdentities(SshEnvironment.identities(runtime, chat)) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { status = "密钥清单读取失败" }
        finally { busy = false }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("SSH · 全局") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SSH / SFTP / SCP 通过持续终端执行。设备密钥、连接配置与主机指纹由所有会话共用。", fontSize = 12.sp)
            Text(status, fontSize = 12.sp)
            Button(enabled = !busy && publicKey.isEmpty(), onClick = { scope.launch { inspect(true) } }) { Text(if (busy) "正在处理…" else "生成设备密钥") }
            if (publicKey.isNotEmpty()) {
                Text(fingerprint, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                SelectionContainer { Text(publicKey, fontSize = 11.sp) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(publicKey)); status = "公钥已复制" }) { Text("复制公钥") }
            }
            Text("使用 Ed25519 无口令设备密钥，私钥保留在应用私有目录。将公钥添加至目标服务器或 Git 托管平台后才能登录。", fontSize = 12.sp)
            HorizontalDivider()
            Text("导入对方提供的私钥", fontSize = 14.sp)
            OutlinedTextField(value = identityName, onValueChange = { identityName = it },
                enabled = !busy, singleLine = true, label = { Text("密钥名称，如 work-server") },
                supportingText = { Text("1–40 位字母、数字、下划线或短横线") })
            TextButton(enabled = !busy && identityName.trim().matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,39}")),
                onClick = { picker.launch(arrayOf("*/*")) }) { Text("选择私钥文件并导入") }
            Text("支持无口令 OpenSSH / PEM 私钥，最大 64 KiB。同名不覆盖。原始文件仍由你管理；私钥内容不会显示或发送给模型。", fontSize = 11.sp)
            for (identity in identities) {
                Text(identity.getString("name"), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                Text(identity.getString("fingerprint"), fontSize = 11.sp)
                Text(identity.getString("identity_file"), fontSize = 11.sp)
            }
            Text("可告诉 Capri：用 work-server 密钥连接指定服务器。她会按需查询名称、路径和指纹；服务器地址、用户名与主机指纹仍需提供或确认。", fontSize = 12.sp)
            Text("连接配置：/opt/capri/ssh/config\n主机指纹：/opt/capri/ssh/known_hosts", fontSize = 11.sp)
            Text("默认严格核对主机指纹；首次连接需先核对并登记指纹。连接配置与 known_hosts 仍通过终端管理，不支持后台密码登录。", fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("完成") } })
}
