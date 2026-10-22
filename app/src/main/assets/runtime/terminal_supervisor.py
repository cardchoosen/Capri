"""Capri local PTY supervisor. Control files are internal protocol, not a security boundary."""
import errno
import json
import os
from pathlib import Path
import pty
import select
import signal
import shlex
import termios
import time
import traceback

BASE = Path('/run/capri')
LIMIT = 2_000_000


def atomic(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value))
    temporary.replace(path)


# Interactive ash aborts the rest of a command line on Ctrl+C, so an inline trailing
# completion command is not reliable. Publish the exit status when the next prompt is ready.
prompt = "$(/bin/busybox printf '%s' \"$?\" > /run/capri/prompt.tmp; /bin/busybox mv /run/capri/prompt.tmp /run/capri/prompt)"
(BASE / 'shell_init.sh').write_text(
    "unset PS1 PS2\nPS1=''\nPS2=''\n"
    "if [ \"$$\" = \"$CAPRI_SHELL_PID\" ]; then\nPS1=" + shlex.quote(prompt) +
    "\nreadonly PS1 CAPRI_SHELL_PID\nfi\n")
pid, master = pty.fork()
if pid == 0:
    os.environ.update(TERM='dumb', ENV='/run/capri/shell_init.sh', CAPRI_SHELL_PID=str(os.getpid()))
    os.chdir('/workspace')
    os.execv('/bin/sh', ['/bin/sh', '-i'])

# No echo of submitted scripts or input. The shell still has a controlling terminal/job control.
attrs = termios.tcgetattr(master)
attrs[3] &= ~(termios.ECHO | termios.ECHONL)
termios.tcsetattr(master, termios.TCSANOW, attrs)
os.set_blocking(master, False)
active = None
output = None
written = 0
started = 0
pending_input = b''
dispatched = False
initialized = False
interrupt_reason = None
interrupt_at = None
terminal_reason = 'closed'
transcript = (BASE / 'output.bin').open('ab', buffering=0)


def interrupt():
    try:
        # Let the tty driver deliver Ctrl+C to its real foreground job. Under Android/PRoot,
        # a userspace tcgetpgrp/killpg fallback can target the shell instead of that job.
        os.write(master, b'\x03')
    except OSError:
        pass


def finish(exit_code=None, reason=None):
    global active, output, interrupt_reason, interrupt_at
    if active:
        if output:
            output.close()
        atomic(BASE / 'commands' / active['id'] / 'result.json',
               dict(exit_code=exit_code, reason=reason or 'exited'))
        active = None
        output = None
        interrupt_reason = None
        interrupt_at = None


atomic(BASE / 'state.json', dict(state='starting', shell_pid=pid))
try:
    while True:
        if (BASE / 'close').exists():
            terminal_reason = 'closed'
            break
        readable, writable, _ = select.select([master], [master] if pending_input else [], [], .05)
        if readable:
            try:
                data = os.read(master, 8192)
            except OSError as error:
                if error.errno != errno.EIO:
                    raise
                data = b''
            if not data:
                _, status = os.waitpid(pid, 0)
                finish(os.waitstatus_to_exitcode(status), interrupt_reason)
                terminal_reason = 'shell_exited'
                break
            room = max(0, LIMIT - os.fstat(transcript.fileno()).st_size)
            transcript.write(data[:room])
            if len(data) > room:
                finish(reason='output_limit')
                terminal_reason = 'output_limit'
                break
            if output:
                room = max(0, LIMIT - written)
                output.write(data[:room])
                output.flush()
                written += len(data)
                if written > LIMIT:
                    finish(reason='output_limit')
                    terminal_reason = 'output_limit'
                    break
        if writable:
            try:
                count = os.write(master, pending_input[:4096])
                pending_input = pending_input[count:]
                if not pending_input:
                    dispatched = True
            except BlockingIOError:
                pass
        if active:
            folder = BASE / 'commands' / active['id']
            # Consume newly published input once; it never contains queued command wrappers.
            incoming = BASE / 'input'
            if incoming.exists() and len(pending_input) < 8192:
                packet = json.loads(incoming.read_text())
                if packet.get('id') == active['id']:
                    pending_input += packet['text'].encode()
                incoming.unlink()
            if (BASE / 'cancel').exists():
                target = (BASE / 'cancel').read_text()
                (BASE / 'cancel').unlink()
                if target == active['id'] and interrupt_at is None:
                    if not dispatched:
                        pending_input = b''
                        # Discard any partially queued tty input without running the script.
                        termios.tcflush(master, termios.TCIOFLUSH)
                        finish(reason='cancelled')
                        atomic(BASE / 'state.json', dict(state='ready', shell_pid=pid))
                        continue
                    interrupt_reason, interrupt_at = 'cancelled', time.monotonic()
                    pending_input = b''
                    interrupt()
            if time.monotonic() - started >= active['timeout_seconds'] and interrupt_at is None:
                interrupt_reason, interrupt_at = 'timeout', time.monotonic()
                pending_input = b''
                interrupt()
            marker = BASE / 'prompt'
            if marker.exists():
                # Read bytes already available before marking this command finished.
                while select.select([master], [], [], 0)[0]:
                    try:
                        data = os.read(master, 8192)
                    except (BlockingIOError, OSError):
                        break
                    if not data:
                        break
                    room = max(0, LIMIT - os.fstat(transcript.fileno()).st_size)
                    transcript.write(data[:room])
                    if len(data) > room:
                        written = LIMIT + 1
                    output.write(data[:max(0, LIMIT - written)])
                    written += len(data)
                raw_code = marker.read_text().strip()
                if not raw_code:
                    continue
                code = int(raw_code)
                marker.unlink()
                overflow = written > LIMIT
                finish(code, 'output_limit' if overflow else interrupt_reason)
                pending_input = b''
                if overflow:
                    terminal_reason = 'output_limit'
                    break
                atomic(BASE / 'state.json', dict(state='ready', shell_pid=pid))
            elif interrupt_at is not None and time.monotonic() - interrupt_at > 2:
                # Uncooperative commands terminate the whole terminal; never pretend it is reusable.
                finish(reason=interrupt_reason)
                terminal_reason = 'interrupt_failed'
                break
        else:
            marker = BASE / 'prompt'
            if marker.exists():
                marker.unlink()
                initialized = True
                atomic(BASE / 'state.json', dict(state='ready', shell_pid=pid))
            request = BASE / 'request.json'
            if initialized and request.exists():
                candidate = json.loads(request.read_text())
                request.unlink()
                identifier = candidate['id']
                if len(identifier) != 36 or any(c not in '0123456789abcdef-' for c in identifier):
                    raise ValueError('invalid command id')
                active = candidate
                dispatched = False
                written, started = 0, time.monotonic()
                output = (BASE / 'commands' / identifier / 'output.bin').open('ab', buffering=0)
                # The prompt hook reports completion even when Ctrl+C aborts this source command.
                wrapper = ". /run/capri/commands/{0}/main.sh\n".format(identifier)
                pending_input = wrapper.encode()
                atomic(BASE / 'state.json', dict(state='running', shell_pid=pid, command_id=identifier))
except BaseException:
    terminal_reason = 'supervisor_error'
    traceback.print_exc()
finally:
    transcript.close()
    finish(reason=terminal_reason)
    try:
        group = os.tcgetpgrp(master)
        if group > 1 and group != os.getpgrp():
            os.killpg(group, signal.SIGKILL)
    except OSError:
        pass
    try:
        if pid > 1 and pid != os.getpgrp():
            os.killpg(pid, signal.SIGKILL)
    except OSError:
        pass
    os.close(master)
    atomic(BASE / 'state.json', dict(state='closed', reason=terminal_reason))
