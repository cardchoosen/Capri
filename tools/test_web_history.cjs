'use strict';
const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync(__dirname+'/web/app.js','utf8');
const end=source.indexOf('let skillRequest=');
const history=source.slice(source.indexOf('// A single visible timeline:'),end<0?source.length:end);
class Element {
  constructor(tag){this.tagName=tag;this.children=[];this.style={};this.attributes={};this.classList={add:()=>{}};this.scrollHeight=90;}
  append(...children){this.children.push(...children);}
  replaceChildren(...children){this.children=children;}
  setAttribute(key,value){this.attributes[key]=value;}
  focus(){this.focused=true;}
  setSelectionRange(){}
}
function fixture(){
  const calls=[],scope={console,TextEncoder,crypto:require('node:crypto'),document:{createElement:tag=>new Element(tag)},
    connected:true,dispatching:false,pending:null,state:{context_id:'context',history_revision:5},chat:'chat',epoch:'epoch',
    historyTarget:null,historyEditor:null,historyLoad:0,nodes:new Map(),entries:new Map(),views:new Map(),
    api:async(method,args)=>{calls.push({method,args});return method==='web_message'?{text:'Original message'}:{accepted:true};},
    controls(){},failure(message){scope.error=message;},pendingSet(value){scope.pending=value;},requestRefresh(){scope.refreshed=true;},
    renderMessages(){scope.rendered=true;}};
  vm.createContext(scope);vm.runInContext(fs.readFileSync(__dirname+'/web/chat-layout.js','utf8')+'\n'+history,scope);
  function entry(id,index){const value={id,index,role:'user',text:'Original message'};const key=scope.groupChatEntries([value])[0].key;
    const record={node:new Element('article'),signature:'original'};scope.nodes.set(key,record);scope.entries.set(index,value);return {value,record};}
  return {scope,calls,entry};
}
(async()=>{
  // Use the actual renderer's key with an unrelated numeric index: the reported no-op regression.
  const f=fixture(),message=f.entry('stable-id',42);
  await f.scope.openHistorical(message.value);
  assert.equal(f.scope.historyEditor.input.value,'Original message');assert.equal(message.record.node.children[0].tagName,'form');
  assert.equal(f.scope.historyEditor.input.focused,true);
  f.scope.cancelHistorical();assert.equal(f.scope.historyEditor,null);assert.equal(message.record.signature,'');assert.equal(f.scope.rendered,true);
  await f.scope.openHistorical(message.value);f.scope.historyEditor.input.value='Edited 用户 message';
  await f.scope.submitHistorical('edit');
  const edit=f.calls.find(c=>c.method==='web_revise');assert.equal(edit.args.entry_id,'stable-id');assert.equal(edit.args.mode,'edit');
  assert.equal(edit.args.text,'Edited 用户 message');assert.equal(edit.args.history_revision,5);assert.equal(f.scope.pending,null);assert.equal(f.scope.historyEditor,null);
  assert.equal(f.calls.filter(c=>c.method==='web_revise').length,1);
  await f.scope.retryHistorical(message.value);const retry=f.calls.filter(c=>c.method==='web_revise').at(-1);
  assert.equal(retry.args.mode,'retry');assert.equal(retry.args.entry_id,'stable-id');assert.equal('text' in retry.args,false);
  // Slow older loads must not replace a newer editor.
  const race=fixture(),first=race.entry('first',1),second=race.entry('second',2),loads=[];
  race.scope.api=()=>new Promise(resolve=>loads.push(resolve));
  const a=race.scope.openHistorical(first.value),b=race.scope.openHistorical(second.value);
  loads[1]({text:'Second'});await b;loads[0]({text:'First'});await a;
  assert.equal(race.scope.historyEditor.input.value,'Second');assert.equal(race.scope.historyTarget.entry_id,'second');
  const stale=fixture(),old=stale.entry('old',9);let resolve;
  stale.scope.api=()=>new Promise(r=>{resolve=r;});const load=stale.scope.openHistorical(old.value);
  stale.scope.state.history_revision++;resolve({text:'Old'});await load;assert.equal(stale.scope.historyEditor,null);assert.match(stale.scope.error,/历史记录已变化/);
  const missing=fixture();await missing.scope.openHistorical({id:'gone',index:3});assert.match(missing.scope.error,/消息已不在当前页面/);
  const cancelled=fixture(),cancelledEntry=cancelled.entry('cancelled',4);let reject;
  cancelled.scope.api=()=>new Promise((_,r)=>{reject=r;});const abandoned=cancelled.scope.openHistorical(cancelledEntry.value);
  cancelled.scope.cancelHistorical();reject(new Error('Late failure'));await abandoned;assert.equal(cancelled.scope.error,undefined);
  console.log('PASS: actual message keys, editor/cancel, edit/resend payloads, scoped revision, load race and missing-node feedback');
})().catch(error=>{console.error(error);process.exitCode=1;});
