#!/usr/bin/env python3
import subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
for name in ('dev3_rework_drive.py','dev3_rework_service.py','dev3_rework_tests.py'):
    subprocess.run(['python3', str(root/'tools'/name)], check=True)
print('dev3 verifier rework applied')
