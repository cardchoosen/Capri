package ai.capri.app.extensions.hub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun PublicSkillSettings(hub:PublicSkillHub) {
    val revision by hub.updates.collectAsState();var open by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    OutlinedButton(onClick={open=true}){Text("公共 Skills")}
    if(open) {
        val state=remember(revision){hub.status()}
        AlertDialog(onDismissRequest={open=false},title={Text("公共 Skills")},text={Column {
            Text(HubGitHub.REPOSITORY)
            Row { Text("自动获取和更新",Modifier.weight(1f));Switch(checked=hub.enabled(),onCheckedChange={hub.enable(it)}) }
            Text("已收录 ${state.optInt("skill_count")} 个；本机修改与同名个人 Skill 会保留。下载不执行脚本。")
            if(state.optString("last_error").isNotBlank())Text("同步尚未完成，继续保留本地内容；请检查网络后重试。")
            TextButton(onClick={scope.launch {withContext(Dispatchers.IO){hub.sync()}}}){Text("立即同步")}
            TextButton(onClick={open=false;hub.reopen()}){Text("查看待处理的公开贡献")}
        }},confirmButton={TextButton(onClick={open=false}){Text("关闭")}})
    }
}
@Composable internal fun PublicSkillApproval(hub:PublicSkillHub,chat:String) {
    val revision by hub.updates.collectAsState();val request by hub.openRequests.collectAsState()
    var hidden by remember(chat,request){mutableStateOf<String?>(null)};var selected by remember(chat){mutableStateOf("")};var offset by remember{mutableIntStateOf(0)}
    val record=remember(chat,revision){val a=hub.list(chat);(0 until a.length()).map{a.getJSONObject(it)}.firstOrNull{!it.optBoolean("acknowledged") && it.optString("state") in setOf("awaiting_user","approved","failed","publishing","submitted")}}
    val key=record?.optString("key")?:return
    LaunchedEffect(key){selected="";offset=0}
    if(hidden==key)return
    val context=LocalContext.current
    if(record.optString("state")=="submitted") {
        // A durable PR receipt ends approval; it must not require another modal acknowledgement.
        LaunchedEffect(chat,key) {
            hub.acknowledge(chat,key)
            Toast.makeText(context,"公开贡献已提交 PR #${record.optInt("number")}，链接已保留在贡献回执中。",Toast.LENGTH_LONG).show()
        }
        return
    }
    val busy=hub.running(chat,key)
    AlertDialog(onDismissRequest={if(!busy)hidden=key},title={Text("是否公开贡献这个 Skill？")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) {
            Text("${record.optString("id")} → ${HubGitHub.REPOSITORY}")
                Text("确认后，下列文件将通过你的 GitHub 账号公开上传并提交 PR，按 MIT 许可贡献。审核合并前也已公开。不会上传聊天记录；请核对文件是否包含私人内容。")
                val files=record.getJSONObject("manifest").getJSONArray("files")
                for(i in 0 until files.length()) {val file=files.getJSONObject(i);TextButton(onClick={selected=file.getString("path");offset=0}){Text("${file.getString("path")} · ${file.getInt("bytes")} 字节")}}
                if(selected.isNotBlank()) {
                    val text=runCatching{hub.resource(chat,key,selected)}.getOrDefault("文件不可读，请重新准备贡献。")
                    Text(selected);SelectionContainer{Text(text.drop(offset).take(6000))}
                    if(offset>0)TextButton(onClick={offset=(offset-6000).coerceAtLeast(0)}){Text("上一页")}
                    if(offset+6000<text.length)TextButton(onClick={offset+=6000}){Text("下一页")}
                }
                if(record.optString("state") in setOf("failed","approved","publishing") && !busy)Text("提交尚未确认。请检查 GitHub 账号及权限后重试；会先核对已有分支和 PR。")
                if(busy)Text("正在提交，请稍候…")
        }
    },confirmButton={TextButton(enabled=!busy,onClick={hub.approve(chat,key)}){Text("确认公开并提交 PR")}},dismissButton={
        Row { TextButton(enabled=!busy,onClick={hub.decline(chat,key);hidden=key}){Text("仅本机使用")};TextButton(enabled=!busy,onClick={hidden=key}){Text("稍后")}}
    })
}
