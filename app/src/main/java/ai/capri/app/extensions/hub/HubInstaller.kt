package ai.capri.app.extensions.hub

import ai.capri.app.agent.reliability.DurableJson
import ai.capri.app.extensions.SkillParser
import ai.capri.app.extensions.SkillVersions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Called under the existing import lock; origin snapshots protect user edits and deletions. */
internal class HubInstaller(private val stateFile:File) {
    fun apply(sources:List<File>,skillsRoot:File,builtins:Set<String>,install:(File,Boolean)->Unit,disable:(String)->Unit):JSONObject {
        sources.forEach{require(HubPackage.metadata(it).id==it.name)}
        val state=DurableJson(stateFile);val incoming=mutableSetOf<String>();val skipped=JSONArray();var changed=0
        for(source in sources) {
            val id=source.name;incoming+=id;if(id in builtins){skipped.put(id);continue}
            val old=state.access { it.optJSONObject(id)?.let{value->JSONObject(value.toString())} }
            val target=File(skillsRoot,id);val before=if(target.exists())SkillVersions.revision(target)else null
            if(old?.optBoolean("suppressed")==true || old!=null && before==null || before!=null && (old==null || before!=old.optString("installed_revision"))) { skipped.put(id);continue }
            val version=SkillVersions.revision(source)
            if(before!=version) { install(source,before!=null);changed++ }
            require(SkillVersions.revision(target)==version) { "Installed public Skill differs from its source; origin was not advanced." }
            state.access{it.put(id,JSONObject().put("installed_revision",version).put("source",HubGitHub.REPOSITORY))}
        }
        val origins=state.access{JSONObject(it.toString())}
        for(id in origins.keys())if(id !in incoming) {
            val target=File(skillsRoot,id);val old=origins.getJSONObject(id)
            if(target.exists() && SkillVersions.revision(target)==old.optString("installed_revision"))disable(id)
        }
        return JSONObject().put("status","ok").put("changed",changed).put("preserved_local",skipped)
    }
}
