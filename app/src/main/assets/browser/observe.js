(function () {
  // A bounded semantic DOM summary, not a full accessibility tree or authorization channel.
  const visible = e => { const r=e.getBoundingClientRect(),s=getComputedStyle(e); return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'&&e.getAttribute('aria-hidden')!=='true'; };
  const clean = s => String(s||'').replace(/\s+/g,' ').trim();
  const token = __CAPRI_SNAPSHOT__;
  const refs = new Map(), elements = [];
  const labelText = e => Array.from(e.labels||[]).map(label=>{const copy=label.cloneNode(true);copy.querySelectorAll('input,textarea,select,button').forEach(n=>n.remove());return clean(copy.textContent);}).join(' ');
  const identity = e => [e.tagName,e.id,e.getAttribute('type'),e.getAttribute('role'),e.getAttribute('name'),e.getAttribute('aria-label'),labelText(e),e.getAttribute('placeholder'),e.getAttribute('href'),e.tagName==='INPUT'||e.tagName==='TEXTAREA'?'':clean(e.textContent).slice(0,200)].join('|');
  let visited=0, truncated=false;
  const iter=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_ELEMENT);
  let e;
  while ((e=iter.nextNode())) {
    if (++visited>6000) { truncated=true; break; }
    if (!e.matches('a[href],button,input,textarea,select,[role],[contenteditable="true"],summary') || !visible(e)) continue;
    if (e.closest('[hidden],[aria-hidden="true"]')) continue;
    const tag=e.tagName.toLowerCase(), type=(e.getAttribute('type')||'').toLowerCase();
    if (type==='hidden') continue;
    const role=e.getAttribute('role')||({a:'link',button:'button',input:'textbox',textarea:'textbox',select:'combobox',summary:'button'}[tag]||tag);
    const label=clean(e.getAttribute('aria-label')||labelText(e)||e.getAttribute('placeholder')||e.innerText||e.getAttribute('name')||'').slice(0,180);
    const ref='e'+(elements.length+1); refs.set(ref,{node:e,identity:identity(e)});
    const item={ref,role,name:label,type,enabled:!e.disabled&&e.getAttribute('aria-disabled')!=='true',readonly:!!e.readOnly};
    if(tag==='select')item.options=Array.from(e.options).slice(0,30).map(o=>({value:o.value,label:clean(o.text).slice(0,100),selected:o.selected,enabled:!o.disabled}));
    if (type==='password') item.redacted=true;
    else if (type==='checkbox'||type==='radio') item.checked=!!e.checked;
    if (tag==='iframe') item.unsupported=true;
    elements.push(item);
    if(elements.length>=120){truncated=true;break;}
  }
  // Text nodes exclude scripts/styles and editable fields, including contenteditable secrets.
  const textIter=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_TEXT);
  const chunks=[];let size=0,n,count=0;
  while ((n=textIter.nextNode())) {
    if(++count>10000){truncated=true;break;}
    const p=n.parentElement;
    if(!p||p.closest('script,style,noscript,textarea,input,[contenteditable], [hidden], [aria-hidden="true"]')||!visible(p))continue;
    const t=clean(n.nodeValue);if(!t)continue;
    const clipped=t.slice(0,Math.max(0,7000-size));if(clipped){chunks.push(clipped);size+=clipped.length+1;}
    if(size>=7000){truncated=true;break;}
  }
  // Nodes never become native capabilities; all data remains untrusted page content.
  window.__capriObservation={token,refs,identity,url:location.href,viewport:[innerWidth,innerHeight,scrollX,scrollY].join(',')};
  const result={snapshot_id:token,title:document.title.slice(0,160),url:location.href,ready_state:document.readyState,
    text:chunks.join('\n'),elements,truncated,scope:'main_frame_dom',iframes:document.querySelectorAll('iframe').length};
  const bytes=()=>new TextEncoder().encode(JSON.stringify(result)).length;
  while(bytes()>16000&&result.elements.length){result.elements.pop();result.truncated=true;}
  while(bytes()>16000&&result.text.length){result.text=result.text.slice(0,Math.floor(result.text.length*.8));result.truncated=true;}
  const kept=new Set(result.elements.map(e=>e.ref)); for(const key of refs.keys())if(!kept.has(key))refs.delete(key);
  return result;
})()
