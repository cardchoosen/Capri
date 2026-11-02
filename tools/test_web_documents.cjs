'use strict';
const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const all=[];
class Element {
 constructor(tag){this.tagName=tag;this.children=[];this.attributes={};this.hidden=false;this.scrollTop=0;all.push(this);}
 append(...children){this.children.push(...children);}
 replaceChildren(...children){this.children=children;}
 setAttribute(k,v){this.attributes[k]=v;}
 after(...nodes){this.afterNodes=nodes;}
 set innerHTML(_){throw Error('Document content must never use innerHTML');}
}
const get=id=>all.find(x=>x.id===id)||Object.assign(new Element('div'),{id});
const header=new Element('header');const sample='# Report\n<script>unsafe()</script>\n\n| Key | Value |\n| --- | --- |\n| a | b |\n```js\nalert(1)\n```';
const blob=new Blob([sample]);let fetches=0,revoked=0;
const scope={document:{createElement:tag=>new Element(tag),createTextNode:text=>Object.assign(new Element('#text'),{textContent:text}),querySelector:()=>header},window:{},$:get,
 state:{chat_id:'a',context_id:'ctx',history_revision:0,epoch:'epoch',sessions:[{chat_id:'a'},{chat_id:'b'}]},chat:'a',epoch:'epoch',token:'fixture',
 viewport:{pinned:false,lastTop:120,bottom(){this.pinned=true;}},AbortController,TextDecoder,URL:{createObjectURL:()=>`blob:fixture`,revokeObjectURL(){revoked++;}},
 fetch:async()=>{fetches++;return {ok:true,blob:async()=>blob};},failure(message){scope.error=message;}};
get('messages').scrollTop=120;get('composer');
vm.createContext(scope);
const source=fs.readFileSync(__dirname+'/web/app.js','utf8');vm.runInContext(source.slice(source.indexOf('function inline('),source.indexOf('// Parse Markdown once'))+'\n'+fs.readFileSync(__dirname+'/web/documents.js','utf8'),scope);
const docs=scope.window.CapriDocuments;
function find(node,text){if(node.textContent===text)return node;for(const child of node.children||[]){const match=find(child,text);if(match)return match;}}
async function settle(){await new Promise(r=>setImmediate(r));await new Promise(r=>setImmediate(r));}
(async()=>{
 const entry={id:'report',index:9},item={name:'report.MD',mime:'text/plain',size:blob.size};
 assert.equal(docs.isMarkdown(item),true);assert.equal(docs.isMarkdown({name:'report.html'}),false);
 docs.open(entry,item);docs.open(entry,item);await settle();
 assert.equal(fetches,1);assert.equal(get('messages').hidden,true);assert.equal(get('composer').hidden,true);assert.equal(get('document-panel').hidden,false);
 assert.ok(all.some(n=>n.tagName==='h1'));assert.ok(all.some(n=>n.tagName==='table'));assert.ok(all.some(n=>n.tagName==='code'));
 assert.ok(all.some(n=>n.textContent==='<script>unsafe()</script>'));assert.equal(all.some(n=>n.tagName==='script'),false);
 get('messages').scrollTop=0;find(header.afterNodes[0],'对话').onclick();assert.equal(get('messages').scrollTop,120);assert.equal(get('document-panel').hidden,true);
 docs.open(entry,item);await settle();assert.equal(fetches,1);
 docs.beforeSwitch();scope.chat='b';scope.state={...scope.state,chat_id:'b'};docs.sync(scope.state);assert.equal(get('document-panel').hidden,true);
 scope.chat='a';scope.state={...scope.state,chat_id:'a'};docs.sync(scope.state);docs.open(entry,item);await settle();assert.equal(fetches,1);
 docs.open({id:'large',index:10},{name:'large.md',size:3*1024*1024});await settle();assert.equal(fetches,1);assert.ok(find(get('document-panel'),'文档超过 2 MiB 预览上限，请下载后查看，或让 Capri 按段分析。'));
 find(header.afterNodes[0],'×').onclick();assert.ok(revoked>0);
 scope.state={...scope.state,epoch:'new-epoch'};scope.epoch='new-epoch';docs.sync(scope.state);assert.equal(get('document-panel').hidden,true);
 docs.open(entry,item);await settle();assert.equal(fetches,2);assert.equal(get('document-panel').hidden,false);
 console.log('PASS: Markdown view tabs, safe headings/tables/code, single load, scroll restoration, chat isolation, close cleanup and bounded preview');
})().catch(error=>{console.error(error);process.exitCode=1;});
