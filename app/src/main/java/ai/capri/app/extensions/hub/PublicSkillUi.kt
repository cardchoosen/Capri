package ai.capri.app.extensions.hub

import ai.capri.app.ConfigGroup
import ai.capri.app.ConfigIntro
import ai.capri.app.ConfigNotice
import ai.capri.app.ConfigToggle
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

@Composable internal fun PublicSkillSettings(hub:PublicSkillHub,chat:String) {
    val revision by hub.updates.collectAsState();var open by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    var syncing by remember{mutableStateOf(false)};var notice by remember{mutableStateOf("")}
    ai.capri.app.SettingsRow("公共 Skill 仓库","自动同步、贡献与待处理记录"){open=true}
    if(open) {
        val state=remember(revision){hub.status()}
        ai.capri.app.DetailPage(onDismissRequest={open=false},title={Text("公共 Skill 仓库")},text={Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            ConfigIntro("全局 · 公共 Skill 仓库",HubGitHub.REPOSITORY)
            ConfigGroup("获取与更新") {
            ConfigToggle("自动同步","只下载公共 Skill，不会自动公开本机内容。",hub.enabled()){hub.enable(it)}
            Text("已收录 ${state.optInt("skill_count")} 个；本机修改与同名个人 Skill 会保留。下载不执行脚本。")
            if(state.optString("last_error").isNotBlank())Text("同步尚未完成，继续保留本地内容；请检查网络后重试。")
            TextButton(enabled=!syncing,onClick={scope.launch {
                syncing=true;notice=""
                try { val result=withContext(Dispatchers.IO){hub.sync()};notice=if(!hub.enabled())"自动同步已关闭。" else if(result.optString("status")=="partial")"同步未完成，已保留本地内容，请检查网络后重试。" else "同步完成，已收录 ${result.optInt("skill_count")} 个公共 Skill。" }
                finally { syncing=false }
            }}){Text(if(syncing)"正在同步…" else "立即同步")}
            if(syncing)LinearProgressIndicator(Modifier.fillMaxWidth())
            ConfigNotice(notice)
            }
            ConfigIntro("当前对话 · 贡献记录","公开贡献只在你主动要求时准备；待处理记录不会在启动时重弹。")
            val receipts=remember(chat,revision){hub.list(chat)}
            if(receipts.length()==0)Text("暂无贡献记录。需要公开时，可在对话中主动让 Capri 准备贡献。")
            for(i in 0 until receipts.length()) {
                val receipt=receipts.getJSONObject(i);val state=receipt.optString("state")
                ConfigGroup(receipt.optString("id")) {
                Text(when(state){"submitted"->"已提交 PR";"declined"->"仅本机使用";"failed"->"提交未确认";"publishing","approved"->"待核对提交结果";else->"等待确认"},style=MaterialTheme.typography.bodySmall)
                if(state=="submitted")SelectionContainer{Text(receipt.optString("pull_request"))}
                else if(state!="declined")OutlinedButton(onClick={hub.reopen(chat,receipt.getString("key"));open=false},modifier=Modifier.fillMaxWidth()){Text("查看并处理")}
                }
            }
        }},confirmButton={TextButton(onClick={open=false}){Text("关闭")}})
    }
}
@Composable internal fun PublicSkillApproval(hub:PublicSkillHub,chat:String) {
    val revision by hub.updates.collectAsState();val request by hub.openRequests.collectAsState()
    val active=request?.takeIf{it.chat==chat}?:return
    val key=active.key
    var selected by remember(chat,key){mutableStateOf("")};var offset by remember(chat,key){mutableIntStateOf(0)}
    val record=remember(chat,key,revision){runCatching{hub.read(chat,key)}.getOrNull()}?:return
    val context=LocalContext.current
    if(record.optString("state")=="submitted") {
        // A durable PR receipt ends approval; it must not require another modal acknowledgement.
        LaunchedEffect(chat,key) {
            hub.acknowledge(chat,key);hub.dismiss(chat,key)
            Toast.makeText(context,"公开贡献已提交 PR #${record.optInt("number")}，链接已保留在贡献回执中。",Toast.LENGTH_LONG).show()
        }
        return
    }
    val busy=hub.running(chat,key)
    AlertDialog(onDismissRequest={if(!busy)hub.dismiss(chat,key)},title={Text("是否公开贡献这个 Skill？")},text={
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
        Row { TextButton(enabled=!busy,onClick={hub.decline(chat,key)}){Text("仅本机使用")};TextButton(enabled=!busy,onClick={hub.dismiss(chat,key)}){Text("稍后")}}
    })
}
