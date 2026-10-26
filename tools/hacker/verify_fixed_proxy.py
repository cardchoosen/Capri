#!/usr/bin/env python3
"""Fixed vivo→Mac HTTP lab. Default exercises NAT lifecycle without ARP; --active needs fresh fixture requests.
Requires the separately owned ordinary-UID proxy engine and Mac fixture to be running.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
REMOTE = '/data/local/tmp/capri-fixed-http-proxy'
STATE = '/data/local/tmp/capri-pair-relay'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--fixture', type=Path)
    parser.add_argument('--active', action='store_true')
    parser.add_argument('--case', choices=['deadline','stop','worker','controller','engine'], default='deadline')
    parser.add_argument('--engine-stop-file', type=Path, help='Same owned host stop marker passed to engine runner')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.active and not args.fixture:
        parser.error('--active requires --fixture')
    if args.case == 'engine' and (not args.active or not args.engine_stop_file):
        parser.error('engine case requires --active and --engine-stop-file')
    if args.engine_stop_file and args.engine_stop_file.exists():
        parser.error('Engine stop marker must not already exist')
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output/'report.json').exists():
        raise RuntimeError('Use a new output directory; preserve earlier evidence')
    adb = [args.adb, '-s', args.serial]
    binary = args.output/'helper'
    compiler = args.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang'
    subprocess.run([str(compiler), '-DCAPRI_HTTP_PROXY_TEST', '-DCAPRI_RELAY_FAULT_TESTS', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', str(ROOT/'tools/hacker/pair_relay.c'), '-o', str(binary)], check=True)
    report = {'case': args.case, 'active': args.active, 'fixed_fixture_only': True, 'gateway_modified': False, 'https': False}
    pushed = False
    before = None
    running = None

    def shell(script, root=True, timeout=10):
        result = subprocess.run(adb+['shell', 'su -c '+shlex.quote(script) if root else script], capture_output=True, text=True, timeout=timeout)
        result.check_returncode()
        return result.stdout

    def exists(path):
        return shell('if test -e '+shlex.quote(path)+'; then printf yes; else printf no; fi').strip()=='yes'

    def helper(action):
        return subprocess.run(adb+['shell','su -c '+shlex.quote(REMOTE+' '+action)], capture_output=True,text=True,timeout=40)

    def baseline():
        return {key:shell(command) for key,command in {
            'routes':'ip -4 route show table all', 'rules':'ip -4 rule show',
            'filter':'iptables -w 2 -t filter -S', 'nat':'iptables -w 2 -t nat -S',
            'parameters':'cat /proc/sys/net/ipv4/ip_forward /proc/sys/net/ipv4/conf/wlan0/rp_filter; getenforce',
        }.items()}

    def probes():
        if not args.fixture:
            return []
        return [json.loads(line) for line in (args.fixture/'events.jsonl').read_text().splitlines() if json.loads(line).get('event')=='probe']

    def fresh():
        if not args.active:
            return
        rows=[r for r in probes() if r.get('source')=='192.168.1.10']
        if len(rows)<3 or any(r.get('visibility')!='visible' for r in rows[-3:]) or (datetime.now(timezone.utc)-datetime.fromisoformat(rows[-1]['time'])).total_seconds()>2:
            raise RuntimeError('Need three fresh, visible, direct vivo probes; no interception started')

    try:
        fresh()
        if exists(REMOTE) or exists(STATE):
            raise RuntimeError('Existing helper/journal must be investigated first')
        before=baseline()
        for key,value in before.items():
            (args.output/(key+'-before.txt')).write_text(value)
        shell('ping -c 1 -W 1 192.168.1.10 >/dev/null; ping -c 1 -W 1 192.168.1.16 >/dev/null')
        subprocess.run(adb+['push',str(binary),REMOTE],check=True,capture_output=True)
        pushed=True
        shell('chmod 700 '+REMOTE,root=False)
        helper('preflight').check_returncode()
        fresh()
        initial=probes()
        started=datetime.now(timezone.utc)
        report['started_at']=started.isoformat()
        if args.case in ('stop','engine'):
            action='start' if args.active else 'guardstart'
            running=subprocess.Popen(adb+['shell','su -c '+shlex.quote(REMOTE+' '+action)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
            deadline=time.monotonic()+5
            while time.monotonic()<deadline:
                state=helper('status');state.check_returncode();observed=json.loads(state.stdout)
                if observed.get('active') and (observed.get('state') or {}).get('phase')=='running':
                    break
                time.sleep(.1)
            else:
                raise RuntimeError('Did not reach running phase')
            if args.active:
                deadline=time.monotonic()+4
                while time.monotonic()<deadline:
                    marked=[r for r in probes()[len(initial):] if r.get('request_marked') and r.get('visibility')=='visible']
                    if len(marked)>=2:
                        break
                    time.sleep(.1)
                else:
                    raise RuntimeError('No proxy traffic before requested stop')
            if args.case=='engine':
                args.engine_stop_file.parent.mkdir(parents=True,exist_ok=True)
                args.engine_stop_file.touch(exist_ok=False)
            else:
                helper('stop').check_returncode()
            stdout,stderr=running.communicate(timeout=20)
            value=subprocess.CompletedProcess([],running.returncode,stdout,stderr)
            running=None
        else:
            action={'deadline':('start' if args.active else 'guardcheck'),
                    'worker':('fault_worker' if args.active else 'guard_fault_worker'),
                    'controller':('fault_controller' if args.active else 'guard_fault_controller')}[args.case]
            value=helper(action)
        report['helper_exit']=value.returncode
        (args.output/'native.stdout').write_text(value.stdout)
        (args.output/'native.stderr').write_text(value.stderr)
        metrics={}
        for line in value.stdout.splitlines():
            metrics.update(json.loads(line))
        report['native']=metrics
        if args.case=='controller':
            deadline=time.monotonic()+10
            while time.monotonic()<deadline:
                state=helper('status');state.check_returncode();observed=json.loads(state.stdout)
                if not observed.get('active'):
                    report['orphan_state']=observed
                    metrics.update((observed.get('state') or {}).get('metrics') or {})
                    break
                time.sleep(.1)
            else:
                raise RuntimeError('Orphan worker did not release its lease')
        if exists(STATE+'/intent'):
            recovered=helper('recover')
            report['recovery_exit']=recovered.returncode
            recovered.check_returncode()
        expected={'deadline':0,'stop':0,'worker':81,'controller':91,'engine':81}[args.case]
        assert value.returncode==expected, 'Unexpected helper exit'
        if args.case=='controller':
            assert report.get('orphan_state',{}).get('intent_present') and report.get('recovery_exit')==0, 'Controller recovery journal not verified'
        else:
            assert metrics.get('nat_cleanup_succeeded') and not metrics.get('recovery_required'), 'NAT/neighbor cleanup was not confirmed'
            reason={'deadline':'deadline','stop':'stop_requested','worker':'worker_exit','engine':'proxy_unavailable'}[args.case]
            assert metrics.get('reason')==reason, 'Wrong termination reason'
        settled=helper('recover');settled.check_returncode()
        final_state=json.loads(settled.stdout)
        assert not final_state.get('active') and not final_state.get('intent_present'), 'Task not settled'
        report['final_state']=final_state
        after=baseline()
        for key,text in after.items():
            (args.output/(key+'-after.txt')).write_text(text)
        report['baseline_unchanged']=before==after
        assert before==after, 'Baseline changed; do not blanket-restore'
        if args.active:
            count=len(probes())
            deadline=time.monotonic()+5
            while time.monotonic()<deadline:
                later=[r for r in probes()[count:] if r.get('source')=='192.168.1.10' and not r.get('request_marked')]
                if len(later)>=3:
                    break
                time.sleep(.2)
            measured=probes()[len(initial):]
            marked=[r for r in measured if r.get('source')=='192.168.1.8' and r.get('request_marked')]
            report['proxied_backend_probes']=len(marked)
            report['direct_probes_after_stop']=len(later)
            report['visibility_states']=sorted({r.get('visibility') for r in measured})
            report['client_timeout_delta']=max(0,measured[-1].get('failures',0)-initial[-1].get('failures',0)) if measured and initial else None
            timeline=initial[-1:]+measured
            report['max_gap_seconds']=max(((datetime.fromisoformat(b['time'])-datetime.fromisoformat(a['time'])).total_seconds() for a,b in zip(timeline,timeline[1:])),default=0)
            assert metrics.get('proxy_admitted_frames',0)>0 and len(marked)>=(3 if args.case=='deadline' else 1), 'No target proxy evidence'
            assert len(later)>=3, 'Direct recovery not verified'
            if args.case!='controller':
                assert metrics.get('post_restore_frames')==0 and metrics.get('restoration_frames_sent'), 'Native restoration not verified'
            assert report['visibility_states']==['visible'], 'Page left foreground'
            assert report['client_timeout_delta']<=(1 if args.case=='engine' else 0) and report['max_gap_seconds']<=5, 'Recovery interruption exceeded bound'
        else:
            assert metrics.get('proxy_admitted_frames',0)==0 and not metrics.get('restoration_frames_sent'), 'Guard unexpectedly handled target traffic'
        report['passed']=True
    except BaseException as error:
        report['passed']=False
        report['error_type']=type(error).__name__
        report['error']=str(error)[:500]
        raise
    finally:
        try:
            if running is not None:
                helper('stop').check_returncode()
                running.communicate(timeout=20)
            if pushed:
                if exists(STATE+'/intent'):
                    helper('recover').check_returncode()
                if exists(STATE):
                    shell('rm -f '+' '.join(STATE+'/'+name for name in ('lock','status.json','status.tmp','stop.request'))+'; rmdir '+STATE)
                shell('rm '+REMOTE,root=False)
                report['helper_artifacts_removed']=True
            if before is not None:
                final=baseline()
                report['final_baseline_unchanged']=before==final
                report['before_sha256']={k:hashlib.sha256(v.encode()).hexdigest() for k,v in before.items()}
                report['after_sha256']={k:hashlib.sha256(v.encode()).hexdigest() for k,v in final.items()}
                if before!=final:
                    report['passed']=False
                    for key,text in final.items():
                        (args.output/(key+'-final.txt')).write_text(text)
        except Exception as error:
            report['cleanup_error']=type(error).__name__+': '+str(error)[:200]
            report['passed']=False
        (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps(report),flush=True)
    if not report.get('passed'):
        raise SystemExit('Fixed proxy verification incomplete')


if __name__=='__main__':
    main()
