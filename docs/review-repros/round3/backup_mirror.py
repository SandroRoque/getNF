"""Original tools/supabase-backup.py mirror()/check() against the DISPOSABLE stack only (bucket `receipts`, synthetic names).
1) object name with '..' accepted by Storage as a member? 2) object deleted between listing and download.
3) files kept after deletion online. 4) check() health after a failing mirror."""
import importlib.util,json,os,tempfile,urllib.request,urllib.error,uuid,sys,datetime
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]; s=json.load(open('/tmp/getnf-review-round2/status.json')); URL='http://127.0.0.1:55321'; assert s['API_URL']==URL
spec=importlib.util.spec_from_file_location('backup',ROOT/'tools/supabase-backup.py'); b=importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
env={'SUPABASE_URL':URL,'SUPABASE_SECRET_KEY':s['SECRET_KEY']}; out=[]
def log(x): print(x); out.append(x)
def raw(path,method='GET',data=None,key=None,ctype='application/json'):
    key=key or s['SECRET_KEY']
    try:
        with urllib.request.urlopen(urllib.request.Request(URL+path,data,{'apikey':key,'Authorization':'Bearer '+key,'Content-Type':ctype},method=method),timeout=30) as r:return r.status,r.read()
    except urllib.error.HTTPError as e:return e.code,e.read()
mark='review-r3-'+uuid.uuid4().hex[:8]; made=[]
def put(name,body=b'x'):
    st,_=raw('/storage/v1/object/receipts/'+name,'POST',body,ctype='text/plain'); made.append(name); return st
try:
    # 1: traversal-like names through Storage (service role bypasses policies; this asks only whether Storage itself rejects them)
    for name in (f'{mark}/../escape-{mark}.txt', f'{mark}/%2e%2e/escape2-{mark}.txt', f'{mark}//double.txt'):
        st,body=raw('/storage/v1/object/receipts/'+name,'POST',b'x',ctype='text/plain'); log(f'upload name {name!r}: HTTP {st} {body[:80].decode(errors="replace")}')
        if st==200: made.append(name)
    # 2: failing mirror: lister sees three objects, one disappears before it is downloaded
    names=[f'{mark}/a.txt',f'{mark}/b.txt',f'{mark}/c.txt']
    for n in names: put(n)
    real=b.storage; state={'killed':False}
    def flaky(e,path,body=None):
        if path.startswith('object/receipts/') and f'{mark}%2Fb.txt' in path.replace('/','%2F',1) or path.endswith(f'{mark}/b.txt'):
            if not state['killed']:
                state['killed']=True; raw('/storage/v1/object/receipts','DELETE',json.dumps({'prefixes':[f'{mark}/b.txt']}).encode())
        return real(e,path,body)
    b.storage=flaky
    with tempfile.TemporaryDirectory(dir='/tmp/getnf-review-round2') as tmp:
        try:
            r=b.mirror(env,tmp); log(f'mirror result {r}')
        except Exception as ex: log(f'mirror raised {type(ex).__name__}: {ex}')
        got=sorted(str(p.relative_to(tmp)) for p in Path(tmp).rglob('*') if p.is_file() and p.name!='.index.json' and mark in str(p))
        log(f'files mirrored before failure (this run marker only): {got}')
        log(f'.index.json written after failure: {Path(tmp,".index.json").exists()}  (so the next run re-downloads everything it already fetched)')
        b.storage=real
        # 3: object deleted online stays in the mirror on later runs
        r2=b.mirror(env,tmp); log(f'second mirror {r2}; c.txt present: {Path(tmp,mark,"c.txt").exists()}')
        raw('/storage/v1/object/receipts','DELETE',json.dumps({'prefixes':[f'{mark}/c.txt']}).encode())
        r3=b.mirror(env,tmp); log(f'after deleting c.txt online: mirror {r3}; copy still on the PC: {Path(tmp,mark,"c.txt").exists()}')
        # 4: health check only looks at the dump
        d=Path(tmp,'dumps'); d.mkdir(); (d/'db-2026-01-01_000000.dump').write_bytes(b'x'); log('check() verdict with a fresh dump and no files mirrored/failed mirror: exit '+str(b.check(str(d))))
finally:
    b.storage=real if 'real' in dir() else b.storage
    raw('/storage/v1/object/receipts','DELETE',json.dumps({'prefixes':made+[f'{mark}/{x}.txt' for x in 'abc']+[f'escape-{mark}.txt',f'escape2-{mark}.txt',f'{mark}/double.txt']}).encode())
    st,body=raw('/storage/v1/object/list/receipts','POST',json.dumps({'prefix':mark,'limit':100}).encode()); log('cleanup, objects under marker: '+str(len(json.loads(body))))
open(Path(__file__).with_name('backup-mirror.txt'),'w').write('\n'.join(out)+'\n')
