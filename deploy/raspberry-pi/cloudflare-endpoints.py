#!/usr/bin/env python3
"""Export a Compose Quick Tunnel URL without confusing successive containers."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import pwd
import re
import subprocess
import tempfile
import time
from urllib.request import urlopen

URL_PATTERN = re.compile(r'https://[a-z0-9-]+\.trycloudflare\.com\b')


def docker(*args):
    return subprocess.check_output(['docker', *args], stderr=subprocess.DEVNULL,
                                   timeout=10, text=True)


def write_atomic(path, text, owner=None):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent,
                                         prefix='.' + path.name + '-', delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(text)
        temporary.chmod(0o600)
        if owner is not None:
            os.chown(temporary, owner.pw_uid, owner.pw_gid)
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def read_cache(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {}


def endpoint_state(cache_path):
    try:
        ids = docker('ps', '-aq', '--filter', 'label=com.docker.compose.project=smartpool',
                     '--filter', 'label=com.docker.compose.service=cloudflare').split()
        if len(ids) != 1:
            raise ValueError('Expected exactly one Quick Tunnel container')
        container = json.loads(docker('inspect', ids[0]))[0]
        if not container['State']['Running']:
            raise ValueError('Quick Tunnel is stopped')
        generation = container['Id'] + ':' + container['State']['StartedAt']
        cache = read_cache(cache_path)
        if cache.get('generation') == generation and URL_PATTERN.fullmatch(cache.get('url', '')):
            url = cache['url']
        else:
            # cloudflared writes its URL and status messages to stderr.
            result = subprocess.run(['docker', 'logs', '--since', container['State']['StartedAt'], ids[0]],
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=10,
                                    check=True)
            matches = URL_PATTERN.findall(result.stdout)
            if not matches:
                raise ValueError('Quick Tunnel has not received a URL')
            url = matches[-1]
            write_atomic(cache_path, json.dumps({'generation': generation, 'url': url}))
        with urlopen('http://127.0.0.1:20241/ready', timeout=3) as response:
            if response.status != 200:
                raise ValueError('Quick Tunnel has not connected to Cloudflare')
        return ('status=online\nprovider=cloudflare\n'
                f'api_url={url}\nhttp_api_url={url}/pool_api/pool_api.php\n'
                f'android_http_api_url={url}/pool_api/pool_api.php\n'
                f'mqtt_wss_uri=wss://{url.removeprefix("https://")}/mqtt\n')
    except (OSError, ValueError, KeyError, IndexError, TypeError, subprocess.SubprocessError):
        return 'status=offline\nprovider=cloudflare\nQuick Tunnel is not ready.\n'


def save_output(path, state, owner):
    try:
        old_state = path.read_text().partition('\n')[2]
    except FileNotFoundError:
        old_state = ''
    if old_state == state:
        return
    timestamp = datetime.now().astimezone().isoformat(timespec='seconds')
    write_atomic(path, 'updated_at=' + timestamp + '\n' + state, owner)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--user', required=True)
    parser.add_argument('--state', type=Path, default=Path('/var/lib/smartpool/cloudflare-state.json'))
    parser.add_argument('--watch', action='store_true')
    args = parser.parse_args()
    owner = pwd.getpwnam(args.user)
    args.state.parent.mkdir(parents=True, exist_ok=True)
    while True:
        save_output(args.output, endpoint_state(args.state), owner)
        if not args.watch:
            break
        time.sleep(10)


if __name__ == '__main__':
    main()
