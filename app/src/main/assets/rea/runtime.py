"""Managed REA runtime. Installation is explicit; serving never downloads or executes targets."""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import select
import signal
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile

ENGINE = "engine/node_modules/rea-agents"
PROFILES = {
    'apk': 'android,apktool,artifact,application,session',
    'javascript': 'artifact,application,session',
    'native': 'direct,enhanced,native,application,session',
    'runtime': 'frida,application,session',
}
ROOT = Path('/opt/capri/rea')
SOURCE = Path(__file__).with_name('source.json')


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix('.pending')
    tmp.write_text(json.dumps(value, ensure_ascii=False))
    os.replace(tmp, path)


def digest(path, algorithm):
    h = hashlib.new(algorithm)
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.digest()


def progress(stage):
    atomic_json(ROOT / 'progress.json', {'stage': stage, 'updated_at': int(time.time())})


def fetch(spec, directory, limit=200_000_000):
    url = spec['url']
    if not url.startswith('https://'):
        raise ValueError('Dependency URL must use HTTPS.')
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / hashlib.sha256(url.encode()).hexdigest()
    def valid():
        if not target.is_file(): return False
        if 'sha256' in spec: return digest(target, 'sha256').hex() == spec['sha256']
        algorithm, encoded = spec['integrity'].split('-', 1)
        if algorithm != 'sha512': raise ValueError('Unsupported integrity algorithm.')
        return base64.b64encode(digest(target, algorithm)).decode() == encoded
    if valid(): return target
    progress('Downloading ' + url.rsplit('/',1)[-1])
    partial = target.with_suffix('.download')
    try:
        with urllib.request.urlopen(url, timeout=45) as response, partial.open('wb') as out:
            if not response.url.startswith('https://'): raise ValueError('Insecure dependency redirect.')
            total = 0
            for chunk in iter(lambda: response.read(1024 * 1024), b''):
                total += len(chunk)
                if total > limit: raise ValueError('Dependency exceeds its download limit.')
                out.write(chunk)
        os.replace(partial, target)
        if not valid():
            target.unlink()
            raise ValueError('Dependency integrity mismatch.')
        return target
    finally:
        partial.unlink(missing_ok=True)


def extract(archive, destination):
    # Python's data filter rejects devices, escaping links, and absolute member paths.
    if not hasattr(tarfile, 'data_filter'): raise RuntimeError('Python with safe tar extraction filters is required.')
    with tarfile.open(archive) as tar:
        members = tar.getmembers()
        if len(members) > 30000 or sum(m.size for m in members) > 600_000_000:
            raise ValueError('Dependency archive exceeds extraction limits.')
        tar.extractall(destination, members=members, filter='data')


def run(args, env=None, timeout=900, cwd=None):
    # No shell interpolation. A timeout is a failure, never permission to replay the target.
    stage = 'Building Ghidra ARM64' if args[0]=='make' else 'Installing pinned npm dependencies' if any('npm-cli.js' in a for a in args) else 'Preparing Ubuntu packages' if args[0].endswith('apt-get') else 'Running '+Path(args[0]).name
    progress(stage)
    print('Preparing: ' + args[0], file=sys.stderr, flush=True)
    with tempfile.TemporaryFile() as log:
        process = subprocess.run(args, env=env, timeout=timeout, cwd=cwd, stdout=log, stderr=log)
        size=log.tell();log.seek(0);head=log.read(3500).decode(errors='replace')
        log.seek(max(0,size-3500));tail=log.read().decode(errors='replace')
        if process.returncode: raise RuntimeError(f"Command failed ({process.returncode}): {head}\n{tail}")
        print(tail, file=sys.stderr, flush=True)


def environment(profile, root=ROOT):
    if profile not in PROFILES: raise ValueError('Unknown REA profile.')
    env = dict(os.environ)
    env.update({
        'PATH': str(root / 'node/bin') + ':/usr/local/bin:/usr/bin:/bin',
        'REA_MCP_TOOL_FAMILIES': PROFILES[profile],
        'REA_MCP_INPUT_SCHEMA_PROFILE': 'compact',
        'REA_MCP_OUTPUT_SCHEMA_PROFILE': 'off',
        'REA_MCP_MAX_RESPONSE_BYTES': '10485760',
        'REA_JADX_MCP_JAR': str(root / 'jadx.jar'),
        'REA_JADX_HEAP_MIB': '768', 'REA_JADX_ACTIVE_PROCESSOR_COUNT': '2',
        'REA_APKTOOL_COMMAND': str(root/'apktool'),
        'GHIDRA_INSTALL_DIR': str(root / 'ghidra'),
        'GHIDRA_HEADLESS_MAXMEM': '1024M',
        'NODE_OPTIONS': '--max-old-space-size=512',
        'XDG_CACHE_HOME': str(Path.home() / '.cache'),
        'XDG_STATE_HOME': str(Path.home() / '.local/state'),
        'npm_config_cache': '/opt/capri/cache/npm',
        'npm_config_audit': 'false', 'npm_config_fund': 'false',
    })
    return env


def status(profile, root=ROOT):
    marker = root / ('installed-' + profile + '.json')
    try: state = json.loads(marker.read_text()) if marker.is_file() else {}
    except (ValueError, OSError): state = {}
    required=['node/bin/node',ENGINE+'/dist/mcp/main.js']
    if profile=='apk': required += ['jadx.jar']
    if profile=='native': required += ['ghidra/Ghidra/Features/Decompiler/os/linux_arm_64/decompile']
    if profile=='runtime': required += ['frida/node_modules/frida/build/frida_binding.node']
    return {'profile': profile, 'installed': bool(state) and all((root / p).exists() for p in required),
            'installation': state, 'validation': 'unverified',
            'note': 'Installed files do not prove provider readiness. Run doctor and verify a real target.'}


def prepare(profile):
    if platform.system() != 'Linux' or platform.machine() not in ('aarch64', 'arm64'):
        raise ValueError('Managed installation requires Linux ARM64.')
    spec = json.loads(SOURCE.read_text())
    if digest(SOURCE.with_name('engine-lock.json'), 'sha256').hex() != spec['lock_sha256']:
        raise ValueError('REA dependency lock digest mismatch.')
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT / 'prepare.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        state = status(profile)
        if state['installed'] and state['installation'].get('source') == spec:
            return state
        required = 3_500_000_000 if profile == 'native' else 900_000_000
        if shutil.disk_usage(ROOT).free < required:
            raise RuntimeError('Insufficient application storage for REA preparation.')
        cache = Path('/opt/capri/cache/rea')
        if not (ROOT / 'node/bin/node').exists():
            archive = fetch(spec['node'], cache)
            with tempfile.TemporaryDirectory(dir=ROOT) as tmp:
                progress('Extracting Node runtime')
                extract(archive, tmp)
                node = next(Path(tmp).iterdir())
                os.replace(node, ROOT / 'node')
        env = environment(profile)
        if not (ROOT / ENGINE / 'dist/mcp/main.js').is_file():
            lock_spec = json.loads(SOURCE.with_name('engine-lock.json').read_text())
            if lock_spec['packages']['node_modules/rea-agents']['integrity'] != spec['rea']['integrity']:
                raise ValueError('REA dependency lock does not match the pinned release.')
            with tempfile.TemporaryDirectory(dir=ROOT, prefix='.engine-') as tmp:
                engine = Path(tmp)
                shutil.copyfile(SOURCE.with_name('engine-package.json'), engine/'package.json')
                shutil.copyfile(SOURCE.with_name('engine-lock.json'), engine/'package-lock.json')
                run([str(ROOT/'node/bin/node'), str(ROOT/'node/lib/node_modules/npm/bin/npm-cli.js'),
                     'ci', '--omit=dev', '--omit=optional', '--ignore-scripts', '--no-audit', '--no-fund'],
                    env=env, cwd=engine)
                os.replace(engine, ROOT / 'engine')
        packages = ['procps', 'binutils']
        if profile in ('apk','native'): packages += ['openjdk-21-jdk-headless']
        if profile == 'native': packages += ['make','g++','bison','flex']
        missing = [p for p in packages if subprocess.run(['dpkg-query', '-W', '-f=${Status}', p], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True).stdout != 'install ok installed']
        if missing:
            apt_env = dict(env, DEBIAN_FRONTEND='noninteractive')
            run(['/usr/bin/apt-get', 'update'], env=apt_env, timeout=300)
            run(['/usr/bin/apt-get', 'install', '-y', '--no-install-recommends', *missing], env=apt_env)
        if profile == 'apk' and not (ROOT / 'jadx.jar').exists():
            shutil.copyfile(fetch(spec['jadx'], cache), ROOT / 'jadx.jar')
        if profile == 'apk':
            if not (ROOT/'apktool.jar').exists(): shutil.copyfile(fetch(spec['apktool'],cache),ROOT/'apktool.jar')
            launcher=ROOT/'apktool'
            launcher.write_text('#!/bin/sh\nexec /usr/bin/java -jar /opt/capri/rea/apktool.jar "$@"\n')
            launcher.chmod(0o755)
        if profile == 'native' and not (ROOT/'ghidra/Ghidra/Features/Decompiler/os/linux_arm_64/decompile').exists():
            archive = fetch(spec['ghidra'], cache, limit=700_000_000)
            stage=ROOT/'.ghidra-staging';stage.mkdir(exist_ok=True);tmp=str(stage)
            marker=stage/'archive-sha256'
            if not marker.is_file() or marker.read_text()!=spec['ghidra']['sha256']:
                if marker.exists(): raise RuntimeError('Ghidra staging belongs to a different release.')
                progress('Extracting Ghidra')
                with zipfile.ZipFile(archive) as z:
                    files = z.infolist()
                    if len(files)>100000 or sum(x.file_size for x in files)>3_000_000_000: raise ValueError('Ghidra archive exceeds limits.')
                    for member in files:
                        path = Path(tmp, member.filename)
                        if not path.resolve().is_relative_to(Path(tmp).resolve()): raise ValueError('Ghidra member escapes installation.')
                        if member.is_dir(): path.mkdir(parents=True,exist_ok=True); continue
                        path.parent.mkdir(parents=True,exist_ok=True)
                        with z.open(member) as src, path.open('wb') as dst: shutil.copyfileobj(src,dst)
                        path.chmod(((member.external_attr>>16)&0o777) or 0o644)
                marker.write_text(spec['ghidra']['sha256'])
            ghidra=Path(tmp)/'ghidra_12.1.4_PUBLIC'
            source=ghidra/'Ghidra/Features/Decompiler/src/decompile/cpp'
            run(['make','-j2','ghidra_opt','ARCH_TYPE=','OSDIR=linux_arm_64'],env=env,cwd=source,timeout=1800)
            destination=ghidra/'Ghidra/Features/Decompiler/os/linux_arm_64/decompile';destination.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(source/'ghidra_opt',destination);destination.chmod(0o755)
            os.replace(ghidra,ROOT/'ghidra')
        if profile == 'runtime':
            if digest(SOURCE.with_name('frida-lock.json'),'sha256').hex()!=spec['frida_lock_sha256']:
                raise ValueError('Frida dependency lock digest mismatch.')
            if not (ROOT/'frida/node_modules/frida/build/frida_binding.node').is_file():
                with tempfile.TemporaryDirectory(dir=ROOT,prefix='.frida-') as tmp:
                    target=Path(tmp)
                    shutil.copyfile(SOURCE.with_name('frida-package.json'),target/'package.json')
                    shutil.copyfile(SOURCE.with_name('frida-lock.json'),target/'package-lock.json')
                    run([str(ROOT/'node/bin/node'),str(ROOT/'node/lib/node_modules/npm/bin/npm-cli.js'),'ci','--omit=dev','--omit=optional','--ignore-scripts','--no-audit','--no-fund'],env=env,cwd=target)
                    module=target/'node_modules/frida'
                    extract(fetch(spec['frida_binding'],cache),module)
                    binding=module/'build/frida_binding.node'
                    with binding.open('rb') as stream: header=stream.read(20)
                    if header[:5]!=b'\x7fELF\x02' or int.from_bytes(header[18:20],'little')!=183:
                        raise ValueError('Frida binding must be an ARM64 ELF shared library.')
                    old=ROOT/'frida.previous'
                    if old.exists(): shutil.rmtree(old)
                    if (ROOT/'frida').exists(): os.replace(ROOT/'frida',old)
                    os.replace(target,ROOT/'frida')
                    if old.exists(): shutil.rmtree(old)
            native=ROOT/'frida/node_modules/frida';binding=ROOT/'engine/node_modules/frida'
            if not binding.exists(): binding.symlink_to(native)
        atomic_json(ROOT / ('installed-' + profile + '.json'), {'source': spec, 'prepared_at': int(time.time()),
            'limitations': ['Provider readiness must be verified on the current device.',
                             'Runtime instrumentation requires compatible Frida bindings and an explicitly selected device/server.',
                             'PTY-dependent workflows are not enabled by this static installation.']})
        progress(profile + ': dependencies installed; provider verification required')
        return status(profile)


def filter_message(message, side, allowed):
    """Scope both discovery and dispatch without changing the advertised schemas."""
    if side == 'client' and message.get('method') == 'tools/call':
        if message.get('params', {}).get('name') not in allowed:
            return {'jsonrpc':'2.0', 'id':message.get('id'), 'error':{'code':-32601, 'message':'Tool is not enabled in this REA profile.'}}, False
    if side == 'server':
        result=message.get('result')
        if isinstance(result,dict) and isinstance(result.get('tools'),list):
            result['tools']=[tool for tool in result['tools'] if tool.get('name') in allowed]
    return message, True


def bounded_response(message, workspace=Path('/workspace'), limit=2_000_000):
    encoded=json.dumps(message,separators=(',',':')).encode()
    if len(encoded)<=limit: return encoded
    result=message.get('result')
    if not isinstance(result,dict) or 'content' not in result:
        raise RuntimeError('REA control response exceeds the delivery budget.')
    directory=workspace/'.capri/rea/results'
    if not directory.resolve().is_relative_to(workspace.resolve()):
        raise RuntimeError('REA result directory escapes the workspace.')
    directory.mkdir(parents=True,exist_ok=True)
    digest=hashlib.sha256(encoded).hexdigest();target=directory/(digest+'.json')
    if target.is_symlink(): raise RuntimeError('REA retained result must not be a symbolic link.')
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest()!=digest:
        raise RuntimeError('REA retained result changed; it was not overwritten.')
    if not target.exists():
        with target.open('xb') as stream: stream.write(encoded)
        target.chmod(0o600)
    saved={'status':'result_saved','result_file':str(target.relative_to(workspace)),
           'bytes':len(encoded),'sha256':digest,'source_is_error':result.get('isError',False),
           'note':'The original response is retained. Read it in bounded sections; saving is not proof of successful analysis.'}
    return json.dumps({'jsonrpc':'2.0','id':message.get('id'),'result':{'content':[{'type':'text','text':json.dumps(saved)}],'isError':result.get('isError',False)}},separators=(',',':')).encode()


def serve(command, env, profile):
    allowed=set(json.loads(SOURCE.with_name('profile-tools.json').read_text())[profile])
    child=subprocess.Popen(command+['mcp'],env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=sys.stderr)
    streams={sys.stdin.fileno():('client',bytearray()),child.stdout.fileno():('server',bytearray())}
    limit=11_000_000
    def stop(_signal=None,_frame=None):
        if child.stdin and not child.stdin.closed: child.stdin.close()
    signal.signal(signal.SIGTERM,stop)
    try:
        while child.poll() is None:
            ready,_,_=select.select(list(streams),[],[],0.2)
            for descriptor in ready:
                side,buffer=streams[descriptor];chunk=os.read(descriptor,65536)
                if not chunk: return
                buffer.extend(chunk)
                if len(buffer)>limit: raise RuntimeError('REA protocol message exceeds its transport budget.')
                while b'\n' in buffer:
                    raw,_,remaining=buffer.partition(b'\n');buffer[:]=remaining
                    if not raw.strip(): continue
                    value,forward=filter_message(json.loads(raw),side,allowed)
                    target=child.stdin if side=='client' and forward else sys.stdout.buffer
                    target.write((bounded_response(value) if side=='server' else json.dumps(value,separators=(',',':')).encode())+b'\n');target.flush()
    finally:
        stop()
        try: child.wait(timeout=15)
        except subprocess.TimeoutExpired:
            child.terminate()
            try: child.wait(timeout=3)
            except subprocess.TimeoutExpired: child.kill();child.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'status', 'serve', 'doctor'])
    parser.add_argument('profile', choices=PROFILES)
    args = parser.parse_args()
    if args.action == 'prepare': result = prepare(args.profile)
    elif args.action == 'status': result = status(args.profile)
    else:
        if not status(args.profile)['installed']: raise RuntimeError('REA is not prepared; activate its managed MCP first.')
        env = environment(args.profile)
        command = [str(ROOT/'node/bin/node'), str(ROOT/ENGINE/'scripts/rea.mjs')]
        if args.action == 'serve': serve(command,env,args.profile)
        else: os.execve(command[0], command+['doctor','--json'],env)
        return
    print(json.dumps(result))


if __name__ == '__main__':
    try: main()
    except Exception as error:
        if ROOT.is_dir(): progress('Failed: ' + str(error)[:800])
        print(json.dumps({'status': 'failed', 'error': str(error), 'outcome': 'inspect_before_retry'}), file=sys.stderr if sys.argv[1:2]==['serve'] else sys.stdout)
        sys.exit(1)
