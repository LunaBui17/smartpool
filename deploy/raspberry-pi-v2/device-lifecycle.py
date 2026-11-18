#!/usr/bin/env python3
"""Root-only host agent: revoke one confirmed-reset account, then finalize DB deletion."""
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parent
COMPOSE = ['docker', 'compose', '--ansi', 'never', '-f', str(ROOT/'compose.yaml'), '-f', str(ROOT/'compose.cloudflare.yaml')]

def invoke(args):
    return subprocess.run(args, stdin=subprocess.DEVNULL, capture_output=True, text=True, check=True, timeout=120)

def main():
    if os.geteuid() != 0:
        raise RuntimeError('Run lifecycle agent as root')
    with open('/run/smartpool-device-lifecycle.lock', 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        while True:
            try:
                reply=invoke(COMPOSE+['exec','-T','api','php','/opt/smartpool/device_lifecycle.php','--next'])
                job=json.loads(reply.stdout)['job']
                if job:
                    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}',job['device_id']):
                        raise ValueError('Invalid device id')
                    if not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}',job['job_id']):
                        raise ValueError('Invalid job id')
                    if not re.fullmatch(r'[A-F0-9]{2}(?::[A-F0-9]{2}){5}',job['factory_mac']):
                        raise ValueError('Invalid MAC')
                    invoke(['python3',str(ROOT/'mqtt-admin.py'),'revoke-device',job['device_id'],'--reload-only'])
                    invoke(COMPOSE+['exec','-T','control-worker','python','/worker/worker.py','--kick-reset-client',job['factory_mac']])
                    invoke(COMPOSE+['exec','-T','api','php','/opt/smartpool/device_lifecycle.php','--finalize',job['job_id']])
                    print('Device lifecycle completed: '+job['job_id'],flush=True)
            except (subprocess.SubprocessError, OSError, ValueError, KeyError):
                # Do not print subprocess output: tools may produce credential diagnostics.
                print('Lifecycle retry pending; inspect service availability.',flush=True)
            time.sleep(10)

if __name__=='__main__':
    main()
