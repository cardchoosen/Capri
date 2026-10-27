#!/usr/bin/env python3
"""Verify bounded synthetic HTTP cases through the ordinary-UID Pixel engine. No network-rule changes."""
import argparse
import json
from pathlib import Path
from proxy_runtime import ProxyRuntime


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    runtime = ProxyRuntime(args.adb, args.serial)
    code = '''import gzip,json,urllib.request,urllib.error
base='http://192.168.1.6:18766/capri-lab/c82aa79e80e7edb2/protocol/'
o=urllib.request.build_opener(urllib.request.ProxyHandler({}))
payload=bytes(range(256))*4
rows=[]
def read(case,method='GET',data=None):
 req=urllib.request.Request(base+case,data=data,method=method,headers={'Host':'outside.invalid'})
 try:r=o.open(req,timeout=5)
 except urllib.error.HTTPError as e:r=e
 with r:
  assert r.headers.get('X-Capri-Lab-Proxy')=='Pixel'
  return r.status,r.read(8192),r.headers
for case in ('json','binary','gzip','redirect','status','chunked','head','echo'):
 status,body,headers=read(case,'HEAD' if case=='head' else ('POST' if case=='echo' else 'GET'),payload if case=='echo' else None)
 assert status==(503 if case=='status' else 200)
 if case in ('binary','chunked','echo'):assert body==payload
 elif case=='gzip':assert headers['Content-Encoding']=='gzip' and gzip.decompress(body)==payload
 elif case=='head':assert not body and int(headers['Content-Length'])>0
 elif case in ('json','redirect'):assert json.loads(body)=={'fixture':True}
 else:assert body==b'fixture unavailable'
 rows.append({'case':case,'status':status,'bytes':len(body),'passed':True})
print(json.dumps(rows))
'''
    result = runtime.run(['/lab/venv/bin/python', '-c', code], timeout=65)
    args.output.mkdir(parents=True, exist_ok=True)
    report = {'network_rules_changed': False, 'exit_code': result.returncode, 'passed': result.returncode == 0}
    if result.returncode == 0:
        report['cases'] = json.loads(result.stdout)
    else:
        report['diagnostic'] = result.stderr[-1800:]
    (args.output/'report.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report), flush=True)
    result.check_returncode()


if __name__ == '__main__':
    main()
