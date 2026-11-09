"""tools/llm-worker.py Supabase.select(): limit/offset without ORDER BY while the phones keep writing. Disposable stack only."""
import importlib.util,json,subprocess,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]; s=json.load(open('/tmp/getnf-review-round2/status.json')); URL='http://127.0.0.1:55321'; assert s['API_URL']==URL
spec=importlib.util.spec_from_file_location('w',ROOT/'tools'/'llm-worker.py'); w=importlib.util.module_from_spec(spec); spec.loader.exec_module(w)
C='supabase_db_getnf-review-round2'
def sql(q):
    r=subprocess.run(['docker','exec','-i',C,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-Atq'],input=q,capture_output=True,text=True)
    if r.returncode:raise SystemExit(r.stderr)
    return r.stdout.strip()
L=str(uuid.uuid4()); N=2500; out=[]
sql(f"insert into ledger(id,name) values('{L}','pagination synthetic'); insert into issuer(ledger_id,cnpj,name) select '{L}',lpad(g::text,14,'0'),'Loja '||g from generate_series(1,{N}) g; vacuum analyze issuer;")
class Db(w.Supabase):
    pages=0
    def call(self,method,path,*a,**k):
        r=super().call(method,path,*a,**k)
        self.pages+=1
        if self.pages==1:  # a phone syncs between the worker's first and second page: 800 stores get a new name
            sql(f"update issuer set name=name||'!' where ledger_id='{L}' and cnpj in (select cnpj from issuer where ledger_id='{L}' order by cnpj limit 800);")
        return r
rows=Db(URL,s['SECRET_KEY']).select('issuer',ledger_id=f'eq.{L}')
ids=[r['cnpj'] for r in rows]
out.append(f'rows that exist: {N}; rows the worker received: {len(ids)}; distinct: {len(set(ids))}; duplicated: {len(ids)-len(set(ids))}; missing: {N-len(set(ids))}')
sql(f"delete from ledger where id='{L}';")
out.append('cleanup: ledger rows left '+sql(f"select count(*) from ledger where id='{L}'")+', issuers left '+sql(f"select count(*) from issuer where ledger_id='{L}'"))
print('\n'.join(out)); open(Path(__file__).with_name('worker-pagination.txt'),'w').write('\n'.join(out)+'\n')
