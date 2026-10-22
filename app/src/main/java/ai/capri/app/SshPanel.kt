package ai.capri.app

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
    val scope = rememberCoroutineScope()
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
    LaunchedEffect(Unit) { inspect(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("SSH · 全局") }, text = {
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
            Text("连接配置：/opt/capri/ssh/config\n主机指纹：/opt/capri/ssh/known_hosts", fontSize = 11.sp)
            Text("默认严格核对主机指纹；首次连接需先核对并登记指纹。首版通过终端管理连接与 known_hosts，不支持后台密码登录。", fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
