"""Normal per-venv pip installation with a pinned Alpine-native wheel channel.
No rootfs/APK database writes and no system-site-packages leakage between chats.
"""
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import sys


def selection(requirements, manifest):
    from pip._vendor.packaging.requirements import Requirement
    from pip._vendor.packaging.utils import canonicalize_name
    parsed = [Requirement(value) for value in requirements]
    if not parsed or len(parsed) > 20:
        raise ValueError('需要1..20个包规格')
    for req in parsed:
        if req.url or req.marker or canonicalize_name(req.name) == 'pip':
            raise ValueError('此入口仅支持包名、extras和版本条件；pip由引导维护')
    available = manifest['packages']
    offline = all(not req.extras and canonicalize_name(req.name) in available and
                  req.specifier.contains(available[canonicalize_name(req.name)]['version'], prereleases=True)
                  for req in parsed)
    return 'bundled-alpine' if offline else 'pypi-and-bundled'


def install(requirements, root=Path('/opt/capri/python-packages')):
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest['architecture'] != platform.machine() or manifest['python_abi'] != 'cp%d%d' % sys.version_info[:2]:
        raise RuntimeError('内置原生包与Python/CPU不匹配，请检查环境版本')
    source = selection(requirements, manifest)
    for entry in manifest['packages'].values():
        wheel = root / 'wheels' / entry['wheel']
        if wheel.parent != root / 'wheels' or hashlib.sha256(wheel.read_bytes()).hexdigest() != entry['sha256']:
            raise RuntimeError('内置Python包校验失败，不继续安装')
    print('Capri安装渠道：' + ('内置Alpine官方预编译包（离线）' if source == 'bundled-alpine' else 'PyPI兼容wheel + 内置Alpine包'), flush=True)
    command = [sys.executable, '-I', '-m', 'pip', '--isolated', '--disable-pip-version-check', '--no-input',
               '--timeout', '20', '--retries', '2', 'install', '--only-binary=:all:', '--find-links', str(root / 'wheels')]
    if source == 'bundled-alpine': command.append('--no-index')
    result = subprocess.run(command + requirements)
    if result.returncode:
        print('安装未完成：请检查上方具体的版本/依赖/网络错误；不自动改为源码编译或放宽版本条件。', flush=True)
    return result.returncode


if __name__ == '__main__':
    try: sys.exit(install(sys.argv[1:]))
    except Exception as error:
        print('Python包安装失败：' + str(error)[:1000], file=sys.stderr)
        sys.exit(1)
