import json,urllib.request,xml.etree.ElementTree as E,subprocess
from pathlib import Path
root=Path('/tmp/getnf-review-round2/consent-device')
s=json.loads(Path('/tmp/getnf-review-round2/status.json').read_text());assert s['API_URL']=='http://127.0.0.1:55321'
adb=['/home/roque/Android/Sdk/platform-tools/adb','-s','emulator-5554']
feedback=subprocess.check_output(adb+['exec-out','run-as','br.com.roqlab.caderneta.debug','cat','shared_prefs/feedback.xml'])
install=next(n.text for n in E.fromstring(feedback) if n.get('name')=='install')
item=(root/'item.txt').read_text()
def get(path):
 req=urllib.request.Request(s['API_URL']+path,headers={'apikey':s['SECRET_KEY']})
 with urllib.request.urlopen(req) as response:return response.status,response.read()
_,rows=get('/rest/v1/contribution_file?select=name&install_id=eq.'+install)
rows=json.loads(rows)
print('Persisted contribution rows AFTER completed withdrawal:',len(rows),[r['name'] for r in rows])
code,data=get('/storage/v1/object/authenticated/contributions/'+install+'/'+item+'/page.html')
print('Storage object after withdrawal:',code,'synthetic bytes:',len(data))
adb=['/home/roque/Android/Sdk/platform-tools/adb','-s','emulator-5554']
prefs=E.fromstring(subprocess.check_output(adb+['exec-out','run-as','br.com.roqlab.caderneta.debug','cat','shared_prefs/test-contribution.xml']))
flags={n.get('name'):n.get('value') for n in prefs if n.tag=='boolean'}
print('Consent flags:',flags)
assert flags['enabled']=='false' and flags['withdraw']=='false'
assert len(rows)==0,'BUG: a real old upload persisted after withdrawal completed; no further withdrawal pending'
