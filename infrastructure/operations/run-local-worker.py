#!/usr/bin/env python3
"""Run the local worker with only Redis/evaluator settings from the root .env."""
import os
from pathlib import Path
import re
import shlex

root = Path(__file__).resolve().parents[2]
jar = root / 'services/judge-worker/target/judge-worker-0.1.0-SNAPSHOT-exec.jar'
if not jar.is_file():
    raise SystemExit('Build the worker first: mvn -pl services/judge-worker -am package -DskipTests')
values = {}
for line in (root / '.env').read_text().splitlines():
    if '=' not in line or line.lstrip().startswith('#'):
        continue
    key, raw = line.split('=', 1)
    parts = shlex.split(raw, comments=True)
    values[key.strip()] = ' '.join(parts)
if values.get('WORKER_TRANSPORT_ENABLED', '').lower() != 'true':
    raise SystemExit('Enable WORKER_TRANSPORT_ENABLED in .env and recreate the API first.')
if not re.fullmatch(r'[A-Za-z0-9_-]{43,128}', values.get('JUDGE_WORKER_TOKEN', '')):
    raise SystemExit('Configure a valid JUDGE_WORKER_TOKEN in .env.')

# Keep Docker/Java discovery settings, but do not pass Portal, database or S3 secrets.
environment = {key: value for key, value in os.environ.items() if key in {
    'PATH', 'HOME', 'JAVA_HOME', 'LANG', 'LC_ALL', 'TMPDIR',
    'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG', 'DOCKER_CERT_PATH', 'DOCKER_TLS_VERIFY',
}}
environment.update({key: value for key, value in values.items() if
    key.startswith(('REDIS_', 'WORKER_')) or key in {'JUDGE_API_BASE_URL', 'JUDGE_WORKER_TOKEN'}})
workspace = root / '.local/worker-work'
workspace.mkdir(parents=True, exist_ok=True, mode=0o700)
print('Starting local Judge worker. Stop with Ctrl+C; credentials are not displayed.', flush=True)
os.chdir(root)
os.execvpe('java', ['java', f'-Djava.io.tmpdir={workspace}', '-jar', str(jar)], environment)
