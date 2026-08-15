#!/usr/bin/env python3
from pathlib import Path

path = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunDriveDev3PolicyTest.java')
text = path.read_text()
old = 'assertTrue(g.contains("driveSignalCursor()-1"));assertTrue(g.contains("Integer.MAX_VALUE"));'
new = 'assertTrue(g.contains("int cursor=driveSignalCursor()"));assertTrue(g.contains("cursor-1"));assertTrue(g.contains("Integer.MAX_VALUE"));assertTrue(g.contains("driveRebaselineAuthorized"));'
assert old in text
path.write_text(text.replace(old, new, 1))
print('cursor rebase regression harness aligned')
