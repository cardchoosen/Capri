#!/usr/bin/env python3
"""Verify synthetic IPv4 routing/forwarding in disconnected network namespaces on one explicit device."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[2]
REMOTE = '/data/local/tmp/capri-netns-forwarding-prototype'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--ndk', type=Path, required=True)
    args = parser.parse_args()
    out = ROOT / 'work/hacker-prototype'
    out.mkdir(parents=True, exist_ok=True)
    compiler = args.ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang'
    binary = out / 'capri-netns-forwarding'
    subprocess.run([str(compiler), '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                    str(ROOT / 'tools/hacker/netns_forwarding.c'), '-o', str(binary)], check=True)
    adb = [args.adb, '-s', args.serial]
    report = {'prototype_only': True, 'serial': args.serial, 'real_wifi_traffic_tested': False}
    pushed = False

    def shell(script, root=True, timeout=8):
        command = 'su -c ' + shlex.quote(script) if root else script
        result = subprocess.run(adb + ['shell', command], capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f'Command failed ({result.returncode}): {result.stderr}')
        return result.stdout

    def baseline():
        return {
            'namespace': shell('readlink /proc/self/ns/net').strip(),
            'ipv4_rules': shell('/system/bin/ip -4 rule show'),
            'ipv4_routes': shell('/system/bin/ip -4 route show table all'),
            'filter_rules': shell('/system/bin/iptables -w 2 -t filter -S'),
            'ipv4_parameters': shell('cat /proc/sys/net/ipv4/ip_forward /proc/sys/net/ipv4/ip_default_ttl /proc/sys/net/ipv4/conf/all/accept_redirects /proc/sys/net/ipv4/conf/all/send_redirects /proc/sys/net/ipv4/conf/default/forwarding /proc/sys/net/ipv4/conf/wlan0/forwarding /proc/sys/net/ipv4/conf/wlan0/rp_filter'),
            'selinux': shell('getenforce').strip(),
        }

    try:
        before = baseline()
        if shell('test -e ' + REMOTE + '; printf "%s" "$?"', root=False).strip() != '1':
            raise RuntimeError('Temporary binary exists; refusing overwrite')
        subprocess.run(adb + ['push', str(binary), REMOTE], check=True, capture_output=True, text=True)
        pushed = True
        shell('chmod 700 ' + REMOTE, root=False)
        result = json.loads(shell(REMOTE, timeout=30))
        assert result['isolated'] and result['virtual_route_forwarding']
        assert result['parameters_restored'] and result['virtual_interfaces_removed']
        after = baseline()
        for key, value in before.items():
            if value != after[key]:
                raise RuntimeError(f'Host baseline changed: {key}; inspect without blanket restore')
        report['experiment'] = result
        report['host_before_sha256'] = {key: hashlib.sha256(value.encode()).hexdigest() for key, value in before.items()}
        report['host_after_sha256'] = {key: hashlib.sha256(value.encode()).hexdigest() for key, value in after.items()}
        report['host_baseline_unchanged'] = True
        report['passed'] = True
        print(json.dumps(result, ensure_ascii=False), flush=True)
        print('Host namespace, policy rules, routes, filter rules and checked IPv4/SELinux parameters unchanged.', flush=True)
    except BaseException as exc:
        report['passed'] = False
        report['error'] = str(exc)
        raise
    finally:
        if pushed:
            try:
                shell('rm ' + REMOTE, root=False)
                report['temporary_binary_removed'] = True
            except Exception as exc:
                report['passed'] = False
                report['cleanup_error'] = str(exc)
        (out / 'netns-forwarding-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if not report.get('passed'):
        raise RuntimeError('Verification or cleanup incomplete; inspect report')


if __name__ == '__main__':
    main()
