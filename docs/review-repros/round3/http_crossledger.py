"""Cross-ledger and privilege-crossing attacks. Disposable stack only; effects confirmed by service-role reads.
Actors: V owner(LA); M manager(LA); U member(LA)+owner(LB); X outsider."""
from pathlib import Path
exec((Path(__file__).parent.parent/'round2'/'http_matrix.py').read_text().split('users=[newuser()')[0])
from urllib.parse import quote
U_=lambda:str(uuid.uuid4())
V,M,Um,X=[newuser() for _ in range(4)]
LA=rpc('create_ledger',{'name':'Cross A'},V); LB=rpc('create_ledger',{'name':'Cross B'},Um)
for u,role in ((M,'manager'),(Um,'member')): rpc('join_ledger',{'code':rpc('create_invite',{'lid':LA,'role':role},V)},u)
uid=lambda u:u['user']['id']
def adm(path,method='GET',body=None):
    c,d=req('/rest/v1/'+path,method,body,admin=True,headers={'Prefer':'return=representation'}); return c,d
def put(t,b):
    c,d=adm(t,'POST',b); assert c==201,(t,c,d); return d[0]
def role_of(lid,u):
    c,d=adm(f'ledger_member?ledger_id=eq.{lid}&user_id=eq.{uid(u)}'); return d[0]['role'] if d else None
def members(lid): return sorted((r['user_id'],r['role']) for r in adm(f'ledger_member?ledger_id=eq.{lid}')[1])
def ledger(lid): return adm(f'ledger?id=eq.{lid}')[1]
def unchanged(label,before,after): check(label,before==after)
# --- RPC privilege crossing (state compared before/after)
snap=lambda: (members(LA),members(LB),sorted((r['id'],r['name']) for r in adm('ledger?id=in.(%s,%s)'%(LA,LB))[1]),len(adm('ledger_invite?ledger_id=eq.%s'%LA)[1]))
for who,u,calls in (
  ('outsider',X,[('rename_ledger',{'lid':LA,'name':'pwn'}),('set_role',{'lid':LA,'uid':uid(X),'role':'owner'}),('create_invite',{'lid':LA,'role':'member'}),
                ('remove_member',{'lid':LA,'uid':uid(V)}),('delete_ledger',{'lid':LA}),('leave_ledger',{'lid':LA}),('set_display_name',{'lid':LA,'name':'pwn'}),
                ('sync_taxonomy',{'lid':LA}),('claim_fetch',{'lid':LA,'chave':'1'*44}),('release_fetch',{'lid':LA,'chave':'1'*44})]),
  ('member',Um,[('rename_ledger',{'lid':LA,'name':'pwn'}),('set_role',{'lid':LA,'uid':uid(Um),'role':'owner'}),('set_role',{'lid':LA,'uid':uid(Um),'role':'manager'}),
                ('create_invite',{'lid':LA,'role':'member'}),('remove_member',{'lid':LA,'uid':uid(M)}),('delete_ledger',{'lid':LA})]),
  ('manager',M,[('set_role',{'lid':LA,'uid':uid(V),'role':'member'}),('set_role',{'lid':LA,'uid':uid(M),'role':'owner'}),('remove_member',{'lid':LA,'uid':uid(V)}),
                ('delete_ledger',{'lid':LA}),('rename_ledger',{'lid':LA,'name':'by manager'})]),
  ):
    for fn,args in calls:
        pre=snap(); cat_pre=len(adm('category?ledger_id=eq.%s'%LA)[1]); code,_=req('/rest/v1/rpc/'+fn,'POST',args,u); post=snap(); cat_post=len(adm('category?ledger_id=eq.%s'%LA)[1])
        print(f'   {who} {fn}: HTTP {code}, ledger name now {ledger(LA)[0]["name"] if ledger(LA) else None!r}') if pre!=post else None
        check(f'{who} {fn} {list(args)[1:]} leaves state unchanged' if True else '',(pre==post and cat_pre==cat_post) or (who=='manager' and fn=='rename_ledger'))
# --- moving rows between two valid ledgers by privilege
cat=put('category',{'ledger_id':LB,'label':'in B'})
c,_=req('/rest/v1/category?ledger_id=eq.%s&id=eq.%s'%(LB,cat['id']),'PATCH',{'ledger_id':LA},Um,headers={'Prefer':'return=representation'})
check('owner(LB)+member(LA) cannot move a category into LA (manager-only there)',adm('category?id=eq.%s&ledger_id=eq.%s'%(cat['id'],LA))[1]==[]); print('   HTTP',c)
rec=put('receipt',{'ledger_id':LA,'chave':'2'*44,'captured_at':'2026-01-01T00:00:00Z'})
c,_=req('/rest/v1/receipt?ledger_id=eq.%s&chave=eq.%s'%(LA,'2'*44),'PATCH',{'ledger_id':LB},Um,headers={'Prefer':'return=representation'})
moved=adm('receipt?chave=eq.%s&ledger_id=eq.%s'%('2'*44,LB))[1]!=[]
print('   INFO moving a receipt LA->LB by a member of both: HTTP',c,'moved' if moved else 'refused')
iss=put('issuer',{'ledger_id':LA,'cnpj':'moveme','name':'x'})
c,_=req('/rest/v1/issuer?ledger_id=eq.%s&cnpj=eq.moveme'%LA,'PATCH',{'ledger_id':LB},Um)
print('   INFO moving an issuer LA->LB by a member of both: HTTP',c,'moved' if adm('issuer?cnpj=eq.moveme&ledger_id=eq.%s'%LB)[1] else 'refused')
# --- cross-ledger references on insert (valid member of both ledgers)
bcat=put('category',{'ledger_id':LB,'label':'B ref'}); bprod=put('product',{'ledger_id':LB,'category_id':bcat['id']})
bitem=put('store_item',{'ledger_id':LB,'code_kind':'store','code':U_(),'description':'b','product_id':bprod['id']}); btag=put('tag',{'ledger_id':LB,'slug':U_(),'description':'t'})
bkey='3'*44; put('receipt',{'ledger_id':LB,'chave':bkey,'captured_at':'2026-01-01T00:00:00Z'})
acat=put('category',{'ledger_id':LA,'label':'A own'}); aprod=put('product',{'ledger_id':LA,'category_id':acat['id']})
akey='4'*44; put('receipt',{'ledger_id':LA,'chave':akey,'captured_at':'2026-01-01T00:00:00Z'})
tests=[('product -> category of B','product',{'ledger_id':LA,'name':'x','category_id':bcat['id']}),
       ('store_item -> product of B','store_item',{'ledger_id':LA,'code_kind':'store','code':U_(),'description':'x','product_id':bprod['id']}),
       ('product_tag -> tag of B','product_tag',{'ledger_id':LA,'product_id':aprod['id'],'tag_id':btag['id']}),
       ('receipt_line -> store_item of B','receipt_line',{'ledger_id':LA,'chave':akey,'line_no':1,'description':'x','code':'x','qty_e4':1,'unit_price_e4':1,'gross_cents':1,'store_item_id':bitem['id'],'discount_source':'html'}),
       ('receipt_line -> receipt of B','receipt_line',{'ledger_id':LA,'chave':bkey,'line_no':2,'description':'x','code':'x','qty_e4':1,'unit_price_e4':1,'gross_cents':1,'discount_source':'html'}),
       ('receipt_photo -> receipt of B','receipt_photo',{'ledger_id':LA,'chave':bkey,'local_name':U_(),'position':0}),
       ('receipt_scan -> receipt of B','receipt_scan',{'ledger_id':LA,'chave':bkey,'user_id':uid(Um),'scanned_at':'2026-01-01T00:00:00Z'}),
       ('link_suggestion -> store_item/product of B','link_suggestion',{'ledger_id':LA,'id':U_(),'store_item_id':bitem['id'],'product_id':bprod['id']})]
for label,t,body in tests:
    c,r=req('/rest/v1/'+t,'POST',body,Um); check(f'reference {label} refused (HTTP {c})',c in (400,403,409))
# --- storage cross-ledger: member of LA reads/writes under LB's prefix
for path,label in ((f'{LB}/x-{U_()}.txt','write into B prefix'),):
    c,_=req('/storage/v1/object/receipts/'+path,'POST',b'x',user=X,raw=True,headers={'Content-Type':'text/plain'}); check('outsider '+label+' refused',c in (400,401,403))
bobj=f'{LB}/b-{U_()}.txt'; req('/storage/v1/object/receipts/'+bobj,'POST',b'x',user=Um,raw=True,headers={'Content-Type':'text/plain'})
c,_=req('/storage/v1/object/receipts/'+bobj,'GET',user=M,raw=True); check('manager of A (not in B) cannot read B object',c in (400,401,403,404))
c,_=req('/storage/v1/object/receipts/'+bobj,'GET',user=Um,raw=True); check('owner of B can read its object',c==200)
req('/storage/v1/object/receipts','DELETE',{'prefixes':[bobj]},admin=True)
# a receipt WITH children moved between two ledgers the actor belongs to
ck='5'*44; put('receipt',{'ledger_id':LA,'chave':ck,'captured_at':'2026-01-01T00:00:00Z','page_path':f'{LA}/{ck}/page.html'})
put('receipt_photo',{'ledger_id':LA,'chave':ck,'local_name':'p0','position':0,'object_path':f'{LA}/{ck}/p0.webp'})
c,r=req('/rest/v1/receipt?ledger_id=eq.%s&chave=eq.%s'%(LA,ck),'PATCH',{'ledger_id':LB},Um,headers={'Prefer':'return=representation'})
inB=adm('receipt?ledger_id=eq.%s&chave=eq.%s'%(LB,ck))[1]; ph=adm('receipt_photo?chave=eq.%s'%ck)[1]
print('   INFO move of a receipt with a photo LA->LB: HTTP',c,'| receipt in B:',bool(inB),'| photo rows ledger:',[x['ledger_id']==LB and 'B' or 'A' for x in ph],'| page_path still under LA prefix:',bool(inB) and inB[0]['page_path'].startswith(LA))
print('TOTAL',len(checks),'FAILED',sum(not ok for _,ok in checks))
