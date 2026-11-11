'use strict';

const el = id => document.getElementById(id);
const headers = {'X-Capri-Chat': '1', 'Content-Type': 'application/json'};
const remote = document.body.dataset.remote === 'true';
let sessions = [];
let selected = sessionStorage.getItem('capri-host-session') || 'default';
let busy = false;
let connected = false;
let loading = false;
let selection = 0;
let modelOptions;
let settingsView = false;
const drafts = new Map();
const draftImages = new Map();
const draftAudio = new Map();
const turnLabels = {
  preparing: '正在准备…', thinking: '正在思考…', tools: '正在执行工具…',
  recovering: '正在恢复…', delivering: '正在生成回复…', cancelling: '正在停止…',
  waiting: '等待你补充信息。',
};
const toolHints = {
  ls: '正在查看目录', read: '正在读取文件', write: '正在写入文件', edit: '正在编辑文件',
  grep: '正在搜索工作区', message: '正在交付文件', session_artifacts: '正在查看已交付文件',
  project: '正在识别项目', coding_reference: '正在查看源码', coding_diagnostics: '正在检查代码',
  coding_restore: '正在恢复版本', apply_patch: '正在应用补丁', document_extract: '正在提取文档正文',
  web_search: '正在搜索网页', web_read: '正在读取网页', skill: '正在查看 Skill',
  browser: '正在读取浏览器快照', mcp_resources: '正在读取 MCP 资料', agent_jobs: '正在管理计划任务',
  skill_hub: '正在查看 Skill 目录', audio_info: '正在检查音频',
  knowledge: '正在查找或保存知识', task_experience: '正在查找或保存任务经验',
  personal_memory: '正在查找或保存记忆', conversation_history: '正在查看对话记录',
  context_history: '正在检查上下文', host_environment: '正在检查工作环境',
  agent_rules: '正在查看规则', model_info: '正在查看模型设置', extensions: '正在检查扩展',
  tool_search: '正在查找工具', tool_describe: '正在查看工具用法', tool_invoke: '正在调用工具',
  clarify: '正在准备澄清问题',
};

function error(message = '') {
  el('error').textContent = message;
  el('error').hidden = !message;
}

async function api(path, options = {}) {
  const response = await fetch(path, {...options, headers: {...headers, ...options.headers}});
  if (response.status === 401) {
    const failure = new Error('请重新连接 Capri。');
    failure.status = 401;
    throw failure;
  }
  if (!response.ok) {
    const failure = new Error('服务请求失败（HTTP ' + response.status + '）。');
    failure.status = response.status;
    throw failure;
  }
  return response;
}

function sessionPath(path) {
  return path + '?session=' + encodeURIComponent(selected);
}

function controls() {
  el('create').disabled = !connected || busy || loading;
  el('send').disabled = !connected || busy || loading;
  el('input').readOnly = busy || loading;
  el('refresh').disabled = !connected || busy || loading;
  el('export').disabled = !connected || busy || loading;
  el('upload').disabled = !connected || busy || loading;
  el('files-toggle').disabled = !connected || loading;
  el('session-menu').disabled = !connected || busy || loading;
  el('settings-tab').disabled = !connected || busy || loading;
  el('skills-open').disabled = !connected || busy || loading;
  el('skills-open').hidden = !modelOptions?.skillCatalog;
  el('chat-model').disabled = !connected || busy || loading;
  el('chat-effort').disabled = !connected || busy || loading;
  const image = draftImages.get(selected);
  el('chat-image').hidden = !image;
  el('chat-image').disabled = busy || loading;
  if (image) el('chat-image').textContent = image.split('/').pop() + ' ×';
  el('transcribe-audio').hidden = !modelOptions?.audioTranscription || !draftAudio.has(selected);
  el('transcribe-audio').disabled = busy || loading;
  el('sessions').querySelectorAll('button').forEach(button => { button.disabled = busy || loading; });
}

el('chat-image').onclick = () => {
  draftImages.delete(selected);
  controls();
};

el('transcribe-audio').onclick = async () => {
  const path = draftAudio.get(selected);
  if (!path || busy || loading) return;
  loading = true;
  controls();
  error();
  try {
    const response = await api(sessionPath('/api/workspace/transcribe'), {method: 'POST', body: JSON.stringify({path})});
    const transcript = await response.json();
    const text = el('input').value;
    if (text.length + transcript.text.length + 1 <= 4000) {
      el('input').value = text ? text + '\n' + transcript.text : transcript.text;
    } else {
      saveDownload(new Blob([transcript.text], {type: 'text/plain;charset=utf-8'}), 'transcript.txt');
    }
    draftAudio.delete(selected);
  } catch (failure) { error(failure.message); }
  finally { loading = false; controls(); }
};

const skillsDialog = el('skills-catalog');
const skillsVersion = el('skills-version');
let skillsPreview = null;
let skillsGeneration = 0;
function catalog(action, fields = {}) {
  return api(sessionPath('/api/skills/catalog'), {method: 'POST', body: JSON.stringify({action, ...fields})})
    .then(response => response.json());
}

async function previewSkill() {
  const generation = ++skillsGeneration;
  skillsPreview = null;
  el('skills-install').disabled = true;
  el('skills-rollback').disabled = true;
  el('skills-preview').textContent = '';
  el('skills-revision').replaceChildren();
  const item = skillsVersion.selectedOptions[0];
  if (!item) return;
  try {
    const preview = await catalog('inspect', {name: item.dataset.name, version: item.dataset.version});
    if (generation !== skillsGeneration) return;
    skillsPreview = preview;
    const resources = preview.resources?.length ? '\n\n资源文件\n' + preview.resources.join('\n') : '';
    el('skills-preview').textContent = preview.content + resources;
    el('skills-install').disabled = preview.revision === preview.workspace.installedRevision;
    el('skills-revision').replaceChildren(...preview.workspace.retainedRevisions.map(id => new Option(id.slice(0, 12), id)));
    el('skills-rollback').disabled = !preview.workspace.installedRevision || !el('skills-revision').value;
  } catch (failure) { if (generation === skillsGeneration) el('skills-status').textContent = failure.message; }
}

el('skills-open').onclick = async () => {
  el('skills-status').textContent = '';
  skillsVersion.replaceChildren();
  skillsDialog.showModal();
  void previewSkill();
  try {
    const {skills} = await catalog('list');
    if (!skillsDialog.open) return;
    skillsVersion.replaceChildren(...skills.flatMap(skill => skill.versions.map(version => {
      const option = new Option(skill.name + ' · ' + version);
      option.dataset.name = skill.name;
      option.dataset.version = version;
      return option;
    })));
    if (!skillsVersion.options.length) el('skills-status').textContent = '目录里没有可预览的版本。';
    await previewSkill();
  } catch (failure) { el('skills-status').textContent = failure.message; }
};
el('skills-close').onclick = () => { skillsGeneration++; skillsDialog.close(); };
skillsVersion.onchange = previewSkill;

async function updateSkill(action, fields) {
  if (!skillsPreview) return;
  const {name, version, revision, workspace} = skillsPreview;
  el('skills-install').disabled = true;
  el('skills-rollback').disabled = true;
  try {
    await catalog(action, {name, version, catalogRevision: revision, currentRevision: workspace.installedRevision, ...fields});
    el('skills-status').textContent = action === 'install' ? '已安装到当前会话。' : '已恢复保留的修订。';
    await previewSkill();
  } catch (failure) {
    el('skills-status').textContent = failure.message;
    await previewSkill();
  }
}
el('skills-install').onclick = () => updateSkill('install');
el('skills-rollback').onclick = () => updateSkill('rollback', {restoreRevision: el('skills-revision').value});

function renderSessions() {
  el('sessions').replaceChildren(...sessions.map(session => {
    const button = document.createElement('button');
    button.textContent = session.title;
    button.classList.toggle('selected', session.id === selected);
    button.setAttribute('aria-current', session.id === selected ? 'true' : 'false');
    button.onclick = () => select(session.id);
    return button;
  }));
  el('title').textContent = settingsView ? '设置' : currentTitle();
  controls();
}

function currentTitle() {
  return sessions.find(session => session.id === selected)?.title ?? 'Capri 对话';
}

function switchView(settings) {
  if (settingsView === settings) return;
  if (settings) rememberReadingPosition(selected);
  settingsView = settings;
  el('settings-tab').setAttribute('aria-pressed', String(settings));
  el('chat-tab').setAttribute('aria-pressed', String(!settings));
  for (const id of ['messages', 'composer', 'chat-actions', 'create', 'sessions']) el(id).hidden = settings;
  el('settings').hidden = !settings;
  if (settings) {
    showFiles(false);
    document.querySelectorAll('.settings-card[open]').forEach(card => { card.open = false; });
    loadSettings();
  } else {
    select(selected, false);
  }
  renderSessions();
}

let settingsRequest = 0;
async function loadSettings() {
  const request = ++settingsRequest;
  const rules = el('rules-status');
  const skills = el('installed-skills');
  const mcp = el('mcp-status');
  const tools = el('tools-status');
  el('settings-session').textContent = '当前会话 · ' + currentTitle();
  rules.textContent = skills.textContent = mcp.textContent = tools.textContent = '正在读取…';
  try {
    const state = await (await api(sessionPath('/api/settings'))).json();
    if (request !== settingsRequest || !settingsView) return;
    renderRules(rules, state.rules);
    skills.textContent = state.skills.status === 'unavailable' ? '无法读取会话 Skills' :
      state.skills.skills.length ? '' : '当前会话尚未接入 Skill';
    appendNames(skills, state.skills.skills || []);
    if (state.skills.truncated) skills.append('仅显示前 100 项。');
    tools.textContent = state.tools.length + ' 个可用工具';
    appendNames(tools, state.tools);
    if (!state.mcpConfigured) {
      mcp.textContent = '尚未配置 MCP';
      return;
    }
    const status = await (await api('/api/settings/mcp')).json();
    if (request !== settingsRequest || !settingsView) return;
    if (status.status === 'connected') {
      mcp.textContent = '已连接 · ' + status.approvedCount + ' 个授权资源';
      appendNames(mcp, status.resources);
      if (status.more) mcp.append('仅显示服务端返回的首批资源。');
    } else mcp.textContent = '已配置 · 暂时无法连接';
  } catch (failure) {
    if (request !== settingsRequest || !settingsView) return;
    for (const node of [rules, skills, mcp, tools]) {
      if (node.textContent === '正在读取…') node.textContent = failure.message;
    }
  }
}

function renderRules(container, rules) {
  const kernel = document.createElement('details');
  const title = document.createElement('summary');
  title.textContent = '内核级 · 规则文件';
  kernel.append(title);
  if (rules.kernelUnavailable) {
    const status = document.createElement('p');
    status.textContent = '无法读取内核规则目录。';
    kernel.append(status);
  }
  for (const rule of rules.kernel ?? []) {
    const file = document.createElement('details');
    const filename = document.createElement('summary');
    const content = document.createElement('pre');
    filename.textContent = rule.name + (rule.source === 'local' ? ' · 本机' : ' · 内置');
    content.textContent = rule.content;
    file.append(filename, content);
    kernel.append(file);
  }
  container.replaceChildren(kernel,
    ruleEditor('user', '用户级 · 所有会话', rules.user),
    ruleEditor('session', '会话级 · 当前会话', rules.session));
}

function ruleEditor(scope, name, rule) {
  const details = document.createElement('details');
  const summary = document.createElement('summary');
  const form = document.createElement('form');
  const label = document.createElement('label');
  const input = document.createElement('textarea');
  const button = document.createElement('button');
  const status = document.createElement('small');
  const actions = document.createElement('div');
  const endpoint = sessionPath('/api/settings');
  form.className = 'rule-editor';
  summary.textContent = name;
  label.textContent = name;
  label.className = 'visually-hidden';
  label.htmlFor = input.id = scope + '-rules';
  input.rows = 4;
  input.value = rule.content ?? '';
  input.placeholder = '填写规则，留空保存可清除。';
  button.type = 'submit';
  button.textContent = '保存';
  status.setAttribute('role', 'status');
  if (rule.unavailable) status.textContent = '规则无法读取，可以重新保存。';
  actions.append(button, status);
  form.append(label, input, actions);
  details.append(summary, form);
  form.onsubmit = async event => {
    event.preventDefault();
    const content = input.value;
    if (new TextEncoder().encode(content).length > 16 * 1024) {
      status.textContent = '每级规则最多 16 KiB。';
      return;
    }
    button.disabled = input.readOnly = true;
    status.textContent = '正在保存…';
    try {
      await api(endpoint, {method: 'PUT', body: JSON.stringify({scope, content})});
      status.textContent = '已保存，下次新任务生效。';
    } catch (failure) {
      status.textContent = failure.message;
    } finally {
      button.disabled = input.readOnly = false;
    }
  };
  return details;
}

function appendNames(parent, names) {
  if (!names.length) return;
  const list = document.createElement('ul');
  for (const name of names) {
    const item = document.createElement('li');
    item.textContent = name;
    list.append(item);
  }
  parent.append(list);
}

el('chat-tab').onclick = () => switchView(false);
el('settings-tab').onclick = () => switchView(true);
el('settings-refresh').onclick = loadSettings;
el('skills-catalog').addEventListener('close', () => { if (settingsView) loadSettings(); });

function messageRow(role, text, receipts = []) {
  const row = document.createElement('article');
  row.className = role === 'user' ? 'user' : 'assistant';
  const author = document.createElement('label');
  author.textContent = role === 'user' ? '你' : 'CAPRI';
  const content = document.createElement('div');
  if (role === 'user') content.textContent = text;
  else markdown(content, text);
  row.append(author);
  if (receipts.length) {
    const process = toolProcess();
    for (const [index, receipt] of receipts.entries()) process.update({...receipt, id: String(index)});
    row.append(process.node);
  }
  row.append(content);
  return row;
}

function toolProcess() {
  const node = document.createElement('details');
  node.className = 'tool-process';
  const summary = document.createElement('summary');
  const caption = document.createElement('span');
  summary.append(caption);
  const body = document.createElement('div');
  body.className = 'tool-process-content';
  node.append(summary, body);
  const entries = new Map();
  const active = new Map();
  const hints = [];
  let timer = 0;

  function showHint(text) {
    if (caption.textContent === text) return;
    const next = document.createElement('span');
    next.textContent = text;
    caption.replaceChildren(next);
  }

  function showCount() {
    caption.textContent = '工具过程 · ' + entries.size;
  }

  return {
    node,
    has(id) { return entries.has(id); },
    update(event) {
      let entry = entries.get(event.id);
      if (!entry) {
        const card = document.createElement('details');
        card.className = 'tool-entry';
        const title = document.createElement('summary');
        card.append(title);
        body.append(card);
        entry = {card, title};
        entries.set(event.id, entry);
      }
      const status = event.status === undefined ? '执行中' :
        ({ok: '已完成', error: '失败', not_executed: '未执行'})[event.status] || event.status;
      entry.title.textContent = event.tool + ' · ' + status;
      entry.card.classList.toggle('failed', event.status === 'error');
      if (event.output || event.error) {
        const output = document.createElement('pre');
        output.textContent = event.output || event.error;
        entry.card.append(output);
      }
      if (event.type === 'tool_start') {
        const hint = Object.hasOwn(toolHints, event.tool) ? toolHints[event.tool] : '正在执行工具';
        active.set(event.id, hint);
        if (!timer) {
          summary.classList.add('tool-running');
          showHint(hint);
          timer = setInterval(() => {
            showHint(hints.shift() || active.values().next().value || '正在整理工具结果');
          }, 850);
        } else if (hints.at(-1) !== hint && hints.length < 12) {
          hints.push(hint);
        }
      } else {
        active.delete(event.id);
        if (!timer) showCount();
      }
    },
    finish() {
      clearInterval(timer);
      timer = 0;
      summary.classList.remove('tool-running');
      showCount();
    },
  };
}

async function readTurn(response, onEvent) {
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  let pending = '';
  try {
    while (true) {
      const {value, done} = await reader.read();
      if (done) break;
      pending += value;
      let end;
      while ((end = pending.indexOf('\n')) !== -1) {
        const line = pending.slice(0, end);
        pending = pending.slice(end + 1);
        if (!line) continue;
        const event = JSON.parse(line);
        if (event.type === 'done') return event.result;
        onEvent(event);
      }
    }
    throw new Error('回复连接已中断。');
  } finally {
    await reader.cancel().catch(() => {});
  }
}

async function recoverTurn(turnId, currentCursor, onEvent, onGap) {
  const until = Date.now() + 155_000;
  while (Date.now() < until) {
    const path = sessionPath('/api/chat/events') + '&turn=' + encodeURIComponent(turnId) + '&after=' + currentCursor();
    try {
      const response = await api(path, {cache: 'no-store'});
      const batch = await response.json();
      if (batch.gap) onGap();
      for (const event of batch.events) onEvent(event);
      if (batch.result) return batch.result;
    } catch (failure) {
      if (!(failure instanceof TypeError)) throw failure;
    }
    await new Promise(resolve => setTimeout(resolve, 800));
  }
  throw new Error('等待重新连接超时。');
}

function scrollToLatest() {
  el('messages').scrollTop = el('messages').scrollHeight;
}

function updateLive(update) {
  const messages = el('messages');
  const follow = messages.scrollHeight - messages.scrollTop - messages.clientHeight <= 32;
  const previous = messages.scrollTop;
  update();
  if (follow) scrollToLatest();
  else messages.scrollTop = previous;
}

const readingTimers = new Map();
function rememberReadingPosition(session) {
  const pane = el('messages');
  const history = el('history');
  if (!history.children.length || loading) return;
  const bottom = pane.scrollHeight - pane.scrollTop - pane.clientHeight <= 32;
  const top = pane.getBoundingClientRect().top;
  const anchor = Array.from(history.children).find(row => row.dataset.historyKey && row.getBoundingClientRect().bottom > top);
  const saved = bottom ? {bottom} : anchor && {key: anchor.dataset.historyKey, offset: anchor.getBoundingClientRect().top - top};
  if (saved) {
    const key = 'capri-reading:' + session;
    const pending = {...saved, updatedAt: Date.now()};
    const serialized = JSON.stringify(pending);
    try { sessionStorage.setItem(key, serialized); } catch (_) {}
    clearTimeout(readingTimers.get(session));
    readingTimers.set(session, setTimeout(async () => {
      readingTimers.delete(session);
      try {
        const response = await api('/api/chat/reading?session=' + encodeURIComponent(session), {
          method: 'POST', body: serialized,
        });
        const position = await response.json();
        if (sessionStorage.getItem(key) === serialized) sessionStorage.setItem(key, JSON.stringify(position));
      } catch (_) {}
    }, 700));
  }
}

let scrollFrame = 0;
el('messages').addEventListener('scroll', () => {
  if (scrollFrame) return;
  const session = selected;
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    if (session === selected) rememberReadingPosition(session);
  });
}, {passive: true});

function renderModelOptions() {
  if (!modelOptions) return;
  let preference;
  try { preference = JSON.parse(localStorage.getItem('capri-host-model:' + selected)); } catch (_) {}
  const effortLabels = {none: '关闭', low: '低', high: '高', max: '最高'};
  const modelLabels = {'deepseek-flash': 'DeepSeek Flash', 'deepseek-v4-pro': 'DeepSeek V4 Pro'};
  const model = el('chat-model');
  const effort = el('chat-effort');
  model.replaceChildren(...modelOptions.models.map(id => new Option(modelLabels[id] || id, id)));
  effort.replaceChildren(...modelOptions.efforts.map(id => new Option(effortLabels[id] || id, id)));
  model.value = modelOptions.models.includes(preference?.model) ? preference.model : modelOptions.defaultModel;
  effort.value = modelOptions.efforts.includes(preference?.effort) ? preference.effort : modelOptions.defaultEffort;
  el('chat-effort-control').hidden = modelOptions.efforts.length < 2;
}

function modelSelection() {
  return {model: el('chat-model').value, effort: el('chat-effort').value};
}

el('chat-model').onchange = el('chat-effort').onchange = () => {
  try { localStorage.setItem('capri-host-model:' + selected, JSON.stringify(modelSelection())); } catch (_) {}
};

function renderHistory(messages, remotePosition) {
  const rows = messages.map((message, index) => {
    const row = messageRow(message.role, message.content, message.toolReceipts);
    row.dataset.historyKey = message.turnId || String(index);
    return row;
  });
  el('history').replaceChildren(...rows);
  let saved;
  try { saved = JSON.parse(sessionStorage.getItem('capri-reading:' + selected)); } catch (_) {}
  if (remotePosition && (!saved || remotePosition.updatedAt >= saved.updatedAt)) saved = remotePosition;
  const anchor = saved?.key && rows.find(row => row.dataset.historyKey === saved.key);
  if (anchor && Number.isFinite(saved.offset)) {
    const pane = el('messages');
    pane.scrollTop += anchor.getBoundingClientRect().top - pane.getBoundingClientRect().top - saved.offset;
  } else scrollToLatest();
}

async function loadHistory() {
  const generation = ++selection;
  renderModelOptions();
  const response = await api(sessionPath('/api/chat'));
  const {messages, readingPosition} = await response.json();
  if (generation === selection) renderHistory(messages, readingPosition);
}

async function loadSessions() {
  const response = await api('/api/sessions');
  sessions = (await response.json()).sessions;
  if (!sessions.some(session => session.id === selected)) selected = sessions[0]?.id ?? 'default';
  renderSessions();
}

async function select(id, savePosition = true) {
  if (busy || loading) return;
  if (savePosition) rememberReadingPosition(selected);
  loading = true;
  drafts.set(selected, el('input').value);
  selected = id;
  sessionStorage.setItem('capri-host-session', id);
  el('input').value = drafts.get(id) ?? '';
  el('history').replaceChildren();
  if (!el('session-files').hidden) loadFiles();
  error();
  renderSessions();
  try { await loadHistory(); }
  catch (failure) { error(failure.message); }
  finally { loading = false; controls(); }
}

const sessionDialog = el('session-dialog');
el('session-menu').onclick = () => {
  el('session-name').value = currentTitle();
  el('session-edit-status').textContent = '';
  sessionDialog.showModal();
};
el('session-close').onclick = () => sessionDialog.close();
el('session-edit').onsubmit = event => {
  event.preventDefault();
  const title = el('session-name').value.trim();
  if (!title) {
    el('session-edit-status').textContent = '请输入会话名称。';
    return;
  }
  mutateSession('PATCH', title);
};
el('session-delete').onclick = () => {
  if (confirm('删除「' + currentTitle() + '」及其会话文件？此操作无法撤销。')) mutateSession('DELETE');
};

async function mutateSession(method, title) {
  if (loading || busy) return;
  loading = true;
  controls();
  el('session-save').disabled = el('session-delete').disabled = true;
  el('session-edit-status').textContent = '';
  try {
    const response = await api(sessionPath('/api/sessions'), {
      method, ...(title ? {body: JSON.stringify({title})} : {}),
    });
    const data = await response.json();
    sessionDialog.close();
    if (method === 'PATCH') {
      sessions = sessions.map(session => session.id === data.id ? {...session, title: data.title} : session);
      renderSessions();
    } else {
      sessions = data.sessions;
      clearTimeout(readingTimers.get(selected));
      readingTimers.delete(selected);
      sessionStorage.removeItem('capri-reading:' + selected);
      drafts.delete(selected);
      draftImages.delete(selected);
      draftAudio.delete(selected);
      el('input').value = '';
      loading = false;
      await select(sessions.find(session => session.id !== selected)?.id ?? 'default', false);
    }
  } catch (failure) {
    el('session-edit-status').textContent = failure.status === 409 ? '会话仍有任务在运行，请稍后重试。' : failure.message;
  } finally {
    loading = false;
    el('session-save').disabled = el('session-delete').disabled = false;
    controls();
  }
}

el('create').onclick = async () => {
  if (busy || loading) return;
  loading = true;
  controls();
  try {
    const title = '对话 · ' + new Date().toLocaleString('zh-CN', {
      month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit'
    });
    const response = await api('/api/sessions', {method: 'POST', body: JSON.stringify({title})});
    const session = await response.json();
    sessions.unshift(session);
    loading = false;
    await select(session.id);
  } catch (failure) { error(failure.message); }
  finally { loading = false; controls(); }
};

el('composer').onsubmit = async event => {
  event.preventDefault();
  if (busy || loading) return;
  const text = el('input').value.trim();
  if (!text) return;
  const image = draftImages.get(selected);
  if (image && el('chat-model').value !== 'deepseek-flash') {
    error('发送图片时，请选择 DeepSeek Flash。');
    return;
  }
  busy = true;
  controls();
  error();
  el('input').value = '';
  const pending = messageRow('user', text);
  pending.classList.add('pending');
  el('history').append(pending);
  el('progress').textContent = 'Capri 正在回复…';
  scrollToLatest();
  const live = messageRow('assistant', '');
  const content = live.lastElementChild;
  const process = toolProcess();
  process.node.hidden = true;
  live.insertBefore(process.node, content);
  live.hidden = true;
  el('history').append(live);
  let preview = '';
  let frame = 0;
  const controller = new AbortController();
  const stop = el('cancel-turn');
  const steer = el('steer-turn');
  const requestedTurnId = crypto.randomUUID();
  let turnId = '';
  const poll = async () => {
    try {
      const response = await api(sessionPath('/api/chat/status'), {signal: controller.signal});
      const status = await response.json();
      if (controller.signal.aborted) return;
      turnId = status.turnId === requestedTurnId ? requestedTurnId : '';
      stop.hidden = !turnId;
      stop.disabled = status.phase === 'cancelling';
      steer.hidden = !turnId || !['preparing', 'thinking', 'tools', 'recovering'].includes(status.phase);
      el('progress').textContent = turnLabels[status.phase] || 'Capri 正在回复…';
    } catch (_) {
      // The chat request can complete even when a status check fails.
    }
    if (!controller.signal.aborted) setTimeout(poll, 800);
  };
  stop.onclick = async () => {
    if (!turnId) return;
    stop.disabled = true;
    try {
      await api(sessionPath('/api/chat/cancel'), {method: 'POST', body: turnId});
    } catch (failure) {
      if (!controller.signal.aborted) error(failure.message);
    }
  };
  steer.onclick = async () => {
    const text = window.prompt('补充本轮任务的要求')?.trim();
    if (!text || !turnId) return;
    try {
      await api(sessionPath('/api/chat/steer'), {method: 'POST', body: JSON.stringify({turnId, text})});
      el('progress').textContent = '已收到补充，Capri 将重新判断后续步骤。';
    } catch (failure) { if (!controller.signal.aborted) error(failure.message); }
  };
  let waiting = false;
  let cursor = 0;
  const onEvent = event => {
    if (event.turnId && event.turnId !== requestedTurnId) return;
    if (event.seq && event.seq <= cursor) return;
    cursor = event.seq || cursor;
    if (event.type === 'reply_start') {
      preview = '';
      updateLive(() => content.replaceChildren());
    } else if (event.type === 'reply_delta') {
      preview += event.text;
      if (!frame) frame = requestAnimationFrame(() => {
        frame = 0;
        updateLive(() => {
          live.hidden = false;
          markdown(content, preview);
        });
      });
    } else if (event.type === 'tool_start' || event.type === 'tool_end') {
      updateLive(() => {
        process.update(event);
        process.node.hidden = false;
        live.hidden = false;
      });
    }
  };
  try {
    const responsePromise = api(sessionPath('/api/chat'), {
      method: 'POST', headers: {'X-Capri-Turn': requestedTurnId, Accept: 'application/x-ndjson'},
      body: JSON.stringify({text, ...modelSelection(), image}),
    });
    void poll();
    let result;
    try {
      result = await readTurn(await responsePromise, onEvent);
    } catch (failure) {
      if (failure.status === 401) throw failure;
      result = await recoverTurn(requestedTurnId, () => cursor, onEvent, () => {
        preview = '';
        updateLive(() => content.replaceChildren());
      });
    }
    controller.abort();
    if (result.outcome === 'cancelled') {
      pending.remove();
      live.remove();
      el('input').value = text;
      error('本轮已停止。已完成的操作仍然有效，可发送「继续」接续。');
      return;
    }
    if (result.error === 'vision_model_required') throw new Error('请切换到 DeepSeek Flash 后发送图片。');
    if (result.error === 'invalid_image') throw new Error('无法读取这张图片，请重新上传。');
    if (result.outcome === 'timed_out' || result.outcome === 'interrupted' || result.outcome === 'failed')
      throw new Error('本轮中断。可以发送「继续」，从已保存的执行记录接续。');
    waiting = result.outcome === 'waiting';
    if (result.outcome !== 'complete' && !waiting) throw new Error('本轮未完成。');
    drafts.delete(selected);
    draftImages.delete(selected);
    pending.classList.remove('pending');
    cancelAnimationFrame(frame);
    updateLive(() => {
      markdown(content, result.reply);
      for (const [index, receipt] of (result.tool_receipts || []).entries()) {
        const id = receipt.id || String(index);
        if (!process.has(id)) process.update({...receipt, id});
      }
      if (result.tool_receipts?.length) process.node.hidden = false;
      live.hidden = false;
      process.finish();
    });
  } catch (failure) {
    pending.remove();
    live.remove();
    el('input').value = text;
    error(failure.message + ' 可先刷新会话，确认后再决定是否重发。');
  } finally {
    process.finish();
    cancelAnimationFrame(frame);
    controller.abort();
    stop.hidden = true;
    stop.onclick = null;
    steer.hidden = true;
    steer.onclick = null;
    el('progress').textContent = waiting ? turnLabels.waiting : '';
    busy = false;
    controls();
    if (!el('session-files').hidden) loadFiles();
  }
};
el('input').onkeydown = event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    if (!el('send').disabled) el('composer').requestSubmit();
  }
};
el('refresh').onclick = () => select(selected);

el('upload').onclick = () => el('workspace-file').click();
el('workspace-file').onchange = async () => {
  const input = el('workspace-file');
  const file = input.files[0];
  input.value = '';
  if (!file || busy || loading) return;
  if (file.size > 1024 * 1024) {
    error('文件不能超过 1 MiB。');
    return;
  }
  loading = true;
  controls();
  error();
  try {
    const response = await api(sessionPath('/api/workspace/upload'), {
      method: 'POST', body: file,
      headers: {'Content-Type': 'application/octet-stream', 'X-Capri-Filename': encodeURIComponent(file.name)}
    });
    const {path} = await response.json();
    if (!el('session-files').hidden) loadFiles();
    if (/\.(png|jpe?g|gif|webp)$/i.test(file.name)) draftImages.set(selected, path);
    if (/\.wav$/i.test(file.name)) draftAudio.set(selected, path);
    const reference = '工作区文件：' + path;
    const current = el('input').value;
    if (current.length + reference.length + 1 <= 4000)
      el('input').value = current ? current + '\n' + reference : reference;
    else error('文件已上传：' + path);
  } catch (failure) { error(failure.message); }
  finally { loading = false; controls(); }
};

function saveDownload(blob, name) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

let filesRequest = 0;
let filesSession;
function showFiles(open) {
  const button = el('files-toggle');
  el('session-files').hidden = !open;
  button.setAttribute('aria-expanded', String(open));
  button.title = open ? '收起会话文件' : '展开会话文件';
  button.setAttribute('aria-label', button.title);
  if (open) loadFiles();
  else if (document.activeElement === el('files-close')) button.focus();
}

el('files-toggle').onclick = () => showFiles(el('session-files').hidden);
el('files-close').onclick = () => showFiles(false);

async function loadFiles() {
  const request = ++filesRequest;
  const tree = el('file-tree');
  const status = el('files-status');
  const open = filesSession === selected ? new Set([...tree.querySelectorAll('details[open]')].map(node => node.dataset.path)) : new Set();
  filesSession = selected;
  const current = () => request === filesRequest && filesSession === selected && !el('session-files').hidden;
  tree.replaceChildren();
  status.textContent = '正在读取会话文件…';
  try {
    const {files, legacy, truncated} = await (await api(sessionPath('/api/workspace/files'))).json();
    if (!current()) return;
    status.textContent = truncated ? '文件较多，当前仅显示部分文件。' :
      files.length + legacy.length ? '' : '当前会话还没有文件。';
    const folders = new Map([['', tree]]);
    for (const file of files) {
      const parts = file.path.split('/');
      const name = parts.pop();
      let parent = '';
      for (const part of parts) {
        const path = parent ? parent + '/' + part : part;
        if (!folders.has(path)) {
          const directory = fileFolder(part, path, open.has(path));
          folders.get(parent).append(directory);
          folders.set(path, directory.lastElementChild);
        }
        parent = path;
      }
      const download = sessionPath('/api/workspace/file') + '&path=' + encodeURIComponent(file.path);
      folders.get(parent).append(fileRow(name, file, download));
    }
    if (legacy.length) {
      const directory = fileFolder('历史产物', ':legacy', open.has(':legacy'));
      for (const file of legacy) directory.lastElementChild.append(fileRow(file.name, file,
        sessionPath('/api/artifacts/' + encodeURIComponent(file.id))));
      tree.append(directory);
    }
  } catch (failure) {
    if (current()) status.textContent = failure.message;
  }
}

function fileFolder(name, path, open) {
  const directory = document.createElement('details');
  const label = document.createElement('summary');
  label.textContent = name;
  directory.dataset.path = path;
  directory.open = open;
  directory.append(label, document.createElement('div'));
  return directory;
}

function fileRow(name, file, download) {
  const button = document.createElement('button');
  const label = document.createElement('span');
  const meta = document.createElement('small');
  label.textContent = name;
  meta.textContent = (file.published ? '已交付 · ' : '') + new Intl.NumberFormat('zh-CN', {
    style: 'unit', unit: file.size >= 1024 ? 'kilobyte' : 'byte', maximumFractionDigits: 1,
  }).format(file.size >= 1024 ? file.size / 1024 : file.size);
  button.className = 'file-row';
  button.title = '下载 ' + (file.path ?? name);
  button.append(label, meta);
  button.onclick = async () => {
    button.disabled = true;
    try { saveDownload(await (await api(download)).blob(), name); }
    catch (failure) { error(failure.message); }
    finally { button.disabled = false; }
  };
  return button;
}

el('export').onclick = async () => {
  try {
    const response = await api(sessionPath('/api/sessions/export'), {method: 'POST'});
    saveDownload(await response.blob(), 'capri-conversation.md');
  } catch (failure) { error(failure.message); }
};

const deviceOutput = el('device-status');
async function deviceRequest(body) {
  const response = await fetch('/api/device/launch', body ?
    {method: 'POST', headers, body: JSON.stringify(body)} : {headers});
  const state = await response.json();
  deviceOutput.textContent = JSON.stringify(state, null, 2);
  return {ok: response.ok, state};
}

el('device-open').onclick = () => { el('device').showModal(); el('device-refresh').click(); };
el('device-close').onclick = () => el('device').close();
el('device-refresh').onclick = () => deviceRequest().catch(() => { deviceOutput.textContent = '设备状态读取失败'; });
el('launch').onsubmit = async event => {
  event.preventDefault();
  const button = el('launch').querySelector('button');
  button.disabled = true;
  try {
    let {ok, state} = await deviceRequest({package_name: el('launch').elements.packageName.value.trim()});
    for (let i = 0; ok && i < 22 && ['queued', 'dispatched'].includes(state.launch?.state); i++) {
      await new Promise(resolve => setTimeout(resolve, 1000));
      ({ok, state} = await deviceRequest());
    }
  } catch (_) { deviceOutput.textContent = '结果未确认，请刷新设备状态。'; }
  finally { button.disabled = false; }
};

async function connect(token) {
  try {
    if (token) await api('/api/access', {method: 'POST', headers: {Authorization: 'Bearer ' + token}});
    await loadSessions();
    modelOptions = await (await api(sessionPath('/api/chat/options'))).json();
    await loadHistory();
    connected = true;
    el('access').reset();
    el('access').hidden = true;
    el('composer').hidden = false;
    el('device-open').hidden = !remote;
    el('connection').textContent = '服务端在线';
    error();
    controls();
  } catch (failure) {
    el('access').hidden = !remote;
    el('connection').textContent = '等待连接';
    if (token || !remote || failure.status !== 401) error(failure.message);
  }
}
el('access').onsubmit = event => {
  event.preventDefault();
  connect(el('access').elements.token.value.trim());
};
const supplied = new URLSearchParams(location.hash.slice(1)).get('access');
if (supplied !== null) history.replaceState(null, '', location.pathname + location.search);
connect(supplied);
