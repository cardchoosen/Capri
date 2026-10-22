"""Local diagnostics only; does not contact repositories or download files."""
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import sysconfig


def inspect_environment():
    tools = {}
    for name in ('git', 'curl', 'ssh', 'sftp', 'scp', 'ssh-keygen', 'ssh-keyscan', 'node', 'npm', 'npx', 'gcc', 'g++', 'make', 'pkg-config'):
        executable = shutil.which(name)
        try:
            if not executable:
                raise RuntimeError('command not found')
            if name not in ('ssh', 'sftp', 'scp', 'ssh-keygen', 'ssh-keyscan'):
                command = [executable, '--version']
            else:
                # SSH is the package version authority; other tools are probed for presence.
                command = [shutil.which('ssh'), '-V']
            result = subprocess.run(command, capture_output=True, text=True, timeout=5)
            version = result.stdout or result.stderr
            tools[name] = {'available': result.returncode == 0, 'path': executable,
                           'exit_code': result.returncode, 'version': version.splitlines()[0] if version else '',
                           'error': result.stderr[:1000] if result.returncode else ''}
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            tools[name] = {'available': False, 'path': executable, 'error': str(error)[:1000]}
    from pip._vendor.packaging.tags import platform_tags
    native_manifest = json.loads(Path('/opt/capri/python-packages/manifest.json').read_text())
    libraries = {}
    for name in ('pillow', 'numpy', 'matplotlib'):
        try:
            distribution = importlib.metadata.distribution(name)
            origin = distribution.read_text('capri-origin.json')
            libraries[name] = {'installed': True, 'version': distribution.version,
                               'source': json.loads(origin)['source'] if origin else 'venv / pip'}
        except importlib.metadata.PackageNotFoundError:
            libraries[name] = {'installed': False}
    manifest = json.loads(Path('/opt/capri/tools/manifest.json').read_text())
    return {'python': sys.version, 'executable': sys.executable, 'prefix': sys.prefix,
            'base_prefix': sys.base_prefix, 'in_venv': sys.prefix != sys.base_prefix,
            'pip': importlib.metadata.version('pip'), 'site_packages': sysconfig.get_path('purelib'),
            'package_count': len(list(importlib.metadata.distributions())), 'tls': ssl.OPENSSL_VERSION,
            'cert_file': ssl.get_default_verify_paths().cafile, 'scope': 'current chat', 'tools': tools,
            'tools_repository': manifest['repository'],
            'development': {'npm_prefix': os.environ.get('npm_config_prefix'),
                            'npm_cache': os.environ.get('npm_config_cache'),
                            'node_icu_data': os.environ.get('NODE_ICU_DATA'),
                            'compiler_sysroot': '/opt/capri/tools',
                            'python_headers': Path('/opt/capri/tools/usr/include/python3.12/Python.h').is_file(),
                            'scope': 'shared binaries; per-chat HOME/cache/global npm and project node_modules',
                            'source_compilation': 'C/C++ toolchain available; python_env still wheel-only'},
            'platform_tags': list(platform_tags()), 'scientific_libraries': libraries,
            'bundled_alpine_packages': {name: info['version'] for name, info in native_manifest['packages'].items()},
            'matplotlib_backend': os.environ.get('MPLBACKEND'),
            'guest_uid': os.getuid(), 'guest_identity': 'PRoot virtual root; Android UID and permissions unchanged',
            'ssh_config': '/opt/capri/ssh/config', 'ssh_known_hosts': '/opt/capri/ssh/known_hosts', 'ssh_scope': 'application',
            'git_https_helper': Path('/opt/capri/tools/usr/libexec/git-core/git-remote-https').is_file(),
            'tools_ca_bundle': Path('/opt/capri/tools/etc/ssl/cert.pem').is_file()}


if __name__ == '__main__':
    print(json.dumps(inspect_environment(), ensure_ascii=False, indent=2))
