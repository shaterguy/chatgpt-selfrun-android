import importlib.util,tempfile,unittest,json,os
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('prepare',Path(__file__).with_name('prepare-ci-edge.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class PrepareTests(unittest.TestCase):
 def test_non_ci_refused_without_network(self):
  with patch.dict(os.environ,{},clear=True),patch.object(m,'api') as api:
   with self.assertRaisesRegex(AssertionError,'CI runner only'):m.prepare('phone-formal')
   api.assert_not_called()
 def test_gate_uses_integer_ids(self):
  run={'id':123,'head_sha':m.COMMIT,'run_attempt':1,'status':'completed','conclusion':'success'};artifact={'expired':False,'workflow_run':{'id':123,'head_sha':m.COMMIT}}
  with patch.object(m,'api',return_value=run):m.check_artifact(m.COMMIT,123,'1',artifact)
 def test_failed_run_rejected(self):
  with patch.object(m,'api',return_value={'head_sha':m.COMMIT,'run_attempt':1,'status':'completed','conclusion':'failure'}):
   with self.assertRaisesRegex(RuntimeError,'not completed successfully'):m.check_artifact(m.COMMIT,123,'1',{})
 def test_wrong_artifact_run_rejected(self):
  run={'head_sha':m.COMMIT,'run_attempt':1,'status':'completed','conclusion':'success'}
  with patch.object(m,'api',return_value=run):
   with self.assertRaisesRegex(RuntimeError,'not bound'):m.check_artifact(m.COMMIT,123,'1',{'expired':False,'workflow_run':{'id':124,'head_sha':m.COMMIT}})
 def test_test_hash_must_match_downloaded_installed_bytes(self):
  with tempfile.TemporaryDirectory() as tmp,patch.object(m,'ROOT',Path(tmp)),patch.dict(os.environ,{'GITHUB_ACTIONS':'true','PHONE_TEST_INSTALLED_APK_SHA256':'0'*64}):
   candidate={'id':1,'name':'selfrun-v4-4010-dev3-'+m.RUN+'-1','expired':False,'workflow_run':{'id':int(m.RUN),'head_sha':m.COMMIT}}
   def api(path):return {'artifacts':[candidate]} if '/artifacts?' in path else {'id':11423749914,'name':'baseline','expired':False,'workflow_run':{'id':37489163965,'head_sha':'913973df386c2f7a941caf8e843d1c090407d3c4'}}
   def download(run,name,dest):
    dest.mkdir(parents=True)
    if name=='baseline':(dest/'old-test.apk').write_bytes(b'different installed bytes')
    else:
     (dest/'SelfRun-Drive-TEST-v4.0.10-dev3.apk').write_bytes(b'candidate');(dest/'SOURCE_COMMIT.txt').write_text(m.COMMIT);(dest/'TEST_SHA256SUMS.txt').write_text('placeholder')
   with patch.object(m,'api',side_effect=api),patch.object(m,'download',side_effect=download),patch.object(m,'check_artifact'):
    with self.assertRaisesRegex(AssertionError,'byte-exact installed TEST APK'):m.prepare('phone-test')
if __name__=='__main__':unittest.main(verbosity=2)
