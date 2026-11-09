"""Real function + local Storage, synthetic files only; tests >1000 historical contributions."""
import json,urllib.request,uuid,concurrent.futures
s=json.load(open('/tmp/getnf-review-round2/status.json'));base='http://127.0.0.1:55321';assert s['API_URL']==base
install=str(uuid.uuid4());items=[str(uuid.uuid4()) for _ in range(1001)]
def request(path,method='GET',data=None,typ='application/json'):
 with urllib.request.urlopen(urllib.request.Request(base+path,data,{'apikey':s['SECRET_KEY'],'Content-Type':typ},method=method),timeout=30) as r:return r.read()
def upload(item):request('/storage/v1/object/contributions/'+install+'/'+item+'/meta.json','POST',b'{}')
with concurrent.futures.ThreadPoolExecutor(max_workers=12) as p:list(p.map(upload,items))
rows=[{'install_id':install,'item_id':i,'name':'meta.json','bytes':2,'received_at':'2026-01-01T00:00:00Z'} for i in items]
for off in range(0,len(rows),500):request('/rest/v1/contribution_file','POST',json.dumps(rows[off:off+500]).encode())
with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:55328/?install='+install+'&withdraw=1',b'',method='POST'),timeout=60) as r:print('withdraw:',r.status,r.read().decode())
left=json.loads(request('/storage/v1/object/list/contributions','POST',json.dumps({'prefix':install,'limit':1000}).encode()))
remaining_rows=json.loads(request('/rest/v1/contribution_file?install_id=eq.'+install))
print('remaining Storage folders:',len(left));print('remaining database rows:',len(remaining_rows))
print('Expected: zero folders and rows')
raise SystemExit(bool(left or remaining_rows))
