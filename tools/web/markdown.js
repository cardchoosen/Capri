// Build text nodes only. Model output never becomes executable HTML.
function inline(parent, text) {
  const parts = text.split(/(`[^`\n]+`|\*\*[^*\n]+\*\*)/g);
  for (const part of parts) { if (!part) continue; const tag = part.startsWith('`') && part.endsWith('`') ? 'code' : part.startsWith('**') && part.endsWith('**') ? 'strong' : null;
    if (!tag) parent.append(document.createTextNode(part)); else { const el=document.createElement(tag); el.textContent=part.slice(tag==='code'?1:2,tag==='code'?-1:-2); parent.append(el); } }
}
function markdown(target, text) {
  target.replaceChildren(); const lines=text.split('\n'); let i=0;
  while(i<lines.length) {
    const line=lines[i]; if(!line.trim()){i++;continue;}
    if(line.startsWith('```')) { const pre=document.createElement('pre'),code=document.createElement('code'); const body=[];i++;while(i<lines.length&&!lines[i].startsWith('```'))body.push(lines[i++]);i++;code.textContent=body.join('\n');pre.append(code);target.append(pre);continue; }
    if(i+1<lines.length && line.includes('|') && /^\s*\|?\s*:?-{3,}/.test(lines[i+1])) {
      const wrap=document.createElement('div'),table=document.createElement('table');wrap.className='table-wrap';let head=true;
      while(i<lines.length&&lines[i].includes('|')){ if(!/^\s*\|?\s*:?-{3,}/.test(lines[i])){const row=document.createElement('tr');for(const cell of lines[i].replace(/^\s*\||\|\s*$/g,'').split('|')){const el=document.createElement(head?'th':'td');inline(el,cell.trim());row.append(el);}table.append(row);head=false;}i++;}wrap.append(table);target.append(wrap);continue;
    }
    const heading=/^(#{1,4})\s+(.+)$/.exec(line), bullet=/^\s*([-*]|\d+\.)\s+(.+)$/.exec(line);
    const el=document.createElement(heading?'h'+heading[1].length:line.startsWith('> ')?'blockquote':'p');inline(el,heading?heading[2]:bullet?'• '+bullet[2]:line.startsWith('> ')?line.slice(2):line);target.append(el);i++;
  }
}
