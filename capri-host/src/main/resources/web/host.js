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
const drafts = new Map();

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
  if (!response.ok) throw new Error('服务请求失败（HTTP ' + response.status + '）。');
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
  el('sessions').querySelectorAll('button').forEach(button => { button.disabled = busy || loading; });
}

function renderSessions() {
  el('sessions').replaceChildren(...sessions.map(session => {
    const button = document.createElement('button');
    button.textContent = session.title;
    button.classList.toggle('selected', session.id === selected);
    button.setAttribute('aria-current', session.id === selected ? 'true' : 'false');
    button.onclick = () => select(session.id);
    return button;
  }));
  el('title').textContent = sessions.find(session => session.id === selected)?.title ?? 'Capri 对话';
  controls();
}

function messageRow(role, text) {
  const row = document.createElement('article');
  row.className = role === 'user' ? 'user' : 'assistant';
  const author = document.createElement('label');
  author.textContent = role === 'user' ? '你' : 'CAPRI';
  const content = document.createElement('div');
  if (role === 'user') content.textContent = text;
  else markdown(content, text);
  row.append(author, content);
  return row;
}

function scrollToLatest() {
  el('messages').scrollTop = el('messages').scrollHeight;
}

function renderHistory(messages) {
  el('history').replaceChildren(...messages.map(message => messageRow(message.role, message.content)));
  scrollToLatest();
}

async function loadHistory() {
  const generation = ++selection;
  const response = await api(sessionPath('/api/chat'));
  const {messages} = await response.json();
  if (generation === selection) renderHistory(messages);
}

async function loadSessions() {
  const response = await api('/api/sessions');
  sessions = (await response.json()).sessions;
  if (!sessions.some(session => session.id === selected)) selected = 'default';
  renderSessions();
}

async function select(id) {
  if (busy || loading) return;
  loading = true;
  drafts.set(selected, el('input').value);
  selected = id;
  sessionStorage.setItem('capri-host-session', id);
  el('input').value = drafts.get(id) ?? '';
  el('history').replaceChildren();
  error();
  renderSessions();
  try { await loadHistory(); }
  catch (failure) { error(failure.message); }
  finally { loading = false; controls(); }
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
  busy = true;
  controls();
  error();
  el('input').value = '';
  const pending = messageRow('user', text);
  pending.classList.add('pending');
  el('history').append(pending);
  el('progress').textContent = 'Capri 正在回复…';
  scrollToLatest();
  let completed = false;
  try {
    const response = await api(sessionPath('/api/chat'), {method: 'POST', body: JSON.stringify({text})});
    const result = await response.json();
    if (result.outcome !== 'complete') throw new Error('本轮未完成。');
    completed = true;
    drafts.delete(selected);
    pending.classList.remove('pending');
    el('history').append(messageRow('assistant', result.reply));
    scrollToLatest();
    await loadHistory();
  } catch (failure) {
    if (completed) {
      error('回复已完成，暂时无法刷新会话记录。');
    } else {
      pending.remove();
      el('input').value = text;
      error(failure.message + ' 可先刷新会话，确认后再决定是否重发。');
    }
  } finally {
    el('progress').textContent = '';
    busy = false;
    controls();
  }
};
el('input').onkeydown = event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    if (!el('send').disabled) el('composer').requestSubmit();
  }
};
el('refresh').onclick = () => select(selected);

el('export').onclick = async () => {
  try {
    const response = await api(sessionPath('/api/sessions/export'), {method: 'POST'});
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement('a');
    link.href = url;
    link.download = 'capri-conversation.md';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
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
