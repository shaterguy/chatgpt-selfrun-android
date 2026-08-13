from pathlib import Path
p = Path('app/src/test/java/com/shaterguy/chatgptselfrun/DriveVariantPolicyTest.java')
s = p.read_text(encoding='utf-8')
s = s.replace('));\\n        assertFalse(', '));\n        assertFalse(')
p.write_text(s, encoding='utf-8')
