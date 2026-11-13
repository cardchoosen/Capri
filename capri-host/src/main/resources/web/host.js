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
let workspaceTabs = [];
let activeWorkspaceTab = 'chat';
let integrationSettings = {ssh: [], privateRoots: [], approvals: []};
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
  render_bar_animation: 'Rendering bar animation',
  project: '正在识别项目', coding_reference: '正在查看源码', coding_diagnostics: '正在检查代码',
  coding_restore: '正在恢复版本', apply_patch: '正在应用补丁', document_extract: '正在提取文档正文',
  web_search: '正在搜索网页', web_read: '正在读取网页', skill: '正在查看 Skill',
  browser: 'Controlling browser', agent_browser: 'Using agent-browser', playwright: 'Running Playwright plan',
  ui_ux_design: 'Searching UI UX design guidance', mcp_resources: '正在读取 MCP 资料', agent_jobs: '正在管理计划任务',
  skill_hub: '正在查看 Skill 目录', audio_info: '正在检查音频',
  knowledge: '正在查找或保存知识', task_experience: '正在查找或保存任务经验',
  personal_memory: '正在查找或保存记忆', conversation_history: '正在查看对话记录',
  context_history: '正在检查上下文', host_environment: '正在检查工作环境',
  agent_rules: '正在查看规则', model_info: '正在查看模型设置', extensions: '正在检查扩展',
  tool_search: '正在查找工具', tool_describe: '正在查看工具用法', tool_invoke: '正在调用工具',
  github: '正在管理 GitHub', ssh: '正在连接 SSH 服务器', private_files: '正在访问私人文件', private_library: '正在整理 Capri 资料库',
  host_exec: '正在运行本机会话命令',
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

function sessionPath(path, session = selected) {
  return path + '?session=' + encodeURIComponent(session);
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
    const row = document.createElement('div');
    row.className = 'session-row';
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = session.title;
    button.classList.toggle('selected', session.id === selected);
    button.setAttribute('aria-current', session.id === selected ? 'true' : 'false');
    button.onclick = () => select(session.id);
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'session-delete';
    remove.textContent = '×';
    remove.title = '删除会话';
    remove.setAttribute('aria-label', '删除会话：' + session.title);
    remove.onclick = () => deleteSession(session);
    row.append(button, remove);
    return row;
  }));
  el('title').textContent = activeWorkspaceTab === 'chat' ? (settingsView ? '设置' : currentTitle()) :
    workspaceTabs.find(tab => tab.id === activeWorkspaceTab)?.name ?? currentTitle();
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
    } else {
      const status = await (await api('/api/settings/mcp')).json();
      if (request !== settingsRequest || !settingsView) return;
      if (status.status === 'connected') {
        mcp.textContent = '已连接 · ' + status.approvedCount + ' 个授权资源';
        appendNames(mcp, status.resources);
        if (status.more) mcp.append('仅显示服务端返回的首批资源。');
      } else mcp.textContent = '已配置 · 暂时无法连接';
    }
    renderIntegrationSettings({...state.integrations, hostExecution: state.hostExecution});
    await loadRemoteDevices(request);
  } catch (failure) {
    if (request !== settingsRequest || !settingsView) return;
    for (const node of [rules, skills, mcp, tools]) {
      if (node.textContent === '正在读取…') node.textContent = failure.message;
    }
  }
}

async function loadRemoteDevices(request = settingsRequest) {
  const target = el('remote-devices');
  const status = el('device-status');
  try {
    const state = await (await api('/api/devices')).json();
    if (request !== settingsRequest || !settingsView) return;
    target.replaceChildren();
    for (const device of state.devices || []) {
      const row = document.createElement('div');
      row.className = 'ssh-server-row';
      const label = document.createElement('span');
      const icon = device.platform === 'android' ? '📱' : '🖥️';
      label.textContent = `${icon} ${device.name} · ${device.platform} · ${device.online ? '在线' : '离线'}`;
      row.append(label);
      if (device.id !== 'host') {
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.textContent = '撤销配对';
        remove.onclick = async () => {
          if (!confirm(`撤销 ${device.name} 的配对？`)) return;
          await api('/api/devices/revoke', {method: 'POST', body: JSON.stringify({device_id: device.id})});
          await loadRemoteDevices(request);
        };
        row.append(remove);
      }
      target.append(row);
    }
    if (!state.devices?.length) target.textContent = '暂无已连接设备。';
  } catch (failure) {
    status.textContent = failure.message;
  }
}

el('device-pair-create').onclick = async () => {
  const status = el('device-status');
  const output = el('device-pairing');
  status.textContent = '正在生成一次性配对码…';
  try {
    const result = await (await api('/api/devices/pairing', {method: 'POST', body: '{}'})).json();
    output.hidden = false;
    output.textContent = `配对码（10 分钟内有效）\n${result.pairing_token}\n\n设备 WSS 地址\n${result.socket_url}`;
    status.textContent = '在 Capri Android 客户端中粘贴配对码。';
  } catch (failure) {
    status.textContent = failure.message;
  }
};

function renderIntegrationSettings(state) {
  const github = state.github || {};
  el('github-status').textContent = github.connected ? '已连接 · ' + (github.login || 'GitHub 用户') : '尚未连接';
  el('github-token').value = '';
  integrationSettings = state;
  const serverList = el('ssh-servers');
  serverList.replaceChildren();
  if (!state.ssh?.length) serverList.textContent = '尚未配置 SSH 服务器。';
  for (const [index, server] of (state.ssh || []).entries()) {
    const row = document.createElement('div');
    row.className = 'ssh-server-row';
    const label = document.createElement('span');
    label.textContent = `${server.name} · ${server.user}@${server.host}:${server.port} · ${server.hasIdentityFile ? 'Capri 已保管私钥副本' : '未导入私钥'}`;
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.textContent = '移除';
    remove.onclick = () => {
      integrationSettings.ssh.splice(index, 1);
      renderIntegrationSettings(integrationSettings);
      void saveSshServers();
    };
    row.append(label, remove);
    serverList.append(row);
  }
  el('private-roots').value = (state.privateRoots || []).join('\n');
  renderPrivateApprovals(state.approvals || []);
  renderPrivateLibrary(state.library || {});
  renderHostExecution(state.hostExecution || {});
}

function renderHostExecution(state) {
  el('host-execution-mode').value = state.mode || 'approval';
  el('host-execution-allowlist').value = (state.allowlist || []).join('\n');
  el('host-execution-backend').textContent = state.sandbox === 'macos-sandbox-exec' ?
    '已检测到 macOS 系统沙箱；工作区外的文件访问、写入和网络请求将被限制。' :
    state.sandbox === 'linux-bwrap' ? '已检测到 bubblewrap；工作区外文件访问、写入和网络请求将被限制。' :
    '未检测到受支持的系统沙箱；本机命令执行保持关闭。';
  const approvals = el('host-execution-approvals');
  approvals.replaceChildren();
  if (state.approvals?.length) {
    const heading = document.createElement('h3'); heading.textContent = '待审批的本机命令'; approvals.append(heading);
    for (const request of state.approvals) {
      const row = document.createElement('div'); row.className = 'approval-row';
      const command = document.createElement('code'); command.textContent = `${request.command} · ${request.cwd || '.'}`;
      const approve = document.createElement('button'); approve.type = 'button'; approve.textContent = '批准';
      const deny = document.createElement('button'); deny.type = 'button'; deny.textContent = '拒绝';
      approve.onclick = () => decidePrivateApproval(request.id, true, 'hostExecution');
      deny.onclick = () => decidePrivateApproval(request.id, false, 'hostExecution');
      row.append(command, approve, deny); approvals.append(row);
    }
  }
  const receipts = el('host-execution-receipts');
  receipts.replaceChildren();
  if (state.receipts?.length) {
    const heading = document.createElement('h3'); heading.textContent = '最近执行回执'; receipts.append(heading);
    for (const item of [...state.receipts].reverse()) {
      const row = document.createElement('div'); row.className = 'approval-row';
      const label = document.createElement('span'); label.textContent = `${item.state} · ${item.command} · ${item.cwd || '.'}`;
      row.append(label); receipts.append(row);
    }
  }
}

function renderPrivateLibrary(library) {
  el('private-library-path').textContent = library.path || '';
  const container = el('private-library-files');
  container.replaceChildren();
  if (!library.files?.length) {
    container.textContent = '资料库目前为空。';
    return;
  }
  const list = document.createElement('ul');
  for (const file of library.files) {
    const item = document.createElement('li');
    const label = document.createElement('span');
    label.textContent = file.path + ' · ' + formatBytes(file.size);
    const download = document.createElement('button');
    download.type = 'button';
    download.textContent = '下载';
    download.onclick = async () => {
      try {
        const response = await api(sessionPath('/api/private-library/file?path=' + encodeURIComponent(file.path)));
        saveDownload(await response.blob(), file.path.split('/').pop());
      } catch (failure) { el('private-library-status').textContent = failure.message; }
    };
    item.append(label, download);
    list.append(item);
  }
  container.append(list);
  if (library.truncated) {
    const note = document.createElement('small');
    note.textContent = '只显示前 500 个文件。';
    container.append(note);
  }
}

function formatBytes(size) {
  if (size < 1024) return size + ' B';
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KiB';
  return (size / (1024 * 1024)).toFixed(1) + ' MiB';
}

el('private-library-import').onclick = () => el('private-library-input').click();
el('private-library-input').onchange = async event => {
  const input = event.currentTarget;
  const files = [...(input.files || [])];
  input.value = '';
  if (!files.length) return;
  const status = el('private-library-status');
  let imported = 0;
  for (const [index, file] of files.entries()) {
    if (file.size > 50 * 1024 * 1024) {
      status.textContent = `${file.name} 超过 50 MiB，已跳过。`;
      continue;
    }
    status.textContent = `正在导入 ${index + 1}/${files.length}：${file.name}`;
    try {
      await api(sessionPath('/api/private-library/upload'), {method: 'POST', body: file,
        headers: {'Content-Type': 'application/octet-stream', 'X-Capri-Filename': encodeURIComponent(file.name)}});
      imported++;
    } catch (failure) {
      status.textContent = `${file.name} 导入失败：${failure.message}`;
    }
  }
  status.textContent = `已导入 ${imported}/${files.length} 个文件。`;
  if (settingsView) await loadSettings();
};

function renderPrivateApprovals(requests) {
  const container = el('private-approvals');
  container.replaceChildren();
  if (!requests.length) return;
  const heading = document.createElement('h3');
  heading.textContent = '待处理的私人文件授权';
  container.append(heading);
  for (const request of requests) {
    const row = document.createElement('div');
    row.className = 'approval-row';
    const description = document.createElement('span');
    description.textContent = `${request.action} · ${request.path}`;
    const approve = document.createElement('button');
    approve.type = 'button';
    approve.textContent = '批准';
    const deny = document.createElement('button');
    deny.type = 'button';
    deny.textContent = '拒绝';
    approve.onclick = () => decidePrivateApproval(request.id, true);
    deny.onclick = () => decidePrivateApproval(request.id, false);
    row.append(description, approve, deny);
    container.append(row);
  }
}

async function saveIntegrations(payload, statusId) {
  const status = el(statusId);
  status.textContent = '正在保存…';
  try {
    const response = await api(sessionPath('/api/settings/integrations'), {method: 'PUT', body: JSON.stringify(payload)});
    const state = await response.json();
    renderIntegrationSettings(state);
    status.textContent = statusId === 'github-status' && state.github?.connected ?
      '已连接 · ' + (state.github.login || 'GitHub 用户') : '已保存。';
    return true;
  } catch (failure) { status.textContent = failure.message; return false; }
}

el('github-form').onsubmit = event => {
  event.preventDefault();
  void saveIntegrations({kind: 'github', token: el('github-token').value}, 'github-status');
};
el('github-disconnect').onclick = () => void saveIntegrations({kind: 'github', disconnect: true}, 'github-status');

el('ssh-add-form').onsubmit = async event => {
  event.preventDefault();
  const values = new FormData(event.currentTarget);
  const key = values.get('identityKey');
  const server = {name: values.get('name'), host: values.get('host'), user: values.get('user'), port: Number(values.get('port'))};
  if (key?.size) {
    if (key.size > 128 * 1024) { el('ssh-status').textContent = '私钥文件不能超过 128 KiB。'; return; }
    const bytes = new Uint8Array(await key.arrayBuffer());
    let binary = '';
    for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
    server.identityKey = btoa(binary);
  }
  const next = [...(integrationSettings.ssh || []), server];
  if (await saveSshServers(next)) {
    event.currentTarget.reset();
    event.currentTarget.elements.port.value = '22';
  }
};
el('ssh-save').onclick = () => void saveSshServers();
function saveSshServers(servers = integrationSettings.ssh || []) {
  return saveIntegrations({kind: 'ssh', servers: servers.map(server => ({...server,
    keepIdentityFile: server.hasIdentityFile || Boolean(server.identityFile)}))}, 'ssh-status');
}

el('private-roots-save').onclick = () => {
  const roots = el('private-roots').value.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
  void saveIntegrations({kind: 'privateRoots', roots}, 'private-status');
};

el('host-execution-save').onclick = async () => {
  const allowlist = el('host-execution-allowlist').value.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
  const status = el('host-execution-status');
  status.textContent = '正在保存…';
  try {
    const response = await api(sessionPath('/api/settings/integrations'), {method: 'PUT', body: JSON.stringify({
      kind: 'hostExecution', mode: el('host-execution-mode').value, allowlist,
    })});
    renderIntegrationSettings(await response.json());
    status.textContent = '执行策略已保存。';
  } catch (failure) { status.textContent = failure.message; }
};

async function decidePrivateApproval(id, approve, kind = 'privateFiles') {
  try {
    const response = await api(sessionPath('/api/settings/approval'), {method: 'POST', body: JSON.stringify({id, approve, kind})});
    const decision = await response.json();
    const input = el('input');
    input.value = kind === 'hostExecution' ? approve ? `我已批准运行命令：${decision.command}，请继续执行并完成任务。` :
      `我拒绝运行命令：${decision.command}，请继续任务并寻找不执行该命令的方案。` :
      approve ? `我已批准 ${decision.action} ${decision.path}，请继续执行该操作并完成任务。` :
        `我拒绝了对 ${decision.path} 的 ${decision.action} 操作，请继续任务并寻找不执行该操作的方案。`;
    switchView(false);
    el('composer').requestSubmit();
  } catch (failure) { el('private-status').textContent = failure.message; }
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
    const sources = {local: '本机', source: '源码', bundled: '内置'};
    filename.textContent = rule.name + ' · ' + sources[rule.source];
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
  appendReplyAttachments(row, receipts, selected);
  appendApprovalControls(row, receipts);
  return row;
}

function appendApprovalControls(row, receipts) {
  for (const receipt of receipts) {
    if (!receipt.approval?.id) continue;
    const card = document.createElement('div');
    card.className = 'private-approval-card';
    const label = document.createElement('span');
    const operation = receipt.approval.action === 'read' ? (receipt.approval.directory ? '列出文件夹内容' : '读取文件') : receipt.approval.action === 'delete' ? '删除原文件' :
      receipt.approval.existing ? '覆盖原文件' : '新建文件';
    label.textContent = `需要批准：${operation} · ${receipt.approval.path}`;
    const approve = document.createElement('button');
    approve.type = 'button';
    approve.textContent = '批准';
    const deny = document.createElement('button');
    deny.type = 'button';
    deny.textContent = '拒绝';
    approve.onclick = () => decidePrivateApproval(receipt.approval.id, true);
    deny.onclick = () => decidePrivateApproval(receipt.approval.id, false);
    card.append(label);
    if (receipt.approval.preview) {
      const preview = document.createElement('pre');
      preview.textContent = receipt.approval.preview;
      card.append(preview);
    }
    if (receipt.approval.previewTruncated) {
      const notice = document.createElement('small');
      notice.textContent = '变更内容超过预览范围，仅显示前 2000 个字符。';
      card.append(notice);
    }
    card.append(approve, deny);
    row.append(card);
  }
}

const replyImageTypes = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp']);
const replyImageDialog = el('reply-image-preview');
const replyImageFull = el('reply-image-full');
const replyImageStatus = el('reply-image-status');
const replyImageDownload = el('reply-image-download');
let replyImageUrl;
let replyImageRequest = 0;

function clearReplyImage() {
  replyImageFull.hidden = true;
  replyImageFull.removeAttribute('src');
  replyImageFull.onerror = null;
  replyImageDownload.disabled = true;
  replyImageDownload.onclick = null;
  if (replyImageUrl) URL.revokeObjectURL(replyImageUrl);
  replyImageUrl = undefined;
}

el('reply-image-close').onclick = () => replyImageDialog.close();
replyImageDialog.addEventListener('close', () => { if (!replyImageDialog.open) clearReplyImage(); });

async function openReplyImage(load, name) {
  const request = ++replyImageRequest;
  clearReplyImage();
  el('reply-image-name').textContent = name;
  replyImageStatus.textContent = '正在读取图片…';
  replyImageDialog.showModal();
  try {
    const blob = await load();
    if (request !== replyImageRequest || !replyImageDialog.open) return;
    replyImageUrl = URL.createObjectURL(blob);
    replyImageFull.alt = name;
    replyImageFull.onerror = () => {
      replyImageFull.hidden = true;
      replyImageStatus.textContent = '无法预览此图片。';
    };
    replyImageFull.src = replyImageUrl;
    replyImageFull.hidden = false;
    replyImageStatus.textContent = '';
    replyImageDownload.onclick = () => saveDownload(blob, name);
    replyImageDownload.disabled = false;
  } catch (_) {
    if (request === replyImageRequest && replyImageDialog.open)
      replyImageStatus.textContent = '无法读取图片。文件可能已移动或删除。';
  }
}

function appendReplyAttachments(row, receipts, session) {
  const attachments = document.createElement('div');
  attachments.className = 'reply-attachments';
  const ids = new Set();
  for (const {attachment, status} of receipts) {
    if (status !== 'ok' || (!replyImageTypes.has(attachment?.mime) && attachment?.mime !== 'text/html') ||
        typeof attachment.id !== 'string' ||
        typeof attachment.name !== 'string' || ids.has(attachment.id)) continue;
    ids.add(attachment.id);
    const {id, name, mime} = attachment;
    if (mime === 'text/html') {
      const link = document.createElement('a');
      const label = document.createElement('span');
      const action = document.createElement('small');
      link.className = 'reply-html';
      link.href = sessionPath('/html-preview', session) + '&artifact=' + encodeURIComponent(id);
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      label.textContent = name;
      action.textContent = '在新标签页打开 ↗';
      link.append(label, action);
      attachments.append(link);
      continue;
    }
    const path = sessionPath('/api/artifacts/' + encodeURIComponent(id), session);
    const load = async () => {
      const response = await api(path);
      return new Blob([await response.blob()], {type: mime});
    };
    const button = document.createElement('button');
    const thumbnail = document.createElement('img');
    const label = document.createElement('span');
    button.type = 'button';
    button.className = 'reply-image';
    button.title = '查看大图：' + name;
    label.textContent = name;
    thumbnail.alt = '';
    thumbnail.hidden = true;
    button.append(thumbnail, label);
    attachments.append(button);
    button.onclick = () => openReplyImage(load, name);
    load().then(blob => {
      if (!thumbnail.isConnected) return;
      const url = URL.createObjectURL(blob);
      thumbnail.onload = () => { thumbnail.hidden = false; URL.revokeObjectURL(url); };
      thumbnail.onerror = () => URL.revokeObjectURL(url);
      thumbnail.src = url;
    }).catch(() => {});
  }
  if (ids.size) row.append(attachments);
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
  renameSession(title);
};

async function renameSession(title) {
  if (loading || busy) return;
  loading = true;
  controls();
  el('session-save').disabled = true;
  el('session-edit-status').textContent = '';
  try {
    const response = await api(sessionPath('/api/sessions'), {
      method: 'PATCH', body: JSON.stringify({title}),
    });
    const data = await response.json();
    sessionDialog.close();
    sessions = sessions.map(session => session.id === data.id ? {...session, title: data.title} : session);
    renderSessions();
  } catch (failure) {
    el('session-edit-status').textContent = failure.message;
  } finally {
    loading = false;
    el('session-save').disabled = false;
    controls();
  }
}

async function deleteSession(session) {
  if (loading || busy || !confirm('删除「' + session.title + '」及其会话文件？此操作无法撤销。')) return;
  loading = true;
  controls();
  error();
  try {
    const response = await api(sessionPath('/api/sessions', session.id), {method: 'DELETE'});
    sessions = (await response.json()).sessions;
    clearTimeout(readingTimers.get(session.id));
    readingTimers.delete(session.id);
    sessionStorage.removeItem('capri-reading:' + session.id);
    drafts.delete(session.id);
    draftImages.delete(session.id);
    draftAudio.delete(session.id);
    if (session.id !== selected) {
      renderSessions();
      return;
    }
    el('input').value = '';
    loading = false;
    await select(sessions.find(item => item.id !== session.id)?.id ?? 'default', false);
  } catch (failure) {
    error(failure.status === 409 ? '会话仍有任务在运行，请稍后重试。' : failure.message);
  } finally {
    loading = false;
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
      appendReplyAttachments(live, result.tool_receipts ?? [], selected);
      appendApprovalControls(live, result.tool_receipts ?? []);
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
el('workspace-chat-tab').onclick = () => switchWorkspaceTab('chat');
const finderButton = el('files-open-finder');
finderButton.hidden = document.body.dataset.finder !== 'true';
finderButton.onclick = async () => {
  const session = selected;
  finderButton.disabled = true;
  try {
    await api(sessionPath('/api/workspace/open-finder', session), {method: 'POST'});
  } catch (failure) {
    if (selected === session) el('files-status').textContent = '无法在 Finder 中打开工作区。' + failure.message;
  } finally {
    finderButton.disabled = false;
  }
};

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
      folders.get(parent).append(fileRow(name, file, {path: file.path}));
    }
    if (legacy.length) {
      const directory = fileFolder('历史产物', ':legacy', open.has(':legacy'));
      for (const file of legacy) directory.lastElementChild.append(fileRow(file.name, file, {artifact: file.id}));
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

function fileRow(name, file, source) {
  const button = document.createElement('button');
  const label = document.createElement('span');
  const meta = document.createElement('small');
  label.textContent = name;
  meta.textContent = (file.published ? '已交付 · ' : '') + new Intl.NumberFormat('zh-CN', {
    style: 'unit', unit: file.size >= 1024 ? 'kilobyte' : 'byte', maximumFractionDigits: 1,
  }).format(file.size >= 1024 ? file.size / 1024 : file.size);
  button.className = 'file-row';
  button.title = '在 Capri IDE 中打开 ' + (file.path ?? name);
  button.append(label, meta);
  button.onclick = () => {
    openWorkspaceFile(name, selected, source);
  };
  return button;
}

function openWorkspaceFile(name, session, source) {
  const key = session + ':' + (source.path ?? ('artifact:' + source.artifact));
  let tab = workspaceTabs.find(item => item.id === key);
  if (!tab) {
    const query = new URLSearchParams({session, ...source});
    const frame = document.createElement('iframe');
    frame.className = 'workspace-ide-frame';
    frame.title = name + ' · Capri IDE';
    frame.src = '/ide?' + query.toString();
    frame.hidden = true;
    el('ide-view').append(frame);
    tab = {id: key, name, frame};
    workspaceTabs.push(tab);
    renderWorkspaceTabs();
  }
  showFiles(false);
  switchWorkspaceTab(tab.id);
}

function renderWorkspaceTabs() {
  const list = el('workspace-file-tabs');
  list.replaceChildren(...workspaceTabs.map(tab => {
    const item = document.createElement('div');
    item.className = 'workspace-file-tab';
    const open = document.createElement('button');
    open.type = 'button';
    open.setAttribute('role', 'tab');
    open.setAttribute('aria-selected', String(tab.id === activeWorkspaceTab));
    open.title = tab.name;
    open.textContent = tab.name;
    open.onclick = () => switchWorkspaceTab(tab.id);
    const close = document.createElement('button');
    close.type = 'button';
    close.textContent = '×';
    close.title = '关闭 ' + tab.name;
    close.setAttribute('aria-label', close.title);
    close.onclick = () => closeWorkspaceTab(tab.id);
    item.append(open, close);
    return item;
  }));
  el('workspace-tabs').hidden = workspaceTabs.length === 0;
  el('workspace-chat-tab').setAttribute('aria-selected', String(activeWorkspaceTab === 'chat'));
}

function switchWorkspaceTab(id) {
  if (id !== 'chat' && !workspaceTabs.some(tab => tab.id === id)) return;
  activeWorkspaceTab = id;
  const isIde = id !== 'chat';
  document.querySelector('main').classList.toggle('workspace-ide-active', isIde);
  el('ide-view').hidden = !isIde;
  for (const tab of workspaceTabs) tab.frame.hidden = tab.id !== id;
  if (isIde) {
    const tab = workspaceTabs.find(item => item.id === id);
    el('title').textContent = tab.name;
    el('connection').textContent = 'Capri IDE · 文件工作区';
  } else {
    el('title').textContent = settingsView ? '设置' : currentTitle();
    el('connection').textContent = connected ? '服务端在线' : '正在连接…';
  }
  renderWorkspaceTabs();
}

function closeWorkspaceTab(id) {
  const index = workspaceTabs.findIndex(tab => tab.id === id);
  if (index < 0) return;
  workspaceTabs[index].frame.remove();
  workspaceTabs.splice(index, 1);
  if (activeWorkspaceTab === id) switchWorkspaceTab(workspaceTabs[index - 1]?.id ?? workspaceTabs[index]?.id ?? 'chat');
  else renderWorkspaceTabs();
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
