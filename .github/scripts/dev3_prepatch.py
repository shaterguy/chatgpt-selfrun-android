from pathlib import Path
p=Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java')
s=p.read_text(encoding='utf-8')
old="catch(_){}}if(!persisted)return result('MARKER_FAILED','commit 표식 저장 실패');return result('READY_TO_SUBMIT','continuation 제출 준비 완료');}\\\""
new="catch(_){}}\\\"\n                + \\\"if(!persisted)return result('MARKER_FAILED','commit 표식 저장 실패');return result('READY_TO_SUBMIT','continuation 제출 준비 완료');}\\\""
if s.count(old)!=1:
    raise SystemExit(f'prepatch expected one continuation marker tail, found {s.count(old)}')
p.write_text(s.replace(old,new,1),encoding='utf-8')
