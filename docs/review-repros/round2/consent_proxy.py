"""Local delayed transport for the REAL ContributionWorker. Never accepts non-loopback backend.
Start, enqueue synthetic app page, then revoke consent in UI. Upload waits for withdrawal.
Only filenames, order and counts are logged; no credentials, installation IDs or bodies.
"""
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from urllib.parse import urlsplit,parse_qs
import urllib.request,urllib.error,threading,json,time
BACKEND='http://127.0.0.1:55328'
withdrawn=threading.Event(); started=threading.Event(); lock=threading.Lock(); events=[]
def log(s):
 with lock:events.append(s);print(s,flush=True)
class H(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  self.send_response(200);self.end_headers();self.wfile.write(json.dumps({'upload_started':started.is_set(),'withdrawn':withdrawn.is_set(),'events':events}).encode())
 def do_POST(self):
  q=urlsplit(self.path).query;params=parse_qs(q)
  body=self.rfile.read(int(self.headers.get('content-length','0')))
  is_withdraw='withdraw' in params
  if not is_withdraw:
   log('UPLOAD_BODY_RECEIVED '+params.get('file',['?'])[0]);started.set()
   if not withdrawn.wait(45):
    log('UPLOAD_TIMEOUT_WAITING_WITHDRAWAL');self.send_response(503);self.end_headers();return
  req=urllib.request.Request(BACKEND+'/?'+q,data=body,headers={'Content-Type':self.headers.get('Content-Type','application/json')},method='POST')
  try:
   with urllib.request.urlopen(req,timeout=20) as r:code=r.status;out=r.read()
  except urllib.error.HTTPError as e:code=e.code;out=e.read()
  log(('WITHDRAW_BACKEND_' if is_withdraw else 'UPLOAD_BACKEND_')+str(code))
  self.send_response(code);self.send_header('Content-Type','application/json');self.end_headers()
  try:self.wfile.write(out)
  except BrokenPipeError:log('CLIENT_CLOSED_RESPONSE')
  if is_withdraw and code==200:withdrawn.set()
ThreadingHTTPServer(('127.0.0.1',55330),H).serve_forever()
