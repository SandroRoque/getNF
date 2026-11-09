"""Real local auth refresh delayed at response boundary. No credential/body logs.
GET /review/status and /review/release control the synthetic timing.
All upstream paths are restricted to the disposable localhost instance.
"""
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
import threading,urllib.request,urllib.error,json
ready=threading.Event();release=threading.Event();gate_lock=threading.Lock();gated=True
class H(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  global gated
  if self.path.startswith('/review/'):
   if self.path=='/review/arm':
    with gate_lock:gated=False;ready.clear();release.clear()
   if self.path=='/review/release':release.set();print('RELEASE_REFRESH',flush=True)
   self.send_response(200);self.end_headers();self.wfile.write(json.dumps({'refresh_held':ready.is_set(),'released':release.is_set()}).encode());return
  self.forward()
 def do_POST(self):self.forward()
 def do_PATCH(self):self.forward()
 def do_DELETE(self):self.forward()
 def forward(self):
  global gated
  if not self.path.startswith(('/auth/v1/','/rest/v1/','/storage/v1/')):
   self.send_response(403);self.end_headers();return
  body=self.rfile.read(int(self.headers.get('content-length','0'))) or None
  headers={k:v for k,v in self.headers.items() if k.lower() not in ('host','content-length','connection','accept-encoding')}
  req=urllib.request.Request('http://127.0.0.1:55321'+self.path,body,headers,method=self.command)
  try:
   with urllib.request.urlopen(req,timeout=20) as r:code=r.status;data=r.read()
  except urllib.error.HTTPError as e:code=e.code;data=e.read()
  delay=False
  if '/auth/v1/token?grant_type=refresh_token' in self.path:
   with gate_lock:
    if not gated:gated=True;delay=True
  if delay:
   print('REFRESH_BACKEND_'+str(code)+'_HELD',flush=True);ready.set()
   if not release.wait(180):print('CONTROL_TIMEOUT',flush=True)
  self.send_response(code);self.send_header('Content-Type','application/json');self.end_headers()
  try:self.wfile.write(data)
  except BrokenPipeError:print('CLIENT_CLOSED',flush=True)
ThreadingHTTPServer(('127.0.0.1',55330),H).serve_forever()
