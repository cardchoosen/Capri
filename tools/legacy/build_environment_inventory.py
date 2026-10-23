"""Build trusted file inventories from the exact APK runtime gzip streams."""
from pathlib import Path
import hashlib, io, json, posixpath, tarfile
ROOT=Path(__file__).resolve().parents[2]; ASSETS=ROOT/'app/src/main/assets/runtime'
for key,parts in [('rootfs',['rootfs.bundle']),('devtools',json.loads((ASSETS/'devtools.parts.json').read_text())),('python-packages',['python-packages.bundle'])]:
    data=b''.join((ASSETS/name).read_bytes() for name in parts)
    sha=hashlib.sha256(data).hexdigest(); entries=[]
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            path=posixpath.normpath(member.name)
            if path=='.' or member.isdir():continue
            if path.startswith('/') or '..' in path.split('/'):raise ValueError(path)
            # Python legitimately regenerates bytecode on startup. Check sources instead.
            if '__pycache__' in path.split('/') or path.endswith(('.pyc','.pyo')):continue
            if member.issym():entries.append({'path':path,'link':member.linkname})
            elif member.isfile() or member.islnk():
                digest=hashlib.file_digest(archive.extractfile(member),'sha256').hexdigest()
                entries.append({'path':path,'sha256':digest,'executable':bool(member.mode & 0o111)})
            # Device nodes are intentionally not used for application integrity checks.
    (ASSETS/(key+'.files.json')).write_text(json.dumps({'archive_sha256':sha,'files':sorted(entries,key=lambda e:e['path'])},separators=(',',':'))+'\n')
    if key=='rootfs':(ASSETS/'rootfs.sha256').write_text(sha+'\n')
    print(key,len(entries),sha)
