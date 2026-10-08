#!/usr/bin/env python3
# Fixture 1.0.2: execute only after prepare-ci-edge has pinned all artifact identities.
import json,os,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parent;edge=sys.argv[1];d=json.loads((root/'inputs'/edge/'inputs.json').read_text())
assert edge==d['edge'];env=dict(os.environ);env.update(SOURCE_COMMIT_FILE=d['source_commit_file'],CI_ARTIFACT_ID=d['artifact_id'],CANDIDATE_SHA256SUMS=d['candidate_sha256sums'])
subprocess.run(['bash',str(root/'run-isolated-upgrade.sh'),edge,d['base_apk'],d['candidate_apk'],str(root/'signed'/('fixture-test.apk' if edge.endswith('test') else 'fixture-formal.apk')),d['baseline_sha256'],d['candidate_sha256'],d['source_commit'],d['run_id'],d['run_attempt']],env=env,check=True)
