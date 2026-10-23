"""Task-scoped, offline execution evidence. No installation or user-file traversal.

This is a small capability catalog, not a predictor for arbitrary programs.
Temporary smoke-test files live under the current chat's /tmp and are removed.
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
import time


def command(args, directory, timeout=15):
    try:
        result = subprocess.run(args, cwd=directory, capture_output=True, text=True, timeout=timeout)
        return {'state': 'ready' if result.returncode == 0 else 'failed', 'exit_code': result.returncode,
                'evidence': (result.stdout + result.stderr)[-1200:]}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'state': 'failed', 'evidence': str(error)[:1200]}


def language_check(language, directory):
    if language == 'python':
        return {'state': 'ready', 'evidence': 'This probe executes in the current chat Python: ' + sys.version.split()[0]}
    name = 'g++' if language == 'cpp' else 'node'
    executable = shutil.which(name)
    if not executable:
        return {'state': 'missing', 'evidence': name + ' is not on the current PATH'}
    if language == 'node':
        return command([executable, '-e', "if([3,1,2].sort((a,b)=>a-b).join(',')!=='1,2,3')process.exit(1);console.log('node execution OK')"], directory)
    source = Path(directory, 'smoke.cpp')
    source.write_text('#include <algorithm>\n#include <iostream>\nint main(){int a[]={3,1,2};std::sort(a,a+3);if(a[0]!=1||a[2]!=3)return 1;std::cout<<"C++17 compile and execute OK";}')
    built = command([executable, '-std=c++17', str(source), '-o', 'smoke'], directory, timeout=20)
    if built['state'] != 'ready': return built
    return command([str(Path(directory, 'smoke'))], directory, timeout=5)


def bundled_versions():
    try:
        return {key.lower(): value['version'] for key, value in json.loads(
            Path('/opt/capri/python-packages/manifest.json').read_text())['packages'].items()}
    except (OSError, KeyError, ValueError):
        return {}


def library_check(package, outputs, directory, bundled):
    module = 'PIL.Image' if package == 'pillow' else 'matplotlib'
    try:
        library = importlib.import_module(module)
    except ModuleNotFoundError as error:
        # Missing transitive imports are a broken installation, not an absent top-level package.
        missing = error.name in (module, module.split('.')[0])
        return {'state': 'needs_install' if missing and package in bundled else ('missing' if missing else 'broken'),
                'package': package, 'bundled_version': bundled.get(package), 'evidence': str(error)[:800]}
    except Exception as error:
        return {'state': 'broken', 'package': package, 'evidence': str(error)[:800]}
    try:
        if package == 'pillow':
            if 'gif' in outputs:
                image = library.new('RGB', (8, 8), 'red')
                other = library.new('RGB', (8, 8), 'green')
                path = Path(directory, 'smoke.gif')
                image.save(path, save_all=True, append_images=[other], duration=100, loop=0)
                with library.open(path) as decoded:
                    assert decoded.n_frames == 2
                    for index in range(2): decoded.seek(index); decoded.load()
            if 'png' in outputs:
                path = Path(directory, 'smoke.png')
                library.new('RGB', (8, 8), 'blue').save(path)
                with library.open(path) as decoded: decoded.load(); assert decoded.size == (8, 8)
            evidence = 'Actual encode/decode passed: ' + ', '.join(sorted(set(outputs) & {'gif', 'png'}))
        else:
            library.use('Agg', force=True)
            from matplotlib.figure import Figure
            from matplotlib.backends.backend_agg import FigureCanvasAgg
            figure = Figure(figsize=(1, 1)); FigureCanvasAgg(figure)
            figure.add_subplot().plot([0, 1], [0, 1])
            figure.savefig(Path(directory, 'chart.png'))
            evidence = 'Actual Matplotlib Agg PNG render passed'
        return {'state': 'ready', 'package': package, 'version': importlib.metadata.version(package), 'evidence': evidence}
    except Exception as error:
        return {'state': 'broken', 'package': package, 'evidence': type(error).__name__ + ': ' + str(error)[:800]}


def native_check(output, directory):
    """Check one known native backend; absence does not prove no other backend exists."""
    if output == 'chart':
        return {'state': 'unknown', 'evidence': 'No curated native C++ chart backend is configured; compiler availability alone is insufficient.'}
    header, symbol, link = ('gif_lib.h', 'GifErrorString(0)', '-lgif') if output == 'gif' else ('png.h', 'png_get_libpng_ver(nullptr)', '-lpng')
    source = Path(directory, 'backend.cpp')
    source.write_text('#include <cstdio>\n#include <' + header + '>\nint main(){const char* p=' + symbol + ';if(p)std::puts(p);}')
    result = command(['g++', '-std=c++17', str(source), link, '-o', 'backend'], directory, timeout=8)
    if result['state'] == 'ready':
        result = command([str(Path(directory, 'backend'))], directory, timeout=5)
        if result['state'] == 'ready':
            return {'state': 'unknown', 'evidence': header + ' compile/link/load passed; actual ' + output + ' encoding has not been tested by this catalog.'}
    return {'state': 'needs_native_backend', 'evidence': 'Known backend ' + header + ' not usable in this compile/link test. ' + result['evidence']}


def assess(request):
    start = time.monotonic()
    language, outputs = request['language'], request['outputs']
    helpers = request['allow_helpers']
    checks, recommendations = {}, []
    bundled = bundled_versions()
    with tempfile.TemporaryDirectory(prefix='capri-capability-') as directory:
        checks['language'] = language_check(language, directory)
        if outputs and language == 'cpp' and not helpers:
            for output in outputs: checks['native_' + output] = native_check(output, directory)
            recommendations.append('Strict C++: use a mature native library with verified headers/linking/encoding. Missing backend is not permission denial; do not invent a GIF/LZW/PNG codec. Report the gap before choosing another language.')
        elif outputs and language == 'node' and not helpers:
            checks['native_media'] = {'state': 'unknown', 'evidence': 'This catalog does not yet verify Node-native media packages.'}
        else:
            if set(outputs) & {'png', 'gif'}: checks['pillow'] = library_check('pillow', outputs, directory, bundled)
            if 'chart' in outputs: checks['matplotlib'] = library_check('matplotlib', outputs, directory, bundled)
            if language != 'python' and outputs:
                recommendations.append('Use ' + language + ' for the requested algorithm/rendering; export data or PPM frames and use the checked Python library for encoding. State this mixed-language boundary to the user. It does not satisfy an explicit all-C++/all-Node constraint.')
            if 'gif' in outputs:
                recommendations.append('Pillow Image.save(save_all=True, append_images=..., duration=..., loop=0); verify dimensions/frame count and send_file. No handwritten GIF/LZW codec or font.')
            if 'chart' in outputs: recommendations.append('Matplotlib Agg for chart layout/fonts; save to /workspace and send_file.')
    states = {check['state'] for check in checks.values()}
    if states == {'ready'}: readiness = 'ready'
    elif states <= {'ready', 'needs_install'}: readiness = 'needs_install'
    elif states & {'failed', 'broken'}: readiness = 'broken'
    elif 'needs_native_backend' in states: readiness = 'needs_native_backend'
    else: readiness = 'unknown'
    install = [c['package'] for c in checks.values() if c['state'] == 'needs_install']
    return {'status': 'ok', 'scope': 'current chat; current execution snapshot', 'request': request,
            'readiness': readiness, 'checks': checks,
            'install_if_needed': {'tool': 'python_env', 'action': 'install', 'packages': install} if install else None,
            'recommendations': recommendations, 'elapsed_seconds': round(time.monotonic() - start, 3),
            'limits': ['Only the requested language and png/gif/chart recipes are checked; no guarantee for arbitrary programs.',
                       'No network, Android permissions, remote services, full disk integrity or arbitrary third-party package compatibility test.',
                       'No packages installed by this tool; normal session initialization may create venv/pip. Temporary smoke files are removed.']}
