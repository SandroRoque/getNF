"""Only the disposable local instance. Keys read from /tmp; no credential output."""
import json,urllib.request,urllib.error,uuid
S=json.load(open('/tmp/getnf-review-round2/status.json')); URL='http://127.0.0.1:55321'
assert S['API_URL']==URL
checks=[]
def req(path,method='GET',body=None,user=None,admin=False,raw=False,headers=None):
 h={'apikey':S['SECRET_KEY'] if admin else S['PUBLISHABLE_KEY'],'Content-Type':'application/json'}
 if user:h['Authorization']='Bearer '+user['access_token']
 if headers:h.update(headers)
 data=body if raw else json.dumps(body).encode() if body is not None else None
 try:
  with urllib.request.urlopen(urllib.request.Request(URL+path,data,h,method=method),timeout=20) as r:
   b=r.read();return r.status,(b if raw else json.loads(b) if b else None)
 except urllib.error.HTTPError as e:
  b=e.read();return e.code,b.decode()
def check(label,truth):
 checks.append((label,bool(truth)));print(('PASS ' if truth else 'FAIL ')+label)
def rpc(fn,args,user):return req('/rest/v1/rpc/'+fn,'POST',args,user)[1]
def newuser():
 email='audit-'+str(uuid.uuid4())+'@example.test';password=str(uuid.uuid4())
 code,_=req('/auth/v1/admin/users','POST',{'email':email,'password':password,'email_confirm':True},admin=True);assert code==200
 code,u=req('/auth/v1/token?grant_type=password','POST',{'email':email,'password':password});assert code==200
 return u
users=[newuser() for _ in range(5)];owner,manager,member,stranger,former=users
lid=rpc('create_ledger',{'name':'Auditoria sintética'},owner)
for u,role in [(manager,'manager'),(member,'member'),(former,'member')]:
 invite=rpc('create_invite',{'lid':lid,'role':role},owner)
 check('join '+role,rpc('join_ledger',{'code':invite},u)==lid)
check('former leaves',req('/rest/v1/rpc/leave_ledger','POST',{'lid':lid},former)[0]==200)
key='0'*44
check('owner insert receipt',req('/rest/v1/receipt','POST',{'ledger_id':lid,'chave':key,'captured_at':'2026-01-01T00:00:00Z'},owner)[0]==201)
for role,u in [('owner',owner),('manager',manager),('member',member),('stranger',stranger),('former',former)]:
 allowed=role in ('owner','manager','member')
 code,rows=req('/rest/v1/receipt?ledger_id=eq.'+lid,user=u)
 check(role+' read receipt',code==200 and (len(rows)==1 if allowed else rows==[]))
 code,_=req('/rest/v1/issuer','POST',{'ledger_id':lid,'cnpj':role,'name':'Sintético'},u)
 check(role+' insert issuer',code==201 if allowed else code==403)
 code,_=req('/rest/v1/receipt?ledger_id=eq.'+lid,'PATCH',{'qr_url':'https://example.invalid/'+role},u,headers={'Prefer':'return=representation'})
 check(role+' update receipt status',code==200)
 code,rows=req('/rest/v1/receipt?ledger_id=eq.'+lid,user=owner)
 check(role+' update effect',rows[0]['qr_url']=='https://example.invalid/'+role if allowed else rows[0]['qr_url']!='https://example.invalid/'+role)
 code,_=req('/rest/v1/category','POST',{'ledger_id':lid,'label':'Sintética '+role},u)
 check(role+' create category',code==201 if role in ('owner','manager') else code==403)
 path=lid+'/audit-'+role+'.html'
 code,_=req('/storage/v1/object/receipts/'+path,'POST',b'<p>synthetic</p>',u,raw=True,headers={'Content-Type':'text/html'})
 check(role+' storage upload',code==200 if allowed else code>=400)
known=lid+'/audit-owner.html'
for role,u in [('member',member),('stranger',stranger),('former',former)]:
 code,_=req('/storage/v1/object/authenticated/receipts/'+known,user=u,raw=True)
 check(role+' storage download',code==200 if role=='member' else code>=400)
check('member cannot promote self',req('/rest/v1/rpc/set_role','POST',{'lid':lid,'uid':member['user']['id'],'role':'owner'},member)[0]==403)
# Foreign caderneta reference is rejected by the composite FK.
other=rpc('create_ledger',{'name':'Outra'},stranger)
cid=str(uuid.uuid4());req('/rest/v1/category','POST',{'ledger_id':other,'id':cid,'label':'Privada'},stranger)
code,_=req('/rest/v1/product','POST',{'ledger_id':lid,'id':str(uuid.uuid4()),'category_id':cid},owner)
check('cross-ledger category FK',code==409)
for table in ['issuer','receipt','receipt_photo','category','tag','product','store_item','product_tag','receipt_line','link_suggestion','receipt_scan','category_event','worker_report','ledger_member','ledger_event']:
 code,rows=req('/rest/v1/'+table+'?ledger_id=eq.'+lid,user=stranger)
 check('stranger cannot read '+table,code==200 and rows==[])
for table in ['contribution_file','ticket','feedback','audit_log','category_rule','categorize_job']:
 code,rows=req('/rest/v1/'+table,user=stranger)
 check('private '+table,code in (401,403) or code==200 and rows==[])
# Overwrite and delete each independently owned object; check effect, not just status.
for role,u in [('owner',owner),('manager',manager),('member',member),('stranger',stranger),('former',former)]:
 allowed=role in ('owner','manager','member')
 path=lid+'/mutation-'+role+'.html'
 assert req('/storage/v1/object/receipts/'+path,'POST',b'original',owner,raw=True,headers={'Content-Type':'text/html'})[0]==200
 req('/storage/v1/object/receipts/'+path,'POST',b'replaced',u,raw=True,headers={'Content-Type':'text/html','x-upsert':'true'})
 code,body=req('/storage/v1/object/authenticated/receipts/'+path,user=owner,raw=True)
 check(role+' overwrite effect',code==200 and body==(b'replaced' if allowed else b'original'))
 req('/storage/v1/object/receipts','DELETE',{'prefixes':[path]},u)
 code,body=req('/storage/v1/object/authenticated/receipts/'+path,user=owner,raw=True)
 check(role+' delete object effect',code>=400 if allowed else code==200 and body==b'original')
 # Moving a valid issuer row into a foreign ledger must fail and preserve the original.
 source='move-'+role
 assert req('/rest/v1/issuer','POST',{'ledger_id':lid,'cnpj':source,'name':'Synthetic'},owner)[0]==201
 req('/rest/v1/issuer?ledger_id=eq.'+lid+'&cnpj=eq.'+source,'PATCH',{'ledger_id':other},u)
 code,rows=req('/rest/v1/issuer?ledger_id=eq.'+lid+'&cnpj=eq.'+source,user=owner)
 check(role+' ledger_id mutation preserves origin',code==200 and len(rows)==1)
 # Delete a row belonging to this ledger, check actual effect.
 req('/rest/v1/issuer?ledger_id=eq.'+lid+'&cnpj=eq.'+source,'DELETE',None,u)
 code,rows=req('/rest/v1/issuer?ledger_id=eq.'+lid+'&cnpj=eq.'+source,user=owner)
 check(role+' delete issuer effect',code==200 and len(rows)==(0 if allowed else 1))

# Enumerate snapshot of exposed schema from local privileged API for coverage, without data.
print('TOTAL',len(checks),'FAILED',sum(not x[1] for x in checks))
# Save synthetic account data only in temporary local file for further integration checks.
json.dump({'owner':owner,'lid':lid},open('/tmp/getnf-review-round2/http-fixture.json','w'))
raise SystemExit(any(not x[1] for x in checks))
