"""tools/blank-buyers.py was NOT executed (it reads production credentials through supabase-backup.env()). Its two requests are
replayed verbatim against the DISPOSABLE stack to show what they return there: the row listing is one unpaged request, and a
missing page object answers 4xx (the script retries only >=500 and otherwise raises, ending the whole run)."""
import json,subprocess,urllib.request,urllib.error,uuid,importlib.util
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]; s=json.load(open('/tmp/getnf-review-round2/status.json')); base='http://127.0.0.1:55321'; assert s['API_URL']==base
auth={'apikey':s['SECRET_KEY'],'Authorization':'Bearer '+s['SECRET_KEY']}
C='supabase_db_getnf-review-round2'
def sql(q):
    r=subprocess.run(['docker','exec','-i',C,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-Atq'],input=q,capture_output=True,text=True)
    if r.returncode:raise SystemExit(r.stderr)
    return r.stdout.strip()
L=str(uuid.uuid4()); out=[]
sql(f"insert into ledger(id,name) values('{L}','blank synthetic'); insert into receipt(ledger_id,chave,captured_at,page_path) select '{L}',lpad(g::text,44,'0'),now(),'{L}/'||lpad(g::text,44,'0')||'/page.html' from generate_series(1,1100) g;")
def get(path):  # copied from blank-buyers.py
    import time
    for attempt in range(4):
        try:return urllib.request.urlopen(urllib.request.Request(base+path,headers=auth)).read()
        except urllib.error.HTTPError as err:
            if err.code<500 or attempt==3:raise
            time.sleep(2**attempt)
rows=json.loads(get('/rest/v1/receipt?select=page_path&page_path=not.is.null'))
out.append(f'receipts with a page in the database: {sql(f"select count(*) from receipt where ledger_id=\'{L}\' and page_path is not null")}; rows returned by the script\'s listing request: {len(rows)}')
mine=[r for r in rows if r['page_path'].startswith(L)]; out.append(f'of the {len(rows)} returned, {len(mine)} are this ledger\'s (the listing is unordered and capped, the rest of the {1100-len(mine)} were never seen)')
try: get('/storage/v1/object/receipts/'+mine[0]['page_path']); out.append('first page object: found')
except urllib.error.HTTPError as e: out.append(f'a receipt row whose page object is missing -> HTTP {e.code} (Storage answers 400 NoSuchKey); the script retries only >=500 and does not catch it, so one such row ends the run with a traceback before later pages are checked')
sql(f"delete from ledger where id='{L}';"); out.append('cleanup receipts left: '+sql(f"select count(*) from receipt where ledger_id='{L}'"))
print('\n'.join(out)); open(Path(__file__).with_name('blank-buyers-limits.txt'),'w').write('\n'.join(out)+'\n')
