/* User-authored persistent rules. Writes are explicit UI actions and never retried automatically. */
(()=>{
 const anchor=document.getElementById('run-open');if(!anchor)return;
 const button=document.createElement('button');button.type='button';button.textContent='规则';anchor.after(button);
 const dialog=document.createElement('dialog'),heading=document.createElement('h3'),note=document.createElement('p'),status=document.createElement('p'),tabs=document.createElement('select'),list=document.createElement('div'),title=document.createElement('input'),body=document.createElement('textarea'),toggle=document.createElement('input'),save=document.createElement('button'),remove=document.createElement('button'),fresh=document.createElement('button'),close=document.createElement('button');
 heading.textContent='持续规则';note.textContent='规则每轮自动提供，修改从下一轮生效。当前请求优先于对话规则，对话规则优先于全局规则；权限与核心人格保持原有约束。';
 for(const [value,text]of[['chat','当前对话'],['global','所有对话']]){const o=document.createElement('option');o.value=value;o.textContent=text;tabs.append(o);}
 title.placeholder='标题';title.maxLength=80;title.setAttribute('aria-label','规则标题');body.placeholder='规则内容';body.maxLength=1000;body.rows=6;body.setAttribute('aria-label','规则内容');toggle.type='checkbox';toggle.checked=true;toggle.setAttribute('aria-label','启用规则');tabs.setAttribute('aria-label','规则范围');
 save.textContent='保存';remove.textContent='删除';fresh.textContent='新建／刷新';close.textContent='关闭';dialog.append(heading,note,tabs,list,title,body,toggle,save,remove,fresh,status,close);document.body.append(dialog);
 let owner=null,data=null,id=null,expected=0,busy=false;
 function locked(value){busy=value;for(const node of[tabs,title,body,toggle,save,remove,fresh])node.disabled=value;}
 function current(){if(!state||chat!==owner?.chat_id||epoch!==owner?.epoch||state.context_id!==owner?.context_id)throw Error('会话已变化，请重新打开规则');}
 async function rpc(payload){current();return api('web_rules',{...owner,payload,...(payload.action==='list'?{}:{request_id:crypto.randomUUID()})});}
 function clear(){id=null;title.value='';body.value='';toggle.checked=true;expected=data?.revision||0;}
 async function load(){data=await rpc({action:'list',scope:tabs.value});current();list.replaceChildren();for(const rule of data.rules){const item=document.createElement('button');item.type='button';item.textContent=`${rule.enabled?'✓':'停用 ·'} ${rule.title}`;item.onclick=()=>{id=rule.id;title.value=rule.title;body.value=rule.content;toggle.checked=rule.enabled;expected=data.revision;};list.append(item);}}
 async function perform(fn){if(busy)return;locked(true);status.textContent='';try{await fn();}catch(e){status.textContent=e.message+'；操作结果未确认时请刷新核对，避免覆盖其他修改。';}finally{locked(false);}}
 button.onclick=()=>{if(!state||!connected)return;owner={chat_id:chat,epoch,context_id:state.context_id};dialog.showModal();perform(async()=>{await load();clear();});};
 tabs.onchange=()=>perform(async()=>{await load();clear();});fresh.onclick=()=>perform(async()=>{await load();clear();});close.onclick=()=>dialog.close();
 save.onclick=()=>perform(async()=>{if(!title.value.trim()||!body.value.trim())throw Error('请填写标题和规则内容');await rpc({action:'save',scope:tabs.value,id:id||crypto.randomUUID(),title:title.value,content:body.value,enabled:toggle.checked,expected_revision:expected});await load();clear();status.textContent='已保存，从下一轮生效。';});
 remove.onclick=()=>perform(async()=>{if(!id)throw Error('请先选择规则');await rpc({action:'delete',scope:tabs.value,id,expected_revision:expected});await load();clear();status.textContent='已删除，从下一轮生效。';});
})();
