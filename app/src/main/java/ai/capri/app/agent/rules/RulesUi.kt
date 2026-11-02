package ai.capri.app.agent.rules

import ai.capri.app.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable internal fun RulesSettings(store:RulesStore,chat:String, initiallyOpen:Boolean=false, onClose:(()->Unit)?=null) {
    var open by remember{mutableStateOf(initiallyOpen)};var scope by remember{mutableStateOf("chat")};var tick by remember{mutableIntStateOf(0)}
    var id by remember{mutableStateOf("")};var title by remember{mutableStateOf("")};var content by remember{mutableStateOf("")};var enabled by remember{mutableStateOf(true)};var error by remember{mutableStateOf("")}
    var revision by remember{mutableLongStateOf(0)};var notice by remember{mutableStateOf("")};var editing by remember{mutableStateOf(false)}
    fun clear(){id="";title="";content="";enabled=true;error=""}
    if(!initiallyOpen) SettingsRow("规则管理","全局与当前对话"){open=true;tick++}
    if(open) {
        val data=remember(scope,chat,tick){runCatching{store.read(scope,chat)}.getOrNull()}
        fun back(){if(editing){editing=false;clear()}else{open=false;onClose?.invoke()}}
        DetailPage(onDismissRequest=::back,title={Text(if(editing)if(id.isBlank())"添加规则" else "编辑规则" else "规则管理")},text={
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                ConfigIntro(if(scope=="chat")"当前对话" else "全局 · 所有对话","规则从下一轮生效，用于持续偏好与约束。")
                if(!editing) {
                    TabRow(selectedTabIndex=if(scope=="chat")0 else 1) {
                        listOf("当前对话","所有对话").forEachIndexed { index,label -> Tab(selected=(scope=="chat")== (index==0),onClick={scope=if(index==0)"chat" else "global";clear();notice="";tick++},text={Text(label)}) }
                    }
                    FilledTonalButton(enabled=data!=null,onClick={clear();revision=data!!.getLong("revision");editing=true},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text("添加规则")}
                    if(data==null)ConfigNotice("规则暂不可读，请保留文件后重试。",true)else {
                        val rules=data.getJSONArray("rules")
                        if(rules.length()==0)ConfigGroup("还没有规则","添加后，Capri 会在适用范围内持续参考。"){}
                        for(i in 0 until rules.length()) {val rule=rules.getJSONObject(i)
                            ConfigGroup(rule.getString("title"),if(rule.optBoolean("enabled",true))"已启用" else "已停用") {
                                Text(rule.getString("content"),maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
                                TextButton(onClick={id=rule.getString("id");title=rule.getString("title");content=rule.getString("content");enabled=rule.optBoolean("enabled",true);revision=data.getLong("revision");error="";editing=true}){Text("编辑规则")}
                            }
                        }
                    }
                    OutlinedButton(onClick={tick++;notice="已重新读取规则。"},modifier=Modifier.fillMaxWidth()){Text("刷新列表")}
                }else {
                    ConfigGroup("规则内容","标题与正文合计最多 3000 字符；每个范围最多 20 条。") {
                        OutlinedTextField(title,{title=it;error=""},label={Text("标题")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(content,{content=it;error=""},label={Text("规则内容")},minLines=5,maxLines=12,modifier=Modifier.fillMaxWidth())
                        ConfigToggle("启用规则","停用后保留内容，后续轮次不再提供。",enabled){enabled=it}
                    }
                    if(id.isNotBlank())TextButton(onClick={runCatching{store.delete(scope,chat,id,revision)}.onSuccess{clear();editing=false;notice="规则已删除，将从下一轮生效。";tick++}.onFailure{error="规则已变化，请返回刷新后重新选择。"}}){Text("删除规则",color=MaterialTheme.colorScheme.error)}
                }
                ConfigNotice(notice);ConfigNotice(error,true)
                ConfigHelp("规则的适用边界"){Text("规则不能扩大权限或改变 Capri 核心人格；Skill 在任务需要时加载。",style=MaterialTheme.typography.bodySmall)}
            }
        },confirmButton={if(editing)Button(enabled=title.isNotBlank()&&content.isNotBlank()&&data!=null,onClick={runCatching{store.save(scope,chat,id.ifBlank{UUID.randomUUID().toString()},title,content,enabled,revision)}.onSuccess{clear();editing=false;notice="已保存，将从下一轮生效。";tick++}.onFailure{error="保存未完成：请检查长度，或返回刷新后重新编辑，避免覆盖新修改。"}},modifier=Modifier.fillMaxWidth()){Text("保存规则")}})
    }
}
