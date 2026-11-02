'use strict';
// Read-only, chat-scoped Markdown tabs. Attachment text is data and never executable HTML.
(() => {
  const chats=new Map();let owner='',current=null,epochSeen='';
  const bar=document.createElement('div');bar.className='document-tabs';bar.setAttribute('role','tablist');bar.setAttribute('aria-label','会话子标签');bar.hidden=true;
  const panel=document.createElement('section');panel.id='document-panel';panel.hidden=true;panel.setAttribute('role','tabpanel');
  document.querySelector('main>header').after(bar,panel);
  const markdownFile=item=>/\.(md|markdown)$/i.test(item.name||'')||item.mime==='text/markdown';
  const scope=data=>[data.chat_id,data.context_id,data.history_revision].join('|');
  function release(doc){doc.controller?.abort();if(doc.url)URL.revokeObjectURL(doc.url);}
  function restoreChat(){
    $('messages').hidden=false;$('composer').hidden=false;panel.hidden=true;
    if(current?.reading){const reading=current.reading;current.reading=null;if(reading.pinned)viewport.bottom();else{$('messages').scrollTop=reading.top;viewport.lastTop=reading.top;viewport.pinned=false;$('latest').hidden=false;}}
  }
  function show(){
    bar.replaceChildren();bar.hidden=!current?.tabs.size;
    const conversation=document.createElement('button');conversation.type='button';conversation.setAttribute('role','tab');conversation.textContent='对话';conversation.setAttribute('aria-selected',String(!current?.active));
    conversation.onclick=()=>{current.active=null;show();};bar.append(conversation);
    for(const doc of current?.tabs.values()||[]){
      const item=document.createElement('div');item.className='document-tab';
      const tab=document.createElement('button');tab.type='button';tab.setAttribute('role','tab');tab.textContent=doc.item.name;tab.title=doc.item.name;tab.setAttribute('aria-selected',String(current.active===doc.key));tab.onclick=()=>{current.active=doc.key;show();};
      const close=document.createElement('button');close.type='button';close.textContent='×';close.setAttribute('aria-label','关闭文档 '+doc.item.name);close.onclick=()=>{release(doc);current.tabs.delete(doc.key);if(current.active===doc.key)current.active=null;show();};item.append(tab,close);bar.append(item);
    }
    const doc=current?.tabs.get(current.active);
    if(!doc){restoreChat();return;}
    if(!current.reading)current.reading={top:$('messages').scrollTop,pinned:viewport.pinned};
    $('messages').hidden=true;$('composer').hidden=true;panel.hidden=false;panel.replaceChildren();
    const heading=document.createElement('header'),title=document.createElement('strong');title.textContent=doc.item.name;heading.append(title);
    if(doc.url){const download=document.createElement('a');download.textContent='下载文件';download.href=doc.url;download.download=doc.item.name;heading.append(download);}
    panel.append(heading);const body=document.createElement('div');body.className='document-body';panel.append(body);
    if(doc.error){const error=document.createElement('p');error.className='document-error';error.textContent=doc.error;body.append(error);const retry=document.createElement('button');retry.type='button';retry.textContent='重新读取';retry.onclick=()=>void load(doc);body.append(retry);}
    else if(doc.text!==undefined){
      const preview=doc.text.slice(0,250000).split('\n').slice(0,5000).join('\n');
      if(preview.length<doc.text.length){const note=document.createElement('p');note.textContent='文档较长，当前预览前 250000 字符或 5000 行；可下载完整文件。';body.append(note);}
      const content=document.createElement('div');markdown(content,preview);body.append(content);
    }else{body.textContent='正在读取 Markdown 文档…';}
    panel.scrollTop=doc.scroll||0;panel.onscroll=()=>{doc.scroll=panel.scrollTop;};
  }
  async function load(doc){
    doc.controller?.abort();const controller=new AbortController();doc.controller=controller;doc.loading=true;doc.error='';show();
    if(doc.item.size>2*1024*1024){doc.error='文档超过 2 MiB 预览上限，请下载后查看，或让 Capri 按段分析。';doc.loading=false;show();return;}
    try{
      const response=await fetch('/api/attachment',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({args:doc.source}),signal:controller.signal});
      if(!response.ok){const error=await response.json();throw Error(error.error||'文档读取失败');}
      const blob=await response.blob();if(controller.signal.aborted)return;
      if(blob.size!==doc.item.size||blob.size>2*1024*1024)throw Error('文档不完整或超过预览限制，请重新读取。');
      doc.text=new TextDecoder('utf-8',{fatal:true}).decode(await blob.arrayBuffer());
      if(controller.signal.aborted)return;
      if(doc.url)URL.revokeObjectURL(doc.url);doc.url=URL.createObjectURL(blob);
    }catch(error){if(controller.signal.aborted)return;doc.error=error.message||'文档不是可预览的 UTF-8 文本。';}
    if(doc.controller===controller)doc.loading=false;
    if(current?.tabs.get(doc.key)===doc)show();
  }
  function sync(data){
    if(epochSeen&&epochSeen!==data.epoch){for(const value of chats.values())for(const doc of value.tabs.values())release(doc);chats.clear();owner='';}
    epochSeen=data.epoch;
    if(data.sessions){const available=new Set(data.sessions.map(s=>s.chat_id));for(const [key,value]of chats)if(!available.has(value.chat)){for(const doc of value.tabs.values())release(doc);chats.delete(key);}}
    const next=scope(data);if(next===owner)return;
    restoreChat();owner=next;
    for(const [key,value]of chats)if(value.chat===data.chat_id&&key!==next){for(const doc of value.tabs.values())release(doc);chats.delete(key);}
    current=chats.get(next);if(!current){current={chat:data.chat_id,tabs:new Map(),active:null,reading:null};chats.set(next,current);}
    show();
  }
  function open(entry,item){
    if(!state||!markdownFile(item))return;sync(state);
    const key=entry.id||String(entry.index);
    let doc=current.tabs.get(key);
    if(!doc){
      if(current.tabs.size>=6||Array.from(chats.values()).reduce((n,c)=>n+c.tabs.size,0)>=20)return failure('打开的文档过多，请先关闭不用的文档标签。');
      doc={key,item,source:{chat_id:chat,epoch,entry:entry.index,entry_id:entry.id,context_id:state.context_id},scroll:0};current.tabs.set(key,doc);
    }
    current.active=key;show();if(doc.text===undefined&&!doc.error&&!doc.loading)void load(doc);
  }
  window.CapriDocuments={open,sync,isMarkdown:markdownFile,beforeSwitch(){if(current){current.active=null;show();}}};if(state)sync(state);
})();
