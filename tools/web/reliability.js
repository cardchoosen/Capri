/* Read-only task-event projection. No background polling or mutation replay. */
(()=>{
  const button=document.createElement('button');button.type='button';button.textContent='持久任务';button.id='reliability-open';
  const anchor=document.getElementById('run-open');if(!anchor)return;anchor.after(button);
  const dialog=document.createElement('dialog'),heading=document.createElement('h3'),output=document.createElement('pre'),close=document.createElement('button');
  heading.textContent='持久任务与执行来源';close.textContent='关闭';close.onclick=()=>dialog.close();dialog.append(heading,output,close);document.body.append(dialog);
  button.onclick=async()=>{
    dialog.showModal();output.textContent='读取中…';
    try{if(!state||!connected)throw Error('请先连接手机');const scope={chat_id:chat,context_id:state.context_id};const result=await api('web_events',{...scope,after:0});if(chat!==scope.chat_id||state.context_id!==scope.context_id)throw Error('会话已变化，请重新打开');output.textContent=JSON.stringify(result,null,2);}
    catch(e){output.textContent=e.message;}
  };
})();
