import importlib.util,unittest,types,io,json
from unittest.mock import patch
from pathlib import Path
p=Path(__file__).resolve().parents[3]/'tools/llm-worker.py'
s=importlib.util.spec_from_file_location('worker',p);w=importlib.util.module_from_spec(s);s.loader.exec_module(w)
class Db:
 def __init__(self):self.writes=[]
 def write(self,*a,**k):self.writes.append((a,k))
class Tests(unittest.TestCase):
 def test_invalid_llm_answer_must_not_count_as_agreement(self):
  db=Db();u=types.SimpleNamespace(lid='synthetic',products={'p':{'id':'p','category_review':'automatic','category_source':'jev','category_id':'c'}},path={'c':'Food'},spent={'p':100},rules=[],category_list=lambda:'Food',words=lambda _:'SYNTHETIC',by_path=lambda _:None)
  result=w.audit(db,types.SimpleNamespace(ask=lambda *_:{}),u,1,0,'2026-01-01T00:00:00Z')
  agrees=[a[2]['agree'] for a,k in db.writes if a[1]=='audit_log']
  self.assertNotIn(True,agrees,'empty model response is not a validated agreement')
 def test_dry_run_never_calls_network_write(self):
  db=w.Supabase('http://127.0.0.1:1','synthetic',dry=True)
  db.call=lambda *_a,**_k:self.fail('network write')
  db.write('PATCH','product',{'name':'synthetic'})
 def test_timeout_propagates_without_writes(self):
  db=Db();u=types.SimpleNamespace(lid='synthetic',products={'p':{'id':'p','category_review':'automatic','category_source':'jev','category_id':'c'}},path={'c':'Food'},spent={'p':100},rules=[],category_list=lambda:'Food',words=lambda _:'SYNTHETIC',by_path=lambda _:'c')
  with patch.object(w.urllib.request,'urlopen',side_effect=TimeoutError('synthetic timeout')):
   with self.assertRaises(TimeoutError):w.audit(db,w.Llm('synthetic'),u,1,0,'2026-01-01T00:00:00Z')
  self.assertEqual([],db.writes)
 def test_response_parser_selects_last_json_without_network(self):
  payload={'choices':[{'message':{'content':'thinking {"old":true} answer {"categoria":"Food"}'}}]}
  with patch.object(w.urllib.request,'urlopen',return_value=io.BytesIO(json.dumps(payload).encode())):
   self.assertEqual({'categoria':'Food'},w.Llm('synthetic').ask('synthetic','synthetic'))
 def test_manual_category_not_sent_for_audit(self):
  db=Db();u=types.SimpleNamespace(lid='synthetic',products={'p':{'id':'p','category_review':'confirmed','category_source':'user','category_id':'c'}},path={'c':'Food'},spent={'p':100},rules=[],category_list=lambda:'Food')
  w.audit(db,types.SimpleNamespace(ask=lambda *_:self.fail('manual category sent')),u,1,0,'2026-01-01T00:00:00Z')
  self.assertEqual([],db.writes)
if __name__=='__main__':unittest.main()
