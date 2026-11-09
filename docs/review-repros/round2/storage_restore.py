"""Synthetic local Storage mirror/restore; no reset, production env or original drill."""
import hashlib, importlib.util, json, tempfile, urllib.request, uuid
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
s = json.loads(Path('/tmp/getnf-review-round2/status.json').read_text())
URL = 'http://127.0.0.1:55321'
assert s['API_URL'] == URL
spec = importlib.util.spec_from_file_location('backup', ROOT/'tools/supabase-backup.py')
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
env = {'SUPABASE_URL': URL, 'SUPABASE_SECRET_KEY': s['SECRET_KEY']}
def request(path, method, data, content='application/json'):
    headers = {'apikey': s['SECRET_KEY'], 'Authorization': 'Bearer '+s['SECRET_KEY'], 'Content-Type': content}
    with urllib.request.urlopen(urllib.request.Request(URL+'/storage/v1/'+path, data, headers, method=method), timeout=30) as r:
        return r.read()
prefix = 'review-restore-'+str(uuid.uuid4())
path = prefix+'/nested/synthetic.html'
body = b'<html><body>SYNTHETIC STORAGE RESTORE 33.00</body></html>'
dest = 'review-restore-'+uuid.uuid4().hex[:12]
request('object/receipts/'+path, 'POST', body, 'text/html')
request('bucket', 'POST', json.dumps({'id':dest,'name':dest,'public':False}).encode())
try:
    with tempfile.TemporaryDirectory(prefix='review-storage-', dir='/tmp/getnf-review-round2') as tmp:
        # Run the real production mirror, with explicit disposable endpoint and credentials.
        fetched, count = b.mirror(env, tmp)
        saved = Path(tmp, path).read_bytes()
        assert saved == body
        entries = json.loads(Path(tmp, '.index.json').read_text())
        assert path in entries
        checked = 0
        for name in entries:
            local = Path(tmp, name).read_bytes()
            escaped = b.urllib.parse.quote(name)
            request('object/'+dest+'/'+escaped, 'POST', local, 'application/octet-stream')
            restored = request('object/'+dest+'/'+escaped, 'GET', None)
            assert hashlib.sha256(restored).digest() == hashlib.sha256(local).digest()
            checked += 1
        assert checked == count
        fetched_again, total_again = b.mirror(env, tmp)
        assert fetched_again == 0 and total_again == count
        print('PASS real mirror and separate-bucket restore: objects=', checked)
        print('PASS byte hashes match for every mirrored object')
        print('PASS unchanged second mirror downloads zero objects')
        print('LIMIT: explicit test restore utility; no production disaster recovery orchestration, auth refresh tokens or point-in-time snapshot tested')
finally:
    request('object/receipts', 'DELETE', json.dumps({'prefixes':[path]}).encode())
    request('bucket/'+dest+'/empty', 'POST', b'{}')
    request('bucket/'+dest, 'DELETE', None)
    print('PASS cleanup: temporary destination bucket and source fixture removed')
