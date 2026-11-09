"""Existing foreign destinations, valid FK dependencies; disposable localhost only."""
from pathlib import Path
exec(Path(__file__).with_name('http_crud.py').read_text().split('for role,u in zip(roles,users):')[0])
foreign=rpc('create_ledger',{'name':'Foreign synthetic'},users[3])
# Same dependency IDs in destination ensure failures cannot be explained by a missing FK.
for table,keys,b in [('category',['ledger_id','id'],{'ledger_id':lid,'id':basecat}),('product',['ledger_id','id'],{'ledger_id':lid,'id':baseprod}),('store_item',['ledger_id','id'],{'ledger_id':lid,'id':baseitem}),('receipt',['ledger_id','chave'],{'ledger_id':lid,'chave':basekey})]:
 _,found=rows(table,b,keys);put(table,{**found[0],'ledger_id':foreign})
for role,u in zip(roles,users):
 for t in sorted(membertables|{'category','category_event'}):
  b,keys,patch=fixture(t,u)
  if t=='product_tag':
   _,found=rows('tag',{'ledger_id':lid,'id':b['tag_id']},['ledger_id','id']);put('tag',{**found[0],'ledger_id':foreign})
  seeded=put(t,b)
  code,_=req('/rest/v1/'+t+'?'+filt(seeded,keys),'PATCH',{'ledger_id':foreign},u)
  _,after=rows(t,seeded,keys)
  check(role+' '+t+' foreign ledger_id preserves source',len(after)==1)
  dest={**seeded,'ledger_id':foreign};_,there=rows(t,dest,keys)
  # category_event PK excludes ledger; inspect ledger explicitly rather than count globally.
  check(role+' '+t+' foreign ledger_id no destination',not any(x.get('ledger_id')==foreign for x in there))
  delete(t,seeded,keys)
# A second ledger owned by the same user proves cross-reference tests fail on scoping, not permissions.
other=rpc('create_ledger',{'name':'Second owned synthetic'},owner)
fcat=put('category',{'ledger_id':other,'label':'other'})['id']
fprod=put('product',{'ledger_id':other,'category_id':fcat})['id']
fitem=put('store_item',{'ledger_id':other,'code_kind':'store','code':U(),'description':'other','product_id':fprod})['id']
ftag=put('tag',{'ledger_id':other,'slug':U(),'description':'other'})['id']
fkey='9'*44;put('receipt',{'ledger_id':other,'chave':fkey,'captured_at':now})
fcnpj=U();put('issuer',{'ledger_id':other,'cnpj':fcnpj,'name':'other'})
for t,field,value in [('category','parent_id',fcat),('product','category_id',fcat),('product','suggestion_category_id',fcat),('store_item','product_id',fprod),('product_tag','product_id',fprod),('product_tag','tag_id',ftag),('receipt','issuer_cnpj',fcnpj),('receipt_photo','chave',fkey),('receipt_line','chave',fkey),('receipt_line','store_item_id',fitem),('receipt_line','product_override_id',fprod),('receipt_scan','chave',fkey),('link_suggestion','store_item_id',fitem),('link_suggestion','product_id',fprod)]:
 b,keys,_=fixture(t,owner); seeded=put(t,b)
 code,_=req('/rest/v1/'+t+'?'+filt(seeded,keys),'PATCH',{field:value},owner)
 _,after=rows(t,seeded,keys)
 check(t+' cross-reference '+field,code==409 and len(after)==1 and after[0].get(field)!=value)
 delete(t,seeded,keys)
print('TOTAL',len(checks),'FAILED',sum(not ok for _,ok in checks))
raise SystemExit(any(not ok for _,ok in checks))
