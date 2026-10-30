package ai.capri.app.coding

import org.json.JSONArray
import org.json.JSONObject

/** Bounded synchronous JSON/XML syntax checks. No project code, entities or processes are executed. */
internal object QuickDiagnostics {
    fun inspect(path:String,text:String?):JSONObject {
        if(text==null)return JSONObject().put("status","checked").put("diagnostics",JSONArray())
        if(text.length>1_000_000)return JSONObject().put("status","skipped_size").put("diagnostics",JSONArray())
        val extension=path.substringAfterLast('.',"").lowercase()
        if(extension !in setOf("json","xml"))return JSONObject().put("status","unsupported").put("diagnostics",JSONArray())
        var line=1;var column=1;var message:String?=null
        try {
            if(extension=="json")Json(text).parse() else {
                require(!text.contains("<!DOCTYPE",true)) { "DOCTYPE is not supported by the safe XML checker." }
                val factory=javax.xml.parsers.SAXParserFactory.newInstance().apply { isNamespaceAware=true;isValidating=false }
                val reader=factory.newSAXParser().xmlReader
                reader.entityResolver=org.xml.sax.EntityResolver { _,_ -> throw org.xml.sax.SAXException("External entities are not allowed.") }
                val handler=object:org.xml.sax.helpers.DefaultHandler() {
                    private var depth=0;private val deadline=System.nanoTime()+50_000_000
                    private fun budget(){if(depth>128 || System.nanoTime()>deadline)throw Budget()}
                    override fun startElement(uri:String?,localName:String?,qName:String?,attributes:org.xml.sax.Attributes?){depth++;budget()}
                    override fun endElement(uri:String?,localName:String?,qName:String?){budget();depth--}
                    override fun characters(ch:CharArray?,start:Int,length:Int){budget()}
                    override fun error(e:org.xml.sax.SAXParseException){throw e}
                    override fun fatalError(e:org.xml.sax.SAXParseException){throw e}
                }
                reader.contentHandler=handler;reader.errorHandler=handler
                reader.parse(org.xml.sax.InputSource(java.io.StringReader(text)))
            }
        } catch(e:Position) { line=e.line;column=e.column;message=e.message }
        catch(e:org.xml.sax.SAXParseException){line=e.lineNumber.coerceAtLeast(1);column=e.columnNumber.coerceAtLeast(1);message=e.message}
        catch(e:IllegalArgumentException){message=e.message}
        catch(_:Budget){return JSONObject().put("status","skipped_budget").put("diagnostics",JSONArray())}
        catch(_:Exception){return JSONObject().put("status","checker_unavailable").put("diagnostics",JSONArray())}
        val items=JSONArray()
        if(message!=null)items.put(JSONObject().put("source",extension).put("code","syntax_error").put("severity","error").put("line",line).put("column",column)
            .put("message",message.take(800)).put("anchor",text.lineSequence().drop(line-1).firstOrNull().orEmpty().trim().take(300)))
        return JSONObject().put("status","checked").put("diagnostics",items)
    }
    fun delta(before:JSONObject,after:JSONObject):JSONObject {
        val known=before.optString("status")=="checked" && after.optString("status")=="checked" && !before.optBoolean("truncated") && !after.optBoolean("truncated")
        fun values(o:JSONObject)=o.optJSONArray("diagnostics")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        fun key(d:JSONObject)=listOf("source","code","severity","message","anchor").joinToString("\u0000"){d.optString(it)}
        val old=values(before).groupingBy(::key).eachCount().toMutableMap();val fresh=mutableListOf<JSONObject>();var existing=0
        for(d in values(after)) { val k=key(d);if(known && (old[k] ?: 0)>0){existing++;old[k]=old.getValue(k)-1}else fresh+=d }
        return JSONObject().put("status",after.optString("status")).put("baseline_available",known).put("reason",after.optString("reason")).put("baseline_reason",before.optString("reason"))
            .put(if(known)"new" else "unclassified",JSONArray(fresh.take(100))).put("existing_count",existing).put("truncated",after.optBoolean("truncated") || fresh.size>100).put("findings_omitted",(fresh.size-100).coerceAtLeast(0))
            .put("resolved_count",if(known)old.values.sum() else 0).put("new_errors",if(known)fresh.count { it.optString("severity")=="error" } else JSONObject.NULL)
            .put("notice","Analyzer findings only; absence of reported findings is not build/test proof. Positions are 1-based; anchors distinguish unchanged findings after line shifts.")
    }
    private class Budget:RuntimeException()
    private class Position(val line:Int,val column:Int,message:String):RuntimeException(message)
    private class Json(private val text:String) {
        private var at=0;private val end=System.nanoTime()+50_000_000
        private fun fail():Nothing { val prefix=text.take(at);throw Position(prefix.count { it=='\n' }+1,at-(prefix.lastIndexOf('\n')+1)+1,"Invalid JSON syntax.") }
        private fun step(){at++;if(at%1024==0 && System.nanoTime()>end)throw Budget()}
        private fun space(){while(at<text.length && text[at] in " \t\r\n")step()}
        private fun token(c:Char){space();if(at>=text.length || text[at]!=c)fail();step()}
        fun parse(){space();value(0);space();if(at!=text.length)fail()}
        private fun string(){token('"');while(at<text.length){val c=text[at];step();if(c=='"')return;if(c<' ')fail();if(c=='\\'){if(at>=text.length)fail();val e=text[at];step();if(e=='u'){repeat(4){if(at>=text.length || text[at].digitToIntOrNull(16)==null)fail();step()}}else if(e !in "\"\\/bfnrt")fail()}};fail()}
        private fun value(depth:Int){
            if(depth>64)throw Budget();space();if(at>=text.length)fail()
            when(text[at]) {
                '"' -> string()
                '{' -> {step();space();if(at<text.length && text[at]=='}'){step();return};while(true){string();token(':');value(depth+1);space();if(at<text.length && text[at]=='}'){step();return};token(',')}}
                '[' -> {step();space();if(at<text.length && text[at]==']'){step();return};while(true){value(depth+1);space();if(at<text.length && text[at]==']'){step();return};token(',')}}
                't','f','n' -> {val word=when(text[at]){'t'->"true";'f'->"false";else->"null"};if(!text.startsWith(word,at))fail();repeat(word.length){step()}}
                else -> {
                    if(text[at]=='-')step();if(at>=text.length)fail()
                    if(text[at]=='0')step() else {if(text[at] !in '1'..'9')fail();while(at<text.length && text[at] in '0'..'9')step()}
                    if(at<text.length && text[at]=='.'){step();val start=at;while(at<text.length && text[at] in '0'..'9')step();if(start==at)fail()}
                    if(at<text.length && text[at] in "eE"){step();if(at<text.length && text[at] in "+-")step();val start=at;while(at<text.length && text[at] in '0'..'9')step();if(start==at)fail()}
                }
            }
        }
    }
}
