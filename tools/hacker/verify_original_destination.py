#!/usr/bin/env python3
"""Verify Linux transparent original-destination lookup under ordinary Capri UID.
Only local test connections with two fixed source ports enter a bounded OUTPUT hook.
No vivo ARP announcements or gateway changes. No application/SSH data access.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import socket
import subprocess
import time
import urllib.request
from proxy_runtime import ProxyRuntime, BASE

ROOT = Path(__file__).resolve().parents[2]
REMOTE = '/data/local/tmp/capri-original-dst-probe'
STATE = '/data/local/tmp/capri-pair-relay'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--ndk',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    if (args.output/'report.json').exists():
        raise RuntimeError('Preserve reports; choose a new directory')
    runtime=ProxyRuntime(args.adb,args.serial)
    engine=fixture=native=None
    pushed=False
    original=None
    report={'vivo_path_changed':False,'gateway_changed':False,'root_engine':False}

    def root(script):
        r=subprocess.run(runtime.adb+['shell','su -c '+shlex.quote(script)],capture_output=True,text=True,timeout=15)
        r.check_returncode()
        return r.stdout

    def exists(path):
        return root('if test -e '+shlex.quote(path)+'; then printf yes; else printf no; fi').strip()=='yes'

    def helper(action):
        return subprocess.run(runtime.adb+['shell','su -c '+shlex.quote(REMOTE+' '+action)],capture_output=True,text=True,timeout=30)

    def baseline():
        return {k:root(v) for k,v in {'nat':'iptables -w 2 -t nat -S','filter':'iptables -w 2 -t filter -S',
            'routes':'ip -4 route show table all','rules':'ip -4 rule show',
            'parameters':'cat /proc/sys/net/ipv4/ip_forward /proc/sys/net/ipv4/conf/wlan0/rp_filter; getenforce'}.items()}

    def ready():
        try:
            o=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with o.open('http://192.168.1.6:18766/_capri_proxy_health',timeout=1) as r:
                return r.status==200 and json.load(r)=={'ready':True}
        except (OSError,ValueError):
            return False

    def upload(name,data):
        command=runtime.adb+['shell','-T','run-as ai.capri.app /system/bin/sh -c '+shlex.quote('cat > '+BASE+'/'+name)]
        subprocess.run(command,input=data,check=True,capture_output=True,timeout=10)

    try:
        if exists(STATE) or exists(REMOTE):
            raise RuntimeError('Existing helper/journal must be investigated')
        for host,port in [('192.168.1.6',18766),('192.168.1.3',18765)]:
            try:
                with socket.create_connection((host,port),timeout=1):
                    raise RuntimeError('Required port occupied; never stop an unknown service')
            except ConnectionRefusedError:
                pass
        uid=runtime.shell(['/system/bin/run-as','ai.capri.app','/system/bin/id','-u'],timeout=8)
        uid.check_returncode();uid=int(uid.stdout.strip());assert uid>=10000
        report['android_uid']=uid
        original=baseline()
        compiler=args.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang'
        binary=args.output/'helper'
        subprocess.run([str(compiler),'-DCAPRI_HTTP_PROXY_TEST','-DCAPRI_ORIGINAL_DST_PROBE',f'-DCAPRI_PROBE_UID="{uid}"','-std=c11','-O2','-Wall','-Wextra','-Werror',str(ROOT/'tools/hacker/pair_relay.c'),'-o',str(binary)],check=True)
        subprocess.run(runtime.adb+['push',str(binary),REMOTE],check=True,capture_output=True);pushed=True
        root('chmod 700 '+REMOTE)
        assert helper('start').returncode==64, 'Local probe build must reject active announcements'
        # Populate only the already-confirmed lab peers required by the shared context checks.
        root('ping -c 1 -W 1 192.168.1.8 >/dev/null; ping -c 1 -W 1 192.168.1.3 >/dev/null')
        upload('transparent-addon.py',Path(__file__).with_name('transparent_http_addon.py').read_bytes())
        runtime.shell(['/system/bin/run-as','ai.capri.app','rm','-f',BASE+'/transparent.stop',BASE+'/transparent-events.jsonl'],timeout=8).check_returncode()
        with (args.output/'fixture.log').open('w') as log:
            fixture=subprocess.Popen(['python3',str(ROOT/'tools/hacker/local_http_fixture.py'),'--bind','192.168.1.3','--target','192.168.1.8','--relay','192.168.1.6','--seconds','120','--token','c82aa79e80e7edb2','--output',str(args.output/'fixture')],stdout=log,stderr=subprocess.STDOUT)
        with (args.output/'engine.log').open('w') as log:
            engine=subprocess.Popen(runtime.command(['/lab/venv/bin/mitmdump','-q','--mode','transparent','--listen-host','0.0.0.0','--listen-port','18766','--set','connection_strategy=lazy','--set','http2=false','--set','block_private=false','--set','block_global=false','--set','confdir=/lab/transparent-conf','-s','/lab/transparent-addon.py']),stdout=log,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+20
        while not ready():
            if engine.poll() is not None or time.monotonic()>deadline:
                raise RuntimeError('Transparent engine failed readiness')
            time.sleep(.2)
        native=subprocess.Popen(runtime.adb+['shell','su -c '+shlex.quote(REMOTE+' guardstart')],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            r=helper('status');r.check_returncode();state=json.loads(r.stdout)
            if state.get('active') and (state.get('state') or {}).get('phase')=='running':
                break
            time.sleep(.05)
        else:
            raise RuntimeError('Local OUTPUT hook not ready')
        code='''import http.client,json
rows=[]
for port,host in [(19876,'192.168.1.3:18765'),(19877,'probe.invalid')]:
 c=http.client.HTTPConnection('192.168.1.3',18765,timeout=4,source_address=('192.168.1.6',port))
 c.request('GET','/capri-lab/c82aa79e80e7edb2/protocol/json',headers={'Host':host,'Connection':'close'})
 r=c.getresponse();body=r.read(1024)
 assert r.status==200 and r.getheader('X-Capri-Original-Destination')=='verified' and json.loads(body)=={'fixture':True}
 rows.append({'source_port':port,'status':r.status,'original_destination_verified':True})
 c.close()
print(json.dumps(rows))
'''
        r=runtime.run(['/lab/venv/bin/python','-c',code],timeout=12)
        (args.output/'client.stderr').write_text(r.stderr);r.check_returncode()
        report['connections']=json.loads(r.stdout)
        helper('stop').check_returncode()
        stdout,stderr=native.communicate(timeout=20)
        (args.output/'native.stdout').write_text(stdout)
        assert native.returncode==0
        native=None
        metrics={}
        for line in stdout.splitlines():metrics.update(json.loads(line))
        assert metrics['dry_run'] and metrics['nat_cleanup_succeeded'] and not metrics['restoration_frames_sent'] and metrics['forwarded_frames']==0
        report['native']=metrics
        report['passed']=True
    except BaseException as error:
        report['passed']=False;report['error_type']=type(error).__name__;report['error']=str(error)[:600]
        raise
    finally:
        try:
            if native is not None:
                helper('stop').check_returncode();native.communicate(timeout=20)
            if pushed:
                if exists(STATE+'/intent'):helper('recover').check_returncode()
                if exists(STATE):root('rm -f '+' '.join(STATE+'/'+n for n in ('lock','status.json','status.tmp','stop.request'))+'; rmdir '+STATE)
                root('rm '+REMOTE)
                report['helper_removed']=True
            if engine is not None:
                runtime.shell(['/system/bin/run-as','ai.capri.app','touch',BASE+'/transparent.stop'],timeout=8).check_returncode()
                engine.wait(timeout=15)
                events=runtime.shell(['/system/bin/run-as','ai.capri.app','cat',BASE+'/transparent-events.jsonl'],timeout=8)
                (args.output/'events.jsonl').write_text(events.stdout)
                report['engine_exit']=engine.returncode
                report['listener_closed']=not ready()
                runtime.shell(['/system/bin/run-as','ai.capri.app','rm','-rf',BASE+'/transparent-conf'],timeout=8).check_returncode()
            if fixture is not None:
                (args.output/'fixture/stop').touch();fixture.wait(timeout=5);report['fixture_exit']=fixture.returncode
        except Exception as error:
            report['cleanup_error']=str(error)[:400];report['passed']=False
        try:
            if original is not None:
                after=baseline();report['network_baseline_unchanged']=original==after
                report['before_sha256']={k:hashlib.sha256(v.encode()).hexdigest() for k,v in original.items()}
                report['after_sha256']={k:hashlib.sha256(v.encode()).hexdigest() for k,v in after.items()}
                if original!=after:report['passed']=False
        except Exception as error:
            report['baseline_error']=str(error)[:300];report['passed']=False
        (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)
    if not report.get('passed'):raise SystemExit('Original-destination probe incomplete')


if __name__=='__main__':main()
