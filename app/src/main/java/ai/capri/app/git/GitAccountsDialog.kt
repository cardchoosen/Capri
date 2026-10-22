package ai.capri.app.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun GitAccountsDialog(accounts: GitAccounts, onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf(accounts.list()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var host by remember { mutableStateOf("github.com") }
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var repository by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun reset() { editing = null; host = "github.com"; username = ""; token = ""; repository = ""; message = "" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Git 账号 · 全局") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("所有会话共用，每个 HTTPS 主机一个账号。Token 加密保存，不回显；修改后对后续认证生效。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            entries.forEach { account ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f).clickable(enabled = !busy) {
                        editing = account.host; host = account.host; username = account.username; token = ""; repository = ""; message = ""
                    }.padding(vertical = 6.dp)) {
                        Text(account.host, color = MaterialTheme.colorScheme.primary)
                        Text(account.username, fontSize = 12.sp)
                    }
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                withContext(Dispatchers.IO) { accounts.remove(account.host) }
                                entries = accounts.list(); if (editing == account.host) reset()
                                message = "账号已删除，后续认证不再提供此 Token"
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { message = "删除失败" }
                            finally { busy = false }
                        }
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
            if (editing != null) TextButton(enabled = !busy, onClick = { reset() }) { Text("添加其他主机") }
            OutlinedTextField(host, { host = it; message = "" }, label = { Text("HTTPS 主机") }, placeholder = { Text("github.com") }, enabled = !busy && editing == null, singleLine = true)
            OutlinedTextField(username, { username = it; message = "" }, label = { Text("Git 用户名") }, enabled = !busy, singleLine = true)
            OutlinedTextField(token, { token = it; message = "" }, label = { Text(if (editing != null) "新 Token（留空保留）" else "Token") },
                enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        val canonical = GitCredentialPolicy.host(host)
                        withContext(Dispatchers.IO) { accounts.save(host, username, token) }
                        editing = canonical; host = canonical; token = ""; entries = accounts.list()
                        message = "已保存，所有会话的后续 Git 认证可使用"
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: IllegalArgumentException) { message = error.message ?: "输入无效" }
                    catch (_: Exception) { message = "保存失败，请重试" }
                    finally { busy = false }
                }
            }) { Text("保存账号") }
            OutlinedTextField(repository, { repository = it; message = "" }, label = { Text("测试仓库 HTTPS 地址（可选）") },
                placeholder = { Text("https://github.com/owner/repo.git") }, enabled = !busy, singleLine = true)
            TextButton(enabled = !busy && editing != null && repository.isNotBlank(), onClick = {
                scope.launch {
                    busy = true; message = "正在检查仓库读取权限…"
                    try { message = withContext(Dispatchers.IO) { GitAccountTest.check(accounts, editing!!, repository) } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (error: IllegalArgumentException) { message = error.message ?: "仓库地址无效" }
                    catch (_: Exception) { message = "检查失败，请重试" }
                    finally { busy = false }
                }
            }) { Text("测试已保存账号") }
            if (message.isNotEmpty()) Text(message, fontSize = 12.sp)
            Text("仅支持 HTTPS Token；权限由托管平台决定。提交署名仍按仓库/会话配置。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
