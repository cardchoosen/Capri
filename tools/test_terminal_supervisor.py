"""Host regression tests for the actual supervisor, adapting only paths and BusyBox applet names.

Run: python3 -m unittest discover -s tools -p 'test_terminal_supervisor.py' -v
Android/PRoot, foreground-service and process-death checks live in TerminalDeviceTest.
"""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import uuid


class SupervisorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='capri-terminal-')
        self.base = Path(self.temp.name)
        self.workspace = self.base / 'workspace'
        self.workspace.mkdir()
        (self.base / 'commands').mkdir()
        source = (Path(__file__).resolve().parents[1] / 'app/src/main/assets/runtime/terminal_supervisor.py').read_text()
        source = source.replace("BASE = Path('/run/capri')", 'BASE = Path(' + repr(str(self.base)) + ')')
        source = source.replace("os.chdir('/workspace')", 'os.chdir(' + repr(str(self.workspace)) + ')')
        source = source.replace('/run/capri/', str(self.base) + '/')
        source = source.replace('/bin/busybox printf', '/usr/bin/printf').replace('/bin/busybox mv', '/bin/mv')
        executable = self.base / 'supervisor.py'
        executable.write_text(source)
        self.log = (self.base / 'supervisor.log').open('w')
        self.process = subprocess.Popen([sys.executable, str(executable)], stdout=self.log, stderr=self.log, start_new_session=True)
        self.wait(lambda: (self.base / 'state.json').exists())

    def tearDown(self):
        (self.base / 'close').touch()
        try:
            self.process.wait(timeout=4)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.log.close()
        self.temp.cleanup()

    def wait(self, predicate, timeout=5):
        until = time.monotonic() + timeout
        while not predicate():
            if self.process.poll() is not None:
                self.fail('Supervisor exited: ' + (self.base / 'supervisor.log').read_text())
            if time.monotonic() > until:
                self.fail('Timed out: ' + (self.base / 'supervisor.log').read_text())
            time.sleep(.02)

    def publish(self, name, text):
        temporary = self.base / (name + '.new')
        temporary.write_text(text)
        temporary.replace(self.base / name)

    def submit(self, code, timeout=20):
        identifier = str(uuid.uuid4())
        folder = self.base / 'commands' / identifier
        folder.mkdir()
        (folder / 'main.sh').write_text(code + '\n')
        self.publish('request.json', json.dumps(dict(id=identifier, timeout_seconds=timeout)))
        return folder

    def result(self, folder):
        self.wait(lambda: (folder / 'result.json').exists())
        return json.loads((folder / 'result.json').read_text())

    def test_directory_export_and_shell_functions_survive_many_commands(self):
        folder = self.submit('mkdir nested; cd nested; export CAPRI_TOKEN=kept; counter=7; helper() { echo function-ok; }')
        self.assertEqual(0, self.result(folder)['exit_code'])
        for _ in range(20):
            folder = self.submit('pwd; echo "$CAPRI_TOKEN:$counter"; helper')
            self.assertEqual('exited', self.result(folder)['reason'])
            text = (folder / 'output.bin').read_text()
            self.assertIn('/workspace/nested', text)
            self.assertIn('kept:7', text)
            self.assertIn('function-ok', text)

    def test_stdin_and_interrupt_leave_shell_reusable(self):
        folder = self.submit('read value; printf "stdin:%s\\n" "$value"')
        self.wait(lambda: json.loads((self.base / 'state.json').read_text())['state'] == 'running')
        self.publish('input', json.dumps(dict(id=folder.name, text='Capri\n')))
        self.assertEqual(0, self.result(folder)['exit_code'])
        self.assertIn('stdin:Capri', (folder / 'output.bin').read_text())
        folder = self.submit('sleep 20')
        self.wait(lambda: json.loads((self.base / 'state.json').read_text()).get('command_id') == folder.name)
        time.sleep(.1)
        self.publish('cancel', folder.name)
        self.assertEqual('cancelled', self.result(folder)['reason'])
        self.assertEqual(0, self.result(self.submit('echo still-alive'))['exit_code'])

    def test_timeout_and_utf8_output(self):
        folder = self.submit('sleep 20', timeout=1)
        self.assertEqual('timeout', self.result(folder)['reason'])
        folder = self.submit("printf '摩羯\\n'")
        self.assertEqual(0, self.result(folder)['exit_code'])
        self.assertIn('摩羯', (folder / 'output.bin').read_text())

    def test_output_limit_closes_terminal_and_caps_both_logs(self):
        folder = self.submit('yes x')
        self.assertEqual('output_limit', self.result(folder)['reason'])
        self.process.wait(timeout=4)
        self.assertLessEqual((folder / 'output.bin').stat().st_size, 2_000_000)
        self.assertLessEqual((self.base / 'output.bin').stat().st_size, 2_000_000)
        self.assertEqual('closed', json.loads((self.base / 'state.json').read_text())['state'])


if __name__ == '__main__':
    unittest.main()
