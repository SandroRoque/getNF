"""Destructive only to synthetic preferences on disposable emulator-5556; never emulator-5554."""
from pathlib import Path
exec(Path(__file__).with_name('http_matrix.py').read_text().split('users=[newuser()')[0])
import subprocess,shlex,xml.etree.ElementTree as E
adb=['/home/roque/Android/Sdk/platform-tools/adb','-s','emulator-5556'];pkg='br.com.roqlab.caderneta.debug'
u=newuser(); lid=rpc('create_ledger',{'name':'Auth race synthetic'},u)
root=E.Element('map')
for k,v in {'url':'http://10.0.2.2:55330','key':S['PUBLISHABLE_KEY'],'user':u['user']['id'],'email':u['user']['email'],'refresh':u['refresh_token'],'ledger':lid,'legacy_ledger':lid}.items():E.SubElement(root,'string',{'name':k}).text=v
subprocess.run(adb+['shell','am','force-stop',pkg],check=True)
subprocess.run(adb+['shell',shlex.join(['run-as',pkg,'sh','-c','cat > shared_prefs/supabase.xml'])],input=E.tostring(root),check=True)
subprocess.run(adb+['shell','am','start','-n',pkg+'/io.github.sandroroque.getnf.MainActivity'],check=True,stdout=subprocess.DEVNULL)
print('Prepared disposable emulator-5556 synthetic account; loopback proxy only')
