#!/usr/bin/env python3
"""Bounded developer-only relay of one explicitly fixed phone/fixture pair. No gateway or HTTPS changes."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
REMOTE = '/data/local/tmp/capri-pair-relay-prototype'
STATE = '/data/local/tmp/capri-pair-relay'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    args = parser.parse_args()
    out = ROOT / 'work/hacker-prototype'
    out.mkdir(parents=True, exist_ok=True)
    compiler = args.ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang'
    binary = out / 'capri-pair-relay'
    subprocess.run([str(compiler), '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                    str(ROOT / 'tools/hacker/pair_relay.c'), '-o', str(binary)], check=True)
    adb = [args.adb, '-s', args.serial]
    report = {'prototype_only': True, 'single_direction': True, 'target': '192.168.1.10', 'fixture_server': '192.168.1.16',
              'gateway_modified': False, 'https_tested': False}
    pushed = started = False

    def shell(script, *, expected=0, timeout=8, root=True):
        result = subprocess.run(adb + ['shell', 'su -c ' + shlex.quote(script) if root else script], capture_output=True, text=True, timeout=timeout)
        if result.returncode != expected:
            raise RuntimeError(f'Unexpected exit {result.returncode}: {result.stdout} {result.stderr}')
        return result.stdout

    def helper(action):
        return subprocess.run(adb + ['shell', 'su -c ' + shlex.quote(REMOTE + ' ' + action)], capture_output=True, text=True, timeout=20)

    def exists(path):
        return shell('if test -e ' + shlex.quote(path) + '; then printf yes; else printf no; fi').strip() == 'yes'

    def baseline():
        return {key: shell(command) for key, command in {
            'routes': '/system/bin/ip -4 route show table all',
            'rules': '/system/bin/ip -4 rule show',
            'filter': '/system/bin/iptables -w 2 -t filter -S',
            'parameters': 'cat /proc/sys/net/ipv4/ip_forward /proc/sys/net/ipv4/conf/wlan0/rp_filter; getenforce',
        }.items()}

    def probes():
        rows = [json.loads(line) for line in (args.fixture / 'events.jsonl').read_text().splitlines()]
        return [row for row in rows if row['event'] == 'probe' and row['source'] == '192.168.1.10']

    try:
        recent = probes()
        if not recent or (datetime.now(timezone.utc) - datetime.fromisoformat(recent[-1]['time'])).total_seconds() > 3:
            raise RuntimeError('No fresh vivo fixture traffic; no relay started')
        if exists(REMOTE) or exists(STATE):
            raise RuntimeError('Existing prototype artifact; investigate before starting')
        before = baseline()
        shell('ping -c 1 -W 1 192.168.1.10 >/dev/null; ping -c 1 -W 1 192.168.1.16 >/dev/null')
        subprocess.run(adb + ['push', str(binary), REMOTE], check=True, capture_output=True, text=True)
        pushed = True
        shell('chmod 700 ' + REMOTE, root=False)
        preflight = helper('preflight')
        if preflight.returncode:
            raise RuntimeError(f'Native preflight failed: {preflight.returncode}')
        report['preflight'] = json.loads(preflight.stdout)
        recent = probes()
        if not recent or (datetime.now(timezone.utc) - datetime.fromisoformat(recent[-1]['time'])).total_seconds() > 3:
            raise RuntimeError('Fixture paused before trial; no relay started')
        start = datetime.now(timezone.utc)
        started = True
        result = helper('run')
        report['helper_exit'] = result.returncode
        if result.stdout.strip():
            report['relay'] = json.loads(result.stdout)
        if exists(STATE + '/intent'):
            recovery = helper('recover')
            report['explicit_recovery_exit'] = recovery.returncode
            if recovery.returncode:
                raise RuntimeError('Restoration not confirmed; recovery journal retained')
        end = datetime.now(timezone.utc)
        during = [row for row in probes() if start <= datetime.fromisoformat(row['time']) <= end]
        report['fixture_requests_during_trial'] = len(during)
        count = len(probes())
        time.sleep(3)
        later = probes()
        report['fixture_requests_after_stop'] = len(later) - count
        after = baseline()
        report['host_baseline_unchanged'] = before == after
        report['host_before_sha256'] = {key: hashlib.sha256(value.encode()).hexdigest() for key, value in before.items()}
        report['host_after_sha256'] = {key: hashlib.sha256(value.encode()).hexdigest() for key, value in after.items()}
        assert before == after, 'Host network baseline changed; do not blanket-restore'
        relay = report.get('relay', {})
        assert not relay.get('failed', True), 'Relay worker failed'
        assert relay.get('fixture_get_requests', 0) > 0 and relay.get('forwarded_frames', 0) > 0, 'No fixture request observed via Pixel'
        assert relay.get('send_errors', 1) == 0, 'Frame send errors'
        assert len(during) >= 4 and report['fixture_requests_after_stop'] >= 2, 'Fixture continuity not verified'
        assert relay.get('restoration_frames_sent') and relay.get('post_restore_frames') == 0, 'Direct path recovery not confirmed'
        report['passed'] = True
    except BaseException as exc:
        report['passed'] = False
        report['error'] = str(exc)
        raise
    finally:
        if pushed:
            try:
                if started and exists(STATE + '/intent'):
                    recovery = helper('recover')
                    if recovery.returncode:
                        raise RuntimeError('Recovery failed; retaining helper and intent for investigation')
                if exists(STATE):
                    shell('rm ' + STATE + '/lock; rmdir ' + STATE)
                shell('rm ' + REMOTE, root=False)
                report['device_artifacts_removed'] = True
            except Exception as exc:
                report['cleanup_error'] = str(exc)
                report['passed'] = False
        (out / 'pair-relay-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps(report, ensure_ascii=False), flush=True)
    if not report.get('passed'):
        raise RuntimeError('Relay verification incomplete')


if __name__ == '__main__':
    main()
