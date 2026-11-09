"""27 tables × 5 roles × CRUD. Local disposable only; no credentials printed.
Each denied insert is subsequently seeded with identical payload by service role, proving validity.
Mutations are checked with service-role reads, not inferred from HTTP success.
"""
from pathlib import Path
exec(Path(__file__).with_name('http_matrix.py').read_text().split('users=[newuser()')[0])
from urllib.parse import urlencode
U=lambda:str(uuid.uuid4())
roles=['owner','manager','member','stranger','former']
users=[newuser() for _ in roles]; owner=users[0]; extra=newuser()
lid=rpc('create_ledger',{'name':'CRUD synthetic'},owner)
for role,u in zip(roles[1:],users[1:]):
 if role=='stranger':continue
 rpc('join_ledger',{'code':rpc('create_invite',{'lid':lid,'role':'manager' if role=='manager' else 'member'},owner)},u)
rpc('leave_ledger',{'lid':lid},users[4])

def put(t,b):
 code,data=req('/rest/v1/'+t,'POST',b,admin=True,headers={'Prefer':'return=representation'})
 assert code==201,(t,code,data)
 return data[0]
def filt(b,keys):return urlencode({k:'eq.'+str(b[k]) for k in keys},safe='.')
def rows(t,b,keys,user=None):return req('/rest/v1/'+t+'?'+filt(b,keys),user=user,admin=user is None)
def delete(t,b,keys):return req('/rest/v1/'+t+'?'+filt(b,keys),'DELETE',admin=True)
# Dependencies are separate from tested rows, so deletes are not accidentally blocked by FK references.
basecat=put('category',{'ledger_id':lid,'label':'dependency'})['id']
baseprod=put('product',{'ledger_id':lid,'category_id':basecat})['id']
baseitem=put('store_item',{'ledger_id':lid,'code_kind':'store','code':U(),'description':'synthetic','product_id':baseprod})['id']
basekey='1'*44
put('receipt',{'ledger_id':lid,'chave':basekey,'captured_at':'2026-01-01T00:00:00Z'})
# Suppress processing: this source item has a category; no online categorization is called.
base={'ledger_id':lid}; now='2026-01-01T00:00:00Z'
def fixture(t,u):
 v=U(); b={**base}; keys=['ledger_id','id']; patch={}
 specs={
 'issuer':({'cnpj':v,'name':'synthetic'},['ledger_id','cnpj'],{'name':'changed'}),
 'category':({'id':v,'label':'synthetic'},keys,{'label':'changed'}),
 'tag':({'id':v,'slug':v,'description':'synthetic'},keys,{'description':'changed'}),
 'product':({'id':v,'name':'synthetic','category_id':basecat},keys,{'name':'changed'}),
 'store_item':({'id':v,'code_kind':'store','code':v,'description':'synthetic','product_id':baseprod},keys,{'description':'changed'}),
 'receipt':({'chave':str(uuid.uuid4().int).zfill(44),'captured_at':now},['ledger_id','chave'],{'qr_url':'https://example.invalid/changed'}),
 'receipt_photo':({'chave':basekey,'local_name':v,'position':0},['ledger_id','chave','local_name'],{'position':7}),
 'receipt_line':({'chave':basekey,'line_no':int(uuid.uuid4().int%2000000000),'description':'synthetic','code':'x','qty_e4':10000,'unit_price_e4':10000,'gross_cents':100,'store_item_id':baseitem,'discount_source':'html'},['ledger_id','chave','line_no'],{'description':'changed'}),
 'receipt_scan':({'chave':basekey,'user_id':extra['user']['id'],'scanned_at':now},['ledger_id','chave','user_id'],{'scanned_at':'2026-02-01T00:00:00Z'}),
 'product_tag':({'product_id':baseprod,'tag_id':put('tag',{'ledger_id':lid,'slug':U(),'description':'dependency'})['id']},['ledger_id','product_id','tag_id'],{'deleted':True}),
 'link_suggestion':({'id':v,'store_item_id':baseitem,'product_id':baseprod},keys,{'reason':'changed'}),
 'category_event':({'id':v,'category_id':basecat,'op':'create','at':now,'actor':u['user']['id']},['id'],{'new_label':'changed'}),
 'worker_report':({'report':{'synthetic':True}},['id'],{'report':{'changed':True}}),
 'audit_log':({'product_id':baseprod,'agree':True,'model':'synthetic'},['id'],{'model':'changed'}),
 'category_rule':({'text':'synthetic','model':'synthetic'},['id'],{'text':'changed'}),
 'categorize_job':({'store_item_id':baseitem,'status':'done'},['ledger_id','store_item_id'],{'last_error':'changed'}),
 'ledger_member':({'user_id':extra['user']['id'],'role':'member'},['ledger_id','user_id'],{'display_name':'changed'}),
 'ledger_invite':({'code':v,'role':'member'},['code'],{'role':'manager'}),
 'ledger_event':({'op':'synthetic'},['id'],{'op':'changed'}),
 }
 if t in specs:
  fields,keys,patch=specs[t];return {**b,**fields},keys,patch
 specs={
 'ledger':({'id':v,'name':'synthetic'},['id'],{'name':'changed'}),
 'taxonomy_node':({'path':v,'label':'synthetic','sort':0},['path'],{'label':'changed'}),
 'taxonomy_tag':({'slug':v,'description':'synthetic'},['slug'],{'description':'changed'}),
 'lexicon':({'key':v,'pt':'synthetic','en':'synthetic'},['key'],{'en':'changed'}),
 'barcode_lookup':({'gtin':v,'product':{'synthetic':True}},['gtin'],{'product':{'changed':True}}),
 'contribution_file':({'install_id':v,'item_id':U(),'name':'meta.json','bytes':2},['install_id','item_id','name'],{'bytes':3}),
 'feedback':({'kind':'comentario','body':'synthetic','about':'audit','install_id':v},['id'],{'body':'changed'}),
 'ticket':({'title':'synthetic','kind':'bug','area':'outro'},['id'],{'title':'changed'}),
 }
 return specs[t]
membertables={'issuer','receipt','receipt_photo','tag','product','store_item','product_tag','receipt_line','link_suggestion','receipt_scan'}
readonly={'ledger','ledger_member','ledger_event','worker_report'}
private={'audit_log','barcode_lookup','categorize_job','category_rule','contribution_file','feedback','lexicon','ticket'}
tables=sorted(membertables|readonly|private|{'category','category_event','ledger_invite','taxonomy_node','taxonomy_tag'})
assert len(tables)==27
for role,u in zip(roles,users):
 for t in tables:
  ismember=role in ('owner','manager','member'); managed=role in ('owner','manager')
  canread=(ismember and t in membertables|readonly|{'category','category_event'}) or (managed and t=='ledger_invite') or t in ('taxonomy_node','taxonomy_tag')
  canwrite=(ismember and t in membertables) or (managed and t=='category')
  caninsert=canwrite or (managed and t=='category_event') or t=='feedback'
  b,keys,patch=fixture(t,u)
  # Fixture for singleton dependencies reused after cleanup.
  if t=='categorize_job':delete(t,b,keys)
  code,_=req('/rest/v1/'+t,'POST',b,u)
  check(role+' '+t+' INSERT',code==201 if caninsert else code==403)
  if code==201:
   # Identity ids are absent from submitted payload: find only this synthetic body.
   if 'id' in keys and 'id' not in b:
    _,found=req('/rest/v1/'+t+'?'+filt(b,[k for k in b if k in ('install_id','ledger_id')]),admin=True)
    b={**b,'id':max(r['id'] for r in found)}
   delete(t,b,keys)
  seeded=put(t,{k:v for k,v in b.items() if not(k=='id' and isinstance(v,int))})
  # Ledger access requires actual membership, added only by admin.
  if t=='ledger':put('ledger_member',{'ledger_id':seeded['id'],'user_id':owner['user']['id'],'role':'owner'})
  if t=='ledger':
   for ru,rr in zip(users[1:3],['manager','member']):put('ledger_member',{'ledger_id':seeded['id'],'user_id':ru['user']['id'],'role':rr})
  status,visible=rows(t,seeded,keys,u)
  check(role+' '+t+' SELECT',(status==200 and len(visible)==1) if canread else (status==403 or status==200 and visible==[]))
  status,_=req('/rest/v1/'+t+'?'+filt(seeded,keys),'PATCH',patch,u)
  _,after=rows(t,seeded,keys)
  actual=all((str(after[0].get(k))[:19]==v[:19] if k=='scanned_at' else after[0].get(k)==v) for k,v in patch.items())
  check(role+' '+t+' UPDATE effect',actual==canwrite)
  status,_=req('/rest/v1/'+t+'?'+filt(seeded,keys),'DELETE',user=u)
  _,after=rows(t,seeded,keys)
  candelete=canwrite
  check(role+' '+t+' DELETE effect',(len(after)==0)==candelete)
  delete(t,seeded,keys)
print('TOTAL',len(checks),'FAILED',sum(not ok for _,ok in checks))
raise SystemExit(any(not ok for _,ok in checks))
