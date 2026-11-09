"""Original operational helpers with synthetic inputs; never invoke their CLI main or load .env."""
import importlib.util, tempfile, unittest, json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
def module(name, file):
 s=importlib.util.spec_from_file_location(name,ROOT/'tools'/file); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); return m
backup=module('backup','supabase-backup.py');play=module('play','play.py');release=module('release','release.py')
class Scripts(unittest.TestCase):
 def test_storage_pagination_and_folders(self):
  def storage(_e,_path,body):
   if body['prefix']=='nested':return json.dumps([{'name':'synthetic','id':'x','metadata':{'size':2},'updated_at':'2026-01-01'}]).encode()
   if body['offset']==0:return json.dumps([{'name':'nested','id':None}]+[{'name':str(i),'id':'x'} for i in range(999)]).encode()
   return json.dumps([{'name':'last','id':'x'}]).encode()
  backup.storage=storage
  rows=list(backup.objects({}));self.assertEqual(1001,len(rows));self.assertEqual('nested/synthetic',rows[0][0]);self.assertEqual('last',rows[-1][0])
 def test_dump_password_not_in_command(self):
  e={'SUPABASE_URL':'https://synthetic.supabase.co','SUPABASE_DB_PASSWORD':'synthetic-secret'}
  self.assertNotIn(e['SUPABASE_DB_PASSWORD'],backup.db_url(e));self.assertEqual(e['SUPABASE_DB_PASSWORD'],backup.pg_env(e)['PGPASSWORD'])
 def test_release_parsing_and_bundle_identity(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);(root/'docs/releases').mkdir(parents=True);p=root/'docs/releases/99.md';digest='a'*64
   p.write_text('```release\ncode: 99\nsha: synthetic\naab: sha256:'+digest+'\n```\n')
   fields,_=release.read_manifest(p);self.assertEqual('99',fields['code'])
   old=play.ROOT;play.ROOT=root
   try:
    self.assertEqual(99,play.code_for(digest))
    with self.assertRaises(SystemExit):play.code_for('b'*64)
   finally:play.ROOT=old
 def test_release_notes_limit(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'notes';p.write_text('x'*501)
   with self.assertRaises(SystemExit):play.notes(str(p))
if __name__=='__main__':unittest.main()
