#!/usr/bin/env python3
"""Register a v2 ESP and save its one-time HTTP key locally on the Pi."""
import argparse
import json
import os
from pathlib import Path
import pwd
import re
import subprocess
import tempfile
from urllib.error import HTTPError
from urllib.request import Request, urlopen

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('device_id')
parser.add_argument('student_full_name')
parser.add_argument('student_code')
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}', args.device_id):
    parser.error('Invalid device_id')
if not re.fullmatch(r'[A-Za-z0-9._-]{1,32}', args.student_code):
    parser.error('Invalid student_code')
if not args.student_full_name.strip() or len(args.student_full_name) > 128:
    parser.error('Invalid student_full_name')
full_name = re.sub(r'(?<=[a-z])(?=[A-Z])', ' ', args.student_full_name.strip())

env_file = root / '.env'
settings = dict(line.split('=', 1) for line in env_file.read_text().splitlines() if '=' in line) \
    if os.access(env_file, os.R_OK) else {'PI_BIND_IP': '192.168.60.87'}
url = f"http://{settings['PI_BIND_IP']}:{settings.get('HTTP_PORT', '80')}/pool_api/pool_api.php"
policy_file = root / '.env.mqtt-policy'
admin_password = policy_file.read_text().splitlines()[0] if os.access(policy_file, os.R_OK) else 'luanbui'
owner = pwd.getpwnam(os.environ.get('SUDO_USER', pwd.getpwuid(os.getuid()).pw_name))
key_dir = Path(owner.pw_dir) / 'smartpool-v2-device-keys'
key_file = key_dir / (args.device_id + '.txt')


def call(payload, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = Request(url, json.dumps(payload).encode(), headers=headers, method='POST')
    try:
        with urlopen(req, timeout=15) as response:
            result = json.load(response)
    except HTTPError as error:
        detail = json.load(error).get('message', '')
        raise RuntimeError(f"API {payload['action']} failed: HTTP {error.code}: {detail}") from None
    if result.get('status') != 'success' or result.get('api_version') != 2:
        raise RuntimeError(f"API {payload['action']} returned an invalid response")
    return result


token = call({'action': 'login', 'username': 'admin', 'password': admin_password})['token']
try:
    try:
        call({'action': 'get_device', 'device_id': args.device_id}, token)
    except RuntimeError as error:
        if 'HTTP 404:' not in str(error):
            raise
        call({'action': 'register_device', 'device_id': args.device_id,
              'name': args.device_id, 'student_full_name': full_name,
              'student_code': args.student_code}, token)
    call({'action': 'set_device_active', 'device_id': args.device_id, 'is_active': True}, token)
    if not key_file.exists():
        key = call({'action': 'create_device_credential', 'device_id': args.device_id,
                    'label': 'ESP32-S3 firmware'}, token)['device_key']
        if not re.fullmatch(r'[a-f0-9]{64}', key):
            raise RuntimeError('API returned an invalid device key')
        key_dir.mkdir(mode=0o700, exist_ok=True)
        os.chmod(key_dir, 0o700)
        if os.geteuid() == 0:
            os.chown(key_dir, owner.pw_uid, owner.pw_gid)
        fd, temporary = tempfile.mkstemp(prefix='.key-', dir=key_dir)
        try:
            with os.fdopen(fd, 'w') as stream:
                stream.write(key + '\n')
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(temporary, 0o600)
            if os.geteuid() == 0:
                os.chown(temporary, owner.pw_uid, owner.pw_gid)
            os.replace(temporary, key_file)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    if os.geteuid() == 0:
        status = subprocess.run(['python3', str(root / 'mqtt-admin.py'), 'status'],
                                check=True, capture_output=True, text=True).stdout.splitlines()
        if args.device_id not in status:
            subprocess.run(['python3', str(root / 'mqtt-admin.py'), 'add-device', args.device_id], check=True)
    else:
        print('MQTT account pending: sudo python3 mqtt-admin.py add-device ' + args.device_id)
    print('Device active. Private HTTP key:', key_file)
finally:
    try:
        call({'action': 'logout'}, token)
    except RuntimeError:
        pass
