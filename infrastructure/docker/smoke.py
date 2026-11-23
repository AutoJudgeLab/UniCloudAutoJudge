#!/usr/bin/env python3
"""Isolated Compose smoke test; removes only its own generated project and credentials."""
import base64
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.request
import urllib.error
import uuid

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / 'infrastructure/docker/compose.yml'
PROJECT = 'judge-smoke-' + secrets.token_hex(4)

def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

with tempfile.TemporaryDirectory(prefix='judge-smoke-') as temporary:
    tmp = Path(temporary)
    private = tmp / 'private.pem'
    subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(private)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    private_der = subprocess.check_output(['openssl', 'pkcs8', '-topk8', '-inform', 'PEM', '-outform', 'DER', '-nocrypt', '-in', str(private)])
    public_der = subprocess.check_output(['openssl', 'pkey', '-in', str(private), '-pubout', '-outform', 'DER'])
    password = 'Test9!' + secrets.token_hex(16)
    env = dict(POSTGRES_USER='judge', POSTGRES_DB='online_judge', POSTGRES_PASSWORD=secrets.token_hex(24),
        REDIS_PASSWORD=secrets.token_hex(24), MINIO_ROOT_USER='root' + secrets.token_hex(6), MINIO_ROOT_PASSWORD=secrets.token_hex(24),
        S3_ACCESS_KEY='api' + secrets.token_hex(6), S3_SECRET_KEY=secrets.token_hex(24), S3_BUCKET='judge-private',
        JWT_PRIVATE_KEY_BASE64=base64.b64encode(private_der).decode(), JWT_PUBLIC_KEY_BASE64=base64.b64encode(public_der).decode(),
        ADMIN_BOOTSTRAP_ENABLED='true', ADMIN_BOOTSTRAP_USERNAME='initialadmin', ADMIN_BOOTSTRAP_EMAIL='admin@example.test', ADMIN_BOOTSTRAP_PASSWORD=password,
        POSTGRES_PORT=str(port()), REDIS_PORT=str(port()), MINIO_API_PORT=str(port()), MINIO_CONSOLE_PORT=str(port()), SERVER_PORT=str(port()), METRICS_SNAPSHOT_INTERVAL='PT1S')
    envfile = tmp / '.env'; envfile.write_text(''.join(k + '=' + v + '\n' for k,v in env.items())); envfile.chmod(0o600)
    command = ['docker', 'compose', '--env-file', str(envfile), '-f', str(COMPOSE), '-p', PROJECT, '--profile', 'api']
    # Shell environment must not override this test's independently generated credentials/ports.
    process_env = {k:v for k,v in os.environ.items() if k not in env}
    def compose(*arguments, capture=False):
        return subprocess.run(command + list(arguments), env=process_env, check=True, text=True, capture_output=capture)
    base = 'http://127.0.0.1:' + env['SERVER_PORT']
    def request(method, path, token=None, payload=None, expected=200):
        headers = {'Content-Type':'application/json'}
        if token: headers['Authorization'] = 'Bearer ' + token
        body = json.dumps(payload).encode() if payload is not None else None
        try:
            response = urllib.request.urlopen(urllib.request.Request(base+path, data=body, headers=headers, method=method), timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        assert response.status == expected, 'Unexpected HTTP status: ' + str(response.status) + ' at ' + path
        data = response.read()
        return json.loads(data) if data else None
    def login(username):
        return request('POST','/api/auth/login',payload={'identifier':username,'password':password})['accessToken']
    def register(name):
        return request('POST','/api/auth/register',payload={'username':name,'email':name+'@example.test','password':password},expected=201)
    def sql(statement):
        return compose('exec','-T','postgres','psql','-U','judge','-d','online_judge','-Atc',statement,capture=True).stdout.strip()
    try:
        compose('config','--quiet')
        compose('up','-d','--wait','--wait-timeout','180')
        for path in ('/actuator/health','/actuator/health/liveness','/actuator/health/readiness','/actuator/health/dependencies'):
            assert request('GET',path)['status'] == 'UP'
        assert '/api/assignments' in request('GET','/v3/api-docs')['paths']
        register('studentone'); teacher = register('teacherone')
        admin = login('initialadmin')
        request('PATCH','/api/admin/users/'+teacher['id']+'/role',admin,{'role':'TEACHER'})
        teacher_token = login('teacherone'); student = login('studentone')
        import datetime
        deadline=(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=1)).isoformat()
        assignment=request('POST','/api/assignments',teacher_token,{'title':'Compose integration','description':'Instructions','maximumScore':100,'deadline':deadline},201)['id']
        request('POST','/api/assignments/'+assignment+'/test-cases',teacher_token,{'inputData':'hidden','expectedOutput':'hidden','points':100,'isHidden':True,'executionOrder':0},201)
        request('POST','/api/assignments/'+assignment+'/publish',teacher_token)
        boundary='judge'+uuid.uuid4().hex
        body=('--'+boundary+'\r\nContent-Disposition: form-data; name="programmingLanguage"\r\n\r\nJAVA\r\n--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="Main.java"\r\n\r\nclass Main {}\r\n--'+boundary+'--\r\n').encode()
        upload=urllib.request.Request(base+'/api/assignments/'+assignment+'/submissions',data=body,headers={'Authorization':'Bearer '+student,'Idempotency-Key':str(uuid.uuid4()),'Content-Type':'multipart/form-data; boundary='+boundary},method='POST')
        with urllib.request.urlopen(upload,timeout=30) as response:
            assert response.status == 201; submission=json.load(response)['submissionId']
        uuid.UUID(submission)
        key=sql("SELECT source_file_key FROM submissions WHERE id = '"+submission+"'")
        # Pass source key as a positional argument, never interpolate it into shell code.
        shell='mc --config-dir /tmp/check alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc --config-dir /tmp/check stat "local/$S3_BUCKET/$1" >/dev/null'
        compose('run','--rm','--no-deps','--entrypoint','/bin/sh','minio-init','-c',shell,'check',key,capture=True)
        until=time.monotonic()+20
        while sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = '"+submission+"'") != 't':
            assert time.monotonic()<until, 'Publisher did not deliver'; time.sleep(.2)
        stream=compose('exec','-T','redis','sh','-c','REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw XRANGE judge:submissions:v1 - +',capture=True).stdout
        assert submission in stream and 'SUBMISSION_CREATED' in stream
        assert request('GET','/api/submissions/'+submission,student)['status']=='PENDING'
        assert request('GET','/api/users/me/submissions',student)['totalElements']==1
        register('outsiderone'); outsider=login('outsiderone')
        request('GET','/api/submissions/'+submission,outsider,expected=404)
        request('GET','/api/admin/users',student,expected=403)
        request('GET','/api/users/me','invalid',expected=401)
        replay=(ROOT/'infrastructure/operations/replay-outbox.sql').read_text()
        replay_command=command+['exec','-T','postgres','psql','-U','judge','-d','online_judge','-v','since=1970-01-01T00:00:00Z','-v','before=2100-01-01T00:00:00Z','-v','batch_size=1']
        subprocess.run(replay_command,input=replay,env=process_env,text=True,capture_output=True,check=True)
        subprocess.run(replay_command+['-v','apply=true'],input=replay,env=process_env,text=True,capture_output=True,check=True)
        until=time.monotonic()+20
        while int(sql("SELECT attempt_count FROM outbox_events WHERE aggregate_id = '"+submission+"'")) < 2:
            assert time.monotonic()<until, 'Replay did not deliver'; time.sleep(.2)
        # A small measured smoke load; it is not a capacity benchmark.
        if os.environ.get('RUN_LOAD') == 'true':
            output=ROOT/'.local/stage10-load'; output.mkdir(parents=True,exist_ok=True)
            load_env=dict(os.environ,BASE_URL=base,ASSIGNMENT_ID=assignment,STUDENT_TOKEN=student,ADMIN_TOKEN=admin,VUS='2',ITERATIONS='4',SUMMARY_PATH=str(output/'summary.json'))
            container=compose('ps','-q','judge-api',capture=True).stdout.strip()
            with (output/'observations.jsonl').open('w') as observations:
                monitor=subprocess.Popen(['python3',str(ROOT/'infrastructure/load/observe.py'),'--duration','10','--interval','1','--container',container],env=load_env,stdout=observations)
                try:
                    with (output/'k6.log').open('w') as log:
                        subprocess.run(['k6','run',str(ROOT/'infrastructure/load/submissions.js')],env=load_env,stdout=log,stderr=log,check=True)
                finally: monitor.wait(timeout=30)
        print('Compose workflow passed: private source, PostgreSQL metadata, outbox, Redis delivery, PENDING state, history, authorization, health and OpenAPI.')
    finally:
        compose('down','-v','--remove-orphans')

