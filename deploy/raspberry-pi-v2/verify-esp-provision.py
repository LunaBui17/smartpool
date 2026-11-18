#!/usr/bin/env python3
"""Check v2 device HTTP and MQTT credentials without touching pump topics."""
import importlib.util
import json
from pathlib import Path
import sys
from urllib.request import Request, urlopen

device_id = sys.argv[1] if len(sys.argv) == 2 else None
if not device_id:
    raise SystemExit('Usage: python3 verify-esp-provision.py DEVICE_ID')
home = Path.home()
key = (home / 'smartpool-v2-device-keys' / (device_id + '.txt')).read_text().strip()
mqtt = dict(line.split('=', 1) for line in (home / 'smartpool-v2-mqtt' / (device_id + '.txt')).read_text().splitlines() if '=' in line)
endpoints = dict(line.split('=', 1) for line in (home / 'smartpool-v2-endpoints.txt').read_text().splitlines() if '=' in line)
request = Request(endpoints['http_api_url'],
                  json.dumps({'action': 'device_identity', 'device_id': device_id}).encode(),
                  headers={'Content-Type': 'application/json', 'X-Pool-Device-Key': key}, method='POST')
with urlopen(request, timeout=15) as response:
    result = json.load(response)
if result.get('status') != 'success' or result.get('device_id') != device_id:
    raise RuntimeError('Device identity failed')
print('Device HTTP identity OK')

spec = importlib.util.spec_from_file_location('smoke_wss', Path(__file__).with_name('smoke-wss.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
client = module.Client(endpoints['mqtt_wss_uri'])
try:
    client.connect(mqtt['MQTT_USERNAME'], mqtt['MQTT_PASSWORD'])
    topic = module.mqtt_string('smartpool/devices/' + device_id + '/diagnostics/probe')
    client.send(0x82, b'\x00\x01' + topic + b'\x00')
    if client.receive() != (0x90, b'\x00\x01\x00'):
        raise RuntimeError('Device MQTT subscription denied')
    payload = b'v2-device-probe'
    client.send(0x30, topic + payload)
    if client.receive() != (0x30, topic + payload):
        raise RuntimeError('Device MQTT publish/subscribe failed')
finally:
    client.close()
print('Device MQTT WSS publish/subscribe OK')
