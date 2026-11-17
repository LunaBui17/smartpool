#!/usr/bin/env python3
"""Operator-only MQTT account management. Secrets never appear in argv/output."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import pwd
import re
import secrets
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent
COMPOSE = ['docker', 'compose', '--ansi', 'never', '-f', str(ROOT / 'compose.yaml'), '-f', str(ROOT / 'compose.cloudflare.yaml')]


def invoke(args, content=None, capture=False):
    return subprocess.run(args, input=content, stdin=None if content is not None else subprocess.DEVNULL,
                          text=True, capture_output=capture, check=True)


def account(action, device=None, secret=None, capture=False):
    args = COMPOSE + ['run', '--rm', '--no-deps', '-T', 'mqtt-init', action]
    if device:
        args.append(device)
    return invoke(args, secret + '\n' if secret is not None else None, capture)


def private_file(path, text, owner):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.parent.is_symlink() or path.is_symlink():
        raise RuntimeError('Refuse symlink credential path')
    fd, temporary = tempfile.mkstemp(prefix='.credentials-', dir=str(path.parent))
    try:
        with os.fdopen(fd, 'w') as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o600)
        os.chown(temporary, owner.pw_uid, owner.pw_gid)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    if path.parent.name == 'smartpool-mqtt':
        os.chmod(path.parent, 0o700)
        os.chown(path.parent, owner.pw_uid, owner.pw_gid)


def registered_device(device):
    code = ('require "/var/www/html/pool_api/database.php"; $d=pool_database_connect(); '
            '$s=$d->prepare("SELECT device_id FROM devices WHERE device_id=? AND is_active=1"); '
            'if(!$s)exit(1); $s->bind_param("s",$argv[1]);$s->execute(); '
            'if(!$s->get_result()->fetch_assoc())exit(1);')
    invoke(COMPOSE + ['exec', '-T', 'api', 'php', '-r', code, '--', device], capture=True)


def restart():
    # Disconnect all existing sessions so revoked accounts cannot retain access.
    invoke(COMPOSE + ['up', '-d', '--no-deps', '--force-recreate', 'mqtt'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'status', 'add-device', 'rotate-device', 'revoke-device'])
    parser.add_argument('device_id', nargs='?')
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('Run with sudo; this manages credentials and broker sessions')
    if args.action not in ('init', 'status') and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}', args.device_id or ''):
        parser.error('A valid device_id is required')
    owner = pwd.getpwnam(os.environ.get('SUDO_USER', 'root'))
    home = Path(owner.pw_dir)
    with open(ROOT / '.mqtt-admin.lock', 'a') as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.action == 'status':
            account('status')
            return
        if args.action == 'init':
            account('init')
            credentials = account('export-diagnostics', capture=True).stdout.splitlines()
            if len(credentials) != 2:
                raise RuntimeError('Invalid diagnostics credential file')
            private_file(home / 'smartpool-mqtt-diagnostics.txt',
                         'MQTT_USERNAME=' + credentials[0] + '\nMQTT_PASSWORD=' + credentials[1]
                         + '\nMQTT_TOPIC=smartpool/diagnostics/_smartpool_diag/probe\n', owner)
            restart()
            print('Diagnostics credentials saved:', home / 'smartpool-mqtt-diagnostics.txt')
            return
        device = args.device_id
        if args.action in ('add-device', 'rotate-device'):
            registered_device(device)
            secret = secrets.token_hex(24)
            # Persist before changing broker, so a restart failure does not lose the key.
            pending = home / 'smartpool-mqtt' / (device + '.pending.txt')
            private_file(pending, 'MQTT_USERNAME=' + device + '\nMQTT_PASSWORD=' + secret
                         + '\nMQTT_BASE_TOPIC=smartpool/devices/' + device + '\n', owner)
            account('add' if args.action == 'add-device' else 'rotate', device, secret)
            os.replace(pending, pending.with_name(device + '.txt'))
            restart()
            print('Device credentials saved:', pending.with_name(device + '.txt'))
        else:
            account('revoke', device)
            restart()
            path = home / 'smartpool-mqtt' / (device + '.txt')
            if path.exists() and not path.is_symlink():
                private_file(path, 'MQTT_USERNAME=' + device + '\nSTATUS=revoked\n', owner)
            print('Device account revoked:', device)


if __name__ == '__main__':
    try:
        main()
    except (subprocess.CalledProcessError, OSError, RuntimeError):
        print('MQTT account update failed. Check registry/active state, Docker and credential files. '
              'Do not share secret files. A broker restart may still be required.', file=sys.stderr)
        sys.exit(1)
