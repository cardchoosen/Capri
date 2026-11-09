'use strict';
// The phone owns selection. Reads never discover provider models or expose credentials.
(() => {
  const dialog=$('model-dialog');let owner=null,accounts=[],generation=0,timer=null,working=false,catalogLoading=false;
  let pending=JSON.parse(sessionStorage.getItem('capri-model-pending')||'null');
  const same=o=>!!o&&o.chat===chat&&o.epoch===epoch;
  const rpc=(o,action,payload={},id)=>api('web_runtime',{chat_id:o.chat,epoch:o.epoch,action,payload,...(id?{request_id:id}:{})});
  const node=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
  function contextLabel(model){
    const n=model?.context;if(!n||n<=0)return '上下文未知';
    const number=x=>Number.isInteger(x)?String(x):x.toFixed(1);
    return (model.source==='models_registry'?'≈':'')+(n>=1000000?number(n/(n%1048576===0?1048576:1000000))+'M':n>=1000?number(n/(n%1024===0?1024:1000))+'K':String(n));
  }
  function save(value){pending=value;if(value)sessionStorage.setItem('capri-model-pending',JSON.stringify(value));else sessionStorage.removeItem('capri-model-pending');controls();}
  function close(){catalogLoading=false;generation++;clearTimeout(timer);owner=null;if(dialog.open)dialog.close();}
  function updateControls(){
    $('model-select').disabled=!connected||!state||state.preparing||state.deleting||dispatching||working||!!pending;
    $('reasoning-select').disabled=$('model-select').disabled;
    $('model-reconcile').hidden=!pending;$('model-reconcile').disabled=!connected||working;
    $('model-query').disabled=working||catalogLoading;for(const b of $('model-options').querySelectorAll('button'))b.disabled=working||!!pending;
  }
  function render(data){
    if(owner&&!same(owner))close();
    const m=data.selected_model;$('model-select').textContent=m?`${m.name||m.id} · ${contextLabel(m)} ⌄`:'选择模型 ⌄';
    $('model-select').title=m?`${m.account_name} · ${m.id}`:'选择当前会话模型';$('model-next-turn').hidden=!data.busy;
    const effortSelect=$('reasoning-select'), levels=m?.reasoning_options||[];
    $('reasoning-control').hidden=!levels.length;
    if(effortSelect.dataset.levels!==levels.join(',')){
      const labels={default:'默认',none:'关闭',low:'Low',high:'High',max:'Max'};
      effortSelect.replaceChildren(...levels.map(level=>{const option=node('option',labels[level]||level);option.value=level;return option;}));
      effortSelect.dataset.levels=levels.join(',');
    }
    effortSelect.value=m?.reasoning_effort||'default';
    effortSelect.title=data.busy?'从下一轮生效':'更改当前会话推理档位';
    for(const b of $('model-options').querySelectorAll('[data-model]')){
      const selected=b.dataset.account===m?.account&&b.dataset.model===m?.id;b.setAttribute('aria-pressed',String(selected));b.querySelector('.model-check').textContent=selected?'✓':'';
    }
  }
  async function page(a,container,offset,query,gen,o){
    const d=await rpc(o,'snapshot',{section:'model_options',id:a.id,offset,query});
    if(gen!==generation||!same(o)||!dialog.open)return;
    for(const m of d.models){const b=node('button',undefined,'model-option');b.type='button';b.dataset.account=a.id;b.dataset.model=m.id;
      const info=node('span',undefined,'model-name');info.append(node('strong',m.name||m.id));if(m.name!==m.id)info.append(node('small',m.id));
      b.append(info,node('small',contextLabel(m)),node('span','','model-check'));b.onclick=()=>select(o,a.id,m.id);container.append(b);}
    if(d.has_more){const more=node('button','更多模型');more.type='button';more.onclick=async()=>{more.disabled=true;try{await page(a,container,d.next,query,gen,o);more.remove();}catch(e){more.disabled=false;$('model-status').textContent=e.message;}};container.append(more);}
    if(!container.children.length)container.parentElement.remove();if(state)render(state);updateControls();
  }
  async function search(){
    const gen=++generation,o=owner,query=$('model-query').value.trim();if(!same(o))return;
    $('model-options').replaceChildren();$('model-status').textContent='正在读取手机模型…';
    // Bound concurrent list reads; pagination keeps large provider catalogs off the chat poll.
    const jobs=accounts.map(a=>{const group=node('section');group.append(node('h4',a.name));const list=node('div');group.append(list);$('model-options').append(group);return {a,list};});
    let cursor=0;
    try{await Promise.all([0,1].map(async()=>{while(cursor<jobs.length){if(gen!==generation||!same(o))return;const {a,list}=jobs[cursor++];await page(a,list,0,query,gen,o);}}));
      if(gen===generation)$('model-status').textContent=$('model-options').children.length?'':accounts.length?'没有匹配的模型。':'添加 API 并获取模型列表，也可手动添加模型 ID。';
    }catch(e){if(gen===generation)$('model-status').textContent=e.message;}
  }
  async function wait(o,id){
    const deadline=Date.now()+20000;let op;
    do{op=await rpc(o,'operation',{id});if(op.state!=='running')break;if(Date.now()>deadline)throw Error('选择尚未确认，请核对后再发送。');await new Promise(r=>setTimeout(r,200));}while(true);
    if(op.state!=='completed')throw Error(op.message||'模型选择未完成');return op;
  }
  async function select(o,account,model,effort){
    if(!same(o)||working||pending||!connected)return;
    working=true;const p={...o,id:crypto.randomUUID(),account,model,effort};save(p);$('model-status').textContent='正在同步手机…';
    const reasoning=effort!==undefined;
    try{
      await rpc(o,reasoning?'model_reasoning':'model_select',{account,model,effort},p.id);
      await wait(o,p.id);
      if(reasoning&&same(o)&&state?.selected_model?.account===account&&state.selected_model.id===model){
        state.selected_model.reasoning_effort=effort;
        render(state);
      }
      save(null);if(!reasoning)close();requestRefresh();
    }
    catch(e){if(same(o)&&state)render(state);failure(e.message+' 请先核对模型或推理档位再发送。');$('model-status').textContent=e.message;}
    finally{working=false;controls();}
  }
  $('reasoning-select').onchange=()=>{
    const m=state?.selected_model;
    if(m)void select({chat,epoch},m.account,m.id,$('reasoning-select').value);
  };
  $('model-select').onclick=async()=>{
    if($('model-select').disabled)return;close();owner={chat,epoch};const o=owner,gen=++generation;catalogLoading=true;accounts=[];$('model-query').value='';$('model-options').replaceChildren();$('model-status').textContent='正在读取手机模型…';dialog.showModal();controls();$('model-close').focus();
    try{const d=await rpc(o,'snapshot',{section:'models'});if(gen!==generation||!same(o)||!dialog.open)return;accounts=d.accounts;catalogLoading=false;controls();$('model-query').focus();await search();}catch(e){if(gen===generation){catalogLoading=false;controls();$('model-status').textContent=e.message;}}
  };
  $('model-query').oninput=()=>{clearTimeout(timer);timer=setTimeout(search,250);};
  $('model-close').onclick=close;dialog.addEventListener('close',()=>{owner=null;generation++;clearTimeout(timer);});
  $('model-manage').onclick=()=>{close();run.section='models';$('run-open').click();};
  $('model-reconcile').onclick=async()=>{
    if(!pending||working)return;working=true;controls();const p=pending;
    try{
      if(p.epoch===epoch){
        let op=null;try{op=await rpc(p,'operation',{id:p.id});}catch{}
        if(op?.state==='running')await wait(p,p.id);
      }
      const data=await api('web_state',{chat_id:p.chat});
      if(data.chat_id!==p.chat)throw Error('原会话已不可用，请在手机核对。');
      if(data.selected_model?.account!==p.account||data.selected_model?.id!==p.model||(p.effort!==undefined&&data.selected_model?.reasoning_effort!==p.effort))failure('当前模型或推理档位与上次选择不同，请核对后重新选择。');
      save(null);requestRefresh();
    }catch(e){failure(e.message+' 已保存待核对记录；请检查手机当前模型。');}finally{working=false;controls();}
  };
  window.CapriModelPicker={render,controls:updateControls,get blocking(){return !!pending&&pending.chat===chat;},contextLabel};
  if(state)render(state);controls();
})();
