"""Synthetic state setup on emulator-5554 only. Saves preferences in /tmp for restoration, never prints them."""
import subprocess, pathlib, uuid, json, shlex
adb=['/home/roque/Android/Sdk/platform-tools/adb','-s','emulator-5554'];pkg='br.com.roqlab.caderneta.debug'
root=pathlib.Path('/tmp/getnf-review-round2/consent-device');root.mkdir(exist_ok=True)
def run(*args,input=None):return subprocess.run(adb+list(args),input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout
run('shell','am','force-stop',pkg)
for name in ('supabase','test-contribution'):
 p=root/(name+'.xml')
 if not p.exists():p.write_bytes(run('exec-out','run-as',pkg,'cat','shared_prefs/'+name+'.xml'))
def put(path,data):run('shell',shlex.join(['run-as',pkg,'sh','-c','cat > '+shlex.quote(path)]),input=data)
put('shared_prefs/supabase.xml',b'<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><string name="url">http://10.0.2.2:55330</string><string name="key">synthetic</string></map>')
put('shared_prefs/test-contribution.xml',b'<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="enabled" value="true"/><boolean name="withdraw" value="false"/></map>')
item=str(uuid.uuid4());directory='files/contributions/'+item
run('shell','run-as',pkg,'mkdir','-p',directory)
put(directory+'/page.html',b'<p>Only synthetic review data</p>')
put(directory+'/meta.json',json.dumps({'kind':'pagina','about':'Synthetic review','writtenAt':'2026-11-05T00:00:00Z','url':'https://example.invalid'}).encode())
(root/'item.txt').write_text(item)
(root/'feedback.xml').write_bytes(run('exec-out','run-as',pkg,'cat','shared_prefs/feedback.xml'))
run('shell','am','start','-n',pkg+'/io.github.sandroroque.getnf.MainActivity')
print('Prepared synthetic queue on emulator-5554; preferences saved privately in /tmp')
