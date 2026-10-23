(function () {
  const a=__CAPRI_ACTION__, s=window.__capriObservation;
  const fail=(status,message)=>({status,message,action_state:'not_applied'});
  if(!s||s.token!==a.snapshot_id||s.url!==location.href)return fail('stale_reference','快照已失效，请重新观察');
  if(s.viewport!==[innerWidth,innerHeight,scrollX,scrollY].join(','))return fail('stale_reference','视口已变化，请重新观察');
  const entry=s.refs.get(a.ref),e=entry&&entry.node;
  if(!e||!e.isConnected||e.ownerDocument!==document)return fail('stale_reference','目标节点已移除');
  if(s.identity(e)!==entry.identity)return fail('target_changed','目标语义已变化');
  const r=e.getBoundingClientRect(), css=getComputedStyle(e);
  if(!r.width||!r.height||css.display==='none'||css.visibility!=='visible'||e.closest('[hidden],[inert],[aria-hidden="true"]'))return fail('target_hidden','目标不可见');
  if(e.matches(':disabled')||e.getAttribute('aria-disabled')==='true')return fail('target_disabled','目标已禁用');
  const x=Math.max(0,Math.min(innerWidth-1,(Math.max(0,r.left)+Math.min(innerWidth,r.right))/2));
  const y=Math.max(0,Math.min(innerHeight-1,(Math.max(0,r.top)+Math.min(innerHeight,r.bottom))/2));
  if(a.phase==='prepare'){
    e.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});
    s.viewport=[innerWidth,innerHeight,scrollX,scrollY].join(',');
    const b=e.getBoundingClientRect();return {status:'ok',action_state:'not_applied',bounds:[b.x,b.y,b.width,b.height]};
  }
  if(a.bounds&&[r.x,r.y,r.width,r.height].some((v,i)=>Math.abs(v-a.bounds[i])>2))return fail('target_moving','目标位置不稳定，请重新观察');
  const hit=document.elementFromPoint(x,y);
  if(r.bottom<=0||r.top>=innerHeight||r.right<=0||r.left>=innerWidth||!hit||!(hit===e||e.contains(hit)))return fail('target_obscured','目标被遮挡或不在视口');
  const tag=e.tagName,type=(e.type||'').toLowerCase();
  if(a.action==='upload'){
    if(tag!=='INPUT'||type!=='file')return fail('unsupported','目标不是文件输入框');
    if(window.visualViewport&&Math.abs(visualViewport.width-innerWidth)>2)return fail('needs_user','缩放页面的文件选择需用户操作');
    return {status:'ok',x,y,viewport_width:innerWidth};
  }
  if(a.action==='click'){
    if(tag==='INPUT'&&['file','password'].includes(type))return fail('needs_user','此控件需用户操作');
    if(typeof e.click!=='function')return fail('unsupported','目标不支持DOM点击');
    e.click();return {status:'ok',action_state:'dispatched',backend:'dom_click'};
  }
  if(a.action==='fill'){
    if(!['INPUT','TEXTAREA'].includes(tag)||['password','file','checkbox','radio','hidden','button','submit','reset','image','range','color'].includes(type))return fail('unsupported','仅支持普通文本输入，密码/文件/复杂编辑器需用户操作');
    if(e.readOnly)return fail('target_readonly','目标只读');
    const proto=tag==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;
    Object.getOwnPropertyDescriptor(proto,'value').set.call(e,a.text);
    e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
    return {status:'ok',action_state:'applied',value_matches:e.value===a.text,backend:'native_value_setter'};
  }
  if(a.action==='select'){
    if(tag!=='SELECT'||e.multiple)return fail('unsupported','仅支持原生单选下拉框');
    const option=Array.from(e.options).find(o=>o.value===a.value);
    if(!option||option.disabled||option.parentElement.disabled)return fail('invalid_argument','选项不存在或已禁用');
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set.call(e,a.value);
    e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
    return {status:'ok',action_state:'applied',value_matches:e.value===a.value,backend:'native_value_setter'};
  }
  return fail('unsupported','操作未实现');
})()
