package ai.capri.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.ensureActive
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
    private val model: CapriModel get() = AgentRuntime.get(application)
    override fun onResume() { super.onResume(); KernelHost.startFromUi(application); model.androidTools.permissions.attach(this);ai.capri.app.extensions.hub.HubAndroid.schedule(application) }
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
    val drawerTasks = remember(update) { vm.sessions.filter { it.kind == SessionKind.TASK }.map { it.copy(messages = mutableListOf()) } }
    val entries = remember(session.id, update) { session.messages.filter(ToolPresentation::visible) }
    val chatPositions = remember(vm.memoryRevision) { mutableMapOf<String, ChatScrollState>() }
    val chatPosition = chatPositions.getOrPut("${session.id}:${session.contextVersion}") { ChatScrollState(entries.size) }
    val historyActions = remember(session.id) { HistoryActions() }
    val busy = vm.jobs.containsKey(session.id)
    val notice = vm.notices[session.id]
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val tab = vm.activeTab
    var sshPanel by remember { mutableStateOf(false) }
    var gitAccounts by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var nativeCapabilities by remember { mutableStateOf(false) }
    var enhancementsPanel by remember { mutableStateOf(false) }
    var knowledgePanel by remember { mutableStateOf(false) }
    var extensionsPanel by remember { mutableStateOf(false) }
    var terminalPanel by remember { mutableStateOf(false) }
    var settingsRoute by rememberSaveable { mutableStateOf("") }
    var workbench by remember { mutableStateOf(false) }
    var extensionCategory by remember { mutableIntStateOf(2) }
    val pendingNativePermission by vm.androidTools.permissions.pending.collectAsState()
    val nativePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.androidTools.permissions.complete(it); vm.refresh() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(pendingNativePermission?.id, lifecycleOwner) {
        val pending = pendingNativePermission ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.withResumed {
            vm.androidTools.permissions.take(pending.id)?.let { nativePermissionLauncher.launch(it.toTypedArray()) }
        }
    }
    var input by remember(session.id, vm.memoryRevision) { mutableStateOf("") }
    var banner by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Session?>(null) }
    var deleteDialogError by remember { mutableStateOf("") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var pickerChat by rememberSaveable { mutableStateOf("") }
    var pickerAsInput by rememberSaveable { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var inputMenu by remember { mutableStateOf(false) }
    val pendingInputs = remember(session.id, update) { vm.pendingInputs(session.id) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = pickerChat.ifBlank { vm.selected }
        val attach = pickerAsInput
        importing = uris.isNotEmpty()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val outcome = runCatching {
                require(vm.sessions.any { it.id == id } && id !in vm.deletingChats) { "会话已删除" }
                if (attach) require(uris.size + vm.pendingInputs(id).size <= InputAttachments.MAX_FILES) { "每条消息最多选择8个文件" }
                val lease = RuntimeGate.beginFiles(context, id, "Import user files")
                try {
                uris.forEach { uri ->
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } ?: "import-${System.currentTimeMillis()}"
                    if (attach) {
                        val job = kotlinx.coroutines.currentCoroutineContext()
                        context.contentResolver.openInputStream(uri)?.use { source -> vm.inputAttachments.import(id, name, source) { job.ensureActive() } } ?: error("无法读取所选文件")
                        return@forEach
                    }
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
                } finally { RuntimeGate.endFiles(lease) }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                importing = false
                banner = outcome.fold({ if (attach) "已选择 ${uris.size} 个附件，可在输入栏查看" else "已导入 ${uris.size} 个文件" }, { it.message ?: "导入失败" })
                vm.refresh()
            }
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除对话？") },
            text = { Column {
                Text("将删除「${target.title}」的消息、工作区和会话环境，并关闭相关终端。全局配置保留。")
                if (deleteDialogError.isNotBlank()) Text(deleteDialogError, color = Red)
            } },
            confirmButton = { TextButton(onClick = {
                runCatching { vm.deleteSession(target.id) }.onSuccess { deleteTarget = null }
                    .onFailure { deleteDialogError = it.message ?: "删除未完成" }
            }) { Text("删除", color = Red) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = Ink, drawerShape = RoundedCornerShape(0.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Mark(Modifier.size(40.dp)); Text("CAPRI", letterSpacing = 5.sp, fontWeight = FontWeight.Bold) }
            val personal = vm.personalSession()
            NavigationDrawerItem(shape = RoundedCornerShape(2.dp),
                label = { Column { Text("Capri", color = Red, fontWeight = FontWeight.SemiBold); Text("默认会话 · 一直在这里", fontSize = 11.sp, color = Red.copy(alpha = .75f)) } },
                colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = Red.copy(alpha = .14f), unselectedContainerColor = Red.copy(alpha = .05f)),
                selected = personal.id == session.id,
                onClick = { vm.select(personal.id); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            Button(enabled = !vm.migrating && !vm.resettingMemory && !vm.memoryResetRequired, onClick = { vm.create(); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text("新建任务对话", Modifier.padding(8.dp)) }
            Text("你的任务空间", color = Muted, modifier = Modifier.padding(16.dp), fontSize = 12.sp)
            if (vm.deletionError.isNotBlank()) Text(vm.deletionError, color = Red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn { items(drawerTasks, key = { it.id }) { item ->
                NavigationDrawerItem(shape = RoundedCornerShape(2.dp),
                    label = { Text(if (item.id in vm.deletingChats) "正在删除…" else item.title, maxLines = 1) },
                    selected = item.id == session.id,
                    onClick = { if (item.id !in vm.deletingChats) { vm.select(item.id); scope.launch { drawer.close() } } },
                    badge = { IconButton(enabled = item.id !in vm.deletingChats && !vm.jobs.containsKey(item.id),
                        onClick = { deleteDialogError = ""; deleteTarget = item }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, "删除会话 ${item.title}", tint = Muted, modifier = Modifier.size(18.dp))
                    } }, modifier = Modifier.padding(horizontal = 12.dp))
            } }
        }
    }) {
        Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Ink, topBar = {
            Column {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "会话列表", tint = White) }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("C A P R I", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                        Text(if (busy) "●  ${notice ?: "思考中"}" else if (session.kind == SessionKind.PERSONAL) "●  在这儿呢" else "●  随时待命", color = Green, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                    if(tab == 0) IconButton(onClick = { workbench = true }) { Icon(Icons.Outlined.Workspaces, "工作台", tint = Muted) }
                    IconButton(onClick = { vm.activeTab = 3 }) { Icon(Icons.Outlined.Settings, "打开设置", tint = Muted) }
                }
                HorizontalDivider(color = Line)
            }
        }, bottomBar = {
            // Keep navigation measured throughout IME animation; the keyboard covers it.
            Column(Modifier.navigationBarsPadding()) {
                HorizontalDivider(color = Line)
                NavigationBar(containerColor = Panel, tonalElevation = 0.dp, windowInsets = WindowInsets(0,0,0,0)) {
                    listOf("对话" to Icons.Outlined.ChatBubbleOutline, "文件" to Icons.Outlined.FolderOpen, "用量" to Icons.Outlined.BarChart, "设置" to Icons.Outlined.Settings).forEachIndexed { index, pair ->
                        NavigationBarItem(selected = tab == index, onClick = { vm.activeTab = index; focusManager.clearFocus(); keyboard?.hide() }, icon = { Icon(pair.second, null) }, label = { Text(pair.first) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Green, selectedTextColor = Green, indicatorColor = Green.copy(alpha = .12f), unselectedIconColor = Muted, unselectedTextColor = Muted))
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
                                Text(if (session.kind == SessionKind.PERSONAL) "来了？\n说吧，我听着。" else "好了，\n你想改写什么？", color = White, fontSize = 32.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(30.dp))
                                (if (session.kind == SessionKind.PERSONAL) listOf("我回来了", "今天不想干活") else listOf("介绍一下你自己", "帮我整理这个工作区")).forEach { label ->
                                    OutlinedButton(onClick = { input = label }, shape = RoundedCornerShape(2.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.padding(bottom = 8.dp)) { Text(label, color = White, fontSize = 12.sp); Spacer(Modifier.width(20.dp)); Icon(Icons.Outlined.NorthEast, null, Modifier.size(14.dp), tint = Green) }
                                }
                            }
                        } else {
                            ConversationTimeline(entries, busy, chatPosition, Modifier.weight(1f), vm.activeTools[session.id], vm.store.workspace, session.id, history = historyActions, context = session.contextVersion, historyVersion = session.historyVersion,
                                onHistory = { entry -> chatPosition.followLatest=false;chatPosition.userScrollPending=false;historyActions.open(session, entry) }, onRetry = { entry ->
                                    runCatching { vm.reviseMessage(session.id,entry.id,null,session.contextVersion,session.historyVersion) }.onFailure { vm.notices[session.id]=it.message.orEmpty();vm.refresh() }
                                }, onHistorySubmit = { historyActions.submit(vm,session) })
                        }
                        vm.environmentState().takeIf { it.phase != "ready" }?.let { state ->
                            Text(state.message, color = if (state.phase == "error") Red else Muted, fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                        }
                        notice?.takeUnless { vm.activeTools.containsKey(session.id) }?.let { Text(it, color = Green, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
                        AgentReliabilityPanel(vm,session)
                        val turnState = vm.turnView(session.id)
                        val requestBudget = turnState.optJSONObject("budget")
                        if (turnState.optString("label").isNotBlank()) Text(turnState.optString("label") + (requestBudget?.let { " · 请求 ${it.optInt("used")}/${it.optInt("maximum")}" } ?: ""), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
                        turnState.optJSONObject("question")?.optJSONArray("options")?.let { options ->
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                                for (i in 0 until options.length()) TextButton(onClick = { input = options.getString(i) }) { Text(options.getString(i), fontSize = 12.sp) }
                            }
                        }
                        ai.capri.app.model.ModelPicker(vm, session.id) { settings = true }
                        if (importing) Text("正在导入附件…", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                        if (pendingInputs.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pendingInputs.forEach { item -> key(item.getString("id")) {
                                Row(Modifier.border(1.dp, Line, RoundedCornerShape(2.dp)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (item.optInt("width") > 0) LocalImage(vm.store.workspace, session.id, item.getString("path"), Modifier.size(48.dp), item.getString("name"), 128)
                                    else Icon(Icons.Outlined.AttachFile, null, Modifier.size(24.dp), tint = Muted)
                                    Column(Modifier.widthIn(max = 160.dp).padding(horizontal = 6.dp)) { Text(item.getString("name"), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 12.sp); Text("${(item.getLong("size") + 1023) / 1024} KB", fontSize = 10.sp, color = Muted) }
                                    IconButton(enabled = !importing, onClick = { val owner = session.id; scope.launch(kotlinx.coroutines.Dispatchers.IO) { val result = runCatching { val lease = RuntimeGate.beginFiles(context, owner, "Remove input selection"); try { vm.inputAttachments.remove(owner, item.getString("id")) } finally { RuntimeGate.endFiles(lease) } }; kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { result.onFailure { banner = "无法移除附件，请刷新后重试" }; vm.refresh() } } }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.Close, "移除附件 ${item.getString("name")}", Modifier.size(16.dp), tint = Muted) }
                                }
                            } }
                        }
                        if (pendingInputs.any { it.optInt("width") > 0 }) Text("图片识别需要支持视觉的模型", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).border(1.dp, if (busy) Red else Line, RoundedCornerShape(2.dp)).background(Panel, RoundedCornerShape(2.dp)).padding(2.dp), verticalAlignment = Alignment.Bottom) {
                            Box {
                                IconButton(enabled = !vm.migrating && !importing, onClick = { inputMenu = true }) { Icon(Icons.Outlined.Add, "添加文件或图片", tint = Muted) }
                                DropdownMenu(expanded = inputMenu, onDismissRequest = { inputMenu = false }) {
                                    DropdownMenuItem(text = { Text("选择图片") }, onClick = { inputMenu = false; pickerChat = session.id; pickerAsInput = true; importer.launch(arrayOf("image/*")) })
                                    DropdownMenuItem(text = { Text("选择文件") }, onClick = { inputMenu = false; pickerChat = session.id; pickerAsInput = true; importer.launch(arrayOf("*/*")) })
                                }
                            }
                            ai.capri.app.extensions.SkillMentionButton(vm) { mention -> input += (if(input.isNotEmpty() && !input.endsWith(" ")) " " else "") + mention }
                            TextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text(if (busy) "补充要求，将在安全边界接入…" else if (turnState.optJSONObject("question") != null) "回答以上问题以继续…" else if (session.kind == SessionKind.PERSONAL) "跟 Capri 说点什么…" else "下达你的指令…", fontSize = 14.sp) }, maxLines = 5, colors = TextFieldDefaults.colors(focusedContainerColor = Panel, unfocusedContainerColor = Panel, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                            if (busy) IconButton(onClick = { vm.stop() }) { Icon(Icons.Outlined.Stop, "停止", tint = Red) }
                            FilledIconButton(shape = RoundedCornerShape(2.dp), onClick = { if(!importing && (input.isNotBlank() || pendingInputs.isNotEmpty())) { if(vm.send(input)) { chatPosition.followLatest = true; input = ""; focusManager.clearFocus(force = true); keyboard?.hide() } } }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Green, contentColor = Ink), modifier = Modifier.padding(bottom = 4.dp)) { Icon(Icons.Outlined.ArrowUpward, if(busy) "补充要求" else "发送") }
                        }
                    }
                    1 -> {
                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("工作区", fontSize = 24.sp); Text(session.title, color = Muted, fontSize = 12.sp) }; OutlinedButton(enabled = !vm.migrating, onClick = { pickerChat = session.id; pickerAsInput = false; importer.launch(arrayOf("*/*")) }) { Text("导入") } }
                        Text("文件仅属于当前会话 · 点击预览，右侧导出", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
                        val files = vm.store.workspace.list(session.id)
                        if(files.isEmpty()) Empty("这里还没有文件", "导入资料，或让 Capri 为你创建。")
                        LazyColumn(contentPadding = PaddingValues(16.dp)) { items(files) { file ->
                            Row(Modifier.fillMaxWidth().clickable {
                                vm.previews.openFile(session.id, file.relativeTo(vm.store.workspace.folder(session.id, "workspace")).path)
                                vm.activeTab = 0; focusManager.clearFocus(); keyboard?.hide()
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
                    2 -> UsageScreen(vm)
                    3 -> MobileSettings(vm) { destination ->
                        when(destination) {
                            "models" -> settings = true
                            "git" -> gitAccounts = true
                            "ssh" -> sshPanel = true
                            "tools", "mcp", "skills" -> { extensionCategory = when(destination) { "tools" -> 2; "mcp" -> 0; else -> 1 }; extensionsPanel = true }
                            "knowledge" -> knowledgePanel = true
                            "enhancements" -> enhancementsPanel = true
                            "native" -> nativeCapabilities = true
                            else -> settingsRoute = destination
                        }
                    }
                }
                }
            }
        }
    }
    if(workbench) DetailPage(onDismissRequest = { workbench = false }, title = { Text("工作台") }, text = {
        Column {
            Text("当前对话 · ${session.title}", style = MaterialTheme.typography.bodySmall)
            SettingsRow("浏览器", "当前对话的网页与浏览记录", Icons.Outlined.Language) { workbench = false; vm.previews.openBrowserHome(session.id); vm.activeTab = 0; focusManager.clearFocus(); keyboard?.hide() }
            SettingsRow("终端", "当前对话的持续终端与执行结果", Icons.Outlined.Terminal) { workbench = false; terminalPanel = true }
        }
    })
    if(settingsRoute.isNotBlank()) key(session.id,settingsRoute) { SettingsDestination(vm, settingsRoute) { settingsRoute = "" } }
    if(enhancementsPanel) ai.capri.app.retrieval.EnhancementsPanel(vm) { enhancementsPanel = false }
    if(knowledgePanel) key(vm.memoryRevision) { ai.capri.app.knowledge.KnowledgePanel(vm) { knowledgePanel = false } }
    if(extensionsPanel) ai.capri.app.extensions.ExtensionsPanel(vm, initialCategory = extensionCategory) { extensionsPanel = false }
    if(sshPanel) SshPanel(vm.runtime, session.id, onDismiss = { sshPanel = false })
    ai.capri.app.extensions.hub.PublicSkillApproval(vm.publicSkills,vm.current().id)
    if(gitAccounts) ai.capri.app.git.GitAccountsDialog(vm.gitAccounts, onDismiss = { gitAccounts = false })
    if(settings) ai.capri.app.model.ModelsPanel(vm, onDismiss = { settings = false })
    if(nativeCapabilities) NativeCapabilities(vm, onDismiss = { nativeCapabilities = false })
    if(terminalPanel) ai.capri.app.terminal.TerminalPanel(vm.terminals, session.id, onDismiss = { terminalPanel = false })
}

@Composable private fun Empty(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(36.dp)) { Text(title, color = White); Spacer(Modifier.height(8.dp)); Text(subtitle, color = Muted, fontSize = 13.sp) } }

@Composable private fun NativeCapabilities(vm: CapriModel, onDismiss: () -> Unit) {
    val actions = vm.androidTools.capabilities().getJSONArray("actions")
    var query by remember { mutableStateOf("") }
    DetailPage(onDismissRequest=onDismiss,title={Text("Android 权限与能力")},text={
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            ConfigIntro("设备能力 · 系统授权", "权限按任务需要申请，系统与厂商限制可能影响实际结果。")
            OutlinedTextField(query,{query=it},label={Text("搜索设备能力")},singleLine=true,modifier=Modifier.fillMaxWidth())
            val matches=(0 until actions.length()).map{actions.getJSONObject(it)}.filter{query.isBlank()||it.toString().contains(query,true)}
            Text("${matches.size} 项能力",style=MaterialTheme.typography.labelMedium,color=Muted)
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(matches) { action -> ConfigGroup(action.getString("title")) {
                    Text(when(action.getString("status")){"available"->"已接入";"permission_required"->"需要系统授权";"root_authorization_required"->"需要 Root 授权";else->"设备不支持"},style=MaterialTheme.typography.bodyMedium)
                    if(action.optString("description").isNotBlank())Text(action.optString("description"),style=MaterialTheme.typography.bodySmall,color=Muted)
                } }
                if(matches.isEmpty())item{ConfigGroup("没有匹配的能力","换一个名称或关键词试试。") {}}
            }
        }
    })
}
