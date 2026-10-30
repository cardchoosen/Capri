"""Bounded Python syntax + optional pylsp diagnostics. Never executes the inspected source."""
import ast
import importlib.util
import json
import os
import resource
from pathlib import Path
import selectors
import subprocess
import sys
import time


def finding(text, line, column, message, source, code, severity='error'):
    lines = text.splitlines()
    return dict(line=max(1, line), column=max(1, column), message=str(message)[:800], source=source,
                code=str(code), severity=severity, anchor=lines[line-1].strip()[:300] if 0 < line <= len(lines) else '')


def syntax(text, present=True):
    if not present:
        return dict(status='checked', diagnostics=[])
    try:
        ast.parse(text)
        return dict(status='checked', diagnostics=[])
    except SyntaxError as error:
        return dict(status='checked', diagnostics=[finding(text, error.lineno or 1, error.offset or 1,
                    error.msg, 'python-ast', 'syntax_error')])
    except (MemoryError, RecursionError, ValueError):
        return dict(status='unavailable', diagnostics=[], reason='parser_limit')


def lsp(text, path, present=True, workspace='/workspace'):
    if not present:
        return dict(status='checked', diagnostics=[])
    if importlib.util.find_spec('pylsp') is None or importlib.util.find_spec('pyflakes') is None:
        return dict(status='unavailable', diagnostics=[], reason='pylsp_not_installed')
    process = subprocess.Popen([sys.executable, '-m', 'pylsp'], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=workspace)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ, 'out')
    selector.register(process.stderr, selectors.EVENT_READ, 'err')
    buffer = bytearray()
    deadline = time.monotonic() + 10
    uri = Path(workspace, path).absolute().as_uri()

    def send(method, params=None, ident=None):
        packet = dict(jsonrpc='2.0', method=method)
        if params is not None:
            packet['params'] = params
        if ident is not None:
            packet['id'] = ident
        body = json.dumps(packet).encode()
        process.stdin.write(('Content-Length: %d\r\n\r\n' % len(body)).encode() + body)
        process.stdin.flush()

    def receive():
        while time.monotonic() < deadline:
            if b'\r\n\r\n' in buffer:
                header, body = bytes(buffer).split(b'\r\n\r\n', 1)
                length = next((int(line.split(b':', 1)[1].strip()) for line in header.split(b'\r\n')
                               if line.lower().startswith(b'content-length:')), -1)
                if length < 0 or length > 1000000:
                    raise ValueError('Invalid LSP frame length')
                if len(body) >= length:
                    payload = json.loads(body[:length]);del buffer[:len(header)+4+length]
                    return payload
            for key, _ in selector.select(.1):
                data = os.read(key.fileobj.fileno(), 8192)
                if not data:
                    selector.unregister(key.fileobj)
                    if key.data == 'out':
                        raise RuntimeError('LSP server exited before diagnostics')
                elif key.data == 'out':
                    buffer.extend(data)
                    if len(buffer) > 1010000:
                        raise ValueError('LSP buffer limit')
            if process.poll() is not None:
                raise RuntimeError('LSP server exited')
        raise TimeoutError('LSP diagnostics deadline')

    def reply_to_request(message):
        if 'id' not in message or 'method' not in message:
            return False
        reply=dict(jsonrpc='2.0', id=message['id'])
        if message['method']=='workspace/configuration':
            reply['result']=[{} for _ in message.get('params', {}).get('items', [])]
        else:
            reply['error']=dict(code=-32601, message='Client method is not supported')
        body=json.dumps(reply).encode()
        process.stdin.write(('Content-Length: %d\r\n\r\n'%len(body)).encode()+body)
        process.stdin.flush()
        return True

    try:
        send('initialize', dict(processId=os.getpid(), rootUri=Path(workspace).absolute().as_uri(),
             capabilities=dict(textDocument=dict(publishDiagnostics=dict(versionSupport=True)))), 1)
        while True:
            message = receive()
            if reply_to_request(message):
                continue
            if message.get('id') == 1 and 'method' not in message:
                if 'error' in message:
                    raise RuntimeError('LSP initialization failed')
                break
        send('initialized', {})
        send('workspace/didChangeConfiguration', dict(settings=dict(pylsp=dict(configurationSources=[], plugins=dict(
            pyflakes=dict(enabled=True), pycodestyle=dict(enabled=False), mccabe=dict(enabled=False),
            flake8=dict(enabled=False), pylint=dict(enabled=False), pydocstyle=dict(enabled=False))))))
        send('textDocument/didOpen', dict(textDocument=dict(uri=uri, languageId='python', version=1, text=text)))
        while True:
            message = receive()
            # Reply only to harmless configuration/capability requests; never apply workspace edits or commands.
            if reply_to_request(message):
                continue
            params=message.get('params', {})
            if message.get('method') != 'textDocument/publishDiagnostics' or params.get('uri') != uri:
                continue
            if params.get('version', 1) != 1:
                continue
            findings=[]
            for item in params.get('diagnostics', [])[:200]:
                start=item.get('range', {}).get('start', {});line=int(start.get('line', 0))+1
                lines=text.splitlines();source_line=lines[line-1] if 0 < line <= len(lines) else ''
                # LSP UTF-16 coordinates -> 1-based Unicode code point columns.
                column=len(source_line.encode('utf-16-le')[:int(start.get('character', 0))*2].decode('utf-16-le', errors='ignore'))+1
                findings.append(finding(text, line, column, item.get('message', ''), item.get('source', 'pylsp'),
                                item.get('code', 'diagnostic'), {1:'error', 2:'warning', 3:'information', 4:'hint'}.get(item.get('severity'), 'unknown')))
            return dict(status='checked', diagnostics=findings, truncated=len(params.get('diagnostics', []))>200)
    except (RuntimeError, ValueError, TimeoutError, OSError) as error:
        return dict(status='unavailable', diagnostics=[], reason=type(error).__name__)
    finally:
        selector.close()
        try:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=2)
        finally:
            for stream in (process.stdin, process.stdout, process.stderr):
                stream.close()


def main():
    resource.setrlimit(resource.RLIMIT_AS, (512*1024*1024, 512*1024*1024))
    request=json.loads(Path('/run/capri/input.json').read_text())
    before,after=request['before'],request['after']
    result=dict(status='ok',syntax_before=syntax(before, request['before_present']),syntax_after=syntax(after, request['after_present']))
    if not request.get('before_known', True):
        result['syntax_before']=dict(status='unavailable', diagnostics=[], reason='baseline_not_recorded')
    if request.get('use_lsp'):
        # Fresh server instances prevent delayed unversioned baseline notifications becoming current findings.
        result.update(lsp_before=lsp(before, request['path'],request['before_present']),lsp_after=lsp(after, request['path'],request['after_present']))
        if not request.get('before_known', True):
            result['lsp_before']=dict(status='unavailable', diagnostics=[], reason='baseline_not_recorded')
    print(json.dumps(result, ensure_ascii=True))


if __name__ == '__main__':
    main()
