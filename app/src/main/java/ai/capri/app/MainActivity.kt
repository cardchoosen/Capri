package ai.capri.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import android.content.Intent
import kotlinx.coroutines.launch

private val Ink = Color(0xFF080B09)
private val Panel = Color(0xFF111612)
private val Line = Color(0xFF29322B)
private val Green = Color(0xFFA5FF75)
private val Red = Color(0xFFFF514F)
private val White = Color(0xFFF4F6F3)
private val Muted = Color(0xFF939E94)

class MainActivity : ComponentActivity() {
    private val model: CapriModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Green, onPrimary = Ink, background = Ink, surface = Panel, onSurface = White, outline = Line, error = Red)) { Capri(model) } }
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
    var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
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
        ModalDrawerSheet(drawerContainerColor = Ink) {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) { Mark(Modifier.size(40.dp)); Text("CAPRI", letterSpacing = 5.sp, fontWeight = FontWeight.Bold) }
            Button(onClick = { vm.create(); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("新会话", Modifier.padding(8.dp)) }
            Text("你的任务空间", color = Muted, modifier = Modifier.padding(24.dp), fontSize = 12.sp)
            LazyColumn { items(vm.sessions.toList(), key = { it.id }) { item ->
                NavigationDrawerItem(label = { Text(item.title, maxLines = 1) }, selected = item.id == session.id, onClick = { vm.select(item.id); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            } }
        }
    }) {
        Scaffold(containerColor = Ink, topBar = {
            Column {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "会话列表", tint = White) }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("C A P R I", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(if (vm.jobs.containsKey(session.id)) "●  ${vm.notices[session.id]}" else "●  随时待命", color = Green, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Tune, "设置", tint = Muted) }
                }
                HorizontalDivider(color = Line)
            }
        }, bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("对话" to Icons.Outlined.ChatBubbleOutline, "文件" to Icons.Outlined.FolderOpen, "运行" to Icons.Outlined.Terminal).forEachIndexed { index, pair ->
                        TextButton(onClick = { tab = index }, colors = ButtonDefaults.textButtonColors(contentColor = if (tab == index) Green else Muted)) { Icon(pair.second, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(pair.first, fontSize = 12.sp) }
                    }
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (banner.isNotEmpty()) { TextButton(onClick = { banner = "" }) { Text(banner, color = Green, fontSize = 12.sp) } }
                when (tab) {
                    0 -> {
                        if (session.messages.isEmpty()) {
                            Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.Center) {
                                Text("CAPRI / 摩羯", color = Muted, fontSize = 12.sp, letterSpacing = 2.sp)
                                Spacer(Modifier.height(20.dp)); Mark(Modifier.size(94.dp)); Spacer(Modifier.height(18.dp))
                                Text("好了，\n你想改写什么？", color = White, fontSize = 32.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(30.dp))
                                listOf("介绍一下你自己", "帮我整理这个工作区").forEach { label ->
                                    OutlinedButton(onClick = { input = label }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.padding(bottom = 8.dp)) { Text(label, color = White, fontSize = 12.sp); Spacer(Modifier.width(20.dp)); Icon(Icons.Outlined.NorthEast, null, Modifier.size(14.dp), tint = Green) }
                                }
                            }
                        } else {
                            val listState = rememberLazyListState()
                            LaunchedEffect(session.id, session.messages.size, update) { if (session.messages.isNotEmpty()) listState.animateScrollToItem(session.messages.lastIndex) }
                            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                items(session.messages.toList()) { message ->
                                    Column(Modifier.fillMaxWidth().then(if (message.role == "user") Modifier.background(Panel, RoundedCornerShape(12.dp)).padding(16.dp) else Modifier)) {
                                        Text(when(message.role) { "user" -> "YOU"; "assistant" -> "CAPRI"; "tool" -> "TOOL / 本机"; else -> "SYSTEM" }, color = if(message.role == "assistant") Green else if(message.role == "status") Red else Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp)
                                        Spacer(Modifier.height(8.dp)); androidx.compose.foundation.text.selection.SelectionContainer { Text(message.text, fontSize = if(message.role == "tool") 12.sp else 15.sp, color = if(message.role == "tool") Muted else White, lineHeight = 24.sp, fontFamily = if(message.role == "tool") FontFamily.Monospace else FontFamily.Default) }
                                    }
                                }
                            }
                        }
                        vm.notices[session.id]?.let { Text(it, color = Green, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
                        Row(Modifier.fillMaxWidth().imePadding().padding(12.dp).border(1.dp, Line, RoundedCornerShape(14.dp)).background(Panel, RoundedCornerShape(14.dp)).padding(4.dp), verticalAlignment = Alignment.Bottom) {
                            IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.Add, "导入文件", tint = Muted) }
                            TextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("下达你的指令…", fontSize = 14.sp) }, maxLines = 5, colors = TextFieldDefaults.colors(focusedContainerColor = Panel, unfocusedContainerColor = Panel, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                            val busy = vm.jobs.containsKey(session.id)
                            FilledIconButton(onClick = { if (busy) vm.stop() else if(input.isNotBlank()) { vm.send(input); if(vm.jobs.containsKey(session.id)) input = "" } }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if(busy) Red else Green, contentColor = Ink), modifier = Modifier.padding(bottom = 4.dp)) { Icon(if(busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if(busy) "停止" else "发送") }
                        }
                    }
                    1 -> {
                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("工作区", fontSize = 24.sp); Text(session.title, color = Muted, fontSize = 12.sp) }; OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("导入") } }
                        Text("文件仅属于当前会话 · 点击导出", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
                        val files = vm.store.workspace.list(session.id)
                        if(files.isEmpty()) Empty("这里还没有文件", "导入资料，或让 Capri 为你创建。")
                        LazyColumn(contentPadding = PaddingValues(20.dp)) { items(files) { file ->
                            Row(Modifier.fillMaxWidth().clickable {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "导出文件"))
                            }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Description, null, tint = Green); Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(file.relativeTo(vm.store.workspace.folder(session.id, "workspace")).path); Text("${file.length()} bytes", fontSize = 11.sp, color = Muted) }; Icon(Icons.Outlined.IosShare, "导出", tint = Muted, modifier = Modifier.size(18.dp)) }; HorizontalDivider(color = Line)
                        } }
                    }
                    2 -> {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                            Text("执行环境", fontSize = 26.sp); Spacer(Modifier.height(20.dp))
                            Text("ON DEVICE / ARM64", color = Green, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                            Spacer(Modifier.height(16.dp)); Text(vm.runtime.status(), color = White, lineHeight = 24.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { vm.installRuntime() }, enabled = !vm.runtimeBusy) { Text(if(vm.runtimeBusy) "正在准备环境…" else if(vm.runtime.ready) "验证 Python" else "初始化 Python 环境") }
                            if(vm.runtimeResult.isNotEmpty()) Text(vm.runtimeResult, color = Green, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                            Spacer(Modifier.height(24.dp)); Text("会话独立目录", color = Green); Text("workspace / 任务文件\nhome / 用户目录\ntmp / 临时文件\nruns / 脚本与执行日志", color = Muted, lineHeight = 28.sp, modifier = Modifier.padding(vertical = 12.dp), fontFamily = FontFamily.Monospace)
                            Text("运行模型：单 APK · Android 原生调度 · PRoot/Python。PRoot 与 Android 共用内核，各会话属于同一应用 UID；目录分离不构成恶意代码安全隔离。", color = Muted, lineHeight = 24.sp, fontSize = 13.sp)
                            Spacer(Modifier.height(24.dp)); Text("当前会话工具记录", fontSize = 16.sp)
                            session.messages.filter { it.role == "tool" || it.role == "status" }.takeLast(10).forEach { Text(it.text, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp)) }
                        }
                    }
                }
            }
        }
    }
    if(settings) Settings(vm, onDismiss = { settings = false })
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
