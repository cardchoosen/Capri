package ai.capri.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import android.content.Intent
import kotlinx.coroutines.launch
import androidx.lifecycle.withResumed
import androidx.lifecycle.compose.LocalLifecycleOwner

private val Ink = Color(0xFF080B09)
private val Panel = Color(0xFF111612)
private val Line = Color(0xFF29322B)
private val Green = Color(0xFFA5FF75)
private val Red = Color(0xFFFF514F)
private val White = Color(0xFFF4F6F3)
private val Muted = Color(0xFF939E94)

class MainActivity : ComponentActivity() {
    private val model: CapriModel by viewModels()
    override fun onResume() { super.onResume(); model.androidTools.permissions.attach(this) }
    override fun onPause() { model.androidTools.permissions.detach(this); super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugBridge.attach(model)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent { MaterialTheme(
            colorScheme = darkColorScheme(primary = Green, onPrimary = Ink, secondary = Red, onSecondary = Ink,
                secondaryContainer = Color(0xFF24311F), onSecondaryContainer = Green,
                background = Ink, surface = Panel, onSurface = White, outline = Line, error = Red),
            shapes = Shapes(extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(2.dp),
                medium = RoundedCornerShape(4.dp), large = RoundedCornerShape(4.dp), extraLarge = RoundedCornerShape(4.dp))) { Capri(model) } }
    }
}

@Composable private fun Mark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawPath(Path().apply { moveTo(w*.42f,h*.14f); lineTo(w*.17f,h*.43f); lineTo(w*.42f,h*.8f); lineTo(w*.59f,h*.59f) }, Green, style = Stroke(w*.065f))
        drawPath(Path().apply { moveTo(w*.62f,h*.18f); lineTo(w*.84f,h*.43f); lineTo(w*.61f,h*.72f) }, Red, style = Stroke(w*.065f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Capri(vm: CapriModel) {
    val update = vm.updates.collectAsState().value
    val session = vm.current()
    val entries = remember(session.id, update) { session.messages.filter(ToolPresentation::visible) }
    val chatPositions = remember { mutableMapOf<String, ChatScrollState>() }
    val chatPosition = chatPositions.getOrPut(session.id) { ChatScrollState(entries.size) }
    val busy = vm.jobs.containsKey(session.id)
    val notice = vm.notices[session.id]
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var tab by remember { mutableIntStateOf(0) }
    var sshPanel by remember { mutableStateOf(false) }
    var gitAccounts by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var nativeCapabilities by remember { mutableStateOf(false) }
    var terminalPanel by remember { mutableStateOf(false) }
    val pendingNativePermission by vm.androidTools.permissions.pending.collectAsState()
    val nativePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.androidTools.permissions.complete(it); vm.refresh() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(pendingNativePermission?.id, lifecycleOwner) {
        val pending = pendingNativePermission ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.withResumed {
            vm.androidTools.permissions.take(pending.id)?.let { nativePermissionLauncher.launch(it.toTypedArray()) }
        }
    }
    var input by remember(session.id) { mutableStateOf("") }
    var banner by remember { mutableStateOf("") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = vm.selected
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val outcome = runCatching {
                uris.forEach { uri ->
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } ?: "import-${System.currentTimeMillis()}"
                    val safe = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).ifBlank { "imported" }
                    val file = vm.store.workspace.file(id, safe)
                    require(!file.exists()) { "同名文件已存在：$safe" }
                    val staged = java.io.File(vm.store.workspace.folder(id, "tmp"), "import-${java.util.UUID.randomUUID()}")
                    try {
                    context.contentResolver.openInputStream(uri)!!.use { source ->
                        staged.outputStream().use { target ->
                            val buffer = ByteArray(8192); var total = 0
                            while (true) { val count = source.read(buffer); if (count < 0) break; total += count; require(total <= 20_000_000) { "单个导入文件最大20MB" }; target.write(buffer, 0, count) }
                        }
                    }
                    java.nio.file.Files.move(staged.toPath(), file.toPath())
                    } finally { staged.delete() }
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                banner = outcome.fold({ "已导入 ${uris.size} 个文件" }, { it.message ?: "导入失败" })
                vm.refresh()
            }
        }
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = Ink, drawerShape = RoundedCornerShape(0.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Mark(Modifier.size(40.dp)); Text("CAPRI", letterSpacing = 5.sp, fontWeight = FontWeight.Bold) }
            Button(enabled = !vm.migrating, onClick = { vm.create(); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("新会话", Modifier.padding(8.dp)) }
            Text("你的任务空间", color = Muted, modifier = Modifier.padding(16.dp), fontSize = 12.sp)
            LazyColumn { items(vm.sessions.toList(), key = { it.id }) { item ->
                NavigationDrawerItem(shape = RoundedCornerShape(2.dp), label = { Text(item.title, maxLines = 1) }, selected = item.id == session.id, onClick = { vm.select(item.id); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            } }
        }
    }) {
        Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Ink, topBar = {
            Column {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "会话列表", tint = White) }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("C A P R I", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                        Text(if (busy) "●  ${notice ?: "思考中"}" else "●  随时待命", color = Green, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Tune, "设置", tint = Red) }
                }
                HorizontalDivider(color = Line)
            }
        }, bottomBar = {
            // Keep navigation measured throughout IME animation; the keyboard covers it.
            Column(Modifier.navigationBarsPadding()) {
                HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("对话" to Icons.Outlined.ChatBubbleOutline, "文件" to Icons.Outlined.FolderOpen, "运行" to Icons.Outlined.Terminal).forEachIndexed { index, pair ->
                        TextButton(onClick = { tab = index }, colors = ButtonDefaults.textButtonColors(contentColor = if (tab == index) Green else Muted)) { Icon(pair.second, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(pair.first, fontSize = 12.sp) }
                    }
                }
            }
        }) { padding ->
            // Apply only the IME inset remaining after the stable Scaffold bottom bar.
            Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
                if (banner.isNotEmpty()) { TextButton(onClick = { banner = "" }) { Text(banner, color = Green, fontSize = 12.sp) } }
                ai.capri.app.preview.WorkspaceSurface(vm.previews, vm.browser, vm.store.workspace, session.id,
                    active = tab == 0, modifier = Modifier.weight(1f), onExport = { path ->
                        val file = vm.store.workspace.file(session.id, path)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "导出文件"))
                    }) {
                when (tab) {
                    0 -> {
                        if (entries.isEmpty()) {
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
                                Text("CAPRI / 摩羯", color = Muted, fontSize = 12.sp, letterSpacing = 2.sp)
                                Spacer(Modifier.height(20.dp)); Mark(Modifier.size(94.dp)); Spacer(Modifier.height(18.dp))
                                Text("好了，\n你想改写什么？", color = White, fontSize = 32.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(30.dp))
                                listOf("介绍一下你自己", "帮我整理这个工作区").forEach { label ->
                                    OutlinedButton(onClick = { input = label }, shape = RoundedCornerShape(2.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.padding(bottom = 8.dp)) { Text(label, color = White, fontSize = 12.sp); Spacer(Modifier.width(20.dp)); Icon(Icons.Outlined.NorthEast, null, Modifier.size(14.dp), tint = Green) }
                                }
                            }
                        } else {
                            ConversationTimeline(entries, busy, chatPosition, Modifier.weight(1f), vm.activeTools[session.id], vm.store.workspace, session.id)
                        }
                        vm.environmentState().takeIf { it.phase != "ready" }?.let { state ->
                            Text(state.message, color = if (state.phase == "error") Red else Muted, fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                        }
                        notice?.takeUnless { vm.activeTools.containsKey(session.id) }?.let { Text(it, color = Green, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).border(1.dp, if (busy) Red else Line, RoundedCornerShape(2.dp)).background(Panel, RoundedCornerShape(2.dp)).padding(2.dp), verticalAlignment = Alignment.Bottom) {
                            IconButton(enabled = !vm.migrating, onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.Add, "导入文件", tint = Muted) }
                            TextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("下达你的指令…", fontSize = 14.sp) }, maxLines = 5, colors = TextFieldDefaults.colors(focusedContainerColor = Panel, unfocusedContainerColor = Panel, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                            FilledIconButton(shape = RoundedCornerShape(2.dp), onClick = { if (busy) vm.stop() else if(input.isNotBlank()) { if(vm.send(input)) { chatPosition.followLatest = true; input = ""; focusManager.clearFocus(force = true); keyboard?.hide() } } }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if(busy) Red else Green, contentColor = Ink), modifier = Modifier.padding(bottom = 4.dp)) { Icon(if(busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if(busy) "停止" else "发送") }
                        }
                    }
                    1 -> {
                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("工作区", fontSize = 24.sp); Text(session.title, color = Muted, fontSize = 12.sp) }; OutlinedButton(enabled = !vm.migrating, onClick = { importer.launch(arrayOf("*/*")) }) { Text("导入") } }
                        Text("文件仅属于当前会话 · 点击预览，右侧导出", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
                        val files = vm.store.workspace.list(session.id)
                        if(files.isEmpty()) Empty("这里还没有文件", "导入资料，或让 Capri 为你创建。")
                        LazyColumn(contentPadding = PaddingValues(16.dp)) { items(files) { file ->
                            Row(Modifier.fillMaxWidth().clickable {
                                vm.previews.openFile(session.id, file.relativeTo(vm.store.workspace.folder(session.id, "workspace")).path)
                                tab = 0; focusManager.clearFocus(); keyboard?.hide()
                            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Description, null, tint = Green)
                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(file.relativeTo(vm.store.workspace.folder(session.id, "workspace")).path); Text("${file.length()} bytes", fontSize = 11.sp, color = Muted) }
                                IconButton(onClick = {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "导出文件"))
                                }) { Icon(Icons.Outlined.IosShare, "导出", tint = Muted, modifier = Modifier.size(18.dp)) }
                            }; HorizontalDivider(color = Line)
                        } }
                    }
                    2 -> {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text("执行环境", fontSize = 22.sp); Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { nativeCapabilities = true }) { Icon(Icons.Outlined.PhonelinkSetup, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Android 原生能力") }
                            OutlinedButton(onClick = { terminalPanel = true }) { Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("持续终端") }
                            OutlinedButton(onClick = { vm.previews.openBrowserHome(session.id); tab = 0; focusManager.clearFocus(); keyboard?.hide() }) { Text("浏览器 · 当前会话") }
                            OutlinedButton(onClick = { sshPanel = true }, enabled = vm.runtime.ready) { Text("SSH · 全局") }
                            OutlinedButton(onClick = { gitAccounts = true }) { Text("Git 账号 · 全局") }
                            Text("设备、网络、Wi-Fi、定位、应用与系统操作统一由 android 工具调用。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
                            Text("ON DEVICE / ARM64", color = Green, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                            EnvironmentPanel(vm)
                            Spacer(Modifier.height(24.dp)); Text("会话独立目录", color = Green); Text("workspace / 任务文件\nhome / 用户目录\ntmp / 临时文件\nruns / 脚本与执行日志", color = Muted, lineHeight = 28.sp, modifier = Modifier.padding(vertical = 12.dp), fontFamily = FontFamily.Monospace)
                            Text("运行模型：单 APK · Android 原生调度 · PRoot/Ubuntu。PRoot 与 Android 共用内核，各会话属于同一应用 UID；目录分离不构成恶意代码安全隔离。", color = Muted, lineHeight = 24.sp, fontSize = 13.sp)

                        }
                    }
                }
                }
            }
        }
    }
    if(sshPanel) SshPanel(vm.runtime, session.id, onDismiss = { sshPanel = false })
    if(gitAccounts) ai.capri.app.git.GitAccountsDialog(vm.gitAccounts, onDismiss = { gitAccounts = false })
    if(settings) Settings(vm, onDismiss = { settings = false })
    if(nativeCapabilities) NativeCapabilities(vm, onDismiss = { nativeCapabilities = false })
    if(terminalPanel) ai.capri.app.terminal.TerminalPanel(vm.terminals, session.id, onDismiss = { terminalPanel = false })
}

@Composable private fun Empty(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(36.dp)) { Text(title, color = White); Spacer(Modifier.height(8.dp)); Text(subtitle, color = Muted, fontSize = 13.sp) } }

@Composable private fun Settings(vm: CapriModel, onDismiss: () -> Unit) {
    val saved = remember { vm.store.config() }
    var endpoint by remember { mutableStateOf(saved.endpoint) }
    var model by remember { mutableStateOf(saved.model) }
    var key by remember { mutableStateOf(saved.key) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("连接模型") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Agent 和文件运行在手机上，推理通过你配置的兼容 Chat Completions 接口完成。", color = Muted, fontSize = 12.sp)
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("API Base URL") }, singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text("模型 ID") }, singleLine = true)
            OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("密钥由 Android Keystore 加密。核心人格随应用内置，Agent 无档案编辑工具。", color = Muted, fontSize = 11.sp)
            if(error.isNotEmpty()) Text(error, color = Red)
        }
    }, confirmButton = { TextButton(onClick = {
        if(!endpoint.startsWith("https://") || model.isBlank() || key.isBlank()) error = "请填写 HTTPS 地址、模型和密钥" else { vm.store.configure(ModelConfig(endpoint.trim(), model.trim(), key.trim())); vm.refresh(); onDismiss() }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun NativeCapabilities(vm: CapriModel, onDismiss: () -> Unit) {
    val capabilities = vm.androidTools.capabilities()
    val actions = capabilities.getJSONArray("actions")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Android 原生能力") }, text = {
        Column {
            Text("${actions.length()} 项已接入动作 · 无 root / ADB / Shizuku。授权按任务需要申请，系统开关和厂商限制仍可能影响结果。", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(actions.length()) { index ->
                    val action = actions.getJSONObject(index)
                    Column {
                        Text(action.getString("title"), fontSize = 14.sp)
                        Text(when(action.getString("status")) { "available" -> "已接入"; "permission_required" -> "需要授权"; else -> "设备不支持" }, color = if(action.getString("status") == "available") Green else Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
