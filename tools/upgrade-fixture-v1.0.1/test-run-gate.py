import importlib.util,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('gate',Path(__file__).with_name('verify-successful-run.py'));g=importlib.util.module_from_spec(s);s.loader.exec_module(g)
class Gate(unittest.TestCase):
 def base(self):return {'id':123,'head_sha':'a'*40,'run_attempt':2,'status':'completed','conclusion':'success'}
 def test_exact_success_allowed(self):self.assertEqual(g.validate(self.base(),'a'*40,2)['id'],123)
 def test_failed_run_artifact_rejected(self):
  r=self.base();r['conclusion']='failure'
  with self.assertRaisesRegex(RuntimeError,'successfully'):g.validate(r,'a'*40,2)
 def test_running_artifact_rejected(self):
  r=self.base();r['status']='in_progress'
  with self.assertRaisesRegex(RuntimeError,'successfully'):g.validate(r,'a'*40,2)
 def test_wrong_commit_rejected(self):
  with self.assertRaisesRegex(RuntimeError,'commit'):g.validate(self.base(),'b'*40,2)
 def test_wrong_attempt_rejected(self):
  with self.assertRaisesRegex(RuntimeError,'attempt'):g.validate(self.base(),'a'*40,1)
class ArtifactGate(unittest.TestCase):
 def base(self):return {'id':456,'name':'signed','expired':False,'workflow_run':{'id':123,'head_sha':'a'*40}}
 def test_exact_artifact_allowed(self):self.assertEqual(g.validate_artifact(self.base(),123,'a'*40)['id'],456)
 def test_other_run_rejected(self):
  with self.assertRaisesRegex(RuntimeError,'bound'):g.validate_artifact(self.base(),124,'a'*40)
 def test_other_commit_rejected(self):
  with self.assertRaisesRegex(RuntimeError,'bound'):g.validate_artifact(self.base(),123,'b'*40)
 def test_expired_rejected(self):
  r=self.base();r['expired']=True
  with self.assertRaisesRegex(RuntimeError,'expired'):g.validate_artifact(r,123,'a'*40)
if __name__=='__main__':unittest.main(verbosity=2)
