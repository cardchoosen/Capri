"""Git credential helper; secrets travel only through pipes and same-UID local IPC."""
import json
import os
import socket
import sys


def read_request(stream):
    fields = {}
    total = 0
    while True:
        line = stream.readline(16385)
        total += len(line)
        if total > 16384:
            raise ValueError('credential request too large')
        if line in (b'', b'\n'):
            return fields
        key, value = line.rstrip(b'\n').decode('utf-8').split('=', 1)
        if key in ('protocol', 'host', 'username'):
            if key in fields or any(ord(c) < 32 for c in value):
                raise ValueError('invalid credential field')
            fields[key] = value


def main():
    try:
        fields = read_request(sys.stdin.buffer)
        # Git cannot overwrite/delete the global user-managed vault through store/erase.
        if len(sys.argv) != 2 or sys.argv[1] != 'get' or fields.get('protocol') != 'https':
            return
        name = os.environ.get('CAPRI_GIT_SOCKET')
        if not name:
            return
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as channel:
            channel.settimeout(3)
            channel.connect('\0' + name)
            channel.sendall((json.dumps(fields) + '\n').encode())
            data = b''
            while b'\n' not in data:
                block = channel.recv(4096)
                if not block or len(data) + len(block) > 32768:
                    raise ValueError('invalid response')
                data += block
        result = json.loads(data)
        if result:
            username, password = result['username'], result['password']
            if any(c in username + password for c in '\r\n\0'):
                raise ValueError('invalid response')
            sys.stdout.write('username=' + username + '\npassword=' + password + '\n\n')
    except Exception:
        sys.stderr.write('Capri Git 凭据不可用，请检查全局 Git 账号设置。\n')


if __name__ == '__main__':
    main()
