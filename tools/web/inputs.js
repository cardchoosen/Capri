'use strict';
// Uploads are explicitly staged on the phone. Only the send action submits them to a model.
(() => {
  const queue=[], previews=new Map();let worker=false,lastScope='';
  const MAX_FILE=512*1024*1024, CHUNK=1024*1024;
  const sizeLabel=bytes=>bytes>=1024*1024?(bytes/(1024*1024)).toFixed(1)+' MiB':Math.ceil(bytes/1024)+' KiB';
  const same=owner=>owner.chat===chat&&owner.epoch===epoch;
  const ownerOf=()=>({chat,epoch});
  const rpc=(owner,action,payload={},requestId)=>api('web_runtime',{chat_id:owner.chat,epoch:owner.epoch,action,payload,...(requestId?{request_id:requestId}:{})});
  async function operation(owner,action,payload,receipt){
    let value;
    if(receipt.sent)value=await rpc(owner,'operation',{id:receipt.id});
    else{receipt.sent=true;value=await rpc(owner,action,payload,receipt.id);}
    const deadline=Date.now()+120000;
    while(value.state==='running'){
      if(Date.now()>deadline)throw Error('手机仍在处理附件，请核对上传状态；不要重复提交。');
      await new Promise(resolve=>setTimeout(resolve,150));value=await rpc(owner,'operation',{id:receipt.id});
    }
    if(value.state!=='completed')throw Error(value.message||'附件操作未完成，请检查手机状态。');
    return value.data?.result;
  }
  function receipt(){return {id:crypto.randomUUID(),sent:false};}
  function currentDrafts(){return state?.input_drafts||[];}
  function cleanupPreview(key){const value=previews.get(key);if(value?.url)URL.revokeObjectURL(value.url);previews.delete(key);}
  function redraw(){if(state)render(state);else controls();}
  async function preview(item,owner,image){
    if(!item.mime?.startsWith('image/'))return;
    let record=previews.get(item.id);
    if(record?.url){image.src=record.url;return;}
    if(record?.loading)return;
    record={loading:true};previews.set(item.id,record);
    try{
      const response=await fetch('/api/attachment',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({args:{chat_id:owner.chat,epoch:owner.epoch,draft_id:item.id}}),signal:AbortSignal.timeout(60000)});
      if(!response.ok)throw Error('附件预览暂不可用');
      const blob=await response.blob();if(previews.get(item.id)!==record)return;
      record.url=URL.createObjectURL(blob);if(same(owner)){for(const target of $('input-files').querySelectorAll('img'))if(target.dataset.inputId===item.id)target.src=record.url;}
    }catch{if(previews.get(item.id)===record)previews.delete(item.id);}
  }
  function card(item,owner,local){
    const node=document.createElement('div');node.className='input-file'+(local?.error?' error':'');
    if(item.mime?.startsWith('image/')){const image=document.createElement('img');image.alt=item.name;image.loading='lazy';node.append(image);image.dataset.inputId=item.id||'';if(local?.url)image.src=local.url;else void preview(item,owner,image);}
    const info=document.createElement('div');info.className='input-file-info';const name=document.createElement('div');name.className='input-file-name';name.textContent=item.name;name.title=item.name;
    const detail=document.createElement('small');detail.textContent=local?(local.error||`${local.progress===100?'正在校验并保存':'正在上传'} ${local.progress||0}% · ${sizeLabel(item.size)}`):`${sizeLabel(item.size)} · 待发送`;
    info.append(name,detail);node.append(info);
    if(local?.error){const retry=document.createElement('button');retry.type='button';retry.textContent='核对 / 重试';retry.onclick=()=>{local.error='';local.waiting=true;void drain();};node.append(retry);}
    const remove=document.createElement('button');remove.type='button';remove.textContent='×';remove.setAttribute('aria-label','移除附件 '+item.name);
    remove.onclick=async()=>{
      if(local){local.cancelled=true;const index=queue.indexOf(local);if(index>=0)queue.splice(index,1);if(local.url)URL.revokeObjectURL(local.url);if(local.uploadId)void rpc(owner,'upload_abort',{id:local.uploadId}).catch(()=>{});redraw();return;}
      remove.disabled=true;
      try{await operation(owner,'input_remove',{id:item.id},receipt());cleanupPreview(item.id);requestRefresh();}
      catch(error){failure(error.message+' 请刷新并核对附件列表。');requestRefresh();}
    };
    node.append(remove);return node;
  }
  function renderInputs(data){
    const owner={chat:data.chat_id,epoch:data.epoch},scope=owner.chat+'|'+owner.epoch,container=$('input-files');
    if(lastScope&&lastScope!==scope){for(const key of previews.keys())cleanupPreview(key);}lastScope=scope;
    container.replaceChildren();const live=new Set((data.input_drafts||[]).map(x=>x.id));
    for(const key of previews.keys())if(!live.has(key))cleanupPreview(key);
    for(const item of data.input_drafts||[])container.append(card(item,owner));
    for(const entry of queue.filter(q=>q.owner.chat===chat&&!q.cancelled))container.append(card({name:entry.file.name,size:entry.file.size,mime:entry.file.type},owner,entry));
    if((data.input_drafts||[]).some(file=>file.width>0)){const hint=document.createElement('small');hint.className='input-vision-hint';hint.textContent='图片识别需要支持视觉的模型';container.append(hint);}
  }
  async function upload(entry){
    const {owner,file}=entry;
    if(!same(owner))throw Error('应用或会话已变化，请先核对当前附件列表。');
    if(!entry.commit.sent){
    if(!entry.uploadId)entry.uploadId=await operation(owner,'upload_begin',{kind:'file',size:file.size},entry.begin);
    if(entry.cancelled){await rpc(owner,'upload_abort',{id:entry.uploadId});return;}
    const status=await rpc(owner,'upload_status',{id:entry.uploadId});let offset=status.offset;
    if(status.size!==file.size||!Number.isInteger(offset)||offset<0||offset>file.size)throw Error('上传进度不匹配，请移除后重新选择。');
    while(offset<file.size){
      if(entry.cancelled)return;
      if(!same(owner))throw Error('会话或应用已变化，上传已暂停；返回原会话后可核对进度继续。');
      const length=Math.min(CHUNK,status.chunk_bytes||24000);
      const bytes=await file.slice(offset,offset+length).arrayBuffer();
      const digest=await crypto.subtle.digest('SHA-256',bytes);
      const sha256=Array.from(new Uint8Array(digest),v=>v.toString(16).padStart(2,'0')).join('');
      const response=await fetch('/api/upload',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/octet-stream',
        'X-Capri-Upload':JSON.stringify({chat_id:owner.chat,epoch:owner.epoch,id:entry.uploadId,offset,sha256})},body:bytes,signal:AbortSignal.timeout(60000)});
      const value=await response.json();if(!response.ok||value.error)throw Error(value.error||'分块传输未确认，请核对进度后继续。');
      if(value.result!==offset+bytes.byteLength)throw Error('手机接收进度不匹配，请核对后继续。');
      offset=value.result;entry.progress=Math.floor(offset/file.size*100);redraw();
    }
    if(entry.cancelled)return;
    }
    const item=await operation(owner,'input_commit',{upload_id:entry.uploadId,name:file.name},entry.commit);
    if(entry.cancelled){await operation(owner,'input_remove',{id:item.id},receipt());requestRefresh();return;}
    if(entry.url){previews.set(item.id,{url:entry.url});entry.url=null;}
    const index=queue.indexOf(entry);if(index>=0)queue.splice(index,1);
    if(same(owner)){state.input_drafts=[...currentDrafts().filter(x=>x.id!==item.id),item];redraw();}requestRefresh();
  }
  async function drain(){
    if(worker)return;worker=true;
    try{while(true){const entry=queue.find(q=>q.waiting&&!q.cancelled);if(!entry)break;entry.waiting=false;
      try{await upload(entry);}catch(error){entry.error=error.message+' 未确认的操作不会自动重放。';}redraw();
    }}finally{worker=false;controls();if(queue.some(e=>e.waiting&&!e.cancelled))void drain();}
  }
  function choose(files){
    if(!connected||!state)return failure('请先连接手机并选择会话。');
    const selected=Array.from(files),existing=queue.filter(q=>q.owner.chat===chat&&!q.cancelled).length+currentDrafts().length;
    if(existing+selected.length>8)return failure('每条消息最多选择8个文件。');
    if(selected.some(file=>file.size<=0||file.size>MAX_FILE))return failure('每个文件须非空且不超过512 MiB。');
    if(selected.some(file=>file.type.startsWith('image/')&&file.size>20000000))return failure('用于视觉识别的图片仍限制为20 MB；普通文件支持512 MiB。');
    const owner=ownerOf();
    for(const file of selected)queue.push({file,owner,begin:receipt(),commit:receipt(),waiting:true,progress:0,url:file.type.startsWith('image/')?URL.createObjectURL(file):null});
    redraw();void drain();
  }
  $('attach-input').onclick=()=>$('file-picker').click();
  $('file-picker').onchange=event=>{choose(event.target.files);event.target.value='';};
  const form=$('composer');
  for(const name of ['dragenter','dragover'])form.addEventListener(name,event=>{if(Array.from(event.dataTransfer?.types||[]).includes('Files')){event.preventDefault();form.classList.add('dragging');}});
  form.addEventListener('dragleave',()=>form.classList.remove('dragging'));
  form.addEventListener('drop',event=>{form.classList.remove('dragging');if(event.dataTransfer?.files?.length){event.preventDefault();choose(event.dataTransfer.files);}});
  $('input').addEventListener('paste',event=>{const files=Array.from(event.clipboardData?.items||[]).filter(x=>x.kind==='file').map(x=>x.getAsFile()).filter(Boolean);if(files.length){event.preventDefault();choose(files);}});
  window.CapriInputs={render:renderInputs,get blocking(){return queue.some(q=>q.owner.chat===chat&&!q.cancelled);}};
  if(state)renderInputs(state);controls();
})();
