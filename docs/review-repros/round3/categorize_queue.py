"""Edge/categorize queue under concurrency and crash, SQL level only (the original claim/fail functions).
Disposable container supabase_db_getnf-review-round2 only; synthetic ledger removed at the end. No network."""
import subprocess,uuid,threading,json,sys
C='supabase_db_getnf-review-round2'
assert C.endswith('review-round2')
def sql(q,check=True):
    r=subprocess.run(['docker','exec','-i',C,'psql','-U','postgres','-v','ON_ERROR_STOP=1','-Atq'],input=q,capture_output=True,text=True)
    if check and r.returncode: raise SystemExit('SQL error: '+r.stderr)
    return r.stdout.strip()
L=str(uuid.uuid4()); N=60; out=[]
def log(*a):
    s=' '.join(map(str,a)); print(s); out.append(s)
sql(f"insert into ledger(id,name) values('{L}','queue synthetic');")
sql(f"""insert into product(ledger_id,id,name) select '{L}',gen_random_uuid(),'p'||g from generate_series(1,{N}) g;
insert into store_item(ledger_id,id,code_kind,code,description,product_id)
 select '{L}',gen_random_uuid(),'store','c'||row_number() over (),'synthetic', id from product where ledger_id='{L}';""")
sql(f"set session_replication_role=replica; update categorize_job set created_at=now()-interval '1 hour' where ledger_id='{L}';")  # the claim waits for a receipt line or ten minutes
log('jobs queued by trigger:',sql(f"select count(*) from categorize_job where ledger_id='{L}'"))
# Make only our jobs claimable: park the rest so batches only see ours
sql(f"update categorize_job set next_try_at=now()+interval '1 day' where ledger_id<>'{L}';")
res={}
def claimer(name,delay):
    res[name]=sql(f"begin; select store_item_id from claim_categorize_jobs(40); select pg_sleep({delay}); commit;").split('\n')
ts=[threading.Thread(target=claimer,args=(n,2)) for n in ('A','B')]
[t.start() for t in ts];[t.join() for t in ts]
a,b=set(res['A']),set(res['B'])
log('concurrent claims (batch 40 each, 60 jobs): A',len(a),'B',len(b),'overlap',len(a&b),'union',len(a|b))
# Poison job: the run dies after every claim (wall-clock limit / crash), so fail_categorize_job is never called
sql(f"update categorize_job set status='done' where ledger_id='{L}';")
item=sql(f"select store_item_id from categorize_job where ledger_id='{L}' limit 1")
sql(f"update categorize_job set status='pending',attempts=0,next_try_at=now() where ledger_id='{L}' and store_item_id='{item}';")
for i in range(1,13):
    got=sql(f"select attempts from claim_categorize_jobs(100) where store_item_id='{item}'")
    sql(f"set session_replication_role=replica; update categorize_job set updated_at=now()-interval '11 minutes' where ledger_id='{L}' and store_item_id='{item}'")  # crash: untouched for >10 min
log('after 12 claim+crash rounds:',sql(f"select 'status='||status||' attempts='||attempts from categorize_job where ledger_id='{L}' and store_item_id='{item}'"))
# A handled failure path for contrast
sql(f"update categorize_job set status='pending',attempts=0,next_try_at=now() where ledger_id='{L}' and store_item_id='{item}';")
for i in range(7):
    sql(f"select count(*) from claim_categorize_jobs(100); select fail_categorize_job('{L}','{item}','synthetic'); update categorize_job set next_try_at=now() where ledger_id='{L}' and store_item_id='{item}';")
log('after 7 claim+handled failure rounds:',sql(f"select 'status='||status||' attempts='||attempts from categorize_job where ledger_id='{L}' and store_item_id='{item}'"))
# Stale run finishing late: job already done by another run, then the stale run reports a failure
sql(f"update categorize_job set status='done',attempts=1 where ledger_id='{L}' and store_item_id='{item}'; select fail_categorize_job('{L}','{item}','late stale failure');")
log('done job after a late failure report from a stale run:',sql(f"select status from categorize_job where ledger_id='{L}' and store_item_id='{item}'"))
sql(f"select apply_categorization('{L}','{item}',(select product_id from store_item where ledger_id='{L}' and id='{item}'),(select id from category where ledger_id='{L}' limit 1),0.95,'synthetic',0.9)",check=False)
sql(f"delete from ledger where id='{L}';")
sql(f"update categorize_job set next_try_at=now() where next_try_at>now()+interval '12 hours';")
log('cleanup: ledger rows left',sql(f"select count(*) from ledger where id='{L}'"),'store items left',sql(f"select count(*) from store_item where ledger_id='{L}'"))
open('categorize-queue.txt','w').write('\n'.join(out)+'\n')
