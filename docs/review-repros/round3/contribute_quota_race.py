"""Per-install daily file limit (DAY_FILES=300) under parallel requests. Original function on the disposable stack (55328)."""
import uuid,urllib.request,urllib.error,concurrent.futures,collections,json
install=str(uuid.uuid4()); F='http://127.0.0.1:55328/'
def post(q):
    try:
        with urllib.request.urlopen(urllib.request.Request(F+'?'+q,b'{}',{'content-type':'application/json'},method='POST'),timeout=120) as r:return r.status
    except urllib.error.HTTPError as e:return e.code
def run(workers,n):
    inst=str(uuid.uuid4())
    with concurrent.futures.ThreadPoolExecutor(workers) as p:
        codes=list(p.map(lambda i:post(f'install={inst}&item={uuid.uuid4()}&file=meta.json'),range(n)))
    stored=post(f'install={inst}&withdraw=1')  # cleanup + not counted
    return collections.Counter(codes)
out=[]
for w in (1,8,32):
    c=run(w,360); out.append(f'workers={w}: 360 requests -> accepted {c[200]}, rejected(429) {c[429]}, other {sum(v for k,v in c.items() if k not in (200,429))}')
print('\n'.join(out)); open('contribute-quota-race.txt','w').write('limit DAY_FILES=300 per install\n'+'\n'.join(out)+'\n')
