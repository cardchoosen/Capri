#!/usr/bin/env python3
"""Optional 100 MiB real transport smoke. Run LargeUploadDeviceTest concurrently on the selected phone.
Only generated fixture bytes are sent; credentials are never printed and no model request is made.
"""
import sys,json,time,uuid,hashlib,subprocess,urllib.request
from pathlib import Path
import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--port',type=int,default=47863)
parser.add_argument('--state-dir',type=Path,default=Path.home()/'Library/Application Support/CapriWeb/state')
parser.add_argument('--report',type=Path,default=Path('work/large-upload/phone-transfer.json'))
options=parser.parse_args()
origin=f'http://127.0.0.1:{options.port}'
token=(options.state_dir/f'access-{options.port}.token').read_text().strip()
adb=['adb','-s',options.serial]
fixture=None
for _ in range(200):
 r=subprocess.run(adb+['exec-out','run-as','ai.capri.app','cat','cache/large-upload-ready.json'],capture_output=True)
 if r.returncode==0:
  try:fixture=json.loads(r.stdout);break
  except ValueError:pass
 time.sleep(.1)
assert fixture,'No isolated phone fixture'
owner={'chat_id':fixture['chat'],'epoch':fixture['epoch']}
def post(path,data,headers):
 request=urllib.request.Request(origin+path,data=data,headers={'Origin':origin,'Authorization':'Bearer '+token,**headers},method='POST')
 with urllib.request.urlopen(request,timeout=60) as response:result=json.load(response)
 assert 'error' not in result,result
 return result
def rpc(action,payload={},key=None):
 args={**owner,'action':action,'payload':payload}
 if key:args['request_id']=key
 return post('/api/call',json.dumps({'method':'web_runtime','args':args}).encode(),{'Content-Type':'application/json'})
def operation(action,payload):
 key=str(uuid.uuid4());value=rpc(action,payload,key);deadline=time.monotonic()+120
 while value.get('state')=='running' and time.monotonic()<deadline:
  time.sleep(.1);value=rpc('operation',{'id':key})
 assert value.get('state')=='completed',value
 return value['data']['result']
size=100*1024*1024;started=time.monotonic();upload=operation('upload_begin',{'kind':'file','size':size})
data=bytes([31])*1048576;chunkhash=hashlib.sha256(data).hexdigest();digest=hashlib.sha256()
for index in range(100):
 offset=index*len(data);meta={**owner,'id':upload,'offset':offset,'sha256':chunkhash}
 result=post('/api/upload',data,{'Content-Type':'application/octet-stream','X-Capri-Upload':json.dumps(meta)})
 assert result['result']==offset+len(data)
 if index==0:
  duplicate=post('/api/upload',data,{'Content-Type':'application/octet-stream','X-Capri-Upload':json.dumps(meta)})
  assert duplicate['result']==len(data)
  assert rpc('upload_status',{'id':upload})['offset']==len(data)
 digest.update(data)
item=operation('input_commit',{'upload_id':upload,'name':'large-upload-fixture.bin'})
assert item['size']==size and item['sha256']==digest.hexdigest(),item
subprocess.run(adb+['shell','run-as','ai.capri.app','touch','cache/large-upload-complete'],check=True,capture_output=True)
report={'bytes':size,'seconds':round(time.monotonic()-started,3),'sha256':item['sha256'],'chunks':100,'duplicate_chunk':'idempotent','transport':'actual authenticated loopback HTTP to USB phone bridge; isolated chat; no model request'}
options.report.parent.mkdir(parents=True,exist_ok=True)
options.report.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)
