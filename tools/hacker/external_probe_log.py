"""Read only the selected public nonce path from the tail of the owner's Nginx log. No other rows leave the server."""
import json
from pathlib import Path
import subprocess


def read_events(key, known_hosts, token):
    if len(token)!=16 or any(x not in '0123456789abcdef' for x in token):raise ValueError('Invalid fixture nonce')
    remote_code='''import json,re
from pathlib import Path
from datetime import datetime,timezone
p=Path('/var/log/nginx/lgtserver-access.log')
f=p.open('rb');f.seek(max(0,p.stat().st_size-2097152));lines=f.read().decode('utf-8',errors='replace').splitlines()
pattern=re.compile(r'\\[([^]]+)\\].*"GET /capri-gateway/TOKEN/probe\\?seq=(\\d+) HTTP/[0-9.]+" (\\d+)')
rows=[]
for line in lines:
 m=pattern.search(line)
 if m:rows.append({'time':datetime.strptime(m[1],'%d/%b/%Y:%H:%M:%S %z').astimezone(timezone.utc).isoformat(),'sequence':int(m[2]),'status':int(m[3])})
print(json.dumps(rows))
'''.replace('TOKEN',token)
    command=['ssh','-i',str(key),'-o','IdentitiesOnly=yes','-o','BatchMode=yes','-o','ConnectTimeout=8','-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(known_hosts),'ubuntu@124.220.51.203','sudo -n python3 -']
    r=subprocess.run(command,input=remote_code,capture_output=True,text=True,timeout=12);r.check_returncode()
    return json.loads(r.stdout)
