"""Check publishable files without ever printing credential values."""
import ast
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
out = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT)
files = [ROOT/p.decode('utf-8') for p in out.split(b'\0') if p]
problems = []
local = ROOT/'fzu_checkin.py'
secrets = []
if local.exists():
    for n in ast.parse(local.read_text(encoding='utf-8-sig')).body:
        if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id in ('USERNAME', 'PASSWORD') for t in n.targets):
            value = ast.literal_eval(n.value)
            if isinstance(value, str) and len(value) >= 5:
                secrets.append(value.encode('utf-8'))
for path in files:
    relative = path.relative_to(ROOT).as_posix()
    if relative.startswith(('.tools/', '.signing/', 'deliverables/')) or path.suffix in ('.jks', '.keystore', '.apk', '.pfx', '.pem', '.key') or relative == 'fzu_checkin.py':
        problems.append(relative)
    elif path.is_file() and any(value in path.read_bytes() for value in secrets):
        problems.append(relative)
reference = ROOT/'reference/fzu_checkin.example.py'
if reference.exists():
    for n in ast.parse(reference.read_text(encoding='utf-8-sig')).body:
        if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id in ('USERNAME', 'PASSWORD') for t in n.targets):
            if ast.literal_eval(n.value) != '':
                problems.append('reference credentials must be empty')
if problems:
    raise SystemExit('Publish blocked; inspect these paths without printing secrets: '+', '.join(sorted(set(problems))))
print(f'PASS: {len(files)} publishable files checked; local credentials and signing material excluded.')
