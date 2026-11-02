package ai.capri.app.agent.rules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable internal fun RulesSettings(store:RulesStore,chat:String) {
    var open by remember{mutableStateOf(false)};var scope by remember{mutableStateOf("chat")};var tick by remember{mutableIntStateOf(0)}
    var id by remember{mutableStateOf("")};var title by remember{mutableStateOf("")};var content by remember{mutableStateOf("")};var enabled by remember{mutableStateOf(true)};var error by remember{mutableStateOf("")}
    var revision by remember{mutableLongStateOf(0)}
    fun clear(){id="";title="";content="";enabled=true;error=""}
    OutlinedButton(onClick={open=true;tick++}){Text("规则")}
    if(open) {
        val data=remember(scope,chat,tick){runCatching{store.read(scope,chat)}.getOrNull()}
        AlertDialog(onDismissRequest={open=false},title={Text("持续规则")},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState())) {
            Text("规则每轮自动提供；Skill 在任务需要时加载。修改从下一轮生效，不能扩大权限或改变 Capri 核心人格。")
            Row { TextButton(onClick={scope="chat";clear();tick++}){Text(if(scope=="chat")"✓ 当前对话" else "当前对话")};TextButton(onClick={scope="global";clear();tick++}){Text(if(scope=="global")"✓ 所有对话" else "所有对话")} }
            if(data==null)Text("规则暂不可读，请保留文件后重试。")else {
                val rules=data.getJSONArray("rules")
                for(i in 0 until rules.length()) { val rule=rules.getJSONObject(i)
                    Row { TextButton(onClick={id=rule.getString("id");title=rule.getString("title");content=rule.getString("content");enabled=rule.optBoolean("enabled",true);revision=data.getLong("revision");error=""}){Text((if(rule.optBoolean("enabled",true))"✓ " else "停用 · ")+rule.getString("title"))} }
                }
                if(id.isBlank())Text("添加规则（每个范围最多 20 条、标题和正文合计 3000 字符）")
                OutlinedTextField(title,{title=it},label={Text("标题")},singleLine=true)
                OutlinedTextField(content,{content=it},label={Text("规则内容")},minLines=3,maxLines=6)
                Row{Text("启用",Modifier.weight(1f));Switch(checked=enabled,onCheckedChange={enabled=it})}
                Row {
                    TextButton(enabled=title.isNotBlank()&&content.isNotBlank(),onClick={runCatching{store.save(scope,chat,id.ifBlank{UUID.randomUUID().toString()},title,content,enabled,if(id.isBlank())data.getLong("revision") else revision)}.onSuccess{clear();tick++}.onFailure{error="保存未完成：请检查长度，或刷新后重新编辑，避免覆盖新修改。"}}){Text("保存")}
                    if(id.isNotBlank())TextButton(onClick={runCatching{store.delete(scope,chat,id,revision)}.onSuccess{clear();tick++}.onFailure{error="规则已变化，请刷新后重新选择。"}}){Text("删除")}
                    TextButton(onClick={clear();tick++}){Text("新建／刷新")}
                }
            }
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton(onClick={open=false}){Text("关闭")}})
    }
}
