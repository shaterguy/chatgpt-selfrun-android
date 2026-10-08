#!/usr/bin/env python3
"""Fixture 1.0.2. Read-only artifact materialization on isolated CI runner only."""
import hashlib,json,os,re,subprocess,sys
from pathlib import Path
from importlib.util import spec_from_file_location,module_from_spec
ROOT=Path(__file__).resolve().parent
spec=spec_from_file_location('gate',ROOT/'verify-successful-run.py');gate=module_from_spec(spec);spec.loader.exec_module(gate)
REPO=gate.REPO;COMMIT='e8a4e06f66025f9a7808dc1cc452c7470e06950e';RUN='37793257908';ATTEMPT='1'
def api(path):
 p=subprocess.run(['gh','api','repos/'+REPO+'/'+path],capture_output=True,text=True,timeout=60)
 if p.returncode:raise RuntimeError('GitHub read failed: '+path)
 return json.loads(p.stdout)
def download(run,name,dest):
 dest.mkdir(parents=True,exist_ok=False)
 p=subprocess.run(['gh','run','download',str(run),'--repo',REPO,'--name',name,'--dir',str(dest)],capture_output=True,text=True,timeout=180)
 if p.returncode:raise RuntimeError('GitHub artifact download failed; no signed URL printed')
def unique(root,name):
 rows=list(root.rglob(name));assert len(rows)==1,'artifact file identity not unique: '+name
 return rows[0]
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def check_artifact(commit,run,attempt,artifact):
 gate.validate(api('actions/runs/'+str(run)),commit,int(attempt))
 gate.validate_artifact(artifact,int(run),commit)
def prepare(edge):
 assert os.environ.get('GITHUB_ACTIONS')=='true','CI runner only; do not execute on user devices'
 assert edge in ('phone-formal','tablet-formal','phone-test','tablet-test')
 candidate=api('actions/runs/'+RUN+'/artifacts?per_page=100')['artifacts']
 rows=[x for x in candidate if x['name']=='selfrun-v4-4010-dev3-'+RUN+'-'+ATTEMPT]
 assert len(rows)==1,'exact candidate artifact is missing or ambiguous';artifact=rows[0]
 check_artifact(COMMIT,RUN,ATTEMPT,artifact)
 directory=ROOT/'inputs'/edge;directory.mkdir(parents=True,exist_ok=False)
 download(RUN,artifact['name'],directory/'candidate')
 cand=unique(directory/'candidate','SelfRun-Drive-'+('TEST-' if edge.endswith('test') else '')+'v4.0.10-dev3.apk')
 source=unique(directory/'candidate','SOURCE_COMMIT.txt');assert source.read_text().strip()==COMMIT
 manifest=unique(directory/'candidate','TEST_SHA256SUMS.txt' if edge.endswith('test') else 'SHA256SUMS.txt')
 if edge.endswith('formal'):
  version='4.0.8' if edge.startswith('phone') else '4.0.0';name='chatgpt-selfrun-drive-v'+version+'.apk'
  base_dir=directory/'baseline';base_dir.mkdir()
  p=subprocess.run(['gh','release','download','drive-v'+version,'--repo',REPO,'--pattern',name,'--dir',str(base_dir)],capture_output=True,text=True,timeout=180)
  if p.returncode:raise RuntimeError('formal baseline download failed')
  baseline=base_dir/name;expected=('d9a186d97bab55a57123d46e33e2fb1376fbcacfabc734da4b9850af37cd6a15' if edge.startswith('phone') else '5cc904e37d601dbfc86a7116278d8e20d0e6d28f13136f695ff0a99f598a0eab')
 else:
  device=edge.split('-')[0];expected=os.environ.get(device.upper()+'_TEST_INSTALLED_APK_SHA256','')
  assert re.fullmatch('[0-9a-f]{64}',expected),'verified actual installed TEST APK SHA is required; cannot substitute co-install proof'
  if device=='phone':bid=11423749914;brun=37489163965;bcommit='913973df386c2f7a941caf8e843d1c090407d3c4'
  else:bid=10877133826;brun=36166420867;bcommit='eab757281e61ac76fe4e46247ef68a264e2a15d1'
  ba=api('actions/artifacts/'+str(bid));check_artifact(bcommit,brun,'1',ba);download(brun,ba['name'],directory/'baseline')
  matches=[p for p in (directory/'baseline').rglob('*.apk') if digest(p)==expected]
  assert len(matches)==1,'baseline artifact contains no unique byte-exact installed TEST APK';baseline=matches[0]
 assert digest(baseline)==expected,'baseline SHA mismatch'
 data={'edge':edge,'base_apk':str(baseline),'candidate_apk':str(cand),'baseline_sha256':expected,'candidate_sha256':digest(cand),'source_commit':COMMIT,'run_id':RUN,'run_attempt':ATTEMPT,'artifact_id':str(artifact['id']),'source_commit_file':str(source),'candidate_sha256sums':str(manifest)}
 (directory/'inputs.json').write_text(json.dumps(data,indent=2)+'\n');return data
if __name__=='__main__':print(json.dumps(prepare(sys.argv[1])))
