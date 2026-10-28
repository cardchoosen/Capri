"""Generic task requirements: inspect facts, prepare project dependencies, or run an explicit small verification.
System packages are installed by the Android owner under a shared-environment maintenance lease.
"""
import importlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import tomllib
import time


def run(args, cwd, timeout=30, limit=8000):
    started = time.monotonic()
    try:
        result = subprocess.run(args, cwd=cwd, capture_output=True, text=True, timeout=timeout)
        data = {'passed': result.returncode == 0, 'exit_code': result.returncode,
                'output': (result.stdout + result.stderr)[-limit:]}
    except (OSError, subprocess.TimeoutExpired) as error:
        data = {'passed': False, 'error': type(error).__name__ + ': ' + str(error)[:500]}
    return {**data, 'elapsed_ms': round((time.monotonic() - started) * 1000)}


def pending_python_packages(packages):
    # Extras need pip's resolver; a version-only cache cannot establish their dependencies.
    from pip._vendor.packaging.requirements import Requirement
    pending = []
    for raw in packages:
        requirement = Requirement(raw)
        try:
            version = importlib.metadata.version(requirement.name)
            satisfied = not requirement.extras and requirement.specifier.contains(version, prereleases=True)
        except importlib.metadata.PackageNotFoundError:
            satisfied = False
        if not satisfied: pending.append(raw)
    return pending


def project_info(root):
    result = {}
    for name in ('requirements.txt', 'pyproject.toml', 'package.json', 'package-lock.json', 'pnpm-lock.yaml', 'yarn.lock', 'Cargo.toml', 'CMakeLists.txt', 'Makefile'):
        path = root / name
        if not path.is_file(): continue
        if name.endswith('lock.json') or name.endswith('lock.yaml'):
            result[name] = {'present': True}; continue
        if path.stat().st_size > 32768:
            result[name] = {'present': True, 'note': 'manifest exceeds 32KiB; inspect explicitly'}; continue
        text = path.read_text(errors='replace')
        try:
            if name == 'package.json':
                data = json.loads(text)
                result[name] = {key: data[key] for key in ('engines', 'packageManager', 'dependencies', 'devDependencies', 'scripts') if key in data}
            elif name == 'pyproject.toml':
                data = tomllib.loads(text)
                result[name] = {key: data[key] for key in ('project', 'build-system') if key in data}
            else: result[name] = {'present': True, 'excerpt': text[:5000]}
        except Exception as error: result[name] = {'error': str(error)[:500]}
    return result


def inspect(request, root):
    required = request.get('requirements', {})
    checks = dict(request.get("_cached_checks", {}))
    for name in required.get('commands', []):
        if 'command:' + name in checks: continue
        executable = shutil.which(name)
        item = {'present': executable is not None, 'path': executable, 'watch_path': executable}
        if executable:
            item['version_probe'] = run([executable, '-V' if name == 'ssh' else '--version'], root, 5, 500)
        checks['command:' + name] = item
    from pip._vendor.packaging.requirements import Requirement
    for raw in required.get('python_packages', []):
        if 'python:' + raw in checks: continue
        requirement = Requirement(raw)
        try:
            version = importlib.metadata.version(requirement.name)
            checks['python:' + raw] = {'present': True, 'version': version,
                                      'satisfies': requirement.specifier.contains(version, prereleases=True)}
        except importlib.metadata.PackageNotFoundError:
            checks['python:' + raw] = {'present': False, 'satisfies': False}
    for name in required.get('imports', []):
        if 'import:' + name in checks: continue
        try:
            loaded = importlib.import_module(name)
            checks['import:' + name] = {'passed': True, 'watch_path': getattr(loaded, '__file__', None)}
        except Exception as error:
            checks['import:' + name] = {'passed': False, 'error': type(error).__name__ + ': ' + str(error)[:500]}
    for name in required.get('system_packages', []):
        if 'system:' + name in checks: continue
        item = run(['dpkg-query', '-W', '-f=${Status}\t${Version}', name], root, 5)
        checks['system:' + name] = {'present': item['passed'] and 'install ok installed' in item.get('output', ''), 'evidence': item}
    headers, libraries = required.get('headers', []), required.get('libraries', [])
    if (headers or libraries) and not ('native_compile_link' in checks and 'native_load' in checks):
        compiler = required.get('compiler', 'cc')
        with tempfile.TemporaryDirectory(prefix='capri-compile-check-') as temporary:
            source = Path(temporary, 'check.cpp' if '++' in compiler else 'check.c')
            source.write_text(''.join('#include <' + header + '>\n' for header in headers) + '\nint main(void){return 0;}\n')
            binary = Path(temporary, 'check')
            result = run([compiler, str(source), '-o', str(binary), '-Wl,--no-as-needed', *['-l' + name for name in libraries]], root, 20)
            checks['native_compile_link'] = result
            if result['passed']: checks['native_load'] = run([str(binary)], root, 5)
    missing = [key for key, value in checks.items() if value.get('present') is False or value.get('satisfies') is False]
    failed = [key for key, value in checks.items() if value.get('passed') is False]
    return {'status': 'ok', 'scope': 'current chat', 'python': sys.version.split()[0], 'venv': sys.prefix,
            'checks': checks, 'missing': missing, 'failed': failed, 'requirements_satisfied': not missing and not failed,
            'project': project_info(root),
            'limits': 'Only declared requirements are checked. Presence/version is not full program validation; use verify for the actual API or behavior. No inference of every task dependency.'}


def _execute(request):
    root = Path('/workspace').resolve()
    action = request.get('action', 'inspect')
    if action == 'verify':
        return {'status': 'ok', 'verification': run(request['command'], root, request.get('timeout_seconds', 30))}
    operations = []
    if action == 'prepare':
        if sys.prefix == sys.base_prefix: raise RuntimeError('Refusing pip installation outside the chat venv')
        required = request.get('requirements', {})
        pip = [sys.executable, '-m', 'pip', '--isolated', '--disable-pip-version-check', '--no-input', '--timeout', '25', '--retries', '1',
               '--cache-dir', '/opt/capri/cache/pip', 'install', '--prefer-binary']
        packages = required.get('python_packages', [])
        if packages:
            pending = pending_python_packages(packages)
            result = run([*pip, *pending], root, 500) if pending else {'passed': True, 'elapsed_ms': 0, 'reused': True, 'output': 'Declared package versions already satisfied; no pip process or index request.'}
            operations.append({'kind': 'python', **result})
            if not result['passed']: return {'status': 'installation_failed', 'operations': operations, 'note': 'Partial changes may remain; inspect before retrying.'}
        if request.get('from_project', False):
            if (root / 'requirements.txt').is_file():
                result = run([*pip, '-r', 'requirements.txt'], root, 500); operations.append({'kind': 'python_project', **result})
                if not result['passed']: return {'status': 'installation_failed', 'operations': operations}
            elif (root / 'pyproject.toml').is_file():
                result = run([*pip, '.'], root, 500); operations.append({'kind': 'python_project', **result})
                if not result['passed']: return {'status': 'installation_failed', 'operations': operations}
            if (root / 'package.json').is_file():
                data = json.loads((root / 'package.json').read_text())
                declared = data.get('packageManager', '')
                if (root / 'pnpm-lock.yaml').is_file() or declared.startswith('pnpm'):
                    args = ['pnpm', 'install', '--frozen-lockfile']
                elif (root / 'yarn.lock').is_file() or declared.startswith('yarn'):
                    return {'status': 'unsupported_project_manager', 'message': 'Use the project-declared Yarn version explicitly; no silent npm fallback.'}
                else: args = ['npm', 'ci' if (root / 'package-lock.json').is_file() else 'install']
                result = run(args, root, 500); operations.append({'kind': 'node_project', **result})
                if not result['passed']: return {'status': 'installation_failed', 'operations': operations}
    inspection_started = time.monotonic()
    result = inspect(request, root)
    result['timings_ms'] = {'inspect': round((time.monotonic() - inspection_started) * 1000)}
    if operations: result['operations'] = operations
    return result


def execute(request):
    started = time.monotonic()
    result = _execute(request)
    timings = result.setdefault('timings_ms', {})
    timings['total'] = round((time.monotonic() - started) * 1000)
    timings['package_operations'] = sum(op.get('elapsed_ms', 0) for op in result.get('operations', []))
    # A pip/apt process combines resolution, transfer and installation; don't invent split timings.
    return result


if __name__ == '__main__':
    try:
        print(json.dumps(execute(json.loads(sys.stdin.read())), ensure_ascii=False))
    except Exception as error:
        print(json.dumps({'status': 'error', 'error': type(error).__name__ + ': ' + str(error)[:1500]}, ensure_ascii=False))
        raise SystemExit(1)
