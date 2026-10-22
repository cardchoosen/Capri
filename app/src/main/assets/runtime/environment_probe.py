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
    for name in ('git', 'curl', 'ssh', 'sftp', 'scp', 'ssh-keygen', 'ssh-keyscan'):
        executable = shutil.which(name)
        try:
            if not executable:
                raise RuntimeError('command not found')
            if name in ('git', 'curl'):
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
    manifest = json.loads(Path('/opt/capri/tools/manifest.json').read_text())
    return {'python': sys.version, 'executable': sys.executable, 'prefix': sys.prefix,
            'base_prefix': sys.base_prefix, 'in_venv': sys.prefix != sys.base_prefix,
            'pip': importlib.metadata.version('pip'), 'site_packages': sysconfig.get_path('purelib'),
            'package_count': len(list(importlib.metadata.distributions())), 'tls': ssl.OPENSSL_VERSION,
            'cert_file': ssl.get_default_verify_paths().cafile, 'scope': 'current chat', 'tools': tools,
            'tools_repository': manifest['repository'],
            'guest_uid': os.getuid(), 'guest_identity': 'PRoot virtual root; Android UID and permissions unchanged',
            'ssh_config': '/opt/capri/ssh/config', 'ssh_known_hosts': '/opt/capri/ssh/known_hosts', 'ssh_scope': 'application',
            'git_https_helper': Path('/opt/capri/tools/usr/libexec/git-core/git-remote-https').is_file(),
            'tools_ca_bundle': Path('/opt/capri/tools/etc/ssl/cert.pem').is_file()}


if __name__ == '__main__':
    print(json.dumps(inspect_environment(), ensure_ascii=False, indent=2))
