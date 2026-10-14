#!/usr/bin/env python3
"""Cold-copy an existing local installation into the new Compose namespace."""
import json, os, re, subprocess, sys, time, uuid
from pathlib import Path

root = Path(__file__).resolve().parent.parent.parent
os.chdir(root)
target = 'pollydrop-public'
compose = ['docker','compose','-p',target,'-f','compose.yaml','-f','deploy/server/compose.server.yaml']
state_path = root/'.runtime/server/migration.json'
def run(args, **kw):
    return subprocess.run(args,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kw).stdout

def inspect(identifier):return json.loads(run(['docker','inspect',identifier]))[0]
def environment(container):return dict(value.split('=',1) for value in container['Config']['Env'] if '=' in value)
def mounted(container,path):
    return next((m['Name'] for m in container['Mounts'] if m['Destination']==path and m['Type']=='volume'),None)
def quote(value):return '"'+value.replace('"','""')+'"'

ids = run(['docker','ps','-q','--filter','publish=8088'],text=True).split()
if not ids:sys.exit(0)
front = inspect(ids[0]);labels = front['Config']['Labels'] or {}
source = labels.get('com.docker.compose.project')
if source == target:sys.exit(0)
if len(ids)!=1 or not source or labels.get('com.docker.compose.service')!='frontend':
    sys.exit('Port 8088 belongs to an unrelated service. No data was changed.')
source_ids = run(['docker','ps','-aq','--filter','label=com.docker.compose.project='+source],text=True).split()
services = {inspect(c)['Config']['Labels']['com.docker.compose.service']:inspect(c) for c in source_ids}
if not all(name in services for name in ('db','backend','frontend')):
    sys.exit('The existing installation could not be identified. No data was changed.')
backend_env=environment(services['backend']); db_env=environment(services['db'])
local={}
for line in (root/'.env').read_text().splitlines():
    if '=' in line and not line.startswith('#'):
        key,value=line.split('=',1);local[key]=value.strip("'\"")
if local.get('APP_MASTER_KEY')!=backend_env.get('APP_MASTER_KEY') or local.get('POSTGRES_PASSWORD')!=db_env.get('POSTGRES_PASSWORD'):
    sys.exit('Existing keys do not match this checkout. No data was changed.')
pairs={}
for service,path,suffix in [('db','/var/lib/postgresql/data','database'),('backend','/data/files','files'),('caddy','/data','caddy_data'),('caddy','/config','caddy_config')]:
    if service not in services:continue
    volume=mounted(services[service],path)
    if not volume:sys.exit('The existing installation uses unsupported storage. No data was changed.')
    pairs[volume]=target+'_'+suffix
for volume in pairs.values():
    if subprocess.run(['docker','volume','inspect',volume],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
        sys.exit('Destination data volumes already exist. Migration stopped without overwriting them.')
print('Preparing a cold copy of database, encrypted content and certificates. Original volumes will be retained.',flush=True)
created=[]; stopped=False;new_db=None
original_env=(root/'.env').read_bytes()
try:
    # All replacements were built by the caller before downtime starts.
    stopped=True
    for name in ('frontend','caddy','backend','db'):
        if name in services:run(['docker','stop',services[name]['Id']])
    for old,new in pairs.items():
        run(['docker','volume','create','--label','com.docker.compose.project='+target,'--label','com.docker.compose.volume='+new[len(target)+1:],new]);created.append(new)
        run(['docker','run','--rm','--entrypoint','sh','--mount',f'type=volume,src={old},dst=/source,readonly','--mount',f'type=volume,src={new},dst=/destination','nginx:1.28-alpine','-c','cp -a /source/. /destination/'])
    run(compose+['up','-d','db'])
    new_db=run(compose+['ps','-q','db'],text=True).strip()
    for _ in range(40):
        if subprocess.run(['docker','exec',new_db,'pg_isready'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
        time.sleep(.5)
    else:raise RuntimeError('Copied database did not start.')
    old_user=db_env['POSTGRES_USER'];old_db=db_env['POSTGRES_DB']; temporary='migration_'+uuid.uuid4().hex
    def sql(user,database,statement):
        # Passwords go through stdin; command lines and output never contain them.
        return run(['docker','exec','-i',new_db,'psql','-v','ON_ERROR_STOP=1','-U',user,'-d',database],input=statement.encode())
    sql(old_user,'postgres',f'CREATE ROLE {quote(temporary)} SUPERUSER LOGIN;')
    if old_db!='pollydrop':sql(temporary,'postgres',f'ALTER DATABASE {quote(old_db)} RENAME TO pollydrop;')
    if old_user!='pollydrop':sql(temporary,'postgres',f'ALTER ROLE {quote(old_user)} RENAME TO pollydrop;')
    password=db_env['POSTGRES_PASSWORD'].replace("'","''")
    sql(temporary,'postgres',f"ALTER ROLE pollydrop PASSWORD '{password}';")
    sql('pollydrop','postgres',f'DROP ROLE {quote(temporary)};')
    text=(root/'.env').read_text()
    for key,value in [('DATABASE_URL','jdbc:postgresql://localhost:5432/pollydrop'),('DATABASE_USER','pollydrop')]:
        if re.search(r'^'+key+r'=',text,re.M):text=re.sub(r'^'+key+r'=.*$',key+'='+value,text,flags=re.M)
        else:text+='\n'+key+'='+value+'\n'
    temp=root/'.env.migrating';temp.write_text(text);temp.chmod(0o600);temp.replace(root/'.env')
    state_path.parent.mkdir(parents=True,exist_ok=True)
    state_path.write_text(json.dumps({'originalContainers':source_ids,'copiedVolumes':list(pairs.values()),'target':target},indent=2)+'\n')
    state_path.chmod(0o600)
    print('Data copied and database/user renamed. Encryption key and password preserved.',flush=True)
except Exception:
    (root/'.env').write_bytes(original_env)
    (root/'.env').chmod(0o600)
    if new_db:
        subprocess.run(['docker','rm','-f',new_db],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    for volume in created:
        subprocess.run(['docker','volume','rm',volume],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if stopped:
        run(['docker','start',services['db']['Id']])
        for name in ('backend','frontend','caddy'):
            if name in services:run(['docker','start',services[name]['Id']])
    sys.exit('Migration failed; original volumes were preserved and the original services were restarted. No secret values were printed.')
