'use strict';

const params = new URLSearchParams(location.search);
const status = document.getElementById('preview-status');
const frame = document.getElementById('preview-frame');
const download = document.getElementById('preview-download');

async function showPreview() {
  const session = params.get('session');
  const artifact = params.get('artifact');
  if (!session || !artifact) {
    status.textContent = '缺少会话或附件。';
    return;
  }
  const response = await fetch('/api/artifacts/' + encodeURIComponent(artifact) + '?session=' + encodeURIComponent(session),
    {headers: {'X-Capri-Chat': '1'}});
  if (!response.ok) throw new Error('Attachment unavailable.');
  const encodedName = /filename\*=UTF-8''([^;]+)/i.exec(response.headers.get('Content-Disposition') ?? '')?.[1];
  const name = encodedName ? decodeURIComponent(encodedName) : '';
  if (!/\.html?$/i.test(name)) throw new Error('Attachment is not HTML.');
  const file = await response.blob();
  const policy = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; base-uri 'none'; form-action 'none'";
  frame.srcdoc = '<!doctype html><meta http-equiv="Content-Security-Policy" content="' + policy + '">' +
    '<meta name="referrer" content="no-referrer">' + await file.text();
  document.title = name + ' · Capri';
  document.getElementById('preview-name').textContent = name;
  download.onclick = () => {
    const url = URL.createObjectURL(file);
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  download.disabled = false;
  frame.hidden = false;
  status.hidden = true;
}

showPreview().catch(() => {
  status.textContent = '无法打开 HTML 附件。请检查文件是否仍在工作区，以及 Capri 是否已连接。';
});
