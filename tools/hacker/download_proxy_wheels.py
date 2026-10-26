#!/usr/bin/env python3
"""Resolve Linux ARM64/Python3.12 wheels on a Mac without executing downloaded code.
The actual phone performs a second offline dependency resolution and pip check.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', args.version):
        parser.error('Release version required')
    args.output.mkdir(parents=True, exist_ok=True)
    # pip's cross-platform wheel flags do not replace host environment markers.
    # Override only marker evaluation in this resolver process, not sys.platform.
    from pip._vendor.packaging import markers
    original = markers.default_environment
    def target_environment():
        values = original()
        values.update(sys_platform='linux', os_name='posix', platform_machine='aarch64', platform_system='Linux',
                      python_version='3.12', python_full_version='3.12.3', implementation_name='cpython', implementation_version='3.12.3')
        return values
    markers.default_environment = target_environment
    from pip._internal.cli.main import main as pip_main
    command = ['download', '--disable-pip-version-check', '--no-input', '--only-binary=:all:', '--index-url', 'https://pypi.org/simple',
               '--dest', str(args.output), '--python-version', '3.12', '--implementation', 'cp', '--abi', 'cp312', '--timeout', '20', '--retries', '1']
    for glibc in range(17, 40):
        command += ['--platform', f'manylinux_2_{glibc}_aarch64']
    command += ['--platform', 'manylinux2014_aarch64', 'mitmproxy==' + args.version]
    result = pip_main(command)
    if result:
        raise SystemExit(result)
    files = [{'filename': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
             for p in sorted(args.output.glob('*.whl'))]
    if any('macosx' in row['filename'] or 'x86_64' in row['filename'] for row in files):
        raise RuntimeError('Wrong target wheel downloaded')
    manifest = {'mitmproxy': args.version, 'target': 'CPython3.12 Linux ARM64', 'source': 'https://pypi.org/simple',
                'files': files, 'total_bytes': sum(row['bytes'] for row in files)}
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'wheels': len(files), 'total_bytes': manifest['total_bytes']}), flush=True)


if __name__ == '__main__':
    main()
