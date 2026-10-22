"""Initialize one chat's venv offline, then execute its command. This is not a security sandbox."""
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import venv


def ensure_environment(home):
    home = Path(home)
    home.mkdir(parents=True, exist_ok=True)
    env = home / '.venv'
    python = env / 'bin/python3'
    with (home / '.capri-venv.lock').open('a') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        if not python.exists():
            venv.EnvBuilder(with_pip=False, symlinks=True).create(env)
        if not (env / 'pyvenv.cfg').is_file():
            raise RuntimeError('会话虚拟环境配置缺失；请先诊断，不自动删除已有依赖。')
        pip = env / ('lib/python%d.%d/site-packages/pip/__init__.py' % sys.version_info[:2])
        seeding = home / '.capri-pip-seeding'
        if seeding.exists():
            # Only recover pip files owned by an interrupted seed, never clear the user's venv.
            site = pip.parent.parent
            for partial in [site / 'pip', *site.glob('pip-*.dist-info')]:
                if partial.is_symlink() or partial.is_file():
                    partial.unlink()
                elif partial.is_dir():
                    shutil.rmtree(partial)
            for name in ('pip', 'pip3', 'pip%d.%d' % sys.version_info[:2]):
                (env / 'bin' / name).unlink(missing_ok=True)
        if not pip.is_file():
            seeding.touch()
            log = home / '.capri-bootstrap.log'
            with log.open('w') as output:
                result = subprocess.run([str(python), '-m', 'ensurepip', '--upgrade', '--default-pip'],
                                        stdout=output, stderr=subprocess.STDOUT, timeout=20)
            if result.returncode or not pip.is_file():
                raise RuntimeError('离线引导 pip 失败：' + log.read_text(errors='replace')[-4000:])
            with log.open('a') as output:
                verified = subprocess.run([str(python), '-I', '-c', 'import pip._internal.cli.main'],
                                          stdout=output, stderr=subprocess.STDOUT, timeout=10)
            if verified.returncode:
                raise RuntimeError('pip完整性检查失败：' + log.read_text(errors='replace')[-4000:])
            seeding.unlink()
        marker = home / '.capri-environment.json'
        temporary = marker.with_suffix('.tmp')
        temporary.write_text(json.dumps({'schema': 1, 'python': str(python), 'base_version': sys.version,
                                         'pip_seed': 'bundled ensurepip; no download', 'scope': 'current chat'}))
        temporary.replace(marker)
    return python


def main():
    python = ensure_environment('/root')
    environment = dict(os.environ)
    environment.update(VIRTUAL_ENV='/root/.venv', PYTHONNOUSERSITE='1',
                       PATH='/root/.venv/bin:/opt/capri/bootstrap/bin:/opt/capri/tools/usr/bin:/usr/local/bin:/usr/bin:/bin',
                       LD_LIBRARY_PATH='/opt/capri/tools/usr/lib:/opt/capri/tools/lib',
                       GIT_EXEC_PATH='/opt/capri/tools/usr/libexec/git-core',
                       GIT_TEMPLATE_DIR='/opt/capri/tools/usr/share/git-core/templates',
                       GIT_TERMINAL_PROMPT='0', GIT_PAGER='cat',
                       GIT_SSL_CAINFO='/opt/capri/tools/etc/ssl/cert.pem',
                       CURL_CA_BUNDLE='/opt/capri/tools/etc/ssl/cert.pem')
    # Runtime configuration, not credential material. Empty helper resets file-based helpers.
    git_config = [('credential.helper', ''),
                  ('credential.helper', '!/root/.venv/bin/python3 -I /opt/capri/bootstrap/git_credential.py'),
                  ('credential.useHttpPath', 'true'), ('http.followRedirects', 'false')]
    environment['GIT_CONFIG_COUNT'] = str(len(git_config))
    for index, (key, value) in enumerate(git_config):
        environment['GIT_CONFIG_KEY_%d' % index] = key
        environment['GIT_CONFIG_VALUE_%d' % index] = value
    command = sys.argv[1:]
    if not command:
        raise RuntimeError('Missing runtime command')
    if command[0] in ('python', 'python3', '/usr/bin/python', '/usr/bin/python3'):
        command[0] = str(python)
    os.execvpe(command[0], command, environment)


if __name__ == '__main__':
    main()
