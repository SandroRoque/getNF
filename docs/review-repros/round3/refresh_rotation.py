"""GoTrue refresh-token rotation on the DISPOSABLE stack: what a stale/parallel refresh does to the stored token."""
import time
from pathlib import Path
exec((Path(__file__).parent.parent/'round2'/'http_matrix.py').read_text().split('users=[newuser()')[0])
out=[]
def log(x): print(x); out.append(x)
def refresh(t):
    c,d=req('/auth/v1/token?grant_type=refresh_token','POST',{'refresh_token':t}); return c,(d if isinstance(d,dict) else {'err':str(d)[:120]})
u=newuser(); T1=u['refresh_token']
c1,r1=refresh(T1); T2=r1.get('refresh_token'); log(f'refresh(T1): {c1}')
c2,r2=refresh(T1); T2b=r2.get('refresh_token'); log(f'refresh(T1) again immediately (parallel caller within the reuse interval): {c2}; same child token as first: {T2b==T2}')
c3,r3=refresh(T2); T3=r3.get('refresh_token'); log(f'refresh(T2): {c3}  [T3 now newest]')
# the app wrote T3 last in one interleaving, T2 in the other. Stored-token = T2 (stale) after T3 exists, then wait out the reuse interval
log('waiting 12 s (past the default 10 s reuse interval)...'); time.sleep(12)
c4,r4=refresh(T2); log(f'refresh(stale T2) after the interval: {c4} {r4.get("err","") if c4!=200 else ""}')
c5,r5=refresh(T3); log(f'refresh(T3, the newest) afterwards: {c5} {r5.get("err","") if c5!=200 else ""}')
c6,r6=refresh(T3); T4=r6.get('refresh_token'); log(f'refresh(T3): {c6}  [T4 newest; T2 is now two generations behind]')
time.sleep(12)
c7,r7=refresh(T2); log(f'refresh(T2, two generations behind) after the interval: {c7} {r7.get("err","") if c7!=200 else ""}')
c8,r8=refresh(T4); log(f'refresh(T4, the newest) afterwards: {c8} {r8.get("err","") if c8!=200 else ""}  [{"family revoked" if c8!=200 else "family intact"}]')
open(Path(__file__).with_name('refresh-rotation.txt'),'w').write('\n'.join(out)+'\n')
