from pathlib import Path

path = Path('tools/verify_selfrun3_dev4_limits_settings.sh')
text = path.read_text(encoding='utf-8')
replacements = {
    'grep -Fq "selfRunDriveVersionCode = 3025004" "$BUILD"': 'grep -Fq "selfRunDriveVersionCode = 3025005" "$BUILD"',
    'grep -Fq "selfRunDriveVersionName = \'3.2.5-dev4\'" "$BUILD"': 'grep -Fq "selfRunDriveVersionName = \'3.2.5-dev5\'" "$BUILD"',
}
for old, new in replacements.items():
    if text.count(old) != 1:
        raise SystemExit(f'expected exactly one match for {old!r}, found {text.count(old)}')
    text = text.replace(old, new, 1)
path.write_text(text, encoding='utf-8')
print('dev5 limits/settings verifier identity updated')
