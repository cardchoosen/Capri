#!/usr/bin/env python3
"""Explicit-device verification of the NO-NETWORK root lifecycle prototype.
No app install, ARP injection, packet capture, route/firewall/sysctl writes.
"""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
REMOTE = '/data/local/tmp/capri-net-lifecycle-prototype'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--ndk', type=Path, required=True)
    args = parser.parse_args()
    out = ROOT / 'work/hacker-prototype'
    out.mkdir(parents=True, exist_ok=True)
    compiler = args.ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang'
    binary = out / 'capri-net-lifecycle'
    subprocess.run([str(compiler), '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                    str(ROOT / 'tools/hacker/lifecycle.c'), '-o', str(binary)], check=True)
    adb = [args.adb, '-s', args.serial]
    report = {'prototype_only': True, 'serial': args.serial, 'network_mutations_requested': 0, 'cases': []}
    active = None
    installed = False
    owns_state = False

    def shell(command, *, expected=0, timeout=10):
        result = subprocess.run(adb + ['shell', command], capture_output=True, text=True, timeout=timeout)
        if result.returncode != expected:
            raise RuntimeError(f'Unexpected result {result.returncode}: {result.stdout} {result.stderr}')
        return result.stdout

    def cmd(*parts, expected=0):
        return shell('su -c ' + shlex.quote(shlex.join([REMOTE, *map(str, parts)])), expected=expected)

    def status():
        return json.loads(cmd('status'))

    def wait_for(predicate, seconds=4):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = status()
            if predicate(value):
                return value
            time.sleep(.1)
        raise RuntimeError('Timed out waiting for prototype state')

    def snapshot():
        return shell("su -c 'cat /proc/sys/net/ipv4/ip_forward; cat /proc/sys/net/ipv4/conf/wlan0/rp_filter; getenforce'").splitlines()

    def record(name):
        report['cases'].append({'name': name, 'passed': True})
        print(f'{name}: PASS', flush=True)

    try:
        before = snapshot()
        if shell('test -e ' + REMOTE + '; printf "%s" "$?"').strip() != '1':
            raise RuntimeError('Temporary binary already exists; refusing overwrite')
        subprocess.run(adb + ['push', str(binary), REMOTE], check=True, capture_output=True, text=True)
        installed = True
        shell('chmod 700 ' + REMOTE)
        initial = status()
        if initial['lock_held'] or initial['simulated_resource_present']:
            raise RuntimeError('Existing task/residue requires explicit investigation')
        owns_state = True

        finished = json.loads(cmd('run', 1))
        assert finished['state']['reason'] == 'deadline'
        assert not finished['lock_held'] and not finished['simulated_resource_present']
        record('bounded_deadline_cleanup')

        command = 'su -c ' + shlex.quote(shlex.join([REMOTE, 'run', '8']))
        active = subprocess.Popen(adb + ['shell', command], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        wait_for(lambda s: s['lock_held'] and s['simulated_resource_present'])
        cmd('run', 1, expected=3)
        cmd('recover', expected=3)
        cmd('stop')
        stdout, stderr = active.communicate(timeout=4)
        assert active.returncode == 0, stderr
        assert json.loads(stdout)['state']['reason'] == 'stop_requested'
        active = None
        final = status()
        assert not final['lock_held'] and not final['simulated_resource_present']
        record('stop_and_exclusive_lease')

        failed = json.loads(cmd('run', 2, 'worker_exit'))
        assert failed['state']['reason'] == 'worker_exit'
        assert not failed['lock_held'] and not failed['simulated_resource_present']
        record('worker_failure_cleanup')

        cmd('run', 2, 'supervisor_exit', expected=71)
        wait_for(lambda s: not s['lock_held'] and not s['simulated_resource_present'])
        recovered = json.loads(cmd('recover'))
        assert recovered['state']['status'] == 'recovered'
        assert not recovered['lock_held'] and not recovered['simulated_resource_present']
        record('supervisor_failure_and_journal_recovery')

        after = snapshot()
        assert before == after, 'Network/SELinux snapshot changed; investigate without blanket restoration'
        report['baseline_before'] = before
        report['baseline_after'] = after
        report['passed'] = True
    except BaseException as exc:
        report['passed'] = False
        report['error'] = str(exc)
        raise
    finally:
        if installed:
            try:
                if owns_state:
                    cmd('stop')
                    if active is not None:
                        active.communicate(timeout=4)
                    wait_for(lambda s: not s['lock_held'])
                    cmd('purge')
                else:
                    report['existing_state_left_untouched'] = True
                shell('rm ' + REMOTE)
                report['device_artifacts_removed'] = True
            except Exception as exc:
                report['cleanup_error'] = str(exc)
                report['device_artifacts_removed'] = False
                report['passed'] = False
        (out / 'lifecycle-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if not report.get('passed'):
        raise RuntimeError('Prototype did not pass; inspect report')
    print('Network configuration unchanged; temporary device artifacts removed.', flush=True)


if __name__ == '__main__':
    main()
