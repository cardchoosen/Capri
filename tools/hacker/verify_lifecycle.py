#!/usr/bin/env python3
"""Explicit-device root lifecycle verification. Default uses simulated resources.
Optional firewall-unreferenced mode mutates only a fresh chain with no traffic hooks.
No app install, ARP injection, packet capture, route, forwarding or SELinux writes.
"""
import argparse
import hashlib
import json
import re
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
    parser.add_argument('--resource', choices=['simulated', 'firewall-unreferenced'], default='simulated')
    args = parser.parse_args()
    real = args.resource == 'firewall-unreferenced'
    remote = REMOTE if not real else '/data/local/tmp/capri-firewall-lifecycle-prototype'
    out = ROOT / 'work/hacker-prototype'
    out.mkdir(parents=True, exist_ok=True)
    compiler = args.ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang'
    binary = out / ('capri-firewall-lifecycle' if real else 'capri-net-lifecycle')
    subprocess.run([str(compiler), '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                    *(['-DCAPRI_FIREWALL_PROBE'] if real else []), str(ROOT / 'tools/hacker/lifecycle.c'), '-o', str(binary)], check=True)
    adb = [args.adb, '-s', args.serial]
    report = {'prototype_only': True, 'serial': args.serial, 'resource': args.resource, 'traffic_path_changes_requested': 0, 'unreferenced_chain_changes_requested': real, 'cases': []}
    active = None
    installed = False
    owns_state = False
    conflict_chain = None

    def shell(command, *, expected=0, timeout=10):
        result = subprocess.run(adb + ['shell', command], capture_output=True, text=True, timeout=timeout)
        if result.returncode != expected:
            raise RuntimeError(f'Unexpected result {result.returncode}: {result.stdout} {result.stderr}')
        return result.stdout

    def cmd(*parts, expected=0):
        return shell('su -c ' + shlex.quote(shlex.join([remote, *map(str, parts)])), expected=expected)

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
        rules_before = shell("su -c '/system/bin/iptables -w 2 -t filter -S'") if real else None
        if rules_before is not None:
            (out / 'firewall-filter-before.txt').write_text(rules_before)
        if shell('test -e ' + remote + '; printf "%s" "$?"').strip() != '1':
            raise RuntimeError('Temporary binary already exists; refusing overwrite')
        subprocess.run(adb + ['push', str(binary), remote], check=True, capture_output=True, text=True)
        installed = True
        shell('chmod 700 ' + remote)
        initial = status()
        if initial['lock_held'] or initial['resource_present']:
            raise RuntimeError('Existing task/residue requires explicit investigation')
        owns_state = True

        finished = json.loads(cmd('run', 1))
        assert finished['state']['reason'] == 'deadline'
        assert not finished['lock_held'] and not finished['resource_present']
        record('bounded_deadline_cleanup')

        command = 'su -c ' + shlex.quote(shlex.join([remote, 'run', '8']))
        active = subprocess.Popen(adb + ['shell', command], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        wait_for(lambda s: s['lock_held'] and s['resource_present'])
        cmd('run', 1, expected=3)
        cmd('recover', expected=3)
        cmd('stop')
        stdout, stderr = active.communicate(timeout=4)
        assert active.returncode == 0, stderr
        assert json.loads(stdout)['state']['reason'] == 'stop_requested'
        active = None
        final = status()
        assert not final['lock_held'] and not final['resource_present']
        record('stop_and_exclusive_lease')

        failed = json.loads(cmd('run', 2, 'worker_exit'))
        assert failed['state']['reason'] == 'worker_exit'
        assert not failed['lock_held'] and not failed['resource_present']
        record('worker_failure_cleanup')

        cmd('run', 2, 'supervisor_exit', expected=71)
        wait_for(lambda s: not s['lock_held'] and not s['resource_present'])
        recovered = json.loads(cmd('recover'))
        assert recovered['state']['status'] == 'recovered'
        assert not recovered['lock_held'] and not recovered['resource_present']
        record('supervisor_failure_and_journal_recovery')

        if real:
            cmd('run', 2, 'both_exit', expected=72)
            residue = wait_for(lambda s: not s['lock_held'])
            assert residue['resource_present'] and residue['kernel_chain_present'] == 1
            assert residue['state']['network_changes'] == 1
            restored = json.loads(cmd('recover'))
            assert not restored['resource_present'] and restored['kernel_chain_present'] == 0
            record('all_helpers_exit_then_real_chain_recovery')

            command = 'su -c ' + shlex.quote(shlex.join([remote, 'run', '8']))
            active = subprocess.Popen(adb + ['shell', command], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            wait_for(lambda s: s['lock_held'] and s.get('kernel_chain_present') == 1)
            chain = shell("su -c 'cat /data/local/tmp/capri-firewall-lifecycle/chain.intent'").strip()
            assert re.fullmatch(r'CPRI_LAB_[0-9a-f]{16}', chain)
            def chain_op(operation):
                return 'su -c ' + shlex.quote(shlex.join(['/system/bin/iptables', '-w', '2', '-t', 'filter', operation, chain,
                    '-m', 'comment', '--comment', 'capri-verifier-conflict', '-j', 'RETURN']))
            shell(chain_op('-A'))
            conflict_chain = chain
            cmd('stop')
            _, stderr = active.communicate(timeout=5)
            assert active.returncode == 8, stderr
            active = None
            preserved_before = shell('su -c ' + shlex.quote(shlex.join(['/system/bin/iptables', '-w', '2', '-t', 'filter', '-S', chain])))
            cmd('recover', expected=8)
            preserved_after = shell('su -c ' + shlex.quote(shlex.join(['/system/bin/iptables', '-w', '2', '-t', 'filter', '-S', chain])))
            assert preserved_before == preserved_after and 'capri-verifier-conflict' in preserved_after
            shell(chain_op('-D'))
            conflict_chain = None
            restored = json.loads(cmd('recover'))
            assert not restored['resource_present'] and restored['kernel_chain_present'] == 0
            record('conflicting_rule_preserved_until_owner_removes_it')

        after = snapshot()
        assert before == after, 'Network/SELinux snapshot changed; investigate without blanket restoration'
        if real:
            rules_after = shell("su -c '/system/bin/iptables -w 2 -t filter -S'")
            (out / 'firewall-filter-after.txt').write_text(rules_after)
            report['filter_rules_before_sha256'] = hashlib.sha256(rules_before.encode()).hexdigest()
            report['filter_rules_after_sha256'] = hashlib.sha256(rules_after.encode()).hexdigest()
            assert rules_before == rules_after, 'Filter rule inventory changed; do not apply blanket restore'
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
                    if conflict_chain is not None:
                        shell('su -c ' + shlex.quote(shlex.join(['/system/bin/iptables', '-w', '2', '-t', 'filter', '-D', conflict_chain,
                            '-m', 'comment', '--comment', 'capri-verifier-conflict', '-j', 'RETURN'])))
                    cmd('purge')
                else:
                    report['existing_state_left_untouched'] = True
                shell('rm ' + remote)
                report['device_artifacts_removed'] = True
            except Exception as exc:
                report['cleanup_error'] = str(exc)
                report['device_artifacts_removed'] = False
                report['passed'] = False
        (out / ('firewall-lifecycle-report.json' if real else 'lifecycle-report.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if not report.get('passed'):
        raise RuntimeError('Prototype did not pass; inspect report')
    print('Baseline restored; temporary device artifacts removed.', flush=True)


if __name__ == '__main__':
    main()
