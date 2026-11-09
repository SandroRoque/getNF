"""Dump-then-mirror order of tools/supabase-backup.py on the DISPOSABLE stack: a photo deleted online between the two steps
leaves a dump row that points to a file no mirror has. Uses the original mirror(); the dump is pg_dump of one table inside the
disposable container (the script's own dump() targets the hosted project and was not run)."""
import importlib.util,json,subprocess,tempfile,urllib.request,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]; s=json.load(open('/tmp/getnf-review-round2/status.json')); URL='http://127.0.0.1:55321'; assert s['API_URL']==URL
spec=importlib.util.spec_from_file_location('backup',ROOT/'tools'/'supabase-backup.py'); b=importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
env={'SUPABASE_URL':URL,'SUPABASE_SECRET_KEY':s['SECRET_KEY']}; C='supabase_db_getnf-review-round2'; out=[]
def sql(q):
    r=subprocess.run(['docker','exec','-i',C,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-Atq'],input=q,capture_output=True,text=True)
    if r.returncode:raise SystemExit(r.stderr)
    return r.stdout.strip()
def api(path,method='GET',body=None,ctype='application/json'):
    return urllib.request.urlopen(urllib.request.Request(URL+'/storage/v1/'+path,body,{'apikey':s['SECRET_KEY'],'Authorization':'Bearer '+s['SECRET_KEY'],'Content-Type':ctype},method=method)).read()
L=str(uuid.uuid4()); chave='7'*44; obj=f'{L}/{chave}/p0.webp'
sql(f"insert into ledger(id,name) values('{L}','dump gap synthetic'); insert into receipt(ledger_id,chave,captured_at) values('{L}','{chave}',now()); insert into receipt_photo(ledger_id,chave,local_name,position,object_path) values('{L}','{chave}','p0',0,'{obj}');")
api('object/receipts/'+obj,'POST',b'x','application/octet-stream')
# 1. the nightly script takes the database dump first
dump=subprocess.run(['docker','exec',C,'pg_dump','-U','postgres','--data-only','--table=public.receipt_photo','--inserts'],capture_output=True,text=True).stdout
row_in_dump=obj in dump; out.append(f'step 1 dump taken: contains the photo row = {row_in_dump}')
# 2. between dump and mirror, the file goes (the person withdraws photos / leaves the caderneta; the row goes too)
api('object/receipts','DELETE',json.dumps({'prefixes':[obj]}).encode()); sql(f"delete from receipt_photo where ledger_id='{L}';")
out.append('step 2 online: file and row deleted')
# 3. then the mirror runs
with tempfile.TemporaryDirectory(dir='/tmp/getnf-review-round2') as tmp:
    b.mirror(env,tmp); mirrored=Path(tmp,obj).exists()
out.append(f'step 3 mirror ran: file present in the mirror = {mirrored}')
out.append(f'RESULT: dump row references a file the mirror does not have: {row_in_dump and not mirrored}; restoring this pair yields a photo row with no file')
sql(f"delete from ledger where id='{L}';")
print('\n'.join(out)); Path(__file__).with_name('dump-mirror-gap.txt').write_text('\n'.join(out)+'\n')
