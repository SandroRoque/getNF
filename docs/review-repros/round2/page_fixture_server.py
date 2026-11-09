"""Loopback only; existing synthetic SC fixture, no upstream requests."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
body = (ROOT/'android/app/src/test/fixtures/sc-detalhes-server-html.html').read_bytes()
assert not any(x in body.lower() for x in (b'src=', b'href=', b'<script', b'url('))
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_GET(self):
        if self.path.startswith('/NFCe_Detalhes.aspx'):
            self.send_response(200)
            self.send_header('Content-Type','text/html; charset=utf-8')
            self.end_headers(); self.wfile.write(body)
            print('SERVED synthetic SC details', flush=True)
        else:
            self.send_response(302)
            self.send_header('Location','/NFCe_Detalhes.aspx'); self.end_headers()
            print('REDIRECT loopback details', flush=True)
HTTPServer(('127.0.0.1',55329),Handler).serve_forever()
