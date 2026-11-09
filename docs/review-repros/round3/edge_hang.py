"""Stalled provider vs the claim/retry bookkeeping of the original categorize function (disposable stack only)."""
import json,subprocess,uuid,os
C='supabase_db_getnf-review-round2'
def sql(q):
    r=subprocess.run(['docker','exec','-i',C,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-Atq'],input=q,capture_output=True,text=True)
    if r.returncode: raise SystemExit(r.stderr)
    return r.stdout.strip()
s=json.load(open('/tmp/getnf-review-round2/status.json')); assert s['API_URL']=='http://127.0.0.1:55321'
L=str(uuid.uuid4()); out=[]
sql(f"""insert into ledger(id,name) values('{L}','hang synthetic');
insert into category(ledger_id,id,label) values('{L}',gen_random_uuid(),'cat');
insert into product(ledger_id,id,name) select '{L}',gen_random_uuid(),'p'||g from generate_series(1,5) g;
insert into store_item(ledger_id,id,code_kind,code,description,product_id) select '{L}',gen_random_uuid(),'store','c'||row_number() over (),'ARROZ SYNTHETIC',id from product where ledger_id='{L}';
set session_replication_role=replica; update categorize_job set next_try_at=now()+interval '1 day' where ledger_id<>'{L}'; update categorize_job set created_at=now()-interval '1 hour' where ledger_id='{L}';""")
env={**os.environ,'SUPABASE_URL':s['API_URL'],'SUPABASE_SERVICE_ROLE_KEY':s['SECRET_KEY']}
r=subprocess.run(['deno','run','--cached-only','--allow-env','--allow-read','--allow-net=127.0.0.1:55321','edge_hang.ts','8000'],env=env,capture_output=True,text=True,timeout=60)
out.append(r.stdout.strip()+(' | stderr: '+r.stderr.strip()[:300] if r.returncode else ''))
out.append('job states after the stalled run: '+sql(f"select string_agg(status||' attempts='||attempts||' error='||coalesce(last_error,'-'),'; ') from (select status,attempts,last_error from categorize_job where ledger_id='{L}') x"))
sql(f"delete from ledger where id='{L}'; set session_replication_role=replica; update categorize_job set next_try_at=now() where next_try_at>now()+interval '12 hours';")
out.append('cleanup ledger rows: '+sql(f"select count(*) from ledger where id='{L}'"))
print('\n'.join(out)); open('edge-hang.txt','w').write('\n'.join(out)+'\n')
