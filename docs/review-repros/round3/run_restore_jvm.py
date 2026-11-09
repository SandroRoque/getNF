import json,os,subprocess
s=json.load(open('/tmp/getnf-review-round2/status.json')); assert s['API_URL']=='http://127.0.0.1:55321'
e={**os.environ,'GETNF_SUPABASE_LOCAL':s['PUBLISHABLE_KEY']+','+s['SECRET_KEY'],'GETNF_SUPABASE_URL':'http://127.0.0.1:55321'}
with open('../docs/review-repros/round3/withdrawal-jvm.txt','w') as log:
    r=subprocess.run(['./gradlew',':app:testDebugUnitTest','--tests','*Round3WithdrawalTest','--offline','-i'],env=e,stdout=log,stderr=subprocess.STDOUT)
raise SystemExit(r.returncode)
