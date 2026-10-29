package ai.capri.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class HistoricalMessage(val entry: Entry, val context: String, val revision: Long)
internal class HistoryActions {
    var target by mutableStateOf<HistoricalMessage?>(null)
    var draft by mutableStateOf("")
    var error by mutableStateOf("")
    fun open(session: Session, entry: Entry) { target=HistoricalMessage(entry,session.contextVersion,session.historyVersion);draft=entry.text;error="" }
    fun submit(vm: CapriModel, chat: Session): Boolean {
        val old=target?:return false
        return runCatching { vm.reviseMessage(chat.id,old.entry.id,draft,old.context,old.revision) }.fold({target=null;true},{error=it.message.orEmpty();false})
    }
}

@Composable internal fun InlineUserMessage(message: Entry, actions: HistoryActions?, enabled: Boolean,
    context: String, revision: Long, onEdit: (() -> Unit)?, onRetry: (() -> Unit)?, onSubmit: (() -> Boolean)?) {
    val editing=actions?.target?.entry?.id==message.id
    val clipboard=LocalClipboardManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF111612),RoundedCornerShape(4.dp)).padding(12.dp)) {
            if(editing&&actions!=null) {
                var value by remember(actions.target) { mutableStateOf(TextFieldValue(actions.draft,TextRange(actions.draft.length))) }
                val focus=remember { FocusRequester() }
                LaunchedEffect(actions.target) { focus.requestFocus();keyboard?.show() }
                val stale=actions.target?.context!=context||actions.target?.revision!=revision
                TextField(value=value,onValueChange={value=it;actions.draft=it.text},modifier=Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription="编辑历史消息" },maxLines=10,
                    colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
                if(stale||actions.error.isNotBlank()) Text(if(stale)"历史记录已变化，请取消后重新编辑" else actions.error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    OutlinedButton(onClick={actions.target=null;keyboard?.hide()},shape=RoundedCornerShape(4.dp)){Text("取消")}
                    Spacer(Modifier.width(8.dp))
                    Button(enabled=enabled&&!stale&&value.text.isNotBlank(),onClick={if(onSubmit?.invoke()==true)keyboard?.hide()},shape=RoundedCornerShape(4.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFF4F6F3),contentColor=Color(0xFF080B09))){Text("发送")}
                }
            } else SelectionContainer { Text(message.text,fontSize=15.sp,color=Color(0xFFF4F6F3),lineHeight=24.sp) }
        }
        if(!editing) Row(Modifier.fillMaxWidth().padding(top=2.dp),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
            if(message.timestamp>0) Text(java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(message.timestamp)),color=Color(0xFF939E94),fontSize=10.sp)
            IconButton(onClick={clipboard.setText(AnnotatedString(message.text))},modifier=Modifier.size(30.dp)){Icon(Icons.Outlined.ContentCopy,"复制消息",Modifier.size(15.dp),tint=Color(0xFF939E94))}
            if(onRetry!=null)IconButton(enabled=enabled,onClick=onRetry,modifier=Modifier.size(30.dp)){Icon(Icons.Outlined.Refresh,"重试消息",Modifier.size(16.dp),tint=Color(0xFF939E94))}
            if(onEdit!=null)IconButton(enabled=enabled,onClick=onEdit,modifier=Modifier.size(30.dp)){Icon(Icons.Outlined.Edit,"编辑消息",Modifier.size(16.dp),tint=Color(0xFF939E94))}
        }
    }
}
