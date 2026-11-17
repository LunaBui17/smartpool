#!/usr/bin/env python3
"""Verify authenticated MQTT over WSS without issuing pump commands."""
import argparse
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import subprocess
import uuid
from urllib.parse import urlsplit


def mqtt_string(text):
    data = text.encode()
    return struct.pack('!H', len(data)) + data


class Client:
    def __init__(self, uri):
        url = urlsplit(uri)
        if url.scheme != 'wss' or not url.hostname:
            raise ValueError('Expected a wss:// URL')
        self.sock = ssl.create_default_context().wrap_socket(
            socket.create_connection((url.hostname, url.port or 443), timeout=15),
            server_hostname=url.hostname)
        self.buffer = bytearray()
        key = base64.b64encode(os.urandom(16)).decode()
        path = url.path or '/mqtt'
        if url.query:
            path += '?' + url.query
        request = (f'GET {path} HTTP/1.1\r\nHost: {url.netloc}\r\n'
                   'Upgrade: websocket\r\nConnection: Upgrade\r\n'
                   f'Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n'
                   'Sec-WebSocket-Protocol: mqtt\r\nUser-Agent: SmartPool-WSS-Check\r\n\r\n')
        self.sock.sendall(request.encode())
        header = bytearray()
        while not header.endswith(b'\r\n\r\n'):
            header += self.read(1)
            if len(header) > 16384:
                raise RuntimeError('WebSocket response header too large')
        lines = header.decode().split('\r\n')
        if lines[0].split()[1] != '101':
            raise RuntimeError('WebSocket upgrade failed: ' + lines[0])
        headers = {name.lower(): value.strip() for name, value in
                   (line.split(':', 1) for line in lines[1:] if ':' in line)}
        accept = base64.b64encode(hashlib.sha1(
            (key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        if headers.get('sec-websocket-accept', '') != accept:
            raise RuntimeError('Invalid WebSocket accept header')

    def read(self, length):
        data = bytearray()
        while len(data) < length:
            part = self.sock.recv(length - len(data))
            if not part:
                raise RuntimeError('Connection closed unexpectedly')
            data += part
        return bytes(data)

    def frame(self, payload, opcode=2):
        length = len(payload)
        head = bytes([0x80 | opcode])
        head += bytes([0x80 | length]) if length < 126 else b'\xfe' + struct.pack('!H', length)
        mask = os.urandom(4)
        self.sock.sendall(head + mask + bytes(value ^ mask[i % 4] for i, value in enumerate(payload)))

    def send(self, kind, payload=b''):
        remaining = len(payload)
        encoded = bytearray()
        while True:
            byte = remaining % 128
            remaining //= 128
            encoded.append(byte | (0x80 if remaining else 0))
            if not remaining:
                break
        self.frame(bytes([kind]) + encoded + payload)

    def receive(self):
        while True:
            if len(self.buffer) >= 2:
                remaining, multiplier, offset = 0, 1, 1
                while offset < len(self.buffer):
                    byte = self.buffer[offset]
                    offset += 1
                    remaining += (byte & 127) * multiplier
                    multiplier *= 128
                    if not byte & 128:
                        if len(self.buffer) >= offset + remaining:
                            kind = self.buffer[0]
                            body = bytes(self.buffer[offset:offset + remaining])
                            del self.buffer[:offset + remaining]
                            return kind, body
                        break
            first, second = self.read(2)
            length = second & 127
            if length == 126:
                length = struct.unpack('!H', self.read(2))[0]
            elif length == 127:
                length = struct.unpack('!Q', self.read(8))[0]
            if length > 1048576:
                raise RuntimeError('WebSocket frame too large')
            mask = self.read(4) if second & 128 else None
            payload = self.read(length)
            if mask:
                payload = bytes(value ^ mask[i % 4] for i, value in enumerate(payload))
            opcode = first & 15
            if opcode in (0, 2):
                self.buffer.extend(payload)
            elif opcode == 9:
                self.frame(payload, opcode=10)
            elif opcode == 8:
                raise RuntimeError('WebSocket peer closed the connection')

    def connect(self, username, password):
        payload = mqtt_string('MQTT') + b'\x04\xc2\x00\x1e'
        payload += mqtt_string('smartpool-wss-' + uuid.uuid4().hex[:8])
        payload += mqtt_string(username) + mqtt_string(password)
        self.send(0x10, payload)
        if self.receive() != (0x20, b'\x00\x00'):
            raise RuntimeError('MQTT authentication failed')

    def close(self):
        try:
            self.send(0xe0)
            self.frame(struct.pack('!H', 1000), opcode=8)
        finally:
            self.sock.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url', help='wss://your-domain/mqtt')
    parser.add_argument('--credentials', required=True, help='Private diagnostics credential file')
    args = parser.parse_args()
    from pathlib import Path
    credentials = dict(line.split('=', 1) for line in Path(args.credentials).read_text().splitlines() if '=' in line)
    if credentials.get('MQTT_USERNAME') != '_smartpool_diag':
        raise RuntimeError('Use the restricted diagnostics account')
    clients = []
    try:
        for username, key in [('_smartpool_diag', 'MQTT_PASSWORD')]:
            client = Client(args.url)
            clients.append(client)
            client.connect(username, credentials[key])
            if username == '_smartpool_diag':
                topic = mqtt_string('smartpool/diagnostics/_smartpool_diag/probe')
                client.send(0x82, b'\x00\x01' + topic + b'\x00')
                if client.receive() != (0x90, b'\x00\x01\x00'):
                    raise RuntimeError('MQTT subscription was denied')
                payload = uuid.uuid4().hex.encode()
                client.send(0x30, topic + payload)
                kind, body = client.receive()
                if kind != 0x30 or body != topic + payload:
                    raise RuntimeError('MQTT publish/subscribe verification failed')
            print(f'{username}: authenticated MQTT over WSS OK', flush=True)
    finally:
        for client in clients:
            client.close()
    print('WSS publish/subscribe OK; no pump commands sent.', flush=True)


if __name__ == '__main__':
    main()
