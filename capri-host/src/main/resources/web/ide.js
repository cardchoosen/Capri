'use strict';

const $ = id => document.getElementById(id);
const query = new URLSearchParams(location.search);
const session = query.get('session') || 'default';
const path = query.get('path');
const artifact = query.get('artifact');
const headers = {'X-Capri-Chat': '1'};
let file;
let previewUrl;
let showPreview = false;

function endpoint(route, values = {}) {
  const params = new URLSearchParams({session, ...values});
  return route + '?' + params.toString();
}

async function request(url, options = {}) {
  const response = await fetch(url, {...options, headers: {...headers, ...options.headers}});
  if (response.status === 401) throw new Error('连接已失效，请返回 Capri 页面重新连接。');
  if (!response.ok) {
    if (response.status === 409) throw new Error('文件在其他位置发生了修改。请重新载入后再保存。');
    throw new Error('文件操作失败（HTTP ' + response.status + '）。');
  }
  return response;
}

function setStatus(message, error = false) {
  $('file-status').textContent = message;
  $('file-status').classList.toggle('error', error);
}

function updateLines() {
  const count = $('editor').value.split('\n').length;
  $('line-numbers').textContent = Array.from({length: count}, (_, index) => index + 1).join('\n');
  $('line-numbers').scrollTop = $('editor').scrollTop;
  paintHighlight();
}

function escapeHtml(text) {
  return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
}

function languageFor(name) {
  const ext = name.split('.').pop().toLowerCase();
  const languages = {
    py: 'python', pyw: 'python', js: 'javascript', jsx: 'javascript', mjs: 'javascript', cjs: 'javascript',
    ts: 'javascript', tsx: 'javascript', java: 'java', kt: 'java', kts: 'java', json: 'json', jsonc: 'javascript',
    html: 'html', htm: 'html', xml: 'html', svg: 'html', css: 'css', scss: 'css', less: 'css',
    sh: 'shell', bash: 'shell', zsh: 'shell', yml: 'yaml', yaml: 'yaml', toml: 'yaml', sql: 'sql',
    go: 'go', rs: 'rust', md: 'markdown', markdown: 'markdown',
  };
  return languages[ext] ?? 'plain';
}

const keywordSets = {
  python: 'and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield False None True',
  javascript: 'as async await break case catch class const continue debugger default delete do else export extends false finally for from function get if import in instanceof let new null of return set static super switch this throw true try typeof undefined var void while with yield interface type enum implements public private protected readonly keyof infer never unknown number string boolean',
  java: 'abstract actual annotation as assert boolean break byte case catch char class companion const continue data default do double else enum export extends false final finally float for fun if implements import in instanceof int interface internal is long native new null object open operator override package private protected public return sealed short static super switch synchronized this throw throws transient true try val var void volatile when while',
  json: 'true false null',
  shell: 'case do done elif else esac fi for function if in select then time until while',
  yaml: 'true false null yes no on off',
  sql: 'add all alter and as asc begin between by case cast check column commit constraint create cross current_date current_timestamp database default delete desc distinct drop else end except exists foreign from full grant group having in index inner insert into is join key left like limit not null offset on or order outer primary references right rollback select set table then to union unique update values view when where',
  go: 'break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var',
  rust: 'as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self static struct super trait true type unsafe use where while',
};

function tokenPattern(language) {
  const comments = {
    python: '#[^\\n]*', shell: '#[^\\n]*', yaml: '#[^\\n]*',
    javascript: '\\/\\*[\\s\\S]*?\\*\\/|\\/\\/[^\\n]*',
    java: '\\/\\*[\\s\\S]*?\\*\\/|\\/\\/[^\\n]*',
    css: '\\/\\*[\\s\\S]*?\\*\\/',
    sql: '\\/\\*[\\s\\S]*?\\*\\/|--[^\\n]*',
    go: '\\/\\*[\\s\\S]*?\\*\\/|\\/\\/[^\\n]*',
    rust: '\\/\\*[\\s\\S]*?\\*\\/|\\/\\/[^\\n]*',
    html: '<!--[\\s\\S]*?-->',
  }[language];
  const strings = language === 'python'
    ? "(?:[rRuUbBfF]{0,2})?(?:\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
    : "(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)";
  const markup = language === 'html' ? '<\\/?[A-Za-z][^>]*>' : '(?!)';
  const property = language === 'css' ? '[A-Za-z_-][\\w-]*(?=\\s*:)' : '(?!)';
  const keywords = keywordSets[language];
  const keyword = keywords ? '\\b(?:' + keywords.split(/\s+/).map(word => word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|') + ')\\b' : '(?!)';
  const jsonKey = language === 'json' ? '"(?:\\\\.|[^"\\\\])*"(?=\\s*:)' : '(?!)';
  return new RegExp([
    '(?<comment>' + (comments ?? '(?!)') + ')',
    '(?<markup>' + markup + ')',
    '(?<jsonKey>' + jsonKey + ')',
    '(?<string>' + strings + ')',
    '(?<number>\\b(?:0[xX][\\da-fA-F]+|0[bB][01]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)[nN]?\\b)',
    '(?<keyword>' + keyword + ')',
    '(?<property>' + property + ')',
    '(?<function>[A-Za-z_$][\\w$]*(?=\\s*\\())',
  ].join('|'), 'gm');
}

function paintHighlight() {
  const source = $('editor').value;
  const target = $('highlighted-code');
  const language = languageFor(file?.name ?? '');
  if (source.length > 300_000 || language === 'plain') {
    target.textContent = source;
    target.className = 'syntax-code';
    return;
  }
  if (language === 'markdown') {
    target.innerHTML = source.split('\n').map(line => {
      if (/^\s{0,3}#{1,6}\s/.test(line)) return '<span class="syntax-heading">' + escapeHtml(line) + '</span>';
      if (/^\s*(```|~~~)/.test(line)) return '<span class="syntax-markup">' + escapeHtml(line) + '</span>';
      return escapeHtml(line).replace(/(`[^`]+`)/g, '<span class="syntax-string">$1</span>')
        .replace(/(\*\*[^*]+\*\*)/g, '<span class="syntax-keyword">$1</span>');
    }).join('\n');
    target.className = 'syntax-code language-markdown';
    return;
  }
  const pattern = tokenPattern(language);
  let output = '';
  let cursor = 0;
  for (const match of source.matchAll(pattern)) {
    const index = match.index;
    output += escapeHtml(source.slice(cursor, index));
    const group = Object.keys(match.groups ?? {}).find(key => match.groups[key] !== undefined);
    const cssToken = group === 'jsonKey' ? 'property' : group;
    output += '<span class="syntax-' + cssToken + '">' + escapeHtml(match[0]) + '</span>';
    cursor = index + match[0].length;
  }
  target.innerHTML = output + escapeHtml(source.slice(cursor));
  target.className = 'syntax-code language-' + language;
}

function changed() {
  const dirty = file?.editable && $('editor').value !== file.text;
  $('save-file').disabled = !dirty;
  setStatus(dirty ? '未保存的更改' : file?.editable ? '已保存' : '只读预览');
}

async function load() {
  if (!path && !artifact) {
    setStatus('缺少文件路径', true);
    $('empty-pane').textContent = '请从会话文件栏选择要打开的文件。';
    $('empty-pane').hidden = false;
    return;
  }
  try {
    const source = artifact ? {artifact} : {path};
    file = await (await request(endpoint('/api/workspace/editor', source))).json();
    document.title = file.name + ' · Capri IDE';
    $('file-name').textContent = file.name;
    $('file-path').textContent = file.path || ('会话产物 · ' + file.name);
    $('download-file').disabled = false;
    $('editor-pane').hidden = !file.textual;
    $('editor').readOnly = !file.editable;
    if (file.textual) {
      $('editor').value = file.text;
      updateLines();
    }
    if (!file.textual) await loadPreview(source);
    else if (/\.html?$/i.test(file.name)) {
      $('preview-toggle').hidden = false;
      $('preview-toggle').onclick = () => {
        showPreview = !showPreview;
        $('editor-pane').hidden = showPreview;
        $('preview-pane').hidden = !showPreview;
        $('preview-toggle').textContent = showPreview ? '编辑源码' : '预览';
        if (showPreview) renderHtmlPreview($('editor').value);
      };
    }
    changed();
  } catch (failure) {
    setStatus(failure.message, true);
    $('empty-pane').textContent = failure.message;
    $('empty-pane').hidden = false;
  }
}

async function loadPreview(source) {
  try {
    const response = await request(endpoint('/api/workspace/preview', source));
    const blob = await response.blob();
    previewUrl = URL.createObjectURL(blob);
    $('preview-pane').hidden = false;
    const mime = blob.type;
    if (mime.startsWith('image/')) {
      $('image-preview').src = previewUrl;
      $('image-preview').alt = file.name;
      $('image-preview').hidden = false;
    } else if (mime.startsWith('video/')) {
      $('video-preview').src = previewUrl;
      $('video-preview').hidden = false;
    } else if (mime.startsWith('audio/')) {
      $('audio-preview').src = previewUrl;
      $('audio-preview').hidden = false;
    } else if (mime === 'application/pdf' || mime === 'text/html') {
      $('document-preview').src = previewUrl;
      $('document-preview').hidden = false;
    } else {
      $('preview-message').textContent = '此文件为二进制格式，暂不支持网页预览；可以下载到本地查看。';
      $('preview-message').hidden = false;
    }
  } catch (failure) { setStatus(failure.message, true); }
}

function renderHtmlPreview(content) {
  if (previewUrl) URL.revokeObjectURL(previewUrl);
  previewUrl = URL.createObjectURL(new Blob([content], {type: 'text/html;charset=utf-8'}));
  $('preview-pane').hidden = false;
  $('document-preview').src = previewUrl;
  $('document-preview').hidden = false;
}

$('editor').addEventListener('input', () => { updateLines(); changed(); });
$('editor').addEventListener('scroll', () => {
  $('line-numbers').scrollTop = $('editor').scrollTop;
  $('highlighted-code').scrollTop = $('editor').scrollTop;
  $('highlighted-code').scrollLeft = $('editor').scrollLeft;
});
$('save-file').onclick = async () => {
  $('save-file').disabled = true;
  try {
    const result = await (await request(endpoint('/api/workspace/save'), {
      method: 'PUT', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({path: file.path, content: $('editor').value, revision: file.revision}),
    })).json();
    file.revision = result.revision;
    file.text = $('editor').value;
    setStatus('已保存');
  } catch (failure) { setStatus(failure.message, true); $('save-file').disabled = false; }
};
$('reload-file').onclick = () => location.reload();
$('download-file').onclick = async () => {
  try {
    const source = artifact ? {artifact} : {path};
    const response = await request(endpoint('/api/workspace/file', source));
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement('a');
    link.href = url;
    link.download = file.name;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (failure) { setStatus(failure.message, true); }
};
window.addEventListener('beforeunload', event => {
  if (file?.editable && $('editor').value !== file.text) {
    event.preventDefault();
    event.returnValue = '';
  }
  if (previewUrl) URL.revokeObjectURL(previewUrl);
});
window.addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    if (!$('save-file').disabled) $('save-file').click();
  }
});
load();
