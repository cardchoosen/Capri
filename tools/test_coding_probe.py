"""Host-only protocol checks; the optional real-server case uses an explicitly prepared venv."""
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SOURCE=pathlib.Path(__file__).resolve().parents[1]/'app/src/main/assets/runtime/coding_probe.py'
spec=importlib.util.spec_from_file_location('capri_coding_probe',SOURCE)
probe=importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

SERVER=r'''
import json,sys,time

def receive():
    headers={}
    while True:
        line=sys.stdin.buffer.readline()
        if line==b'\r\n':break
        name,value=line.decode().split(':',1);headers[name.lower()]=value.strip()
    return json.loads(sys.stdin.buffer.read(int(headers['content-length'])))
def send(value):
    raw=json.dumps(value).encode();sys.stdout.buffer.write(('Content-Length: %d\r\n\r\n'%len(raw)).encode()+raw);sys.stdout.buffer.flush()
request=receive();send({'jsonrpc':'2.0','id':1,'method':'window/showMessageRequest','params':{}})
reply=receive();assert reply['id']==1 and reply['error']['code']==-32601
send({'jsonrpc':'2.0','id':request['id'],'result':{'capabilities':{}}})
while True:
    request=receive()
    if request.get('method')=='textDocument/didOpen':break
uri=request['params']['textDocument']['uri']
send({'jsonrpc':'2.0','id':42,'method':'workspace/applyEdit','params':{'edit':{'changes':{uri:[]}}}})
reply=receive()
assert reply['id']==42 and reply['error']['code']==-32601
send({'jsonrpc':'2.0','method':'textDocument/publishDiagnostics','params':{'uri':uri,'version':1,'diagnostics':[{'source':'stub','message':'side effects rejected','severity':1,'range':{'start':{'line':0,'character':3},'end':{'line':0,'character':4}}}]}})
time.sleep(5)
'''

class CodingProbeTest(unittest.TestCase):
    def test_syntax_never_executes_source(self):
        self.assertEqual([],probe.syntax("raise RuntimeError('must not execute')")['diagnostics'])
        self.assertEqual(1,len(probe.syntax('def broken(:\n pass')['diagnostics']))

    def test_missing_server_is_explicit(self):
        with mock.patch.object(probe.importlib.util,'find_spec',return_value=None):
            report=probe.lsp('value=1','a.py')
        self.assertEqual('unavailable',report['status'])
        self.assertEqual('pylsp_not_installed',report['reason'])

    def test_protocol_rejects_edits_and_converts_utf16_columns(self):
        original=subprocess.Popen
        with tempfile.TemporaryDirectory() as folder:
            path=pathlib.Path(folder,'space name.py');path.write_text('safe')
            with mock.patch.object(probe.importlib.util,'find_spec',return_value=object()),mock.patch.object(probe.subprocess,'Popen',side_effect=lambda *a,**kw:original([sys.executable,'-u','-c',SERVER],**kw)):
                report=probe.lsp('😀 missing','space name.py',workspace=folder)
            self.assertEqual('checked',report['status'],report)
            self.assertEqual(3,report['diagnostics'][0]['column'])
            self.assertEqual('safe',path.read_text())

    @unittest.skipUnless(importlib.util.find_spec('pylsp') and importlib.util.find_spec('pyflakes'),'Requires the explicitly prepared python-lsp-server[pyflakes] fixture environment')
    def test_real_server_reports_old_and_new_undefined_names(self):
        with tempfile.TemporaryDirectory() as folder:
            before=probe.lsp('print(existing_missing)\n','sample.py',workspace=folder)
            after=probe.lsp('# shifted\nprint(existing_missing)\nprint(new_missing)\n','sample.py',workspace=folder)
        self.assertEqual('checked',before['status'],before)
        self.assertEqual('checked',after['status'],after)
        self.assertEqual(1,len(before['diagnostics']))
        self.assertEqual(2,len(after['diagnostics']))
        self.assertEqual('print(existing_missing)',after['diagnostics'][0]['anchor'])
        self.assertTrue(any('new_missing' in finding['message'] for finding in after['diagnostics']))

if __name__=='__main__':unittest.main()
