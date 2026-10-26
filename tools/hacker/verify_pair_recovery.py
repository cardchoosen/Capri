#!/usr/bin/env python3
"""Verify bounded pair-relay control and fault recovery. Default sends no ARP; --active requires fresh fixture traffic."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
REMOTE = '/data/local/tmp/capri-pair-recovery-prototype'
STATE = '/data/local/tmp/capri-pair-relay'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--active', action='store_true')
    parser.add_argument('--fixture', type=Path)
    parser.add_argument('--case', choices=['all', 'stop', 'worker', 'controller'], default='all')
    args = parser.parse_args()
    if args.active and not args.fixture:
        parser.error('--active requires --fixture')
    out = ROOT / 'work/hacker-prototype'
    out.mkdir(parents=True, exist_ok=True)
    compiler = args.ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang'
    binary = out / 'capri-pair-recovery'
    subprocess.run([str(compiler), '-DCAPRI_RELAY_FAULT_TESTS', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                    str(ROOT / 'tools/hacker/pair_relay.c'), '-o', str(binary)], check=True)
    adb = [args.adb, '-s', args.serial]
    report = {'active_trial': args.active, 'prototype_only': True, 'cases': []}
    pushed = False
    running = None
    original = None

    def shell(script, expected=0, root=True):
        result = subprocess.run(adb + ['shell', 'su -c ' + shlex.quote(script) if root else script], capture_output=True, text=True, timeout=8)
        if result.returncode != expected:
            raise RuntimeError(f'Unexpected shell exit {result.returncode}: {result.stderr}')
        return result.stdout

    def command(action):
        return adb + ['shell', 'su -c ' + shlex.quote(REMOTE + ' ' + action)]

    def invoke(action, expected=0):
        value = subprocess.run(command(action), capture_output=True, text=True, timeout=20)
        if value.returncode != expected:
            raise RuntimeError(f'{action}: exit {value.returncode}: {value.stdout} {value.stderr}')
        return json.loads(value.stdout) if value.stdout.strip() else None

    def status():
        return invoke('status')

    def exists(path):
        return shell('if test -e ' + shlex.quote(path) + '; then printf yes; else printf no; fi').strip() == 'yes'

    def until(predicate, seconds=4):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = status()
            if predicate(value):
                return value
            time.sleep(.05)
        raise RuntimeError('Task state timeout')

    def baseline():
        return {name: shell(script) for name, script in {
            'routes': '/system/bin/ip -4 route show table all',
            'rules': '/system/bin/ip -4 rule show',
            'filter': '/system/bin/iptables -w 2 -t filter -S',
            'parameters': 'cat /proc/sys/net/ipv4/ip_forward /proc/sys/net/ipv4/conf/wlan0/rp_filter; getenforce',
        }.items()}

    def probes():
        if not args.active:
            return []
        return [row for row in (json.loads(line) for line in (args.fixture / 'events.jsonl').read_text().splitlines())
                if row['event'] == 'probe' and row['source'] == '192.168.1.10']

    def fresh():
        rows = probes()
        if args.active and (len(rows) < 3 or any(row.get('visibility') != 'visible' for row in rows[-3:]) or (datetime.now(timezone.utc) - datetime.fromisoformat(rows[-1]['time'])).total_seconds() > 2):
            raise RuntimeError('Need fresh visible-page fixture telemetry; active trial not started')

    try:
        fresh()
        if exists(REMOTE) or exists(STATE):
            raise RuntimeError('Existing artifacts must be investigated before testing')
        original = baseline()
        shell('ping -c 1 -W 1 192.168.1.10 >/dev/null; ping -c 1 -W 1 192.168.1.16 >/dev/null')
        subprocess.run(adb + ['push', str(binary), REMOTE], check=True, capture_output=True, text=True)
        pushed = True
        shell('chmod 700 ' + REMOTE, root=False)
        assert not status()['active'] and not exists(STATE), 'Idle status must not create task files'
        invoke('preflight')
        for case in (('stop', 'worker', 'controller') if args.case == 'all' else (args.case,)):
            fresh()
            row = {'case': case, 'started_at': datetime.now(timezone.utc).isoformat()}
            report['cases'].append(row)
            initial_probes = probes()
            count_before = len(initial_probes)
            failures_before = initial_probes[-1].get('failures', 0) if initial_probes else 0
            if case == 'stop':
                running = subprocess.Popen(command('start' if args.active else 'guardstart'), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                observed = until(lambda value: value['active'] and value.get('state', {}).get('phase') == 'running')
                row['running_state'] = observed
                invoke('guardcheck', expected=66)
                invoke('recover', expected=66)
                if args.active:
                    deadline = time.monotonic() + 3
                    while len(probes()) < count_before + 2 and time.monotonic() < deadline:
                        time.sleep(.1)
                requested = invoke('stop')
                assert requested['stop_requested']
                stdout, stderr = running.communicate(timeout=6)
                row['helper_exit'] = running.returncode
                row['native_finished_at'] = datetime.now(timezone.utc).isoformat()
                row['helper_stderr'] = stderr[:512]
                if stdout.strip():
                    row['result'] = json.loads(stdout)
                assert running.returncode == 0, f'Native stop exit={running.returncode}: {stderr}' 
                assert row['result']['reason'] == 'stop_requested'
                running = None
            elif case == 'worker':
                row['result'] = invoke('fault_worker' if args.active else 'guard_fault_worker', expected=81)
                assert row['result']['fault_injected'] and row['result']['reason'] == 'worker_exit'
            else:
                invoke('fault_controller' if args.active else 'guard_fault_controller', expected=91)
                orphan = until(lambda value: not value['active'])
                assert orphan['recovery_required'] and orphan['intent_present']
                row['orphan_state'] = orphan
                if args.active:
                    metrics = orphan['state']['metrics']
                    assert metrics['fixture_get_requests'] >= 1 and metrics['forwarded_frames'] >= 1 and metrics['send_errors'] == 0, 'Controller fault lacks actual relay evidence'
            row['native_finished_at'] = datetime.now(timezone.utc).isoformat()
            settled = until(lambda value: not value['active'])
            if settled['intent_present']:
                restored = invoke('recover')
                assert not restored['active'] and not restored['intent_present']
                row['recovery'] = restored
            invoke('recover')  # Idempotent: no intent means no announcement.
            after = status()
            assert not after['active'] and not after['intent_present']
            assert after['state']['phase'] in ('stopped', 'recovered'), 'Idempotent recovery must preserve the task outcome'
            assert not invoke('stop').get('stop_requested', False)
            row['final_state'] = after
            if args.active:
                count = len(probes())
                wait_seconds = 3 if case == 'stop' else 5
                time.sleep(wait_seconds)
                final_probes = probes()
                row['post_stop_wait_seconds'] = wait_seconds
                row['requests_during'] = count - count_before
                row['requests_after'] = len(final_probes) - count
                measured = final_probes[max(0, count_before - 1):]
                row['client_timeout_delta'] = max(0, final_probes[-1].get('failures', 0) - failures_before)
                row['page_visibility_states'] = sorted({item.get('visibility', 'unknown') for item in measured})
                row['max_request_gap_seconds'] = max(((datetime.fromisoformat(b['time']) - datetime.fromisoformat(a['time'])).total_seconds() for a, b in zip(measured, measured[1:])), default=0)
                assert row['page_visibility_states'] == ['visible'], 'Browser left foreground; case inconclusive'
                assert row['requests_during'] >= 1 and row['requests_after'] >= 2, f'Fixture continuity failed: during={row["requests_during"]}, after={row["requests_after"]}'
                if 'result' in row:
                    assert row['result']['fixture_get_requests'] >= 1 and row['result']['send_errors'] == 0
            else:
                if 'result' in row:
                    assert row['result']['dry_run'] and row['result']['forwarded_frames'] == 0 and not row['result']['restoration_frames_sent']
            assert original == baseline(), 'Host baseline changed; inspect without blanket restore'
            row['passed'] = True
            print(case + ': PASS', flush=True)
        report['host_baseline_sha256'] = {key: hashlib.sha256(value.encode()).hexdigest() for key, value in original.items()}
        report['host_baseline_unchanged'] = True
        report['passed'] = True
    except BaseException as exc:
        report['passed'] = False
        report['error'] = str(exc) or type(exc).__name__
        raise
    finally:
        if pushed:
            try:
                invoke('stop')
                if running is not None:
                    running.communicate(timeout=6)
                until(lambda value: not value['active'])
                invoke('recover')
                assert not status()['intent_present']
                if exists(STATE):
                    shell('rm -f ' + ' '.join(STATE + '/' + name for name in ('lock', 'status.json', 'status.tmp', 'stop.request')) + '; rmdir ' + STATE)
                shell('rm ' + REMOTE, root=False)
                report['device_artifacts_removed'] = True
            except Exception as exc:
                report['cleanup_error'] = str(exc)
                report['passed'] = False
        if original is not None:
            try:
                final_baseline = baseline()
                report['host_baseline_unchanged'] = original == final_baseline
                if original != final_baseline:
                    report['passed'] = False
                report['host_before_sha256'] = {key: hashlib.sha256(value.encode()).hexdigest() for key, value in original.items()}
                report['host_after_sha256'] = {key: hashlib.sha256(value.encode()).hexdigest() for key, value in final_baseline.items()}
            except Exception as exc:
                report['baseline_check_error'] = str(exc)
                report['passed'] = False
        name = ('pair-recovery-active-' + args.case + '-report.json') if args.active else 'pair-recovery-dry-report.json'
        payload = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
        (out / name).write_text(payload)
        stamp = datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')
        (out / (Path(name).stem + '-' + stamp + '.json')).write_text(payload)
    if not report.get('passed'):
        raise RuntimeError('Recovery verification incomplete')


if __name__ == '__main__':
    main()
