"""UI-only import; stdin contains metadata, never private key bytes. Public inventory is agent-readable."""
import fcntl, json, os, pathlib, re, shutil, subprocess, sys

def manage(root, action, name='', pending=''):
    root = pathlib.Path(root)
    keys = root / 'identities'
    keys.mkdir(mode=0o700, exist_ok=True)
    if keys.is_symlink(): raise ValueError('SSH目录异常')
    with (root / '.identities.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if action == 'import':
            if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]{0,39}', name):
                raise ValueError('名称需为1–40位字母、数字、下划线或短横线')
            if not re.fullmatch(r'import-[a-f0-9-]+', pending): raise ValueError('导入路径异常')
            source = root / pending
            target = keys / name
            pub = keys / (name + '.pub')
            try:
                if source.is_symlink() or not source.is_file(): raise ValueError('导入文件异常')
                if target.exists() or target.is_symlink() or pub.exists() or pub.is_symlink():
                    raise ValueError('同名密钥已存在，请使用其他名称')
                os.chmod(source, 0o600)
                check = subprocess.run(['/usr/bin/ssh-keygen', '-y', '-P', '', '-f', str(source)],
                    capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=10)
                if check.returncode: raise ValueError('私钥无效或受口令保护；当前仅支持无口令私钥')
                # Publish only after validation, never overwrite any existing identity.
                with pub.open('x') as output: output.write(check.stdout.strip() + '\n')
                os.chmod(pub, 0o644)
                created = False
                try:
                    # Android filesystems/SELinux can reject hard links; exclusive create
                    # still guarantees that an existing private key is never overwritten.
                    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    created = True
                    with os.fdopen(descriptor, 'wb') as output, source.open('rb') as input_file:
                        shutil.copyfileobj(input_file, output)
                        output.flush(); os.fsync(output.fileno())
                except BaseException:
                    if created: target.unlink(missing_ok=True)
                    pub.unlink(); raise
            finally:
                source.unlink(missing_ok=True)
        elif action != 'list': raise ValueError('不支持的操作')
        inventory = []
        for pub in sorted(keys.glob('*.pub')):
            private = pub.with_suffix('')
            if pub.is_symlink() or private.is_symlink() or not private.is_file(): continue
            result = subprocess.run(['/usr/bin/ssh-keygen', '-lf', str(pub)], capture_output=True, text=True, timeout=5)
            if result.returncode == 0:
                inventory.append({'name': private.name, 'identity_file': '/opt/capri/ssh/identities/' + private.name,
                                  'fingerprint': result.stdout.strip()})
        data = {'scope': 'application', 'identities': inventory}
        temporary = root / '.identities.json.tmp'
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        os.chmod(temporary, 0o600)
        os.replace(temporary, root / 'identities.json')
        return data

if __name__ == '__main__':
    try:
        request = json.load(sys.stdin)
        print(json.dumps(manage('/opt/capri/ssh', **request), ensure_ascii=False))
    except Exception as error:
        # Do not print subprocess stderr, key bytes or a traceback.
        print(json.dumps({'error': str(error) if isinstance(error, ValueError) else 'SSH密钥管理失败'}, ensure_ascii=False))
        sys.exit(1)
