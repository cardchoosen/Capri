#!/usr/bin/env python3
"""Selected vivo page drives synthetic requests to its owner's existing external HTTP server.
Opaque cross-origin fetches are corroborated by selected server-log records, not treated as HTTP-status proof.
"""
import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import TCPServer
import json
from pathlib import Path
import time
from urllib.parse import urlsplit, parse_qs


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--token',required=True);p.add_argument('--output',required=True,type=Path)
    args=p.parse_args();assert len(args.token)==16 and all(x in '0123456789abcdef' for x in args.token)
    args.output.mkdir(parents=True,exist_ok=True)
    if (args.output/'events.jsonl').exists():raise RuntimeError('Use a fresh directory')
    route='/capri-gateway-driver/'+args.token
    remote='http://124.220.51.203/capri-gateway/'+args.token+'/probe'
    def event(kind,**fields):
        row={'time':datetime.now(timezone.utc).isoformat(),'event':kind,**fields}
        with (args.output/'events.jsonl').open('a') as stream:stream.write(json.dumps(row)+'\n')
        if kind!='telemetry':print(json.dumps(row),flush=True)
    class Handler(BaseHTTPRequestHandler):
        def setup(self):super().setup();self.connection.settimeout(2)
        def log_message(self,*args):pass
        def reply(self,status,body,ctype='text/plain; charset=utf-8',head=False):
            self.send_response(status);self.send_header('Content-Type',ctype);self.send_header('Content-Length',str(len(body)))
            self.send_header('Cache-Control','no-store');self.send_header('Connection','close');self.send_header('Referrer-Policy','no-referrer')
            self.send_header('Content-Security-Policy',"default-src 'none'; script-src 'unsafe-inline'; connect-src 'self' http://124.220.51.203")
            self.end_headers()
            if not head:self.wfile.write(body)
        def do_HEAD(self):
            if self.client_address[0] in {'192.168.1.3','192.168.1.6'} and urlsplit(self.path).path=='/_driver_health':
                return self.reply(200,b'',head=True)
            self.reply(403,b'',head=True)
        def do_GET(self):
            parsed=urlsplit(self.path)
            if self.client_address[0] in {'192.168.1.3','192.168.1.6'} and parsed.path=='/_driver_health':
                return self.reply(200,json.dumps({'ready':True,'entry':'/start','route':route}).encode(),'application/json')
            if self.client_address[0]!='192.168.1.8':return self.reply(403,b'Not selected test device.')
            if parsed.path in {'/','/start','/start/',route+'/'}:
                self.send_response(302);self.send_header('Location',route);self.send_header('Content-Length','0');self.send_header('Cache-Control','no-store');self.send_header('Connection','close');self.end_headers()
                event('entry_redirect',category='canonical_slash' if parsed.path==route+'/' else 'short_entry')
                return
            if parsed.path==route:
                page='''<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Capri 外网路径验证</title><h2>外网路径验证</h2><p>每秒访问你指定的服务器，不读取账号信息。请保持前台。</p><p id="s">检查中…</p><script>
let seq=0,failures=0;async function tick(){let a=new AbortController(),t=setTimeout(()=>a.abort(),1800);try{await fetch(REMOTE+'?seq='+ ++seq,{mode:'no-cors',credentials:'omit',referrerPolicy:'no-referrer',cache:'no-store',signal:a.signal});document.getElementById('s').textContent='外网请求完成 · '+seq+' 次 · '+(document.hidden?'后台':'前台');}catch(e){failures++;document.getElementById('s').textContent='重试 · '+failures;}finally{clearTimeout(t);let q=new URLSearchParams({seq:String(seq),failures:String(failures),visibility:document.visibilityState});await fetch(location.pathname+'/telemetry?'+q,{cache:'no-store'}).catch(()=>{});if(seq<600)setTimeout(tick,1000);}}tick();</script>'''.replace('REMOTE',json.dumps(remote))
                event('page');return self.reply(200,page.encode(),'text/html; charset=utf-8')
            if parsed.path!=route+'/telemetry':
                event('entry_mismatch',category='old_driver_path' if parsed.path.startswith('/capri-gateway-driver/') else 'other')
                return self.reply(404,('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><p>测试入口已更新。</p><p><a href="'+route+'">点此进入当前外网测试页</a></p>').encode(),'text/html; charset=utf-8')
            query=parse_qs(parsed.query,max_num_fields=3)
            def counter(name):
                value=query.get(name,['0'])[0];return int(value) if value.isascii() and value.isdecimal() and len(value)<=6 else 0
            visibility=query.get('visibility',['unknown'])[0]
            if visibility not in ('visible','hidden'):visibility='unknown'
            event('telemetry',sequence=counter('seq'),failures=counter('failures'),visibility=visibility)
            self.reply(200,b'OK')
    class LocalServer(HTTPServer):
        def server_bind(self):
            TCPServer.server_bind(self)
            self.server_name='192.168.1.3';self.server_port=self.server_address[1]
    server=LocalServer(('192.168.1.3',18765),Handler);server.timeout=.5
    event('ready',url='http://192.168.1.3:18765'+route,external_server='124.220.51.203:80',lifetime_seconds=900)
    deadline=time.monotonic()+900
    try:
        while time.monotonic()<deadline and not (args.output/'stop').exists():
            try:server.handle_request()
            except (ConnectionError,TimeoutError):pass
    finally:server.server_close();event('stopped')


if __name__=='__main__':main()
