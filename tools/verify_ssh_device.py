"""Development-only loopback SSH/SFTP server and Android instrumentation driver.
Requires paramiko in the selected interpreter (or --dependencies directory).
ADB reverse is only a test transport and is removed afterwards; Capri runtime never needs it.
"""
import argparse
from pathlib import Path
import os
import shlex
import socket
import subprocess
import sys
import tempfile
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--dependencies', type=Path)
    args = parser.parse_args()
    if args.dependencies:
        sys.path.insert(0, str(args.dependencies.resolve()))
    import paramiko
    base = Path(__file__).resolve().parents[1]
    logs = base / 'work/diagnostics'
    logs.mkdir(parents=True, exist_ok=True)

    def adb(*command, **kwargs):
        return subprocess.run([args.adb, '-d', *command], check=True, **kwargs)

    def instrumentation(name, **parameters):
        command = ['shell', 'am', 'instrument', '-w', '-r']
        for key, value in parameters.items():
            command += ['-e', key, str(value)]
        command += ['-e', 'class', 'ai.capri.app.SshClientDeviceTest#' + name,
                    'ai.capri.app.test/androidx.test.runner.AndroidJUnitRunner']
        result = adb(*command, capture_output=True, text=True, timeout=180)
        text = result.stdout + result.stderr
        (logs / ('ssh-' + name + '.log')).write_text(text)
        print(text, flush=True)
        if 'OK (1 test)' not in text:
            raise RuntimeError('SSH device acceptance failed: ' + name)

    try:
        instrumentation('prepareFixture', ssh_fixture='prepare')
    except Exception:
        adb('shell', 'run-as', 'ai.capri.app', 'rm', '-rf', 'files/sessions/ssh-client-test', capture_output=True)
        raise
    public = adb('exec-out', 'run-as', 'ai.capri.app', 'cat',
                 'files/sessions/ssh-client-test/workspace/ssh-fixture/id_ed25519.pub', capture_output=True, text=True).stdout.split()[1]
    host_key = paramiko.RSAKey.generate(2048)
    changed_key = paramiko.RSAKey.generate(2048)
    sockets = []
    stopped = threading.Event()
    with tempfile.TemporaryDirectory(prefix='ssh-fixture-', dir=base / 'work') as temporary:
        root = Path(temporary)
        transfer = root / 'transfer'
        transfer.mkdir()
        repo = root / 'repo'
        subprocess.run(['git', 'init', '-q', str(repo)], check=True)
        (repo / 'README').write_text('Capri SSH Git fixture\n')
        subprocess.run(['git', '-C', str(repo), 'add', 'README'], check=True)
        subprocess.run(['git', '-C', str(repo), '-c', 'user.name=Capri Test', '-c', 'user.email=test@example.invalid',
                        'commit', '-qm', 'fixture'], check=True)

        class Files(paramiko.SFTPServerInterface):
            def path(self, value):
                candidate = (transfer / value.lstrip('/')).resolve()
                if not candidate.is_relative_to(transfer):
                    raise OSError('outside test root')
                return candidate

            def canonicalize(self, path):
                relative = self.path(path).relative_to(transfer).as_posix()
                return '/' if relative == '.' else '/' + relative

            def stat(self, path):
                try: return paramiko.SFTPAttributes.from_stat(self.path(path).stat())
                except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno or 2)

            lstat = stat

            def open(self, path, flags, attr):
                try:
                    descriptor = os.open(self.path(path), flags, attr.st_mode or 0o600)
                    stream = os.fdopen(descriptor, 'r+b' if flags & os.O_RDWR else 'wb' if flags & os.O_WRONLY else 'rb')
                    handle = paramiko.SFTPHandle(flags)
                    if flags & (os.O_WRONLY | os.O_RDWR): handle.writefile = stream
                    if not flags & os.O_WRONLY: handle.readfile = stream
                    handle.stat = lambda: paramiko.SFTPAttributes.from_stat(os.fstat(stream.fileno()))
                    def attributes(value):
                        try:
                            if value.st_mode is not None: os.fchmod(stream.fileno(), value.st_mode)
                            if value.st_size is not None: os.ftruncate(stream.fileno(), value.st_size)
                            if value.st_atime is not None and value.st_mtime is not None:
                                os.utime(stream.fileno(), (value.st_atime, value.st_mtime))
                            return paramiko.SFTP_OK
                        except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno or 2)
                    handle.chattr = attributes
                    return handle
                except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno or 2)

            def chattr(self, path, attr):
                try: paramiko.SFTPServer.set_file_attr(str(self.path(path)), attr); return paramiko.SFTP_OK
                except OSError as error: return paramiko.SFTPServer.convert_errno(error.errno or 2)

        class SftpService(paramiko.SFTPServer):
            def finish_subsystem(self):
                # OpenSSH scp checks the SSH process exit status after SFTP EOF.
                try: self.sock.send_exit_status(0)
                except (OSError, EOFError): pass
                super().finish_subsystem()

        class Server(paramiko.ServerInterface):
            def get_allowed_auths(self, username): return 'publickey'
            def check_auth_publickey(self, username, key):
                return paramiko.AUTH_SUCCESSFUL if username == 'fixture-user' and key.get_base64() == public else paramiko.AUTH_FAILED
            def check_channel_request(self, kind, channel):
                return paramiko.OPEN_SUCCEEDED if kind == 'session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
            def check_channel_exec_request(self, channel, command):
                def execute():
                    time.sleep(0.05)
                    process = None
                    try:
                        if command == b'capri-echo': channel.sendall(b'fixture-remote-ok\n'); status = 0
                        elif command == b'capri-fail': status = 17
                        elif command == b'capri-sleep':
                            for _ in range(300):
                                if channel.closed or stopped.wait(0.1): break
                            status = 0
                        elif shlex.split(command.decode()) == ['git-upload-pack', '/repo']:
                            process = subprocess.Popen(['/usr/bin/git-upload-pack', str(repo)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                            def feed():
                                try:
                                    while block := channel.recv(32768): process.stdin.write(block); process.stdin.flush()
                                except (OSError, EOFError): pass
                                finally:
                                    try: process.stdin.close()
                                    except OSError: pass
                            threading.Thread(target=feed, daemon=True).start()
                            while block := process.stdout.read1(32768): channel.sendall(block)
                            status = process.wait(20)
                        else: status = 127
                        channel.send_exit_status(status)
                    except (OSError, EOFError, paramiko.SSHException): pass
                    finally:
                        if process and process.poll() is None: process.kill()
                        channel.close()
                threading.Thread(target=execute, daemon=True).start()
                return True

        listener = socket.socket()
        listener.bind(('127.0.0.1', 0)); listener.listen(8); listener.settimeout(1)
        port = listener.getsockname()[1]

        def connection(client):
            transport = paramiko.Transport(client)
            sockets.append(transport)
            try:
                transport.add_server_key(host_key)
                transport.set_subsystem_handler('sftp', SftpService, Files)
                transport.start_server(server=Server())
                while transport.is_active() and not stopped.wait(0.1): pass
            except (OSError, EOFError, paramiko.SSHException): pass
            finally: transport.close()

        def accept():
            while not stopped.is_set():
                try: client, _ = listener.accept()
                except socket.timeout: continue
                except OSError: break
                threading.Thread(target=connection, args=(client,), daemon=True).start()
        threading.Thread(target=accept, daemon=True).start()
        try:
            adb('reverse', 'tcp:' + str(port), 'tcp:' + str(port), capture_output=True)
            instrumentation('sshSftpScpAndGitUseKeysAndRejectUnknownOrChangedHosts', ssh_fixture_port=port,
                            ssh_host_key=host_key.get_base64(), ssh_changed_key=changed_key.get_base64())
            assert (transfer / 'upload.txt').read_bytes() == (transfer / 'scp.txt').read_bytes()
            print('SSH remote status, SFTP/SCP round trips, SSH Git, host verification and timeout passed.', flush=True)
        finally:
            stopped.set(); listener.close()
            for transport in sockets: transport.close()
            adb('reverse', '--remove', 'tcp:' + str(port), capture_output=True)
            adb('shell', 'run-as', 'ai.capri.app', 'rm', '-rf', 'files/sessions/ssh-client-test', capture_output=True)


if __name__ == '__main__':
    main()
