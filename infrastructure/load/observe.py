#!/usr/bin/env python3
"""Collect safe metric samples; credentials are read only from environment variables."""
import argparse
import json
import os
import subprocess
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--duration', type=int, default=60)
parser.add_argument('--interval', type=float, default=5)
parser.add_argument('--container', help='Optional local API container ID for Docker CPU/memory snapshots')
args = parser.parse_args()
if args.duration < 1 or args.interval <= 0:
    parser.error('Duration and interval must be positive')
base = os.environ['BASE_URL'].rstrip('/')
token = os.environ['ADMIN_TOKEN']
allowed = ('judge_', 'process_cpu_usage', 'process_cpu_time_seconds', 'jvm_memory_used_bytes', 'jvm_memory_max_bytes')
end = time.monotonic() + args.duration
while time.monotonic() < end:
    sample = {'timestamp': time.time()}
    try:
        request = urllib.request.Request(base + '/actuator/prometheus', headers={'Authorization': 'Bearer ' + token})
        with urllib.request.urlopen(request, timeout=10) as response:
            lines = response.read().decode().splitlines()
        sample['metrics'] = [line for line in lines if line.startswith(allowed)]
    except Exception:
        sample['metrics_error'] = 'Metrics unavailable or unauthorized'
    if args.container:
        result = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', args.container], capture_output=True, text=True)
        sample['container'] = json.loads(result.stdout) if result.returncode == 0 and result.stdout.strip() else {'error': 'stats unavailable'}
    print(json.dumps(sample), flush=True)
    time.sleep(args.interval)

