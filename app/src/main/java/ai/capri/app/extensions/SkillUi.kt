package ai.capri.app.extensions

import ai.capri.app.CapriModel
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun SkillMentionButton(vm:CapriModel,insert:(String)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    var skills by remember{mutableStateOf<List<SkillInfo>>(emptyList())}
    val scope=rememberCoroutineScope()
    LaunchedEffect(vm.selected) { expanded=false }
    Box {
        TextButton(onClick={val owner=vm.selected;scope.launch { val found=withContext(Dispatchers.IO){vm.extensions.skills.catalog(owner).skills.filter{vm.extensions.skills.permission(owner,it)!="deny"}};if(vm.selected==owner){skills=found;expanded=true} }}){Text("@",Modifier.sizeIn(minWidth=16.dp))}
        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
            if(skills.isEmpty())DropdownMenuItem(text={Text("暂无可用 Skill")},onClick={expanded=false})
            skills.forEach{s->DropdownMenuItem(text={Text("${s.name} · @${s.id}")},onClick={insert("@${s.id} ");expanded=false})}
        }
    }
}

@Composable internal fun SkillLibrarySettings(vm:CapriModel,onDismiss:()->Unit) {
    var text by remember{mutableStateOf(vm.extensions.skills.settings().toString(2))}
    var error by remember{mutableStateOf("")};var saving by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!saving)onDismiss()},title={Text("Skill 来源与权限")},text={Column {
        Text("sources：remote 使用 id/type/url/enabled；directory 使用 id/type/chat/path（当前会话 ${vm.selected}）。permissions：pattern/effect(allow、ask、deny)，可加 chat。manual：仅手动调用的ID。项目目录在当前会话工作区内。")
        OutlinedTextField(text,{text=it},Modifier.fillMaxWidth().heightIn(min=180.dp,max=350.dp),label={Text("配置 JSON")},enabled=!saving)
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(enabled=!saving&&vm.jobs.isEmpty(),onClick={scope.launch {saving=true;try{withContext(Dispatchers.IO){vm.extensions.skills.configure(JSONObject(text))};vm.refresh();onDismiss()}catch(e:Exception){error=e.message.orEmpty()}finally{saving=false}}}){Text("保存")}},dismissButton={TextButton(enabled=!saving,onClick=onDismiss){Text("取消")}})
}
