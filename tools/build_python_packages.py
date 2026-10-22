"""Repack pinned Alpine ARM64 Python packages as Capri-local wheels + shared libraries.

Resolve only from official v3.22 main/community APKINDEX files; rebuilds verify APK
SHA256. APK scripts are not executed. These linux_aarch64 wheels require the Capri
library layer and are NOT advertised as portable/manylinux/musllinux wheels.
"""
import argparse, base64, csv, gzip, hashlib, io, json, os, posixpath, re, shutil, subprocess, tarfile, zipfile
from email.parser import BytesParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'work/python-native-build'
LOCK = ROOT / 'docs/python-native-packages.json'
ASSETS = ROOT / 'app/src/main/assets/runtime'
SITE = 'usr/lib/python3.12/site-packages/'
ROOTS = ['py3-matplotlib', 'py3-matplotlib-fonts', 'py3-numpy', 'py3-pillow']

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def normalized(name): return re.sub(r'[-_.]+', '-', name).lower()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resolve', action='store_true')
    args = parser.parse_args()
    BASE.mkdir(parents=True, exist_ok=True)
    if args.resolve:
        lookup = {}; indices = {}
        for repo in ['main', 'community']:
            path = BASE / (repo + '-APKINDEX')
            indices[repo] = digest(path)
            for block in path.read_text().split('\n\n'):
                d = dict(line.split(':', 1) for line in block.splitlines() if ':' in line)
                if 'P' not in d: continue
                d['repository'] = repo; lookup[d['P']] = d
                for provided in d.get('p', '').split(): lookup.setdefault(re.split('[=<>~]', provided)[0], d)
        chosen = {}
        def visit(name):
            if name.startswith('!'): return
            d = lookup[re.split('[=<>~]', name)[0]]
            if d['P'] in chosen: return
            chosen[d['P']] = dict(d)
            for dependency in d.get('D', '').split(): visit(dependency)
        for name in ROOTS: visit(name)
        lock = {'schema': 1, 'alpine': '3.22', 'architecture': 'aarch64', 'python_abi': 'cp312',
                'index_sha256': indices, 'roots': ROOTS, 'packages': sorted(chosen.values(), key=lambda d: d['P'])}
    else:
        lock = json.loads(LOCK.read_text())
    base_versions = {d['P']: d['V'] for d in json.loads((ROOT / 'docs/runtime-packages.json').read_text())}
    stage = BASE / 'unpacked'; shutil.rmtree(stage, ignore_errors=True); stage.mkdir()
    ownership = {}; package_metadata = {}
    for d in lock['packages']:
        filename = f"{d['P']}-{d['V']}.apk"; apk = BASE / filename
        url = f"https://dl-cdn.alpinelinux.org/alpine/v3.22/{d['repository']}/aarch64/{filename}"
        if not apk.exists(): subprocess.run(['curl','--fail','--location','--silent','--show-error','--retry','2','--max-time','120','--output',str(apk),url],check=True)
        sha = digest(apk)
        if not args.resolve and sha != d['sha256']: raise RuntimeError('APK checksum mismatch: ' + filename)
        d.update(sha256=sha, url=url)
        d['included'] = d['P'] not in ('musl', 'python3') and base_versions.get(d['P']) != d['V']
        if d['included']:
            with tarfile.open(fileobj=io.BytesIO(gzip.decompress(apk.read_bytes())), ignore_zeros=True) as tar:
                members = [m for m in tar.getmembers() if not m.name.startswith('.')]
                for m in members:
                    if m.issym() and m.linkname.startswith('/'):
                        m.linkname = posixpath.relpath(m.linkname.lstrip('/'), posixpath.dirname(m.name))
                    if m.name.startswith(SITE) and not m.isdir(): ownership[m.name[len(SITE):]] = d['P']
                tar.extractall(stage, members=members, filter='data')
        package_metadata[d['P']] = d
        print(filename, flush=True)
    site = stage / SITE
    wheel_groups = {}; dist_owners = {}
    for metadata in sorted([*site.glob('*.dist-info/METADATA'), *site.glob('*.egg-info/PKG-INFO')]):
        headers = BytesParser().parsebytes(metadata.read_bytes())
        name = headers['Name']; version = headers['Version']
        owner = ownership[str(metadata.relative_to(site))]
        if owner in dist_owners: raise RuntimeError('Multiple distributions in APK: ' + owner)
        dist_owners[owner] = normalized(name)
        wheel_groups[normalized(name)] = {'name': name, 'version': version, 'info': normalized(name).replace('-', '_')+'-'+version+'.dist-info', 'original_info': metadata.parent.name, 'files': {}}
    # Alpine separates Matplotlib's data/fonts from the Python module. Materialize
    # them in the wheel data scheme so Alpine's sys.prefix-relative lookup still works.
    dist_owners.update({'py3-matplotlib-data': 'matplotlib', 'py3-matplotlib-fonts': 'matplotlib'})
    for path, owner in ownership.items():
        if path.endswith('.pyc') or '/__pycache__/' in path or path.endswith(('/RECORD','/WHEEL','/INSTALLER')): continue
        source = site / path
        if source.is_dir():
            # Directory symlink (e.g. matplotlib/mpl-data) is expanded into real wheel files.
            files = [(str(Path(path) / p.relative_to(source)), p) for p in source.rglob('*') if p.is_file()]
        elif source.is_file(): files = [(path, source)]
        else: continue
        if owner not in dist_owners: raise RuntimeError('Unassigned Python files: ' + owner)
        group = wheel_groups[dist_owners[owner]]
        for logical, file in files:
            original_info = group['original_info']
            if logical.startswith(original_info + '/'):
                suffix = logical[len(original_info)+1:]
                if suffix == 'PKG-INFO': suffix = 'METADATA'
                logical = group['info'] + '/' + suffix
            group['files'][logical] = file.read_bytes()
    mpl = wheel_groups['matplotlib']['files']
    data = stage / 'usr/share/matplotlib/mpl-data'
    if data.exists():
        for p in data.rglob('*'):
            if p.is_file(): mpl['matplotlib-'+wheel_groups['matplotlib']['version']+'.data/data/share/matplotlib/mpl-data/' + str(p.relative_to(data))] = p.read_bytes()
    assert any('/fonts/ttf/' in p for p in mpl), 'Missing Matplotlib fonts'
    layer = BASE / 'layer'; shutil.rmtree(layer, ignore_errors=True); (layer/'wheels').mkdir(parents=True)
    for prefix in ['lib','usr/lib']:
        source = stage / prefix
        if source.exists():
            shutil.copytree(source, layer/prefix, symlinks=True, ignore=shutil.ignore_patterns('python3*'), dirs_exist_ok=True)
    manifest = {'schema': 1, 'alpine': lock['alpine'], 'architecture': lock['architecture'], 'python_abi': 'cp312', 'packages': {}, 'library_source': 'pinned Alpine APKs; see python-native-packages.json'}
    for key, group in sorted(wheel_groups.items()):
        files = group['files']; info = group['info']
        native = any(p.endswith('.so') for p in files)
        tag = 'cp312-cp312-linux_aarch64' if native else 'py3-none-any'
        files[info+'/WHEEL'] = f"Wheel-Version: 1.0\nGenerator: capri-alpine-repack\nRoot-Is-Purelib: {'false' if native else 'true'}\nTag: {tag}\n".encode()
        origin = {'source': 'Alpine v3.22 ARM64 APK', 'original_distribution': group['name'], 'version': group['version'], 'capri_library_layer_required': native}
        files[info+'/capri-origin.json'] = (json.dumps(origin, indent=2)+'\n').encode()
        rows = [(p, 'sha256='+base64.urlsafe_b64encode(hashlib.sha256(content).digest()).decode().rstrip('='), str(len(content))) for p,content in sorted(files.items())]
        record = info+'/RECORD'; output = io.StringIO(); writer = csv.writer(output, lineterminator='\n'); writer.writerows(rows+[(record,'','')]); files[record] = output.getvalue().encode()
        filename = f"{key.replace('-', '_')}-{group['version']}-{tag}.whl"
        target = layer/'wheels'/filename
        with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED) as archive:
            for p, content in sorted(files.items()):
                entry = zipfile.ZipInfo(p, date_time=(2020,1,1,0,0,0)); entry.compress_type = zipfile.ZIP_DEFLATED; entry.external_attr = 0o644 << 16; archive.writestr(entry, content)
        manifest['packages'][key] = {'version': group['version'], 'wheel': filename, 'sha256': digest(target)}
    (layer/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    asset = ASSETS/'python-packages.bundle'
    with asset.open('wb') as output, gzip.GzipFile(fileobj=output,mode='wb',filename='',mtime=0) as compressed:
        with tarfile.open(fileobj=compressed,mode='w') as archive:
            for p in sorted(layer.rglob('*')):
                entry=archive.gettarinfo(p,arcname=str(p.relative_to(layer))); entry.uid=entry.gid=entry.mtime=0;entry.uname=entry.gname=''
                if entry.isfile():
                    with p.open('rb') as f: archive.addfile(entry,f)
                else: archive.addfile(entry)
    LOCK.write_text(json.dumps(lock,indent=2)+'\n')
    sha=digest(asset);(ASSETS/'python-packages.sha256').write_text(sha+'\n')
    hashes=ROOT/'docs/runtime-sha256.json'; values=json.loads(hashes.read_text()); values[str(asset.relative_to(ROOT))]=sha;hashes.write_text(json.dumps(values,indent=2)+'\n')
    print('WHEELS',json.dumps(manifest['packages']),flush=True); print('BUNDLED',asset.stat().st_size,sha)

if __name__ == '__main__': main()
