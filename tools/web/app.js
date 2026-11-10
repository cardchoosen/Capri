'use strict';
const $ = id => document.getElementById(id);
const fragment = new URLSearchParams(location.hash.slice(1));
if (fragment.has('token')) { sessionStorage.setItem('capri-token', fragment.get('token')); history.replaceState(null, '', '/'); }
const token = sessionStorage.getItem('capri-token') || '';
let chat = sessionStorage.getItem('capri-chat') || '', epoch = '', state = null, connected = false;
let pending = JSON.parse(sessionStorage.getItem('capri-pending') || 'null');
let polling = false, dispatching = false, olderCursor = 0, navSignature = '', generation = 0;
let pollTimer = null, refreshAgain = false;
const views = new Map();
let deleteTarget = null, historyTarget = null, historyEditor = null, historyLoad = 0;
let entries = new Map(), nodes = new Map();
// Reading intent survives rendering; proximity alone must never resume following.
class ChatViewport {
  constructor(element, changed=()=>{}) {
    this.element=element;this.changed=changed;this.pinned=true;this.lastTop=element.scrollTop;
    element.addEventListener('wheel',e=>{if(e.deltaY<0)this.pause();},{passive:true});
    let touchY=null;
    element.addEventListener('touchstart',e=>{touchY=e.touches[0]?.clientY;},{passive:true});
    element.addEventListener('touchmove',e=>{const y=e.touches[0]?.clientY;if(touchY!=null&&y>touchY)this.pause();touchY=y;},{passive:true});
    element.addEventListener('keydown',e=>{if(['ArrowUp','PageUp','Home'].includes(e.key)&&!e.target.closest('textarea,input'))this.pause();});
    element.addEventListener('scroll',()=>{
      const top=element.scrollTop;
      if(top<this.lastTop-1)this.pinned=false;
      else if(this.atBottom())this.pinned=true;
      this.lastTop=top;this.changed(this.pinned);
    },{passive:true});
  }
  atBottom(){const e=this.element;return e.scrollHeight-e.clientHeight-e.scrollTop<=2;}
  pause(){this.pinned=false;this.changed(false);}
  bottom(){this.pinned=true;this.element.scrollTop=this.element.scrollHeight;this.lastTop=this.element.scrollTop;this.changed(true);}
  capture(){
    const e=this.element;if(this.pinned)return {pinned:true};
    const edge=e.getBoundingClientRect().top;
    const anchor=Array.from($('entries').children).find(n=>n.getBoundingClientRect().bottom>edge);
    return {pinned:this.pinned,top:e.scrollTop,anchor,offset:anchor?.getBoundingClientRect().top};
  }
  restore(saved){
    if(saved.pinned){this.bottom();return;}
    const offset=saved.anchor?.isConnected?saved.anchor.getBoundingClientRect().top-saved.offset:0;
    this.element.scrollTop=saved.top+offset;this.lastTop=this.element.scrollTop;this.pinned=false;this.changed(false);
  }
}
const viewport=new ChatViewport($('messages'),pinned=>{$('latest').hidden=pinned;});
$('latest').onclick=()=>viewport.bottom();
function pendingSet(value) { pending = value; if (value) sessionStorage.setItem('capri-pending', JSON.stringify(value)); else sessionStorage.removeItem('capri-pending'); controls(); }
function controls() {
  window.CapriModelPicker?.controls();
  $('send').textContent=state?.busy?'补充要求 ↑':'发送 ↑';
  $('input').placeholder=state?.busy?'补充要求，将在安全边界接入…':state?.run_state?.question?'回答以上问题以继续…':'从这里继续对话…';
  $('uncertain').hidden = !pending || dispatching;
  $('send').disabled = !connected || dispatching || !!pending || !state || state.deleting || state.preparing || !!window.CapriInputs?.blocking || !!window.CapriModelPicker?.blocking;
  $('create').disabled = !connected || dispatching || !!pending || !state || state.preparing;
  $('catalog-open').disabled=!connected||!state;
  $('run-open').disabled=!connected||!state;
  $('attach-input').disabled=!connected||!state||state.preparing||state.deleting;
  $('skill-mention').disabled=!connected||!state||state.preparing;
  const locked=historyLocked();for(const b of document.querySelectorAll('.user-edit,.user-retry'))b.disabled=locked;
  if(historyEditor){const stale=historyTarget&&(chat!==historyTarget.chat_id||epoch!==historyTarget.epoch||state?.context_id!==historyTarget.from_context||state?.history_revision!==historyTarget.history_revision);historyEditor.send.disabled=locked||!!stale;if(stale)historyEditor.error.textContent='历史记录已变化，请取消后重新编辑。';}
  $('stop').hidden = !state?.busy; $('stop').disabled = !connected || dispatching;
}
function failure(message) { $('error').textContent = message; $('error').hidden = !message; }
async function api(method, args) {
  const response = await fetch('/api/call', { method: 'POST', headers: {'Content-Type':'application/json', Authorization:'Bearer '+token}, body:JSON.stringify({method,args}), signal:AbortSignal.timeout(60000) });
  const result = await response.json();
  if (!response.ok || result.error) {
    const messages={kernel_stopped:'内核已关闭；请打开手机上的 Capri 后恢复连接。',device_offline:'等待已选择的手机连接；请检查 USB 和调试授权。',device_unauthorized:'请在手机上允许这台电脑进行 USB 调试。',device_ambiguous:'检测到多台手机，请用 --serial 指定设备。',app_not_ready:'正在恢复 Capri，请检查手机是否需要解锁或确认启动。',bridge_unavailable:'手机调试入口不可用，请确认安装的是 Capri Debug 版本。',connection_lost:'连接已断开，正在自动恢复；未确认的操作不会自动重发。'};
    const error=new Error(response.status===401?'访问凭证已变化，请用电脑服务的“打开网页”命令重新进入。':messages[result.code]||result.error||'连接失败');error.code=result.code;throw error;
  }
  return result;
}
// Parse Markdown once, then reveal existing text nodes at most every 32 ms.
function revealFinal(body, text, record) {
  markdown(body,text);
  const chunks=[];let total=0;
  for(const block of body.children){
    const walker=document.createTreeWalker(block,NodeFilter.SHOW_TEXT);let node;
    while((node=walker.nextNode())){const chars=Array.from(node.data);chunks.push({node,chars,start:total,block});total+=chars.length;node.data='';}
    block.hidden=true;
  }
  const version=record.version,gen=generation,start=performance.now(),duration=Math.min(8000,total*18);
  let cursor=0,lastLength=-1,lastFrame=-Infinity,finished=false;
  record.finish=()=>{if(finished)return;finished=true;for(const chunk of chunks){chunk.node.data=chunk.chars.join('');chunk.block.hidden=false;}record.finish=null;};
  function animate(now){
    if(finished||gen!==generation||version!==record.version)return;
    if(now-lastFrame<32){requestAnimationFrame(animate);return;}lastFrame=now;
    const length=duration?Math.ceil(total*Math.min(1,(now-start)/duration)):total;
    if(length!==lastLength){const reading=viewport.capture();
      while(cursor<chunks.length){const chunk=chunks[cursor],count=Math.min(chunk.chars.length,Math.max(0,length-chunk.start));
        if(count>0){chunk.block.hidden=false;chunk.node.data=chunk.chars.slice(0,count).join('');}
        if(count<chunk.chars.length)break;cursor++;
      }
      lastLength=length;viewport.restore(reading);
    }
    if(length<total)requestAnimationFrame(animate);else record.finish();
  }
  requestAnimationFrame(animate);
}
function attachmentView(body, entry, record) {
  let item;try{item=JSON.parse(entry.text);}catch{body.textContent='附件记录无法读取';return;}
  const sourceChat=chat,sourceEpoch=epoch,sourceContext=state?.context_id,controller=new AbortController();let objectUrl=null,observer=null,started=false;
  const figure=document.createElement('figure');figure.className='attachment-figure';body.append(figure);
  const imageTypes=new Set(['image/png','image/jpeg','image/gif','image/webp']),isImage=imageTypes.has(item.mime);
  const status=document.createElement('p');status.className='attachment-status';status.textContent=isImage?'正在读取图片…':'附件 · '+(item.name||'文件');
  const caption=document.createElement('figcaption');caption.textContent=(item.direction==='input'?'你发送的附件 · ':'')+(item.caption||item.name||'附件');
  const name=document.createElement('small');name.textContent=(item.name||'附件')+' · '+Math.ceil((item.size||0)/1024)+' KB';
  const download=document.createElement('button');download.className='attachment-download';download.textContent=isImage?'下载原图':'下载文件';
  let image=null;
  if(isImage){const frame=document.createElement('div');frame.className='attachment-frame';
    if(item.width>0&&item.height>0)frame.style.aspectRatio=String(item.width/item.height);
    image=document.createElement('img');image.alt=item.caption||item.name||'Capri 图片';image.decoding='async';frame.append(image,status);figure.append(frame);
  }else figure.append(status);
  figure.append(caption,name,download);
  if(/\.(md|markdown)$/i.test(item.name||'')||item.mime==='text/markdown'){
    const view=document.createElement('button');view.type='button';view.className='attachment-view';view.textContent='查看';view.onclick=()=>window.CapriDocuments?window.CapriDocuments.open(entry,item):failure('文档预览组件尚未就绪，请刷新页面');figure.append(view);
  }
  async function load(){
    if(started||controller.signal.aborted)return;started=true;
    try{
      const response=await fetch('/api/attachment',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},
        body:JSON.stringify({args:{chat_id:sourceChat,epoch:sourceEpoch,entry:entry.index,context_id:sourceContext,entry_id:entry.id}}),signal:controller.signal});
      if(!response.ok){const error=await response.json();throw new Error(error.error||'附件读取失败');}
      const blob=await response.blob();if(controller.signal.aborted)return;
      if(blob.size!==item.size||blob.size>(isImage?20000000:536870912))throw new Error('附件不完整，请重试');
      objectUrl=URL.createObjectURL(blob);
      if(image){image.onload=()=>{status.hidden=true;};image.onerror=()=>{status.textContent='图片无法解码，请下载原图查看';};image.src=objectUrl;}
      else{status.textContent='附件已就绪';}
    }catch(error){if(!controller.signal.aborted){started=false;status.hidden=false;status.textContent=error.message;}}
  }
  download.onclick=async()=>{if(!objectUrl)await load();if(objectUrl&&!controller.signal.aborted){const link=document.createElement('a');link.href=objectUrl;link.download=item.name||'attachment';link.click();}};
  record.cancel=()=>{controller.abort();observer?.disconnect();if(objectUrl)URL.revokeObjectURL(objectUrl);};
  if(isImage){observer=new IntersectionObserver(items=>{if(items.some(x=>x.isIntersecting)){observer.disconnect();void load();}},{root:$('messages'),rootMargin:'300px'});observer.observe(figure);}
}
function resetMessages() { historyLoad++;historyTarget=null;historyEditor=null;generation++;for(const record of nodes.values()){record.finish?.();record.cancel?.();}entries.clear();nodes.clear();$('entries').replaceChildren();olderCursor=0; }
function requestRefresh(){clearTimeout(pollTimer);if(polling){refreshAgain=true;return;}void refresh();}
function switchChat(id){
  window.CapriDocuments?.beforeSwitch();
  closeSkillPicker();
  if(state){views.set(chat,{state:{...state,entries:[...entries.values()],before:olderCursor},top:$('messages').scrollTop,pinned:viewport.pinned});if(views.size>5)views.delete(views.keys().next().value);}
  chat=id;sessionStorage.setItem('capri-chat',chat);state=null;resetMessages();
  const cached=views.get(chat);if(cached){render(cached.state,true);$('messages').scrollTop=cached.top;viewport.lastTop=cached.top;viewport.pinned=cached.pinned;$('latest').hidden=cached.pinned;}
  else{$('title').textContent='加载会话…';$('connection').textContent='正在同步手机…';controls();}
  void api('web_select',{chat_id:id,epoch}).catch(error=>failure(error.message));
  requestRefresh();
}
function renderMessages(data, initial, older=false) {
  const reading=viewport.capture();
  for(const entry of data.entries) entries.set(entry.index,entry);
  const ordered=[...entries.values()].sort((a,b)=>a.index-b.index), groups=groupChatEntries(ordered);
  const live=new Set();
  let positionIndex=0;
  for(const group of groups) {
    const key=group.key;live.add(key);const signature=JSON.stringify(group.items);let record=nodes.get(key);
    if(!record) { const node=document.createElement(group.tool?'details':'article');record={node,signature:'',version:0};nodes.set(key,record); }
    const node=record.node;
    if(record.signature!==signature){record.cancel?.();record.cancel=null;record.signature=signature;record.version++;const entry=group.items[0];
      if(group.tool){
        const open=node.open;record.toolOpen=record.toolOpen||new Set();node.className='tool-process';node.replaceChildren();
        const summary=document.createElement('summary'),info=group.items[0],count=info.process_count??group.items.filter(e=>e.role==='tool').length;
        summary.textContent=info.process_running?(info.process_label||'工具执行中'):`工具记录 · ${count} 条`;
        node.append(summary);const content=document.createElement('div');content.className='tool-process-content';node.append(content);
        for(const item of group.items){
          if(item.role!=='tool'){const note=document.createElement('div');note.className='tool-preamble';markdown(note,item.text);content.append(note);continue;}
          const data=toolOutputSummary(item),detail=document.createElement('details'),key=item.id||item.index;
          detail.className='tool-entry'+(data.failed?' failed':'');const label=document.createElement('summary');label.textContent=data.label;detail.append(label);
          detail.open=record.toolOpen.has(key);
          const show=()=>{if(!detail.open)return;record.toolOpen.add(key);if(detail.dataset.loaded)return;detail.dataset.loaded='true';const pre=document.createElement('pre');pre.textContent=data.display+(item.truncated?'\n[内容较长，此处节选]':'');const copy=document.createElement('button');copy.type='button';copy.className='tool-copy';copy.textContent='复制输出';copy.onclick=()=>navigator.clipboard.writeText(data.body).catch(()=>failure('复制失败，请手动选择输出'));detail.append(pre,copy);};
          detail.addEventListener('toggle',()=>{if(!detail.open)record.toolOpen.delete(key);show();});show();content.append(detail);
        }
        node.open=open;node.ontoggle=()=>{if(node.open)viewport.pause();};
      }
      else {node.className=entry.role;node.replaceChildren();if(entry.role==='assistant'){const label=document.createElement('label');label.textContent='CAPRI';node.append(label);}const body=document.createElement('div');node.append(body);
        if(entry.role==='attachment'){attachmentView(body,entry,record);}else if(!initial&&!older&&entry.role==='assistant'&&entry.final_reply&&Date.now()-entry.timestamp<30000){revealFinal(body,entry.text,record);}else if(entry.role==='assistant')markdown(body,entry.text);else body.textContent=entry.text;
        if(entry.role==='user'&&entry.id){body.className='user-bubble';const footer=document.createElement('div');footer.className='user-footer';if(entry.timestamp){const time=document.createElement('span');time.textContent=new Date(entry.timestamp).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});footer.append(time);}footer.append(historyButton('复制消息','copy',()=>copyHistorical(entry)),historyButton('重试消息','retry',()=>retryHistorical(entry)),historyButton('编辑消息','edit',()=>openHistorical(entry)));node.append(footer);}

        if(entry.truncated){const hint=document.createElement('small');hint.textContent='内容较长，此处节选；完整内容可在手机查看。';node.append(hint);}
      }
    }
    const position=positionIndex++;
    if($('entries').children[position]!==node)$('entries').insertBefore(node,$('entries').children[position]||null);
  }
  for(const [key,record]of nodes)if(!live.has(key)){record.cancel?.();record.node.remove();nodes.delete(key);}
  if(older)olderCursor=data.before;
  else if(initial)olderCursor=data.before;
  $('older').hidden=olderCursor===0;
  if(older)viewport.restore({...reading,pinned:false});else if(initial)viewport.bottom();else viewport.restore(reading);
}
function render(data,initial=false){
  if(chat!==data.chat_id||epoch!==data.epoch)closeSkillPicker();
  window.CapriDocuments?.sync(data);
  state=data;epoch=data.epoch;chat=data.chat_id;sessionStorage.setItem('capri-chat',chat);
  const task=data.run_state;const budget=task?.budget;$('task-state').textContent=(task?.label||'')+(budget?` · 请求 ${budget.used}/${budget.maximum}`:'');
  const question=$('task-question'),qs=JSON.stringify(task?.question||null);
  if(question.dataset.question!==qs){question.dataset.question=qs;question.replaceChildren();for(const option of task?.question?.options||[]){const b=document.createElement('button');b.type='button';b.textContent=option;b.onclick=()=>{$('input').value=option;$('input').focus();};question.append(b);}}
  $('title').textContent=data.title;$('connection').textContent='已连接手机 · 会话同步';
  const signature=JSON.stringify(data.sessions)+chat;
  const available=new Set(data.sessions.map(s=>s.chat_id));for(const id of views.keys())if(!available.has(id))views.delete(id);
  if(signature!==navSignature){
    navSignature=signature;$('sessions').replaceChildren();$('pinned-sessions').replaceChildren();
    for(const s of data.sessions){
      const row=document.createElement('div');row.className='session-row'+(s.kind==='PERSONAL'?' personal-session':'');row.dataset.chatId=s.chat_id;
      const b=document.createElement('button');b.textContent=s.deleting?'正在删除…':(s.busy?'● ':'')+s.title;
      b.className=(s.chat_id===chat?'selected ':'')+(s.busy?'busy':'');b.disabled=!!s.deleting;b.onclick=()=>switchChat(s.chat_id);row.append(b);
      if(s.kind==='PERSONAL'){b.setAttribute('aria-label',s.title+' · 默认会话');const badge=document.createElement('span');badge.className='personal-badge';badge.textContent='默认';row.append(badge);}
      if(s.kind==='TASK'){
        const remove=document.createElement('button');remove.className='session-delete';remove.textContent='×';
        remove.title=s.busy?'请先停止当前任务':'删除对话';remove.setAttribute('aria-label','删除会话 '+s.title);remove.disabled=!!s.busy||!!s.deleting||dispatching;
        remove.onclick=()=>{deleteTarget=s;$('delete-description').textContent=`将删除「${s.title}」的消息、工作区和会话环境，并关闭相关终端。全局配置保留。`;$('delete-dialog').showModal();};row.append(remove);
      }
      $(s.kind==='PERSONAL'?'pinned-sessions':'sessions').append(row);
    }
  }
  if(data.deletion_error)failure(data.deletion_error);
  window.CapriModelPicker?.render(data);window.CapriInputs?.render(data);renderMessages(data,initial);const progress=$('progress'),label=data.busy?(data.active_tool||data.notice||'思考中'):'';if(progress.dataset.label!==label){progress.dataset.label=label;progress.replaceChildren();if(data.busy){const spin=document.createElement('span');spin.className='spinner';progress.append(spin,document.createTextNode(label));}}
  if(viewport.pinned)viewport.bottom();
  controls();if($('catalog-dialog').open&&catalogScope!==chat+'|'+epoch){catalogId='';loadCatalog();}
}
async function refresh(){
  if(polling){refreshAgain=true;return;}clearTimeout(pollTimer);polling=true;
  const requestedChat=chat,gen=generation;
  try{
    const args={chat_id:chat};if(state){args.epoch=epoch;args.revision=state.revision;}
    const data=await api('web_state',args);if(gen!==generation||requestedChat!==chat){refreshAgain=true;return;}
    connected=true;failure('');$('connection').textContent='已连接手机 · 会话同步';
    if(data.unchanged){if(state?.deletion_error)failure(state.deletion_error);controls();return;}
    if(state&&(epoch!==data.epoch||data.memory_revision!==state.memory_revision||data.context_id!==state.context_id||data.history_revision!==state.history_revision))views.clear();
    const initial=!state||data.chat_id!==chat||epoch!==data.epoch||data.memory_revision!==state.memory_revision||data.total<state.total||data.context_id!==state.context_id||data.history_revision!==state.history_revision;
    if(initial)resetMessages();render(data,initial);
  }catch(error){connected=false;$('connection').textContent=error.code==='kernel_stopped'?'内核已关闭 · 打开 Capri 后恢复':error.code==='device_offline'?'等待手机连接 · 连接后自动同步':'连接中断 · 正在自动恢复';failure(error.message);controls();}
  finally{polling=false;const delay=refreshAgain?0:!connected?2000:document.hidden?2000:state?.busy?250:750;refreshAgain=false;pollTimer=setTimeout(refresh,delay);}
}
$('refresh').onclick=()=>{if(!polling){chat='';state=null;resetMessages();requestRefresh();}};
$('older').onclick=async()=>{const gen=generation;try{const data=await api('web_state',{chat_id:chat,before:olderCursor,context_id:state?.context_id});if(gen===generation)renderMessages(data,false,true);}catch(error){failure(error.message);}};
$('ack').onclick=()=>{pendingSet(null);failure('');requestRefresh();};
$('composer').onsubmit=async event=>{event.preventDefault();const text=$('input').value.trim();if((!text&&!state?.input_drafts?.length)||$('send').disabled)return;
  closeSkillPicker();viewport.bottom();const request={request_id:crypto.randomUUID(),chat_id:chat,epoch,from_context:state.context_id,history_revision:state.history_revision};pendingSet(request);dispatching=true;controls();try{await api('send',{...request,text:text||'请查看附件。',input_ids:(state.input_drafts||[]).map(file=>file.id)});$('input').value='';pendingSet(null);}catch(error){failure(error.message);}finally{dispatching=false;controls();requestRefresh();}};
$('input').onkeydown=event=>{if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing){event.preventDefault();$('composer').requestSubmit();}};
$('create').onclick=async()=>{const request={request_id:crypto.randomUUID(),epoch};pendingSet(request);dispatching=true;controls();try{const result=await api('web_create',request);chat=result.chat_id;state=null;resetMessages();pendingSet(null);}catch(error){failure(error.message);}finally{dispatching=false;controls();requestRefresh();}};
$('stop').onclick=async()=>{dispatching=true;controls();try{await api('web_stop',{chat_id:chat,epoch});}catch(error){failure(error.message);}finally{dispatching=false;controls();requestRefresh();}};
document.addEventListener('visibilitychange',()=>{if(!document.hidden)requestRefresh();});
window.addEventListener('online',requestRefresh);window.addEventListener('pageshow',requestRefresh);
controls();requestRefresh();

$('delete-cancel').onclick=()=>{$('delete-dialog').close();deleteTarget=null;};
$('delete-confirm').onclick=async()=>{
  const target=deleteTarget;if(!target||dispatching)return;
  $('delete-dialog').close();deleteTarget=null;dispatching=true;
  const request={chat_id:target.chat_id,epoch,request_id:crypto.randomUUID()};pendingSet(request);controls();
  try{await api('web_delete',request);views.delete(target.chat_id);pendingSet(null);}
  catch(error){failure(error.message);}
  finally{dispatching=false;controls();requestRefresh();}
};

// Read-only catalog: fetch on demand, never activate services or load/execute Skill scripts.
let catalogCategory='tools',catalogId='',catalogNext=null,catalogSequence=0,catalogSearchTimer=null,catalogScope='';
function catalogText(tag,text){const node=document.createElement(tag);node.textContent=String(text??'');return node;}
function catalogMetadata(item){
  if(catalogCategory==='tools')return item.source;
  if(catalogCategory==='mcp')return `${item.type} · ${item.enabled?'已启用':'已停用'} · ${item.active?'当前会话已选择':'当前会话未选择'} · ${item.state}`;
  return `${item.skill_category==='weapon'?'Weapon':'General'} · ${item.source||'user'} · ${item.autoinvoke?'自动发现':'仅手动'} · ${item.permission||'allow'} · ${item.enabled?'已启用':'已停用'}`;
}
async function loadCatalog(append=false){
  if(!$('catalog-dialog').open)return;
  $('catalog-skill-type').hidden=catalogCategory!=='skills';
  const seq=++catalogSequence,requestedChat=chat,requestedEpoch=epoch;catalogScope=chat+'|'+epoch;
  const args={chat_id:chat,epoch,category:catalogCategory,query:$('catalog-query').value.trim(),offset:append?(catalogNext||0):0};
  if(catalogCategory==='skills')args.skill_type=$('catalog-skill-type').value;
  if(catalogId)args.id=catalogId;
  $('catalog-status').textContent='正在读取手机…';$('catalog-more').hidden=true;
  try{
    const data=await api('web_catalog',args);
    if(seq!==catalogSequence||!$('catalog-dialog').open||chat!==requestedChat||epoch!==requestedEpoch)return;
    const content=$('catalog-content');if(!append)content.replaceChildren();
    $('catalog-back').hidden=!catalogId;
    if(data.item){
      const item=data.item;
      if(!append){content.append(catalogText('h4',item.name),catalogText('small',`${item.id} · ${catalogMetadata(item)}`),catalogText('p',item.description));}
      if(catalogCategory==='tools'){const pre=document.createElement('pre');pre.textContent=JSON.stringify(item.parameters,null,2);content.append(pre);}
      if(catalogCategory==='skills'){let pre=content.querySelector('pre');if(!pre){pre=document.createElement('pre');pre.className='skill-source';content.append(pre);}pre.append(document.createTextNode(item.content));}
      catalogNext=data.item.next_offset??null;$('catalog-status').textContent=catalogCategory==='skills'?'SKILL.md · 仅查看，不执行脚本':'详情 · 仅查看';
    }else{
      for(const item of data.items){const row=document.createElement('article');row.className='catalog-item';row.append(catalogText('strong',item.name),catalogText('small',`${item.id} · ${catalogMetadata(item)}`),catalogText('p',item.description));
        const detail=catalogText('button',catalogCategory==='skills'?'查看 Skill':'详情');detail.onclick=()=>{catalogId=item.id;loadCatalog();};row.append(detail);content.append(row);}
      catalogNext=data.next_offset??null;$('catalog-status').textContent=data.total?`${data.total} 项`:'没有匹配内容';
    }
    $('catalog-more').hidden=catalogNext===null;
  }catch(error){if(seq===catalogSequence){$('catalog-status').textContent=error.message;$('catalog-back').hidden=!catalogId;}}
}
$('catalog-open').onclick=()=>{catalogId='';$('catalog-dialog').showModal();loadCatalog();};
$('catalog-close').onclick=()=>$('catalog-dialog').close();
$('catalog-dialog').addEventListener('close',()=>{catalogSequence++;clearTimeout(catalogSearchTimer);});
$('catalog-tabs').querySelectorAll('button').forEach(button=>button.onclick=()=>{
  catalogCategory=button.dataset.category;catalogId='';catalogNext=null;$('catalog-query').value='';
  $('catalog-tabs').querySelectorAll('button').forEach(tab=>tab.setAttribute('aria-selected',String(tab===button)));loadCatalog();
});
$('catalog-query').oninput=()=>{catalogId='';clearTimeout(catalogSearchTimer);catalogSearchTimer=setTimeout(()=>loadCatalog(),250);};
$('catalog-refresh').onclick=()=>loadCatalog();
$('catalog-back').onclick=()=>{catalogId='';loadCatalog();};
$('catalog-more').onclick=()=>loadCatalog(true);

$('catalog-skill-type').onchange=()=>{catalogId='';loadCatalog();};


// A single visible timeline: edit inside the message bubble, with Cancel / Send.
function historyLocked(){return !connected||dispatching||!!pending||!state||state.busy||state.deleting||state.preparing;}
function historyButton(label,kind,action){const b=document.createElement('button');b.type='button';b.className='user-'+kind;b.title=label;b.setAttribute('aria-label',label);const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('fill','none');svg.setAttribute('stroke','currentColor');svg.setAttribute('stroke-width','1.6');svg.setAttribute('aria-hidden','true');const paths={copy:['M9 9h11v12H9z','M15 9V3H3v12h6'],retry:['M20 7v5h-5','M20 12a8 8 0 1 0-2 6'],edit:['M15 4l5 5','M4 20l5-1L21 7a3.5 3.5 0 0 0-5-5L4 14z']}[kind];for(const d of paths){const p=document.createElementNS(svg.namespaceURI,'path');p.setAttribute('d',d);svg.append(p);}b.append(svg);b.onclick=action;return b;}
function historyRequest(entry){return {chat_id:chat,epoch,from_context:state.context_id,history_revision:state.history_revision,entry_id:entry.id};}
async function copyHistorical(entry){try{const data=await api('web_message',historyRequest(entry));if(data.truncated)throw Error('消息过长，请在手机复制');await navigator.clipboard.writeText(data.text);}catch(e){failure(e.message);}}
function cancelHistorical(){historyLoad++;const target=historyTarget;historyTarget=null;historyEditor=null;if(target){const record=nodes.get(chatEntryKey({id:target.entry_id,index:target.index}));if(record)record.signature='';}if(state)renderMessages({...state,entries:[...entries.values()]},false);controls();}
async function openHistorical(entry){
  if(historyLocked())return;const target=historyRequest(entry),load=++historyLoad;
  try{const data=await api('web_message',target);if(load!==historyLoad)return;if(historyLocked())throw Error('当前状态不允许编辑历史消息，请稍后重试');if(chat!==target.chat_id||epoch!==target.epoch||state.context_id!==target.from_context||state.history_revision!==target.history_revision)throw Error('历史记录已变化，请重新选择消息');if(data.truncated)throw Error('消息过长，请在手机编辑');
    if(historyTarget)cancelHistorical();const record=nodes.get(chatEntryKey(entry));if(!record)throw Error('消息已不在当前页面，请刷新后重新选择');
    historyTarget={...target,index:entry.index};const form=document.createElement('form');form.className='message-editor';const input=document.createElement('textarea');input.setAttribute('aria-label','编辑历史消息');input.value=data.text;input.maxLength=128000;input.rows=2;const error=document.createElement('small');error.className='history-error';const actions=document.createElement('div');actions.className='history-actions';const cancel=document.createElement('button');cancel.type='button';cancel.textContent='取消';cancel.onclick=cancelHistorical;const send=document.createElement('button');send.textContent='发送';send.className='history-send';actions.append(cancel,send);form.append(input,error,actions);form.onsubmit=e=>{e.preventDefault();submitHistorical('edit');};record.node.replaceChildren(form);record.node.classList.add('editing');historyEditor={input,error,send};controls();input.focus();input.setSelectionRange(input.value.length,input.value.length);const resize=()=>{input.style.height='auto';input.style.height=Math.min(320,Math.max(70,input.scrollHeight))+'px';};input.oninput=resize;resize();
  }catch(e){if(load===historyLoad)failure(e.message);}
}
async function retryHistorical(entry){if(historyLocked())return;if(historyTarget)cancelHistorical();historyTarget={...historyRequest(entry),index:entry.index};await submitHistorical('retry');}
async function submitHistorical(mode){
  const target=historyTarget;if(!target||historyLocked())return;const text=historyEditor?.input.value.trim();if(mode==='edit'&&(!text||new TextEncoder().encode(text).length>32000)){historyEditor.error.textContent='编辑内容须为1–32000 UTF-8字节';return;}
  const {index,...scope}=target;const request={...scope,request_id:crypto.randomUUID(),mode};pendingSet({...request,method:'web_revise'});dispatching=true;controls();
  try{await api('web_revise',{...request,...(mode==='edit'?{text}:{})});historyTarget=null;historyEditor=null;pendingSet(null);views.delete(target.chat_id);}
  catch(e){if(historyEditor)historyEditor.error.textContent=e.message;failure(e.message);}
  finally{dispatching=false;controls();requestRefresh();}
}

let skillRequest=0,skillItems=[],skillOwner=null,skillSelection=null;
function closeSkillPicker(focus=false){
  skillRequest++;$('skill-picker').hidden=true;$('skill-mention').setAttribute('aria-expanded','false');
  skillOwner=null;if(focus)$('skill-mention').focus();
}
function renderSkillOptions(){
  const list=$('skill-options'),query=$('skill-query').value.trim().toLowerCase();list.replaceChildren();
  const matches=skillItems.filter(s=>[s.name,s.id,s.description].join(' ').toLowerCase().includes(query));
  for(const skill of matches){
    const option=document.createElement('button');option.type='button';option.className='skill-option';
    const title=document.createElement('strong');title.textContent=skill.name;
    const id=document.createElement('small');id.textContent='@'+skill.id;
    const description=document.createElement('span');description.textContent=skill.description||'';
    option.append(title,id,description);option.onclick=()=>{
      if(!skillOwner||skillOwner.chat!==chat||skillOwner.epoch!==epoch)return closeSkillPicker();
      const input=$('input'),position=skillSelection?.value===input.value?skillSelection:null;
      const start=position?.start??input.value.length,end=position?.end??start;
      const prefix=start&&!/\s/.test(input.value[start-1])?' ':'';
      input.setRangeText(prefix+'@'+skill.id+' ',start,end,'end');closeSkillPicker();input.focus();
    };list.append(option);
  }
  $('skill-status').textContent=matches.length?'':'没有匹配的 Skill';
}
$('skill-mention').onclick=async()=>{
  if(!$('skill-picker').hidden)return closeSkillPicker(true);
  const input=$('input');skillSelection={value:input.value,start:input.selectionStart,end:input.selectionEnd};
  const owner={chat,epoch},request=++skillRequest;skillOwner=owner;skillItems=[];
  $('skill-query').value='';$('skill-options').replaceChildren();$('skill-status').textContent='正在读取 Skills…';
  $('skill-picker').hidden=false;$('skill-mention').setAttribute('aria-expanded','true');$('skill-query').focus();
  try{
    const data=await api('web_runtime',{epoch:owner.epoch,chat_id:owner.chat,action:'snapshot',payload:{section:'extensions'}});
    if(request!==skillRequest||chat!==owner.chat||epoch!==owner.epoch)return;
    skillItems=(data.skills||[]).filter(s=>s.enabled&&s.permission!=='deny');renderSkillOptions();
  }catch(error){if(request===skillRequest)$('skill-status').textContent=error.message;}
};
$('skill-query').oninput=renderSkillOptions;
$('skill-picker').onkeydown=event=>{
  if(event.key==='Escape'){event.preventDefault();closeSkillPicker(true);return;}
  const buttons=Array.from($('skill-options').children),index=buttons.indexOf(document.activeElement);
  if(event.key==='ArrowDown'||event.key==='ArrowUp'){
    event.preventDefault();const next=index+(event.key==='ArrowDown'?1:-1);
    if(next<0)$('skill-query').focus();else buttons[Math.min(next,buttons.length-1)]?.focus();
  }else if(event.key==='Enter'&&document.activeElement===$('skill-query')){event.preventDefault();buttons[0]?.click();}
};
document.addEventListener('pointerdown',event=>{if(!event.target.closest('.skill-control'))closeSkillPicker();});
document.addEventListener('focusin',event=>{if(!event.target.closest('.skill-control')&&!$('skill-picker').hidden)closeSkillPicker();});

// One local reading preference for all conversation text, including code and document tabs.
(() => {
  const picker=$('chat-font-size'),main=document.querySelector('main');
  const allowed=['13','15','17','19'];
  const apply=value=>{picker.value=value;main.style.setProperty('--chat-font-scale',String(Number(value)/15));};
  let saved='15';try{saved=localStorage.getItem('capri-chat-font-size')||'15';}catch{}
  apply(allowed.includes(saved)?saved:'15');
  picker.onchange=()=>{
    const value=allowed.includes(picker.value)?picker.value:'15';
    const reading=$('messages').hidden?null:viewport.capture();apply(value);
    try{localStorage.setItem('capri-chat-font-size',value);}catch{}
    if(reading)requestAnimationFrame(()=>viewport.restore(reading));
  };
})();
