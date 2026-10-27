#!/usr/bin/env python3
"""One eight-second real gateway passthrough trial on the confirmed vivo. No HTTP/TLS parsing or proxy redirection."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time
from external_probe_log import read_events

ROOT=Path(__file__).resolve().parents[2]
REMOTE='/data/local/tmp/capri-gateway-pilot'
STATE='/data/local/tmp/capri-pair-relay'


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('adb','serial'):p.add_argument('--'+name,required=True)
    for name in ('ndk','scope','driver','key','known-hosts','output'):p.add_argument('--'+name,type=Path,required=True)
    args=p.parse_args();scope=json.loads(args.scope.read_text())
    assert scope['target']=='192.168.1.8' and scope['pixel']=='192.168.1.6' and scope['gateway']=='192.168.1.1' and scope['external']=='124.220.51.203:80'
    args.output.mkdir(parents=True,exist_ok=True)
    if (args.output/'report.json').exists():raise RuntimeError('Use a new output directory')
    adb=[args.adb,'-s',args.serial];pushed=False;before=None
    report={'gateway_direction':True,'http_proxy_engine_used':False,'tls_decryption':False,'target':scope['target'],'external':scope['external']}
    def root(script):
        r=subprocess.run(adb+['shell','su -c '+shlex.quote(script)],capture_output=True,text=True,timeout=15);r.check_returncode();return r.stdout
    def exists(path):return root('if test -e '+path+'; then printf yes; else printf no; fi').strip()=='yes'
    def helper(action):return subprocess.run(adb+['shell','su -c '+shlex.quote(REMOTE+' '+action)],capture_output=True,text=True,timeout=30)
    def baseline():
        return {k:root(v) for k,v in {'nat':'iptables -w 2 -t nat -S','filter':'iptables -w 2 -t filter -S','mangle':'iptables -w 2 -t mangle -S','routes':'ip -4 route show table all','rules':'ip -4 rule show','parameters':'cat /proc/sys/net/ipv4/ip_forward /proc/sys/net/ipv4/conf/wlan0/rp_filter; getenforce'}.items()}
    def telemetry():return [x for x in (json.loads(s) for s in (args.driver/'events.jsonl').read_text().splitlines()) if x['event']=='telemetry']
    def fresh():
        rows=telemetry()
        assert len(rows)>=3 and all(x['visibility']=='visible' for x in rows[-3:]) and (datetime.now(timezone.utc)-datetime.fromisoformat(rows[-1]['time'])).total_seconds()<3, 'Need fresh visible vivo external requests'
        return rows
    try:
        initial=fresh()
        external_before=read_events(args.key,args.known_hosts,scope['token'])
        assert any(x['sequence']>=initial[-1]['sequence']-2 for x in external_before), 'No corroborating server events'
        if exists(STATE) or exists(REMOTE):raise RuntimeError('Existing helper/journal requires investigation')
        before=baseline()
        for k,v in before.items():(args.output/(k+'-before.txt')).write_text(v)
        assert 'via 192.168.1.1 dev wlan0' in root('ip -4 route show table wlan0 default')
        binary=args.output/'helper';compiler=args.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang'
        subprocess.run([str(compiler),'-DCAPRI_GATEWAY_RELAY','-std=c11','-O2','-Wall','-Wextra','-Werror',str(ROOT/'tools/hacker/pair_relay.c'),'-o',str(binary)],check=True)
        root('ping -c 1 -W 1 192.168.1.8 >/dev/null; ping -c 1 -W 1 192.168.1.1 >/dev/null')
        subprocess.run(adb+['push',str(binary),REMOTE],check=True,capture_output=True);pushed=True
        root('chmod 700 '+REMOTE)
        helper('preflight').check_returncode();initial=fresh()
        seq_before=initial[-1]['sequence'];failure_before=initial[-1]['failures']
        report['started_at']=datetime.now(timezone.utc).isoformat()
        result=helper('start');report['helper_exit']=result.returncode
        (args.output/'native.stdout').write_text(result.stdout);(args.output/'native.stderr').write_text(result.stderr)
        metrics={}
        for line in result.stdout.splitlines():metrics.update(json.loads(line))
        report['native']=metrics
        if exists(STATE+'/intent'):
            recovered=helper('recover');report['explicit_recovery_exit']=recovered.returncode;recovered.check_returncode()
        result.check_returncode()
        after_native=telemetry();seq_end=after_native[-1]['sequence']
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            later=telemetry()
            if len([r for r in later if r['sequence']>seq_end])>=3:break
            time.sleep(.2)
        final=telemetry();measured=[r for r in final if r['sequence']>seq_before]
        external=read_events(args.key,args.known_hosts,scope['token'])
        (args.output/'selected-server-events.json').write_text(json.dumps(external,indent=2)+'\n')
        during=[r for r in external if seq_before<r['sequence']<=seq_end]
        subsequent=[r for r in external if r['sequence']>seq_end]
        report.update(external_requests_during=len(during),external_requests_after=len(subsequent),response_statuses=sorted({r['status'] for r in during+subsequent}),client_failure_delta=max(0,final[-1]['failures']-failure_before),visibility_states=sorted({r['visibility'] for r in measured}))
        timeline=initial[-1:]+measured
        report['max_request_gap_seconds']=max(((datetime.fromisoformat(b['time'])-datetime.fromisoformat(a['time'])).total_seconds() for a,b in zip(timeline,timeline[1:])),default=0)
        assert metrics.get('forwarded_frames',0)>0 and metrics.get('selected_external_tcp_frames',0)>0 and metrics['send_errors']==0, 'No selected external path evidence'
        assert metrics['kernel_transit_suppression_cleaned'] and not metrics['recovery_required'] and metrics['post_restore_frames']==0 and metrics['restoration_frames_sent'], 'Restoration not confirmed'
        assert len(during)>=4 and len(subsequent)>=3 and report['client_failure_delta']==0 and report['visibility_states']==['visible'] and report['max_request_gap_seconds']<3, 'External continuity failed/inconclusive'
        assert before==baseline(), 'Network baseline changed'
        report['passed']=True
    except BaseException as error:report['passed']=False;report['error']=str(error)[:500];raise
    finally:
        try:
            if pushed:
                if exists(STATE+'/intent'):helper('recover').check_returncode()
                if exists(STATE):root('rm -f '+' '.join(STATE+'/'+n for n in ('lock','status.json','status.tmp','stop.request'))+'; rmdir '+STATE)
                root('rm '+REMOTE);report['helper_removed']=True
        except Exception as error:report['cleanup_error']=str(error)[:500];report['passed']=False
        try:
            if before is not None:
                after=baseline();report['final_baseline_unchanged']=before==after
                report['before_sha256']={k:hashlib.sha256(v.encode()).hexdigest() for k,v in before.items()};report['after_sha256']={k:hashlib.sha256(v.encode()).hexdigest() for k,v in after.items()}
                for k,v in after.items():(args.output/(k+'-after.txt')).write_text(v)
                if before!=after:report['passed']=False
        except Exception as error:report['baseline_error']=str(error)[:300];report['passed']=False
        (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)
    if not report.get('passed'):raise SystemExit('Gateway pilot incomplete')


if __name__=='__main__':main()
