package ai.capri.app.knowledge

/** Host-prepared timeline data. Skill rendering and attachment access remain host-owned. */
data class ContextEntry(val role:String,val text:String,val id:String,val inputs:String?=null,val inputManifest:String="")
