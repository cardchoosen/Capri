'use strict';
const vm=require('node:vm'),fs=require('node:fs'),assert=require('node:assert/strict'),{webcrypto}=require('node:crypto');
class Node {constructor(){this.children=[];this.classList={add(){},remove(){}};}append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=nodes;}setAttribute(){}addEventListener(){}querySelectorAll(){return [];} }
const elements=new Map(),get=id=>{if(!elements.has(id))elements.set(id,new Node());return elements.get(id);};
const size=100*1024*1024;let received=0,chunks=0,loseResponse=true,commits=0,committed=null;
const scope={console,crypto:webcrypto,Uint8Array,ArrayBuffer,AbortSignal,URL,setTimeout,clearTimeout,chat:'chat',epoch:'epoch',connected:true,token:'fixture',window:{},state:{chat_id:'chat',epoch:'epoch',input_drafts:[]},$:get,document:{createElement:()=>new Node()},controls(){},requestRefresh(){},failure(message){throw Error(message);}};
scope.render=data=>scope.window.CapriInputs?.render(data);
scope.api=async(method,args)=>{
 const action=args.action;
 if(action==='upload_begin')return {state:'completed',data:{result:'upload'}};
 if(action==='upload_status')return {size,offset:received,chunk_bytes:1024*1024};
 if(action==='input_commit'){commits++;committed={id:'input',name:'large.bin',size,mime:'application/octet-stream'};throw Error('Commit response lost after import');}
 if(action==='operation'&&committed)return {state:'completed',data:{result:committed}};
 throw Error('Unexpected action '+action);
};
scope.fetch=async(url,request)=>{
 assert.equal(url,'/api/upload');const metadata=JSON.parse(request.headers['X-Capri-Upload']);
 assert.equal(metadata.offset,received);assert.ok(request.body.byteLength<=1024*1024);
 const hash=Buffer.from(await webcrypto.subtle.digest('SHA-256',request.body)).toString('hex');assert.equal(metadata.sha256,hash);
 received+=request.body.byteLength;chunks++;
 if(loseResponse){loseResponse=false;throw Error('Response lost after phone received the chunk');}
 return {ok:true,json:async()=>({result:received})};
};
vm.createContext(scope);vm.runInContext(fs.readFileSync(__dirname+'/web/inputs.js','utf8'),scope);
function find(node,text){if(node.textContent===text)return node;for(const child of node.children||[]){const found=find(child,text);if(found)return found;}}
async function wait(test){for(let n=0;n<500&&!test();n++)await new Promise(r=>setTimeout(r,5));assert.ok(test(),'Upload fixture did not settle');}
(async()=>{
 const file={name:'large.bin',type:'application/octet-stream',size,slice(start,end){return {arrayBuffer:async()=>new Uint8Array(Math.min(size,end)-start).fill(31).buffer};}};
 get('file-picker').onchange({target:{files:[file],value:'selected'}});
 await wait(()=>find(get('input-files'),'核对 / 重试'));
 assert.equal(received,1024*1024);assert.equal(scope.window.CapriInputs.blocking,true);
 find(get('input-files'),'核对 / 重试').onclick();
 await wait(()=>commits===1&&find(get('input-files'),'核对 / 重试'));
 find(get('input-files'),'核对 / 重试').onclick();
 await wait(()=>!scope.window.CapriInputs.blocking);
 assert.equal(received,size);assert.equal(chunks,100);assert.equal(commits,1);assert.equal(scope.state.input_drafts[0].size,size);
 let resolveBegin,aborted=0;
 scope.api=async(method,args)=>{
   if(args.action==='upload_begin')return new Promise(resolve=>{resolveBegin=resolve;});
   if(args.action==='upload_abort'){aborted++;return {result:'removed'};}
   throw Error('Cancelled upload must not proceed: '+args.action);
 };
 get('file-picker').onchange({target:{files:[file],value:'selected'}});
 await wait(()=>resolveBegin);
 find(get('input-files').children.at(-1),'×').onclick();
 resolveBegin({state:'completed',data:{result:'late-upload'}});
 await wait(()=>aborted===1);assert.equal(scope.window.CapriInputs.blocking,false);
 console.log('PASS: 100 MiB bounded chunks, hash metadata, chunk/commit lost-response recovery, single commit and cancellation before begin acknowledgement');
})().catch(error=>{console.error(error);process.exitCode=1;});
