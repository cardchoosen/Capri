"""Local diagnostics only; does not contact repositories or download files."""
import importlib.metadata
import json
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import sysconfig


def inspect_environment():
    tools = {}
    for name in ('git', 'curl'):
        executable = shutil.which(name)
        try:
            if not executable:
                raise RuntimeError('command not found')
            result = subprocess.run([executable, '--version'], capture_output=True, text=True, timeout=5)
            tools[name] = {'available': result.returncode == 0, 'path': executable,
                           'exit_code': result.returncode, 'version': result.stdout.splitlines()[0] if result.stdout else '',
                           'error': result.stderr[:1000]}
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            tools[name] = {'available': False, 'path': executable, 'error': str(error)[:1000]}
    manifest = json.loads(Path('/opt/capri/tools/manifest.json').read_text())
    return {'python': sys.version, 'executable': sys.executable, 'prefix': sys.prefix,
            'base_prefix': sys.base_prefix, 'in_venv': sys.prefix != sys.base_prefix,
            'pip': importlib.metadata.version('pip'), 'site_packages': sysconfig.get_path('purelib'),
            'package_count': len(list(importlib.metadata.distributions())), 'tls': ssl.OPENSSL_VERSION,
            'cert_file': ssl.get_default_verify_paths().cafile, 'scope': 'current chat', 'tools': tools,
            'tools_repository': manifest['repository'],
            'git_https_helper': Path('/opt/capri/tools/usr/libexec/git-core/git-remote-https').is_file(),
            'tools_ca_bundle': Path('/opt/capri/tools/etc/ssl/cert.pem').is_file()}


if __name__ == '__main__':
    print(json.dumps(inspect_environment(), ensure_ascii=False, indent=2))
