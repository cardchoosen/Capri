#!/usr/bin/env python3
"""Debug APK only. Real chat requests through ADB; never reads model credentials.

Examples:
  python3 tools/capri_debug.py send '你是谁' --wait
  python3 tools/capri_debug.py status REQUEST_UUID --offset 0
  python3 tools/capri_debug.py cancel REQUEST_UUID

send defaults to a dedicated new chat, does not change the visible selection.
Request IDs deduplicate within this app process (last 100 requests); after process
restart, unknown requests MUST be inspected in chat history, not auto-resubmitted.
Cancel stops the Agent loop, not previously started persistent terminal commands.
"""
import argparse
import base64
import json
import os
import shlex
import subprocess
import sys
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--adb', default=os.environ.get('CAPRI_ADB', 'adb'))
    sub = parser.add_subparsers(dest='action', required=True)
    send = sub.add_parser('send')
    send.add_argument('text')
    send.add_argument('--chat')
    send.add_argument('--request-id', default=None)
    send.add_argument('--wait', action='store_true')
    send.add_argument('--timeout', type=int, default=180)
    status = sub.add_parser('status'); status.add_argument('request_id'); status.add_argument('--offset', type=int, default=0)
    cancel = sub.add_parser('cancel'); cancel.add_argument('request_id')
    sub.add_parser('sessions')
    args = parser.parse_args()

    def call(method, **values):
        command = ['content', 'call', '--uri', 'content://ai.capri.app.debug', '--method', method]
        # Android's `content` extras parser can consume literal backslashes. Encode the
        # entire request so code, quotes, Unicode and newlines arrive unchanged.
        payload = json.dumps({key: value for key, value in values.items() if value is not None}, ensure_ascii=False).encode()
        encoded = base64.b64encode(payload).decode()
        command += ['--extra', 'payload:s:' + encoded]
        result = subprocess.run([args.adb, '-d', 'shell', '-T', shlex.join(command)], capture_output=True, text=True, timeout=15)
        output = result.stdout
        start = output.find('{json=')
        if result.returncode or start < 0:
            raise RuntimeError('Debug provider unavailable; install debug APK and open Capri. ' + result.stderr[:300])
        data, _ = json.JSONDecoder().raw_decode(output[start + 6:])
        if 'error' in data: raise RuntimeError(data['error'])
        return data

    if args.action == 'send':
        subprocess.run([args.adb, '-d', 'shell', 'am', 'start', '-W', '-n', 'ai.capri.app/.MainActivity'], capture_output=True, check=True, timeout=20)
        request_id = args.request_id or str(uuid.uuid4())
        # Print before dispatch: if ADB drops, this ID can be queried without replaying tools.
        print('request_id=' + request_id, file=sys.stderr, flush=True)
        result = call('send', text=args.text, chat_id=args.chat, request_id=request_id)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        if args.wait:
            deadline = time.monotonic() + args.timeout
            offset = result['next_offset']
            while result['running'] or result['has_more']:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Wait expired; request is not cancelled. Query status ' + request_id)
                if not result['has_more']: time.sleep(2)
                result = call('status', request_id=request_id, offset=offset)
                offset = result['next_offset']
                if result['entries'] or not result['running']:
                    print(json.dumps(result, ensure_ascii=False), flush=True)
    else:
        values = {} if args.action == 'sessions' else {'request_id': args.request_id}
        if args.action == 'status': values['offset'] = args.offset
        print(json.dumps(call(args.action, **values), ensure_ascii=False), flush=True)


if __name__ == '__main__':
    try: main()
    except (RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr); sys.exit(1)
