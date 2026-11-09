"""Two overlapping runs of tools/llm-worker.py's Llm context manager against a FAKE `lms` (no LM Studio, no network)."""
import importlib.util,os,stat,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('w',ROOT/'tools'/'llm-worker.py'); w=importlib.util.module_from_spec(spec); spec.loader.exec_module(w)
tmp=tempfile.mkdtemp(prefix='fake-lms-',dir='/tmp/getnf-review-round2'); state=Path(tmp,'loaded'); calls=Path(tmp,'calls')
fake=Path(tmp,'lms'); fake.write_text(f'''#!/bin/sh
echo "$@" >> {calls}
case "$1" in
 ps) [ -f {state} ] && echo "getnf-llm" ;;
 load) touch {state} ;;
 unload) rm -f {state} ;;
esac
''')
fake.chmod(fake.stat().st_mode|stat.S_IEXEC); w.LMS=str(fake); out=[]
first=w.Llm('k'); second=w.Llm('k')
first.__enter__(); out.append(f'run 1 starts: loaded here={first.loaded_here}, model loaded={state.exists()}')
second.__enter__(); out.append(f'run 2 starts while run 1 is working: loaded here={second.loaded_here}, model loaded={state.exists()}')
first.__exit__(None,None,None); out.append(f'run 1 finishes: model still loaded for run 2 = {state.exists()}')
second.__exit__(None,None,None)
out.append('lms calls: '+'; '.join(l.split()[0] for l in calls.read_text().splitlines()))
print('\n'.join(out)); Path(__file__).with_name('worker-model-lifecycle.txt').write_text('\n'.join(out)+'\n')
