"""Server-side atomicity of contribute: a file received between withdraw's SELECT and its DELETE (disposable stack only).
A loopback proxy holds only the Storage DELETE (the withdraw's remove) so the interleaving is deterministic."""
import json,os,subprocess,threading,time,uuid,urllib.request,http.server,socketserver
s=json.load(open('/tmp/getnf-review-round2/status.json')); API='http://127.0.0.1:55321'; assert s['API_URL']==API
HOLD=threading.Event(); HELD=threading.Event()
class P(http.server.BaseHTTPRequestHandler):
    def log_message(self,*a):pass
    def fwd(self):
        n=int(self.headers.get('content-length') or 0); body=self.rfile.read(n) if n else None
        if self.command=='DELETE' and '/storage/v1/object/contributions' in self.path:
            HELD.set(); HOLD.wait(30)
        req=urllib.request.Request(API+self.path,body,{k:v for k,v in self.headers.items() if k.lower() not in('host','content-length')},method=self.command)
        try: r=urllib.request.urlopen(req,timeout=60); code,data,h=r.status,r.read(),r.headers
        except urllib.error.HTTPError as e: code,data,h=e.code,e.read(),e.headers
        self.send_response(code); self.send_header('content-type',h.get('content-type','application/json')); self.send_header('content-length',str(len(data))); self.end_headers(); self.wfile.write(data)
    do_GET=do_POST=do_PUT=do_DELETE=do_PATCH=do_HEAD=fwd
class S(socketserver.ThreadingMixIn,http.server.HTTPServer): daemon_threads=True
proxy=S(('127.0.0.1',55339),P); threading.Thread(target=proxy.serve_forever,daemon=True).start()
env={**os.environ,'SUPABASE_URL':'http://127.0.0.1:55339','SUPABASE_SERVICE_ROLE_KEY':s['SECRET_KEY']}
fn=subprocess.Popen(['deno','run','--cached-only','--allow-env','--allow-read','--allow-net=127.0.0.1','serve_contribute_proxied.ts'],env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
time.sleep(3)
F='http://127.0.0.1:55338/'; install=str(uuid.uuid4()); A=str(uuid.uuid4()); B=str(uuid.uuid4()); out=[]
def post(q,body=b'{}'):
    with urllib.request.urlopen(urllib.request.Request(F+'?'+q,body,{'content-type':'application/json'},method='POST'),timeout=60) as r:return r.status,r.read().decode()
def storage():
    req=urllib.request.Request(API+'/storage/v1/object/list/contributions',json.dumps({'prefix':install+'/','limit':100}).encode(),{'apikey':s['SECRET_KEY'],'Authorization':'Bearer '+s['SECRET_KEY'],'content-type':'application/json'},method='POST')
    folders=json.load(urllib.request.urlopen(req)); names=[]
    for f in folders:
        r2=urllib.request.Request(API+'/storage/v1/object/list/contributions',json.dumps({'prefix':install+'/'+f['name']+'/','limit':100}).encode(),{'apikey':s['SECRET_KEY'],'Authorization':'Bearer '+s['SECRET_KEY'],'content-type':'application/json'},method='POST')
        names+=[f['name']+'/'+o['name'] for o in json.load(urllib.request.urlopen(r2))]
    return names
def rows():
    r=urllib.request.Request(API+'/rest/v1/contribution_file?install_id=eq.'+install,headers={'apikey':s['SECRET_KEY'],'Authorization':'Bearer '+s['SECRET_KEY']});return json.load(urllib.request.urlopen(r))
try:
    out.append('upload A: %s %s'%post(f'install={install}&item={A}&file=meta.json'))
    res={}
    t=threading.Thread(target=lambda:res.setdefault('w',post(f'install={install}&withdraw=1'))); t.start()
    assert HELD.wait(20),'withdraw never reached the Storage delete'
    out.append('withdraw holds at Storage delete (rows were already read: only A)')
    out.append('upload B while withdraw is in flight: %s %s'%post(f'install={install}&item={B}&file=meta.json'))
    HOLD.set(); t.join(40)
    out.append('withdraw answer: %s %s'%res['w'])
    out.append('database rows afterwards: %d'%len(rows())); left=storage()
    out.append('Storage objects afterwards: %s'%left)
    out.append('second withdraw: %s %s'%post(f'install={install}&withdraw=1'))
    out.append('Storage objects after second withdraw: %s'%storage())
    out.append('RESULT: '+('orphan object with no row' if storage() and not rows() else 'no orphan'))
finally:
    HOLD.set()
    for n in storage():  # remove only this run's synthetic objects
        urllib.request.urlopen(urllib.request.Request(API+'/storage/v1/object/contributions/'+install+'/'+n,headers={'apikey':s['SECRET_KEY'],'Authorization':'Bearer '+s['SECRET_KEY']},method='DELETE'))
    out.append('cleanup objects left: %d'%len(storage()))
    fn.terminate(); proxy.shutdown()
print('\n'.join(out)); open('contribute-withdraw-race.txt','w').write('\n'.join(out)+'\n')
