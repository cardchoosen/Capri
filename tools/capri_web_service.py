#!/usr/bin/env python3
"""Manage the macOS per-user Capri USB web service. No Android package or app data changes."""
import argparse
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import time
import webbrowser

from capri_web import DEFAULT_PORT, access_token

LABEL = 'ai.capri.web'
ROOT = Path(__file__).resolve().parents[1]
APP = Path.home() / 'Library' / 'Application Support' / 'CapriWeb'
RUNTIME = APP / 'runtime'
STATE = APP / 'state'
PLIST = Path.home() / 'Library' / 'LaunchAgents' / (LABEL + '.plist')


def configuration(adb, serial=None):
    arguments = [shutil.which('python3') or sys.executable, str(RUNTIME / 'capri_web.py'), '--adb', str(Path(adb).resolve()), '--state-dir', str(STATE), '--open-browser']
    if serial: arguments += ['--serial', serial]
    return {'Label': LABEL, 'ProgramArguments': arguments, 'WorkingDirectory': str(APP),
            'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 5,
            'StandardOutPath': str(STATE / 'service.log'), 'StandardErrorPath': str(STATE / 'service-error.log')}


def owned_plist():
    if PLIST.is_symlink(): raise RuntimeError('LaunchAgent path must not be a symbolic link.')
    if not PLIST.exists(): return False
    with PLIST.open('rb') as stream: config = plistlib.load(stream)
    if config.get('Label') != LABEL or not any(path in config.get('ProgramArguments', []) for path in (str(RUNTIME / 'capri_web.py'), str(ROOT / 'tools/capri_web.py'))):
        raise RuntimeError('An unrelated LaunchAgent occupies this name; it was not modified.')
    return True


def launchctl(*arguments, check=True):
    return subprocess.run(['launchctl', *arguments], capture_output=True, text=True, check=check, timeout=15)


def unload(target):
    launchctl('bootout', target, check=False)
    # launchd can acknowledge bootout before unregistering the job; do not race bootstrap.
    deadline = time.monotonic() + 15
    while launchctl('print', target, check=False).returncode == 0:
        if time.monotonic() >= deadline:
            raise RuntimeError('The previous web service is still stopping; no duplicate service was started.')
        time.sleep(.2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['install', 'uninstall', 'status', 'open'])
    parser.add_argument('--adb', default=os.environ.get('CAPRI_ADB', str(Path.home() / 'Library/Android/sdk/platform-tools/adb')))
    parser.add_argument('--serial')
    args = parser.parse_args()
    if sys.platform != 'darwin': parser.error('This service installer requires macOS.')
    target = f'gui/{os.getuid()}/{LABEL}'
    if args.action == 'open':
        path = STATE / f'access-{DEFAULT_PORT}.token'
        if not path.is_file(): raise RuntimeError('Start or install the web service first.')
        webbrowser.open(f'http://127.0.0.1:{DEFAULT_PORT}/#token={access_token(path)}')
        print('Opened the local Capri web page.'); return
    present = owned_plist()
    if args.action == 'status':
        status = launchctl('print', target, check=False)
        print('Running/registered' if status.returncode == 0 else 'Not running/registered')
        print('Autostart installed' if present else 'Autostart not installed'); return
    if args.action == 'uninstall':
        if present:
            unload(target); PLIST.unlink()
        print('Autostart removed. Phone data and the local access token were retained.'); return
    if not Path(args.adb).is_file(): raise RuntimeError('ADB executable not found.')
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    RUNTIME.mkdir(parents=True, exist_ok=True, mode=0o700)
    changed = False
    restart_needed = False
    for relative in ('capri_web.py', 'web/index.html', 'web/app.js', 'web/run.js', 'web/inputs.js', 'web/documents.js', 'web/models.js', 'web/chat-layout.js', 'web/style.css'):
        source = ROOT / 'tools' / relative
        destination = RUNTIME / relative
        destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if destination.is_symlink(): raise RuntimeError('Runtime assets must not be symbolic links.')
        if not destination.exists() or source.read_bytes() != destination.read_bytes():
            shutil.copyfile(source, destination); os.chmod(destination, 0o600); changed = True
            if relative == "capri_web.py": restart_needed = True
    for name in ('service.log', 'service-error.log'):
        path = STATE / name
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
        os.fchmod(fd, 0o600); os.close(fd)
    access_token(STATE / f'access-{DEFAULT_PORT}.token')
    config = configuration(args.adb, args.serial)
    if present:
        with PLIST.open('rb') as stream: unchanged = plistlib.load(stream) == config
        if unchanged and not restart_needed and launchctl('print', target, check=False).returncode == 0:
            print('Updated web assets; refresh the existing page.' if changed else 'Autostart is already installed and registered.'); return
        unload(target)
    PLIST.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(PLIST, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'wb') as stream: plistlib.dump(config, stream)
    launchctl('bootstrap', f'gui/{os.getuid()}', str(PLIST))
    print('Installed login autostart and started the local USB web service. It waits for an authorized phone.')


if __name__ == '__main__':
    try: main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr); sys.exit(1)
