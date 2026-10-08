# Fixture gate v1.0.1. Read-only GitHub metadata; never accepts artifacts from a failed run.
import json,re,subprocess,sys
REPO='shaterguy/chatgpt-selfrun-android'
def validate(run,commit,attempt):
 if run.get('head_sha')!=commit:raise RuntimeError('candidate commit differs')
 if run.get('run_attempt')!=attempt:raise RuntimeError('candidate run attempt differs')
 if run.get('status')!='completed' or run.get('conclusion')!='success':raise RuntimeError('exact candidate run is not completed successfully')
 return {k:run.get(k) for k in ['id','head_sha','run_attempt','status','conclusion','html_url']}
def validate_artifact(artifact,run_id,commit):
 linkage=artifact.get('workflow_run') or {}
 if linkage.get('id')!=run_id or linkage.get('head_sha')!=commit:raise RuntimeError('artifact is not bound to the exact candidate run and commit')
 if artifact.get('expired') is not False:raise RuntimeError('artifact is expired or unavailable')
 return {k:artifact.get(k) for k in ['id','name','expired','digest','workflow_run']}
if __name__=='__main__':
 if len(sys.argv)!=5 or not re.fullmatch('[0-9a-f]{40}',sys.argv[1]) or not all(x.isdigit() for x in sys.argv[2:]):raise SystemExit('expected commit run_id run_attempt artifact_id')
 commit,run_id,attempt,artifact_id=sys.argv[1:];p=subprocess.run(['gh','api','repos/'+REPO+'/actions/runs/'+run_id],capture_output=True,text=True,timeout=30)
 if p.returncode:raise SystemExit('read-only CI run lookup failed')
 run=validate(json.loads(p.stdout),commit,int(attempt))
 artifact=subprocess.run(['gh','api','repos/'+REPO+'/actions/artifacts/'+artifact_id],capture_output=True,text=True,timeout=30)
 if artifact.returncode:raise SystemExit('read-only artifact lookup failed')
 print(json.dumps({'run':run,'artifact':validate_artifact(json.loads(artifact.stdout),int(run_id),commit)},sort_keys=True))
