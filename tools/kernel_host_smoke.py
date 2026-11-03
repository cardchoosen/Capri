#!/usr/bin/env python3
"""Run one isolated headless-kernel smoke case over real USB/Web transport and a localhost model.

Requires the current debug and test APKs installed. --screen-off explicitly permits a brief screen-off check.
Never sends a message to a user chat, uses a real model account, or enables a proxy/VPN.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid
from capri_web import Bridge


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--adb',default='adb')
    parser.add_argument('--screen-off',action='store_true',required=True)
    args=parser.parse_args()
    prefix=[args.adb,'-s',args.serial]
    def shell(*parts):return subprocess.run(prefix+['shell',*parts],capture_output=True,text=True,timeout=15,check=True).stdout
    out=Path(__file__).resolve().parents[1]/'work/kernel-independence';out.mkdir(parents=True,exist_ok=True)
    result={};bridge=Bridge(args.adb,args.serial)
    state=bridge.call('web_state',{})
    if any(s.get('busy') for s in state.get('sessions',[])):
        bridge.close();raise RuntimeError('A user task is active; do not interrupt it to run this fixture.')
    with (out/'device.log').open('w') as log:
        proc=subprocess.Popen(prefix+['shell','am','instrument','-w','-r','-e','class','ai.capri.app.KernelHostDeviceTest#headlessStandbyAdmitsScreenOffWebTask','ai.capri.app.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+50;fixture=None
            while time.monotonic()<deadline:
                raw=subprocess.run(prefix+['shell','run-as','ai.capri.app','cat','cache/kernel-fixture-ready.json'],capture_output=True,text=True,timeout=5)
                if raw.returncode==0:
                    fixture=json.loads(raw.stdout);break
                if proc.poll() is not None:raise RuntimeError('Native fixture exited before readiness; inspect device.log.')
                time.sleep(.1)
            if fixture is None:raise RuntimeError('Native fixture readiness timed out.')
            state=bridge.call('web_state',{'chat_id':fixture['chat']})
            assert not state['busy']
            shell('input','keyevent','KEYCODE_SLEEP')
            request_id=str(uuid.uuid4())
            bridge.call('send',{'chat_id':fixture['chat'],'epoch':state['epoch'],'request_id':request_id,'from_context':state['context_id'],'history_revision':state['history_revision'],'text':'Return only JSON: {"kernel_fixture":"ok"}.'})
            time.sleep(7)
            status=bridge.call('status',{'request_id':request_id})
            assert status['running'],'Task stopped while the local model response was intentionally held.'
            policy=shell('dumpsys','netpolicy')
            result['network_policy']=next((x.strip() for x in policy.splitlines() if 'UID=10314 state=' in x),'UID not found')
            result['screen_off_running']=True
            shell('run-as','ai.capri.app','touch','cache/kernel-fixture-release')
            proc.wait(timeout=35)
            log.flush()
            text=(out/'device.log').read_text()
            assert 'OK (1 test)' in text and 'FAILURES!!!' not in text,text[-2500:]
            result['native_case_passed']=True
            (out/'screen-off-report.json').write_text(json.dumps(result,indent=2)+'\n')
            print('PASS: headless standby, real authenticated Web task admission, screen-off execution, one localhost inference and CPU lease release.')
        finally:
            bridge.close()
            subprocess.run(prefix+['shell','run-as','ai.capri.app','touch','cache/kernel-fixture-release'],capture_output=True,timeout=5)
            subprocess.run(prefix+['shell','input','keyevent','KEYCODE_WAKEUP'],capture_output=True,timeout=5)
            if proc.poll() is None:
                try:proc.wait(timeout=10)
                except subprocess.TimeoutExpired:proc.terminate();proc.wait(timeout=5)


if __name__=='__main__':main()
