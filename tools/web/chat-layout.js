'use strict';
// The phone supplies process membership from its own timelineRows implementation.
function groupChatEntries(ordered) {
  const groups=[], processes=new Map();
  for(const entry of ordered){
    const process=entry.process_group;
    if(process){
      let group=processes.get(process);
      if(!group){group={key:'process:'+process,tool:true,items:[]};processes.set(process,group);groups.push(group);}
      group.items.push(entry);continue;
    }
    const previous=groups.at(-1);
    if(entry.role==='tool'&&previous?.legacy){previous.items.push(entry);continue;}
    groups.push({key:'entry:'+(entry.id||entry.index),tool:entry.role==='tool',legacy:entry.role==='tool',items:[entry]});
  }
  return groups;
}
function toolOutputSummary(entry){
  const split=entry.text.indexOf('\n'), name=split<0?entry.text:entry.text.slice(0,split),body=split<0?'':entry.text.slice(split+1);
  let value;try{value=JSON.parse(body);}catch{}
  const status=value?.status,command=value?.data?.command;
  const labels={invalid_argument:'参数不合法',permission_denied:'权限受限',restricted:'权限受限',not_found:'记录不存在',unavailable:'当前不可用',timeout:'调用超时',partial:'返回部分信息',permission_required:'需要授权',foreground_required:'需要返回应用',setting_required:'需要继续操作',user_action_required:'需要继续操作',busy:'等待处理'};
  const commandLabels={failed:'命令退出 '+(command?.exit_code??-1),timeout:'命令超时',output_limit:'输出达到上限',interrupted:'命令已中断',cancelled:'命令已取消',running:'运行中'};
  const failed=['error','restricted','permission_denied','invalid_argument','unsupported','timeout','unavailable','not_found','not_running','tool_error','execution_failed'].includes(status)||['failed','timeout','output_limit','interrupted'].includes(command?.state)||body.startsWith('工具失败')||(name==='python_run'&&body.startsWith('exit=')&&!body.startsWith('exit=0\n'));
  const detail=labels[status]||commandLabels[command?.state]||(failed?'工具执行失败':'');
  return {label:(entry.tool_label||name)+(detail?' · '+detail:''),body,failed,display:value?JSON.stringify(value,null,2):body};
}
