package ai.capri.app.extensions.hub

import ai.capri.app.Workspace
import ai.capri.app.RuntimeGate
import ai.capri.app.extensions.ExtensionStore
import ai.capri.app.git.GitAccounts
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** Application-owned public catalog and explicit native approval. No model receives credentials. */
internal class PublicSkillHub private constructor(private val context:Context) {
    companion object { private var instance:PublicSkillHub?=null
        @Synchronized fun get(context:Context)=instance?:PublicSkillHub(context.applicationContext).also { instance=it }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val gate=Mutex();private val syncGate=Mutex()
    private val remote=HubGitHub()
    private val catalog=HubCatalog(File(context.filesDir,"public-skill-catalog"),remote)
    private val contributions=HubContribution(File(context.filesDir,"skill-contributions"),remote)
    private val store by lazy { ExtensionStore(context) }
    private val jobs=java.util.concurrent.ConcurrentHashMap<String,Job>()
    private val revision=MutableStateFlow(0L);val updates=revision.asStateFlow()
    val openRequests=MutableStateFlow(0L)
    private val prefs=context.getSharedPreferences("public-skill-hub",Context.MODE_PRIVATE)
    fun enabled()=prefs.getBoolean("enabled",true)
    fun enable(value:Boolean) { prefs.edit().putBoolean("enabled",value).commit();if(value)HubAndroid.schedule(context,true)else HubAndroid.cancel(context);revision.update { it+1 } }
    fun status()=catalog.status().put("status",if(prefs.getString("last_error","").isNullOrBlank())catalog.status().optString("status","not_synced")else "partial").put("repository",HubGitHub.REPOSITORY).put("enabled",enabled()).put("last_error",prefs.getString("last_error","")).put("last_attempt",prefs.getLong("last_attempt",0))
    suspend fun sync()=syncGate.withLock {
        if(!enabled())return@withLock status()
        prefs.edit().putLong("last_attempt",System.currentTimeMillis()).commit()
        try {
            val result=catalog.sync();if(!enabled())return@withLock status()
            val applied=store.applyPublicSkills(catalog.sources());prefs.edit().remove("last_error").commit()
            result.put("installation",applied)
        }catch(cancel:CancellationException){prefs.edit().putString("last_error","catalog_refresh_interrupted").commit();throw cancel}
        catch(error:Exception) { prefs.edit().putString("last_error",if(error is HubHttpError)"github_http_${error.code}" else "catalog_refresh_failed").commit();status().put("status","partial") }
        finally { revision.update { it+1 } }
    }
    fun list(chat:String)=contributions.list(chat)
    fun resource(chat:String,key:String,path:String)=contributions.resource(chat,key,path)
    fun read(chat:String,key:String)=contributions.read(chat,key)
    fun running(chat:String,key:String)=jobs["$chat/$key"]?.isActive==true
    fun acknowledge(chat:String,key:String) { contributions.acknowledge(chat,key);revision.update { it+1 } }
    fun reopen() { openRequests.update { it+1 };revision.update { it+1 } }
    suspend fun execute(chat:String,args:JSONObject):String=withContext(Dispatchers.IO) {
        HubContract.validate(args)
        when(args.getString("action")) {
            "status"->status()
            "list"->JSONObject().put("contributions",list(chat))
            "read"->read(chat,args.getString("key"))
            else->{
                val lease=RuntimeGate.beginFiles(context,chat,"Public Skill preview")
                try {
                    val source=Workspace(File(context.filesDir,"sessions")).file(chat,args.getString("path").removePrefix("/workspace/"))
                    val record=contributions.prepare(chat,source,args.getString("id"));revision.update { it+1 }
                    record.put("status",if(record.optString("state")=="awaiting_user")"awaiting_user" else "ok")
                        .put("notice","No files were uploaded by prepare. Native user confirmation publishes the displayed frozen files publicly, before PR review. Chat text cannot approve publication.")
                }finally{RuntimeGate.endFiles(lease)}
            }
        }.toString()
    }
    fun decline(chat:String,key:String) { contributions.decline(chat,key);revision.update { it+1 } }
    /** Called only by the native confirmation button after file preview. */
    fun approve(chat:String,key:String) {
        val jobKey="$chat/$key"
        if(jobs[jobKey]?.isActive==true)return
        contributions.approve(chat,key)
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try { gate.withLock { withTimeout(120000) {
                val accounts=GitAccounts(context);val secret=accounts.secret("github.com")?:error("Configure a personal GitHub account before contributing.")
                val fingerprint=HubPackage.hash("${secret.username}\u0000${secret.token}".toByteArray())
                contributions.publish(chat,key,secret.username,secret.token) { runCatching { accounts.secret("github.com")?.let { HubPackage.hash("${it.username}\u0000${it.token}".toByteArray())==fingerprint }==true }.getOrDefault(false) }
            } } }catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){ /* The durable receipt owns recovery; never echo a credential-bearing exception. */ }
            finally { jobs.remove(jobKey);revision.update { it+1 } }
        }
        jobs[jobKey]=job;revision.update { it+1 };job.start()
    }
    suspend fun stopChat(chat:String) { jobs.filterKeys{it.startsWith("$chat/")}.values.toSet().forEach { it.cancelAndJoin() } }
    suspend fun stopAll() { jobs.values.toSet().forEach { it.cancelAndJoin() } }
}
