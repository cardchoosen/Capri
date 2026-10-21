# Development helper: expects work/alpine.tar.gz and work/APKINDEX fetched from the URLs in docs/THIRD_PARTY.md.
import re, pathlib, subprocess, json, tarfile, gzip, io
base=pathlib.Path('work/runtime-build'); base.mkdir(exist_ok=True)
packages=[]
for block in pathlib.Path('work/APKINDEX').read_text().split('\n\n'):
 d=dict(line.split(':',1) for line in block.splitlines() if ':' in line)
 if 'P' in d: packages.append(d)
lookup={d['P']:d for d in packages}
for d in packages:
 for provided in d.get('p','').split(): lookup.setdefault(re.split('[=<>~]',provided)[0],d)
chosen={}
def visit(name):
 name=re.split('[=<>~]',name)[0]
 if name.startswith('!'):return
 d=lookup[name]
 if d['P'] in chosen:return
 chosen[d['P']]=d
 for dep in d.get('D','').split(): visit(dep)
visit('python3')
for d in chosen.values():
 name=f"{d['P']}-{d['V']}.apk"; target=base/name
 if not target.exists():subprocess.run(['curl','-L','--fail','--silent','--retry','2','-o',str(target),'https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/'+name],check=True)
 print(name,flush=True)
root=base/'rootfs';root.mkdir(exist_ok=True)
with tarfile.open('work/alpine.tar.gz') as tar:tar.extractall(root,filter='fully_trusted')
for d in chosen.values():
 data=gzip.decompress((base/f"{d['P']}-{d['V']}.apk").read_bytes())
 with tarfile.open(fileobj=io.BytesIO(data),ignore_zeros=True) as tar:
  members=[m for m in tar.getmembers() if not m.name.startswith('.')]
  tar.extractall(root,members=members,filter='fully_trusted')
for directory in ['workspace','root','tmp','run/capri']: (root/directory).mkdir(parents=True,exist_ok=True)
(root/'etc/resolv.conf').write_text('nameserver 1.1.1.1\nnameserver 8.8.8.8\n')
with tarfile.open('app/src/main/assets/runtime/rootfs.bundle','w:gz') as tar:
 for p in root.iterdir():tar.add(p,arcname=p.name)
pathlib.Path('docs/runtime-packages.json').write_text(json.dumps(list(chosen.values()),indent=2))
print('BUNDLED',len(chosen),'packages')
