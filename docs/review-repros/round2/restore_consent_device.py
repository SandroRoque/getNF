"""Undo ONLY the synthetic device setup; delete its synthetic remote contribution first."""
import pathlib,subprocess,shlex,xml.etree.ElementTree as E,urllib.request
adb=['/home/roque/Android/Sdk/platform-tools/adb','-s','emulator-5554'];pkg='br.com.roqlab.caderneta.debug';root=pathlib.Path('/tmp/getnf-review-round2/consent-device')
xml=subprocess.check_output(adb+['exec-out','run-as',pkg,'cat','shared_prefs/feedback.xml'])
install=next(n.text for n in E.fromstring(xml) if n.get('name')=='install')
with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:55328/?install='+install+'&withdraw=1',data=b'',method='POST')) as r:assert r.status==200
subprocess.run(adb+['shell','am','force-stop',pkg],check=True)
for name in ('supabase','test-contribution'):
 data=(root/(name+'.xml')).read_bytes();assert data.startswith(b'<?xml')
 cmd=shlex.join(['run-as',pkg,'sh','-c','cat > shared_prefs/'+name+'.xml'])
 subprocess.run(adb+['shell',cmd],input=data,check=True)
print('Synthetic remote contribution removed; original project/account and consent preferences restored on emulator-5554')
