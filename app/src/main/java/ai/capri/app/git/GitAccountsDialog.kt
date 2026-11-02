package ai.capri.app.git

import ai.capri.app.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun GitAccountsDialog(accounts: GitAccounts, onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf(accounts.list()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(entries.isEmpty()) }
    var host by remember { mutableStateOf("github.com") }
    var username by remember { mutableStateOf("") };var token by remember { mutableStateOf("") }
    var repository by remember { mutableStateOf("") };var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) };var failed by remember { mutableStateOf(false) }
    val automaticUsername = runCatching { GitCredentialPolicy.host(host) == "github.com" }.getOrDefault(false)
    val scope = rememberCoroutineScope()
    fun reset() { editing=null;host="github.com";username="";token="";repository="";message="";failed=false }
    fun back() { if(!busy) { if(editorOpen){editorOpen=false;reset()}else onDismiss() } }
    DetailPage(onDismissRequest=::back,title={Text(if(editorOpen)if(editing==null)"添加 Git 账号" else "编辑 Git 账号" else "Git 账号")},text={
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            ConfigIntro("全局 · 所有对话", "每个 HTTPS 主机保存一个账号，供后续 Git 认证使用。")
            if(!editorOpen) {
                FilledTonalButton(onClick={reset();editorOpen=true},modifier=Modifier.fillMaxWidth()){Text("添加账号")}
                if(entries.isEmpty())ConfigGroup("尚未配置 Git 账号","添加 GitHub Token 或其他托管平台账号。"){}
                entries.forEach { account -> ConfigGroup(account.host,account.username) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled=!busy,onClick={editing=account.host;host=account.host;username=account.username;token="";repository="";message="";failed=false;editorOpen=true}){Text("编辑")}
                        TextButton(enabled=!busy,onClick={scope.launch {
                            busy=true;failed=false
                            try { withContext(Dispatchers.IO){accounts.remove(account.host)};entries=accounts.list();message="账号已删除，后续认证不再提供此 Token。" }
                            catch(cancel:CancellationException){throw cancel}
                            catch(_:Exception){message="删除未完成，请重试。";failed=true}
                            finally{busy=false}
                        }}){Text("删除",color=MaterialTheme.colorScheme.error)}
                    }
                } }
            }else {
                ConfigGroup("账号信息",if(automaticUsername)"GitHub 保存时会自动识别用户名。" else "填写托管平台的 HTTPS 主机与用户名。") {
                    OutlinedTextField(host,{host=it;message=""},label={Text("HTTPS 主机")},placeholder={Text("github.com")},enabled=!busy&&editing==null,singleLine=true,modifier=Modifier.fillMaxWidth())
                    if(!automaticUsername)OutlinedTextField(username,{username=it;message=""},label={Text("Git 用户名")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(token,{token=it;message=""},label={Text(if(editing!=null)"新 Token（留空保留）" else "Token")},enabled=!busy,singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),modifier=Modifier.fillMaxWidth())
                    if(editing!=null)ConfigFact("已保存账号",username)
                }
                if(editing!=null)ConfigHelp("测试仓库读取权限") {
                    OutlinedTextField(repository,{repository=it;message=""},label={Text("仓库 HTTPS 地址")},placeholder={Text("https://github.com/owner/repo.git")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedButton(enabled=!busy&&repository.isNotBlank(),onClick={scope.launch {
                        busy=true;failed=false;message="正在检查仓库读取权限…"
                        try { message=withContext(Dispatchers.IO){GitAccountTest.check(accounts,editing!!,repository)} }
                        catch(cancel:CancellationException){throw cancel}
                        catch(error:IllegalArgumentException){message=error.message?:"仓库地址无效";failed=true}
                        catch(_:Exception){message="检查失败，请重试";failed=true}
                        finally{busy=false}
                    }}){Text("测试已保存账号")}
                }
            }
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            ConfigNotice(message,failed)
            ConfigHelp("凭据与权限说明") {Text("Token 加密保存在本机，不回显。仅支持 HTTPS Token；权限由托管平台决定，提交署名沿用仓库或会话配置。",style=MaterialTheme.typography.bodySmall)}
        }
    },confirmButton={if(editorOpen)Button(enabled=!busy,onClick={scope.launch {
        busy=true;failed=false
        try {
            val canonical=GitCredentialPolicy.host(host)
            message=if(automaticUsername&&token.isNotBlank())"正在识别 GitHub 账号…" else "正在保存…"
            val saved=withContext(Dispatchers.IO){accounts.saveFromSettings(host,username,token)}
            username=saved.username;editing=canonical;host=canonical;token="";entries=accounts.list();message="已保存，所有对话的后续 Git 认证可使用。"
        }catch(cancel:CancellationException){throw cancel}
        catch(error:IllegalArgumentException){message=error.message?:"输入无效";failed=true}
        catch(_:Exception){message="保存失败，请重试";failed=true}
        finally{busy=false}
    }},modifier=Modifier.fillMaxWidth()){Text(if(busy)"正在处理…" else "保存账号")}})
}
