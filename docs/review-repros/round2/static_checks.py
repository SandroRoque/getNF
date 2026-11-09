import ast,subprocess,re,json
from pathlib import Path
root=Path(__file__).resolve().parents[3]
for p in sorted((root/'tools').glob('*.py')):ast.parse(p.read_text());print('PYTHON_SYNTAX_OK',p.relative_to(root))
for p in sorted((root/'tools').rglob('*.sh')):
 r=subprocess.run(['bash','-n',str(p)],capture_output=True);print('BASH_SYNTAX',r.returncode,p.relative_to(root))
patterns={'private_key':rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----','secret_key_literal':rb'sb_secret_[A-Za-z0-9_-]{20,}','aws_access':rb'AKIA[0-9A-Z]{16}'}
objects=subprocess.check_output(['git','rev-list','--objects','HEAD'],cwd=root).splitlines()
proc=subprocess.Popen(['git','cat-file','--batch'],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE)
scanned=0;hits=[]
for row in objects:
 sha,_,path=row.partition(b' ');proc.stdin.write(sha+b'\n');proc.stdin.flush();header=proc.stdout.readline().split()
 if len(header)!=3:continue
 size=int(header[2]);body=proc.stdout.read(size);proc.stdout.read(1)
 if header[1]!=b'blob':continue
 scanned+=1
 for kind,pat in patterns.items():
  if re.search(pat,body):hits.append({'type':kind,'blob':sha.decode(),'path':path.decode(errors='replace')})
proc.stdin.close();proc.wait()
print('HISTORY_BLOBS_SCANNED',scanned)
for h in hits:print('SECRET_CANDIDATE',json.dumps(h))
print('SECRET_CANDIDATES',len(hits))
