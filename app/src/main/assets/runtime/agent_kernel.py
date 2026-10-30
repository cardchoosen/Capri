"""Revision-scoped orchestration worker. This process is not a security sandbox."""
import contextlib
import io
import json
import math
import os
import resource
import sys
import threading
import time
import traceback

_wire_in, _wire_out = sys.stdin, sys.stdout
_main_thread = threading.get_ident()
# A hard address-space ceiling is inherited by children and cannot be raised by this worker.
resource.setrlimit(resource.RLIMIT_AS, (384 * 1024 * 1024, 384 * 1024 * 1024))


def send(value):
    raw = json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(',', ':'))
    if len(raw) > 512000:
        raise RuntimeError('Protocol frame exceeds 512000 characters.')
    _wire_out.write(raw + '\n')
    _wire_out.flush()


class Output(io.TextIOBase):
    def __init__(self, limit):
        self.limit, self.size, self.parts, self.exceeded = limit, 0, [], False

    def write(self, text):
        remaining = self.limit - self.size
        self.parts.append(text[:max(0, remaining)])
        self.size += len(text)
        if self.size + capri.size > self.limit:
            self.exceeded = True
            raise RuntimeError('Program output budget exceeded.')
        return len(text)

    def flush(self):
        pass


class ToolError(RuntimeError):
    def __init__(self, result):
        super().__init__('Tool RPC failed; inspect .result and the run receipt.')
        self.result = result


class Capri:
    def begin(self, run, maximum, limit):
        self.run, self.maximum, self.count = run, maximum, 0
        self.limit, self.emitted, self.size, self.exceeded = limit, [], 0, False

    def _rpc(self, calls, batch):
        if threading.get_ident() != _main_thread:
            raise RuntimeError('Tool RPC is supported only on the execution thread.')
        self.count += len(calls)
        if self.count > self.maximum:
            self.exceeded = True
            raise RuntimeError('Program tool-call budget exceeded.')
        try:
            send({'kind': 'rpc', 'run_id': self.run, 'calls': calls, 'batch': batch})
        except RuntimeError:
            self.exceeded = True
            raise
        raw = _wire_in.readline(512001)
        if len(raw) > 512000 or not raw.endswith('\n'):
            raise RuntimeError('Invalid RPC response frame.')
        response = json.loads(raw)
        if response.get('run_id') != self.run:
            raise RuntimeError('RPC response scope mismatch.')
        if response.get('error'):
            raise RuntimeError(response['error'])
        return response['results']

    def call(self, name, arguments):
        item = self._rpc([{'name': name, 'arguments': arguments}], False)[0]
        if item['status'] != 'ok':
            raise ToolError(item)
        return item['result']

    def batch(self, calls):
        return self._rpc(calls, True)

    def emit(self, value):
        encoded = json.dumps(value, ensure_ascii=False, allow_nan=False)
        self.size += len(encoded)
        if self.size + output.size > self.limit:
            self.exceeded = True
            raise RuntimeError('Emitted result budget exceeded.')
        self.emitted.append(value)


capri = Capri()
namespace = {'__name__': '__capri_program__', 'capri': capri}
send({'kind': 'ready', 'memory_limit_bytes': 384 * 1024 * 1024})
while True:
    raw = _wire_in.readline(512001)
    if not raw:
        break
    if len(raw) > 512000 or not raw.endswith('\n'):
        raise RuntimeError('Invalid execution frame.')
    request = json.loads(raw)
    run = request['run_id']
    capri.begin(run, request['max_calls'], request['max_output_chars'])
    output = Output(request['max_output_chars'])
    namespace['capri'] = capri
    # CPU soft budget tracks cumulative CPU time; the host wall deadline remains authoritative.
    used = resource.getrusage(resource.RUSAGE_SELF)
    resource.setrlimit(resource.RLIMIT_CPU, (math.ceil(used.ru_utime + used.ru_stime + request['timeout_ms'] / 1000 + 1), resource.RLIM_INFINITY))
    status, error = 'ok', ''
    try:
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            exec(compile(request['code'], '<capri-program>', 'exec'), namespace, namespace)
    except BaseException as exc:
        status = 'error'
        error = (type(exc).__name__ + ': ' + str(exc))[:1000]
    if output.exceeded or capri.exceeded:
        status, error = 'error', 'Program resource budget exceeded.'
    reusable = status == 'ok' and len(threading.enumerate()) == 1
    try:
        # WNOWAIT observes child existence without consuming a subprocess exit receipt.
        # Android kernels may omit /proc/.../children, so do not depend on that file.
        os.waitid(os.P_ALL, 0, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        reusable = False
    except ChildProcessError:
        pass
    except (OSError, AttributeError):
        # Unsupported platforms remain conservative; Android is verified by the device case.
        reusable = False
    send({'kind': 'done', 'run_id': run, 'status': status, 'error': error,
          'output': ''.join(output.parts), 'emitted': capri.emitted, 'reusable': reusable})
    if not reusable:
        break
