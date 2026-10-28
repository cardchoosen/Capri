#!/usr/bin/env python3
"""One build/deploy/test entry point. Logs and measured timings stay in ignored work/verify.

  python3 tools/verify.py --profile local
  python3 tools/verify.py --profile capability --device --approve-capri-install
  python3 tools/verify.py --profile runtime --device --cleanup-test

Device profiles are deliberate impact-based selections, not a claim of full coverage.
Only connected USB devices (-d); never clears application data or retries an Agent task.
"""
import argparse
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'ai.capri.app'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
PROFILES = {
    'local': [],
    'retrieval': ['ai.capri.app.WebRetrievalDeviceTest'],
    'web-research': ['ai.capri.app.WebResearchDeviceTest'],
    'tools': ['ai.capri.app.ToolsDeviceTest#toolsPanelUsesActualBuiltinsAndConnectedMcp', 'ai.capri.app.ToolLanguageDeviceTest', 'ai.capri.app.WebCatalogDeviceTest'],
    'knowledge': ['ai.capri.app.KnowledgeDeviceTest'],
    'extensions': ['ai.capri.app.ExtensionsDeviceTest', 'ai.capri.app.ToolsDeviceTest'],
    'real-mcp': ['ai.capri.app.RealMcpDeviceTest#remoteContext7Public', 'ai.capri.app.RealMcpDeviceTest#localOfficialTime'],
    'real-mcp-deepwiki': ['ai.capri.app.RealMcpDeviceTest#remoteDeepWiki'],
    'browser': ['ai.capri.app.BrowserDeviceTest'],
    'network': ['ai.capri.app.nativebridge.NativeDeviceTest#networkCheckReportsEvidenceAndReusesShortCache', 'ai.capri.app.BrowserDeviceTest'],
    'maintenance': ['ai.capri.app.EnvironmentMaintenanceDeviceTest'],
    'short-task': ['ai.capri.app.ShortTaskDeviceTest'],
    'execution': ['ai.capri.app.ExecutionToolsDeviceTest', 'ai.capri.app.InstallationEnvironmentDeviceTest'],
    'capability': ['ai.capri.app.CapabilityDeviceTest'],
    'runtime': ['ai.capri.app.CapabilityDeviceTest', 'ai.capri.app.LegacySessionResetDeviceTest'],
}


def instrumentation_ok(text):
    return bool(re.search(r'^OK \([1-9]\d* tests?\)', text, re.M)) and not any(
        token in text for token in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed', 'INSTRUMENTATION_STATUS_CODE: -3', 'INSTRUMENTATION_STATUS_CODE: -4'))


def instrumentation_cases(lines):
    """lines=(monotonic timestamp, line); arrival timings are approximate per-case durations."""
    fields, starts, cases = {}, {}, []
    for stamp, line in lines:
        match = re.match(r'INSTRUMENTATION_STATUS: (class|test)=(.*)', line)
        if match:
            fields[match[1]] = match[2].strip()
        match = re.match(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if not match:
            continue
        key = fields.get('class', '') + '#' + fields.get('test', '')
        code = int(match[1])
        if code == 1:
            starts[key] = stamp
        elif key in starts:
            cases.append({'test': key, 'seconds': round(stamp - starts.pop(key), 3),
                          'result_code': code})
    return cases


class Verification:
    def __init__(self, args):
        self.args = args
        self.directory = ROOT / 'work/verify' / dt.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
        self.directory.mkdir(parents=True)
        self.report = {'profile': args.profile, 'started_at': dt.datetime.now().isoformat(), 'steps': []}
        self.env = os.environ.copy()
        candidates = [args.java_home, self.env.get('JAVA_HOME'), '/Applications/Android Studio.app/Contents/jbr/Contents/Home']
        for candidate in filter(None, candidates):
            release = Path(candidate, 'release')
            version = re.search(r'JAVA_VERSION="(\d+)', release.read_text()) if release.is_file() else None
            if version and int(version[1]) >= 17:
                self.env['JAVA_HOME'] = candidate
                self.report['java_home'] = candidate
                break
        else:
            raise RuntimeError('JDK17+ required; pass --java-home or set JAVA_HOME')
        self.adb = args.adb or os.environ.get('CAPRI_ADB') or str(Path.home() / 'Library/Android/sdk/platform-tools/adb')

    def command(self, name, command, timeout=900, instrument=False):
        print(f'{name}…', flush=True)
        start = time.monotonic()
        log = self.directory / (name + '.log')
        # File-backed output avoids pipe deadlocks and lets the external caller tail progress.
        events = []
        with log.open('w') as output:
            proc = subprocess.Popen(command, cwd=ROOT, env=self.env, stdout=output, stderr=subprocess.STDOUT)
            try:
                if instrument:
                    with log.open() as reader:
                        pending = ''
                        while True:
                            pending += reader.read()
                            complete = pending.split('\n'); pending = complete.pop()
                            events.extend((time.monotonic(), line) for line in complete)
                            if proc.poll() is not None:
                                pending += reader.read()
                                events.extend((time.monotonic(), line) for line in pending.splitlines())
                                break
                            if time.monotonic() - start > timeout:
                                raise subprocess.TimeoutExpired(command, timeout)
                            time.sleep(.2)
                else:
                    proc.wait(timeout=timeout)
            except BaseException:
                proc.terminate()
                try: proc.wait(timeout=5)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait()
                raise
        text = log.read_text(errors='replace')
        good = proc.returncode == 0 and (not instrument or instrumentation_ok(text))
        step = {'name': name, 'seconds': round(time.monotonic() - start, 3),
                'exit_code': proc.returncode, 'passed': good, 'log': str(log.relative_to(ROOT))}
        if instrument: step['cases'] = instrumentation_cases(events)
        self.report['steps'].append(step)
        print(f'{name}: {"PASS" if good else "FAIL"} ({step["seconds"]}s)', flush=True)
        if not good:
            raise RuntimeError(f'{name} failed; see {log}')
        return text

    def adb_read(self, *args):
        return subprocess.run([self.adb, '-d', *args], capture_output=True, text=True, timeout=20, check=True).stdout

    def same_apk(self, package, apk):
        try:
            paths = self.adb_read('shell', 'pm', 'path', package).splitlines()
            path = next(line.removeprefix('package:') for line in paths if line.endswith('/base.apk'))
            digest = self.adb_read('shell', 'sha256sum ' + shlex.quote(path)).split()[0]
            return digest == hashlib.sha256(apk.read_bytes()).hexdigest()
        except (subprocess.SubprocessError, StopIteration, IndexError):
            return False

    def confirm_install(self):
        # Consent is restricted to a visible Capri-labelled installer with these exact controls.
        remote = '/sdcard/capri-verify-install.xml'
        try:
            self.adb_read('shell', 'uiautomator', 'dump', remote)
            root = ET.fromstring(self.adb_read('shell', 'cat', remote))
            nodes = list(root.iter('node'))
            installers = {'com.android.packageinstaller', 'com.google.android.packageinstaller', 'com.vivo.packageinstaller'}
            visible = [n for n in nodes if n.get('package') in installers]
            if not any('capri' in n.get('text', '').lower() for n in visible): return
            labels = {n.get('text'): n for n in visible}
            for label in ('已了解应用的风险检测结果', '继续安装'):
                if label not in labels: return
            for label in ('已了解应用的风险检测结果', '继续安装'):
                node = labels[label]
                if node.get('checked') == 'true': continue
                coords = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                if len(coords) != 4: return
                self.adb_read('shell', 'input', 'tap', str((coords[0] + coords[2]) // 2), str((coords[1] + coords[3]) // 2))
        except (subprocess.SubprocessError, ET.ParseError):
            pass
        finally:
            try: self.adb_read('shell', 'rm', '-f', remote)
            except subprocess.SubprocessError: pass

    def install(self, package, apk):
        name = 'install-' + package
        if self.same_apk(package, apk):
            self.report['steps'].append({'name': name, 'seconds': 0, 'passed': True, 'skipped': 'device APK SHA256 matches'})
            print(name + ': unchanged APK, skipped', flush=True)
            return
        start = time.monotonic()
        log = self.directory / (name + '.log')
        with log.open('w') as output:
            proc = subprocess.Popen([self.adb, '-d', 'install', '-r', str(apk)], stdout=output, stderr=subprocess.STDOUT)
            try:
                while proc.poll() is None:
                    if time.monotonic() - start > 120:
                        raise RuntimeError('USB install timed out; inspect the phone confirmation. No blind retry.')
                    time.sleep(2)
                    if proc.poll() is None and self.args.approve_capri_install:
                        self.confirm_install()
            finally:
                if proc.poll() is None:
                    proc.terminate(); proc.wait(timeout=10)
        good = proc.returncode == 0 and 'Success' in log.read_text(errors='replace')
        self.report['steps'].append({'name': name, 'seconds': round(time.monotonic() - start, 3),
                                    'passed': good, 'log': str(log.relative_to(ROOT))})
        print(f'{name}: {"PASS" if good else "FAIL"}', flush=True)
        if not good: raise RuntimeError(f'Install failed; see {log}. Application data was not cleared.')

    def run(self):
        started = time.monotonic()
        try:
            if self.args.device:
                self.command('device', [self.adb, '-d', 'get-state'], timeout=20)
            tasks = [':app:assembleDebug', ':app:testDebugUnitTest']
            if self.args.device and PROFILES[self.args.profile]: tasks += [':app:assembleDebugAndroidTest']
            self.command('gradle', ['./gradlew', *tasks])
            self.command('host-tests', ['python3', '-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_*.py', '-v'])
            hashes = json.loads((ROOT / 'docs/runtime-sha256.json').read_text())
            for file, expected in hashes.items():
                if hashlib.sha256((ROOT / file).read_bytes()).hexdigest() != expected:
                    raise RuntimeError('Runtime resource hash mismatch: ' + file)
            self.report['verified_resource_hashes'] = len(hashes)
            if self.args.device:
                self.install(PACKAGE, ROOT / 'app/build/outputs/apk/debug/app-debug.apk')
                if PROFILES[self.args.profile]:
                    self.install(PACKAGE + '.test', ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
                    if self.args.profile in ('browser', 'network', 'extensions'):
                        self.command('foreground', [self.adb, '-d', 'shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity'], timeout=20)
                    self.command('instrumentation', [self.adb, '-d', 'shell', 'am', 'instrument', '-w', '-r',
                        '-e', 'terminal_background', 'true', '-e', 'terminal_wait_seconds', '63',
                        '-e', 'class', ','.join(PROFILES[self.args.profile]), RUNNER], timeout=1200, instrument=True)
            self.report['passed'] = True
        except Exception as error:
            self.report.update(passed=False, error=str(error))
            print(str(error), flush=True)
        finally:
            if self.args.device and self.args.cleanup_test:
                try: self.command('cleanup-test', [self.adb, '-d', 'uninstall', PACKAGE + '.test'], timeout=30)
                except Exception as error: self.report['cleanup_error'] = str(error)
            self.report['elapsed_seconds'] = round(time.monotonic() - started, 3)
            output = self.directory / 'report.json'
            output.write_text(json.dumps(self.report, ensure_ascii=False, indent=2) + '\n')
            (self.directory.parent / 'latest.json').write_text(output.read_text())
            print('Report: ' + str(output), flush=True)
        return 0 if self.report.get('passed') else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--profile', choices=PROFILES, default='local')
    parser.add_argument('--device', action='store_true', help='deploy; selected profile also runs its native tests')
    parser.add_argument('--adb')
    parser.add_argument('--java-home')
    parser.add_argument('--approve-capri-install', action='store_true')
    parser.add_argument('--cleanup-test', action='store_true', help='remove test APK after verification; main app stays')
    args = parser.parse_args()
    lockdir = ROOT / 'work/verify'; lockdir.mkdir(parents=True, exist_ok=True)
    with (lockdir / '.lock').open('w') as lock:
        try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: parser.error('Another verification run owns the build/device lock')
        return Verification(args).run()


if __name__ == '__main__':
    raise SystemExit(main())
