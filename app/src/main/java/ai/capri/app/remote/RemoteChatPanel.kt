package ai.capri.app.remote

import android.content.Intent
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.capri.app.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

@Composable
internal fun RemoteModeHome(store: Store, onLocalMode: () -> Unit) {
    Scaffold { insets ->
        Column(Modifier.padding(insets).fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("远程 Capri", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onLocalMode) { Text("切换到本地") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteChatPanel(store, autoConnect = true)
            }
        }
    }
}

@Composable
internal fun RemoteChatPanel(store: Store, autoConnect: Boolean = false, onRemoteMode: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember(store) { runCatching { store.remoteChatConfig() }.getOrNull() }
    var address by remember { mutableStateOf(saved?.optString("address").orEmpty()) }
    var token by remember { mutableStateOf(saved?.optString("token").orEmpty()) }
    var certificate by remember {
        mutableStateOf(saved?.optString("certificate")?.takeIf(String::isNotEmpty)?.let {
            Base64.decode(it, Base64.NO_WRAP)
        })
    }
    var client by remember { mutableStateOf<RemoteChatClient?>(null) }
    var messages by remember { mutableStateOf(emptyList<RemoteChatClient.Message>()) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var deviceConfig by remember(store) { mutableStateOf(runCatching { store.remoteDeviceConfig() }.getOrNull()) }
    var deviceEnabled by remember(store) { mutableStateOf(store.remoteDeviceEnabled()) }
    fun perform(failure: String, action: suspend () -> Unit) {
        busy = true
        status = ""
        scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = failure
            } finally {
                busy = false
            }
        }
    }

    val chooseCa = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform("无法读取 CA 证书，请选择有效的 CA 文件。") {
            certificate = withContext(Dispatchers.IO) {
                val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = it.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= 16_384)
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                RemoteChatClient.parseCa(bytes).encoded
            }
        }
    }
    fun connect(save: Boolean) {
        perform("连接失败，请检查地址、访问令牌和证书。") {
            val (remote, history) = withContext(Dispatchers.IO) {
                val service = RemoteChatClient(address, token.trim(), certificate)
                val history = service.history()
                if (save) store.saveRemoteChatConfig(JSONObject()
                    .put("address", RemoteChatClient.normalizeAddress(address)).put("token", token.trim())
                    .put("certificate", certificate?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty()))
                service to history
            }
            messages = history
            client = remote
        }
    }
    LaunchedEffect(autoConnect, store) {
        if (autoConnect && address.isNotBlank() && token.isNotBlank()) connect(save = false)
    }
    val connected = client
    if (connected == null) {
        Text("连接后可继续服务端的会话。")
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(),
            enabled = !busy, singleLine = true, label = { Text("Capri 服务地址") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(),
            enabled = !busy, singleLine = true, label = { Text("访问令牌") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = PasswordVisualTransformation())
        TextButton(onClick = { chooseCa.launch(arrayOf("*/*")) }, enabled = !busy) {
            Text(if (certificate == null) "导入测试 CA（可选）" else "已选择测试 CA · 更换")
        }
        if (certificate != null) TextButton(onClick = { certificate = null }, enabled = !busy) { Text("使用系统证书") }
        Button(onClick = { connect(save = true) }, enabled = !busy && address.isNotBlank() && token.isNotBlank()) { Text("连接") }
    } else {
        if (onRemoteMode != null) Button(onClick = onRemoteMode, enabled = !busy) { Text("进入远程模式") }
        TextButton(onClick = { client = null; messages = emptyList(); status = "" }, enabled = !busy) { Text("切换连接") }
        TextButton(onClick = {
            perform("无法读取远程会话。") { messages = withContext(Dispatchers.IO) { connected.history() } }
        }, enabled = !busy) { Text("刷新会话") }
        if (autoConnect) {
            Text("设备执行器使用持久 WSS 通道接收云端任务；空闲时不轮询、不持有 CPU 唤醒锁。")
            OutlinedTextField(pairingCode, { pairingCode = it.trim() }, Modifier.fillMaxWidth(),
                enabled = !busy && deviceConfig == null, singleLine = true, label = { Text("一次性配对码") },
                visualTransformation = PasswordVisualTransformation())
            Button(onClick = {
                perform("配对失败，请检查配对码是否过期及服务端连接。") {
                    val cap = withContext(Dispatchers.IO) {
                        ai.capri.app.nativebridge.AndroidTool(context.applicationContext as android.app.Application).capabilities()
                    }
                    val actions = cap.optJSONArray("actions") ?: JSONArray()
                    val names = JSONArray().put("device.describe")
                    for (index in 0 until actions.length()) {
                        val item = actions.getJSONObject(index)
                        if (item.optString("status") != "unsupported") names.put("android." + item.getString("action"))
                    }
                    val paired = withContext(Dispatchers.IO) { connected.pairDevice(pairingCode, names) }
                    val savedConfig = JSONObject().put("device_id", paired.getString("device_id"))
                        .put("device_token", paired.getString("device_token"))
                        .put("socket_url", paired.getString("socket_url"))
                    store.saveRemoteDeviceConfig(savedConfig)
                    deviceConfig = savedConfig
                    pairingCode = ""
                    status = "设备已配对，可以启用接管。"
                }
            }, enabled = !busy && deviceConfig == null && pairingCode.isNotBlank()) { Text("配对设备") }
            if (deviceConfig != null) {
                Text("已配对 · ${deviceConfig?.optString("device_id")}")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (deviceEnabled) "允许 Capri 云端接管此手机" else "设备接管已关闭")
                    Switch(checked = deviceEnabled, enabled = !busy, onCheckedChange = { enabled ->
                        perform(if (enabled) "无法启动设备执行器。" else "无法停止设备执行器。") {
                            if (enabled) {
                                store.setRemoteDeviceEnabled(true)
                                try { DeviceNodeService.start(context); deviceEnabled = true; status = "设备执行器正在连接。" }
                                catch (error: Exception) { store.setRemoteDeviceEnabled(false); throw error }
                            } else {
                                store.setRemoteDeviceEnabled(false)
                                DeviceNodeService.stop(context)
                                deviceEnabled = false
                                status = "已关闭设备接管。"
                            }
                        }
                    })
                }
                TextButton(onClick = {
                    perform("无法解除设备配对。") {
                        if (deviceEnabled) { store.setRemoteDeviceEnabled(false); DeviceNodeService.stop(context) }
                        store.saveRemoteDeviceConfig(null)
                        deviceConfig = null
                        deviceEnabled = false
                    }
                }, enabled = !busy) { Text("解除本机配对") }
            }
            if (!RemoteAppLaunch.hasUsageAccess(context)) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                enabled = !busy) { Text("授权读取当前前台应用状态") }
        }
        messages.forEach { Text((if (it.role == "user") "你：" else "Capri：") + it.content) }
        OutlinedTextField(draft, { if (it.length <= 4_000) draft = it }, Modifier.fillMaxWidth(),
            enabled = !busy, label = { Text("远程消息") })
        Button(onClick = {
            val text = draft.trim()
            perform("发送结果未确认，请刷新会话后再决定是否重发。") {
                withContext(Dispatchers.IO) { connected.send(text) }
                draft = ""
                messages = withContext(Dispatchers.IO) { connected.history() }
            }
        }, enabled = !busy && draft.isNotBlank()) { Text("发送到 Capri") }
    }
    if (busy) Text("正在等待 Capri 服务…")
    if (status.isNotEmpty()) Text(status)
}
