#!/usr/bin/env python3
"""Initial private policy for a NEW v2 installation; never edit an existing DB password blindly."""
import getpass
import ipaddress
import os
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent
if os.geteuid() != 0 or len(sys.argv) != 2:
    raise SystemExit('Usage: sudo python3 setup-passwords.py PI_LAN_IP')
ipaddress.IPv4Address(sys.argv[1])
if (root / '.env').exists() or (root / '.env.mqtt-policy').exists():
    raise SystemExit('Private config already exists. Do not change DB credentials without updating MariaDB.')
admin = getpass.getpass('Management/admin password (7-72 ASCII characters): ')
user = getpass.getpass('Application-user/device password (8-72 ASCII characters): ')
if not re.fullmatch(r'[A-Za-z0-9._-]{7,72}', admin) or not re.fullmatch(r'[A-Za-z0-9._-]{8,72}', user):
    raise SystemExit('Invalid password format')
os.umask(0o077)
(root / '.env').write_text('PI_BIND_IP=' + sys.argv[1] + '\nHTTP_PORT=80\nMQTT_PORT=1883\nADMINER_PORT=8080\n'
                          + 'DB_ROOT_PASSWORD=' + admin + '\nDB_PASSWORD=' + user + '\n')
(root / '.env.mqtt-policy').write_text(admin + '\n' + user + '\n')
print('Private policy created. Values were not printed. Use only with a new empty v2 DB volume.')
