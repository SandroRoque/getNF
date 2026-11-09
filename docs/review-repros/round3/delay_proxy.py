"""Loopback delay proxy 127.0.0.1:55340 -> disposable Supabase 127.0.0.1:55321 (refuses any other upstream).
Control: GET /__ctl?token_ms=N&rest_ms=M delays /auth/v1/token and /rest/v1/ responses (applied BEFORE forwarding)."""
import http.server,socketserver,time,urllib.request,urllib.error,urllib.parse,sys
UP='http://127.0.0.1:55321'; STATE={'token_ms':0,'rest_ms':0}; LOG=open(sys.argv[1] if len(sys.argv)>1 else '/dev/null','a')
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self,*a):pass
    def do(self):
        if self.path.startswith('/__ctl'):
            for k,v in urllib.parse.parse_qsl(urllib.parse.urlsplit(self.path).query):STATE[k]=int(v)
            self.send_response(200);self.send_header('content-length','2');self.end_headers();self.wfile.write(b'ok');return
        n=int(self.headers.get('content-length') or 0);body=self.rfile.read(n) if n else None
        path=self.path.split('?')[0]
        d=STATE['token_ms'] if path.startswith('/auth/v1/token') else STATE['rest_ms'] if path.startswith('/rest/v1/') else 0
        LOG.write(f'{time.time():.2f} {self.command} {path} delay={d}\n');LOG.flush()
        time.sleep(d/1000)
        req=urllib.request.Request(UP+self.path,body,{k:v for k,v in self.headers.items() if k.lower() not in('host','content-length')},method=self.command)
        try:r=urllib.request.urlopen(req,timeout=120);code,data,h=r.status,r.read(),r.headers
        except urllib.error.HTTPError as e:code,data,h=e.code,e.read(),e.headers
        self.send_response(code);self.send_header('content-type',h.get('content-type','application/json'));self.send_header('content-length',str(len(data)));self.end_headers();self.wfile.write(data)
    do_GET=do_POST=do_PUT=do_DELETE=do_PATCH=do_HEAD=do
class S(socketserver.ThreadingMixIn,http.server.HTTPServer):daemon_threads=True
S(('127.0.0.1',55340),H).serve_forever()
