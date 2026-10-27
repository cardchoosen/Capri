#!/usr/bin/env python3
"""No-ARP gateway-resource lifecycle checks. Never changes vivo's gateway mapping."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]
REMOTE='/data/local/tmp/capri-gateway-guard'
STATE='/data/local/tmp/capri-pair-relay'


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',required=True);p.add_argument('--serial',required=True)
    p.add_argument('--ndk',required=True,type=Path);p.add_argument('--output',required=True,type=Path)
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    if (args.output/'report.json').exists():raise RuntimeError('Use a new output directory')
    adb=[args.adb,'-s',args.serial]
    pushed=False;running=None;before=None
    report={'arp_announcements':False,'actual_gateway_takeover':False,'cases':[]}

    def root(script):
        r=subprocess.run(adb+['shell','su -c '+shlex.quote(script)],capture_output=True,text=True,timeout=15)
        r.check_returncode();return r.stdout

    def exists(path):return root('if test -e '+path+'; then printf yes; else printf no; fi').strip()=='yes'

    def invoke(action,expected=0):
        r=subprocess.run(adb+['shell','su -c '+shlex.quote(REMOTE+' '+action)],capture_output=True,text=True,timeout=25)
        if r.returncode!=expected:raise RuntimeError(f'{action} exit {r.returncode}: {r.stdout[:500]}')
        data={}
        for line in r.stdout.splitlines():data.update(json.loads(line))
        return data

    def baseline():
        return {k:root(v) for k,v in {'nat':'iptables -w 2 -t nat -S','filter':'iptables -w 2 -t filter -S',
            'mangle':'iptables -w 2 -t mangle -S','routes':'ip -4 route show table all','rules':'ip -4 rule show',
            'parameters':'cat /proc/sys/net/ipv4/ip_forward /proc/sys/net/ipv4/conf/wlan0/rp_filter; getenforce'}.items()}

    def idle():
        deadline=time.monotonic()+8
        while time.monotonic()<deadline:
            state=invoke('status')
            if not state.get('active'):return state
            time.sleep(.05)
        raise RuntimeError('Lease still active')

    try:
        if exists(STATE) or exists(REMOTE):raise RuntimeError('Existing helper/journal requires investigation')
        route=root('ip -4 route show table wlan0 default')
        assert 'via 192.168.1.1 dev wlan0' in route, 'Fixed gateway does not match default route'
        before=baseline()
        for key,text in before.items():(args.output/(key+'-before.txt')).write_text(text)
        binary=args.output/'helper'
        compiler=args.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang'
        subprocess.run([str(compiler),'-DCAPRI_GATEWAY_RELAY','-DCAPRI_RELAY_FAULT_TESTS','-std=c11','-O2','-Wall','-Wextra','-Werror',str(ROOT/'tools/hacker/pair_relay.c'),'-o',str(binary)],check=True)
        root('ping -c 1 -W 1 192.168.1.8 >/dev/null; ping -c 1 -W 1 192.168.1.1 >/dev/null')
        subprocess.run(adb+['push',str(binary),REMOTE],capture_output=True,check=True);pushed=True
        root('chmod 700 '+REMOTE);invoke('preflight')
        for case in ('stop','worker','controller'):
            row={'case':case};report['cases'].append(row)
            if case=='stop':
                running=subprocess.Popen(adb+['shell','su -c '+shlex.quote(REMOTE+' guardstart')],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                deadline=time.monotonic()+5
                while time.monotonic()<deadline:
                    state=invoke('status');row['last_observed_state']=state
                    if running.poll() is not None:
                        stdout,stderr=running.communicate();row['helper_exit']=running.returncode;row['stdout']=stdout;row['stderr']=stderr[:600];running=None
                        raise RuntimeError('Native setup exited before running phase')
                    if state.get('active') and (state.get('state') or {}).get('phase')=='running':break
                    time.sleep(.05)
                else:raise RuntimeError('Did not reach running phase')
                invoke('recover',66);invoke('guardcheck',66)
                invoke('stop')
                stdout,stderr=running.communicate(timeout=15);assert running.returncode==0
                result={}
                for line in stdout.splitlines():result.update(json.loads(line))
                running=None;row['native']=result
            elif case=='worker':row['native']=invoke('guard_fault_worker',81)
            else:
                invoke('guard_fault_controller',91)
                state=idle();row['orphan']=state
                assert state.get('intent_present') and state.get('recovery_required')
            idle();settled=invoke('recover');assert not settled.get('intent_present')
            settled2=invoke('recover');assert not settled2.get('intent_present')
            row['repeated_recovery_passed']=True
            if 'native' in row:
                r=row['native'];assert r['dry_run'] and r['forwarded_frames']==0 and not r['restoration_frames_sent'] and r['kernel_transit_suppression_cleaned'] and not r['target_gateway_mapping_changed']
            after=baseline();assert before==after, 'Network baseline changed'
            row['baseline_unchanged']=True;row['passed']=True
            print(case+': PASS',flush=True)
        report['passed']=True
    except BaseException as error:
        report['passed']=False;report['error']=str(error)[:500];raise
    finally:
        try:
            if running is not None:
                invoke('stop');stdout,stderr=running.communicate(timeout=15)
                report['final_native_exit']=running.returncode
                (args.output/'final-native.stdout').write_text(stdout)
                (args.output/'final-native.stderr').write_text(stderr)
            if pushed:
                if exists(STATE+'/intent'):invoke('recover')
                if exists(STATE):root('rm -f '+' '.join(STATE+'/'+name for name in ('lock','status.json','status.tmp','stop.request'))+'; rmdir '+STATE)
                root('rm '+REMOTE);report['helper_removed']=True
        except Exception as error:report['cleanup_error']=str(error)[:400];report['passed']=False
        try:
            if before is not None:
                after=baseline();report['final_baseline_unchanged']=before==after
                report['before_sha256']={k:hashlib.sha256(v.encode()).hexdigest() for k,v in before.items()}
                report['after_sha256']={k:hashlib.sha256(v.encode()).hexdigest() for k,v in after.items()}
                for key,text in after.items():(args.output/(key+'-after.txt')).write_text(text)
                if before!=after:report['passed']=False
        except Exception as error:report['baseline_error']=str(error)[:300];report['passed']=False
        (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)
    if not report.get('passed'):raise SystemExit('Gateway guard verification incomplete')


if __name__=='__main__':main()
