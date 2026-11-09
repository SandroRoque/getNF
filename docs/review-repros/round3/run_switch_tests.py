"""Runs Round3AccountSwitchTest methods one by one on emulator-5556 ONLY (explicit serial), app data cleared between."""
import json,subprocess,uuid,urllib.request,os,sys
SER='emulator-5556'; PKG='br.com.roqlab.caderneta.debug'
s=json.load(open('/tmp/getnf-review-round2/status.json')); API='http://127.0.0.1:55321'; assert s['API_URL']==API
def api(path,method='GET',body=None,token=None,admin=False):
    h={'apikey':s['SECRET_KEY'] if admin else s['PUBLISHABLE_KEY'],'Content-Type':'application/json'}
    if token:h['Authorization']='Bearer '+token
    r=urllib.request.urlopen(urllib.request.Request(API+path,json.dumps(body).encode() if body is not None else None,h,method=method),timeout=30)
    d=r.read(); return json.loads(d) if d else None
email=f'r3-switch-{uuid.uuid4()}@example.test'; pw=str(uuid.uuid4())
api('/auth/v1/admin/users','POST',{'email':email,'password':pw,'email_confirm':True},admin=True)
tok=api('/auth/v1/token?grant_type=password','POST',{'email':email,'password':pw})['access_token']
api('/rest/v1/rpc/create_ledger','POST',{'name':'Segunda caderneta sintética'},token=tok)
out=[]
for m in sys.argv[1:]:
    subprocess.run(['adb','-s',SER,'shell','pm','clear',PKG],capture_output=True)
    r=subprocess.run(['adb','-s',SER,'shell','am','instrument','-w','-e','class',f'io.github.sandroroque.getnf.sync.Round3AccountSwitchTest#{m}',
        '-e','proxy','http://10.0.2.2:55340','-e','key',s['PUBLISHABLE_KEY'],'-e','email',email,'-e','password',pw,f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=300)
    text=r.stdout.replace(s['PUBLISHABLE_KEY'],'<key>').replace(pw,'<pw>').replace(email,'<email>')
    out.append('=== '+m+'\n'+text)
    print('=== '+m); print('\n'.join(l for l in text.splitlines() if l.strip() and 'INSTRUMENTATION_STATUS: stack' not in l)[:1800])
open(os.path.join(os.path.dirname(os.path.abspath(__file__)),'switch-tests.txt'),'a').write('\n'.join(out)+'\n')
