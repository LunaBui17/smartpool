"""Deliver authorized pending pump commands to per-device MQTT topics."""
import json
import logging
import os
import time
from pathlib import Path

import paho.mqtt.client as mqtt
import pymysql


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("control-worker")
sent_at = {}
heartbeat = Path("/run/smartpool-control/heartbeat")


def database():
    connection = pymysql.connect(
        host=os.environ.get("POOL_DB_HOST", "db"),
        user=os.environ.get("POOL_DB_USER", "smartpool"),
        password=os.environ["POOL_DB_PASSWORD"],
        database=os.environ.get("POOL_DB_NAME", "pool_db"),
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=True,
    )
    with connection.cursor() as cursor:
        cursor.execute("SET time_zone = '+00:00'")
    return connection


def pending_commands(connection):
    with connection.cursor() as cursor:
        cursor.execute("""
            SELECT c.command_id, c.device_id, c.target, c.enabled,
                   CAST(UNIX_TIMESTAMP(c.expires_at) * 1000 AS UNSIGNED) AS expires_at_ms
            FROM device_commands c
            JOIN devices d ON d.device_id = c.device_id AND d.is_active = 1
            JOIN users u ON u.id = c.requested_by AND u.is_active = 1
            JOIN device_status s ON s.device_id = c.device_id
                 AND s.last_seen_at >= UTC_TIMESTAMP(3) - INTERVAL 60 SECOND
            WHERE c.status = 'pending' AND c.expires_at > UTC_TIMESTAMP(3)
              AND (u.role = 'admin' OR EXISTS (
                  SELECT 1 FROM device_users m WHERE m.device_id = c.device_id
                    AND m.user_id = u.id AND m.can_view = 1 AND m.can_control = 1))
            ORDER BY c.requested_at, c.command_id LIMIT 100
        """)
        return cursor.fetchall()


def on_connect(client, userdata, flags, reason_code, properties):
    if reason_code == 0:
        sent_at.clear()
        client.subscribe("smartpool/devices/+/ack/command", qos=1)
        log.info("Connected to MQTT; listening for device acknowledgements")
    else:
        log.error("MQTT connection rejected: %s", reason_code)


def on_message(client, userdata, message):
    if message.retain:
        return
    parts = message.topic.split("/")
    if len(parts) != 5 or parts[:2] != ["smartpool", "devices"] or parts[3:] != ["ack", "command"]:
        return
    device_id = parts[2]
    try:
        ack = json.loads(message.payload)
        command_id = ack["command_id"]
        result = ack["result"]
        if (ack.get("device_id") != device_id or result not in ("applied", "rejected")
                or not isinstance(command_id, str) or len(command_id) != 36):
            return
        connection = database()
        try:
            with connection.cursor() as cursor:
                cursor.execute("""
                    UPDATE device_commands SET status=%s, acknowledged_at=UTC_TIMESTAMP(3)
                    WHERE command_id=%s AND device_id=%s AND status='pending'
                      AND expires_at > UTC_TIMESTAMP(3)
                """, (result, command_id, device_id))
                if cursor.rowcount:
                    sent_at.pop(command_id, None)
                    log.info("Command %s for %s: %s", command_id, device_id, result)
        finally:
            connection.close()
    except (KeyError, ValueError, pymysql.MySQLError) as error:
        log.warning("Ignored invalid acknowledgement: %s", type(error).__name__)


def main():
    credentials = Path("/auth/bridge.credentials").read_text().splitlines()
    if len(credentials) != 2 or credentials[0] != "_smartpool_bridge":
        raise RuntimeError("Invalid MQTT bridge credentials")
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="smartpool-control-worker")
    client.username_pw_set(credentials[0], credentials[1])
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect("mqtt", 1883, keepalive=30)
    client.loop_start()
    try:
        while True:
            if client.is_connected():
                try:
                    connection = database()
                    try:
                        for command in pending_commands(connection):
                            command_id = command["command_id"]
                            now = time.monotonic()
                            if now - sent_at.get(command_id, 0) < 2:
                                continue
                            device_id = command["device_id"]
                            payload = json.dumps({
                                "device_id": device_id,
                                "command_id": command_id,
                                "target": command["target"],
                                "enabled": bool(command["enabled"]),
                                "expires_at_ms": int(command["expires_at_ms"]),
                            }, separators=(",", ":"))
                            topic = f"smartpool/devices/{device_id}/cmd/pump"
                            info = client.publish(topic, payload, qos=1, retain=False)
                            if info.rc == mqtt.MQTT_ERR_SUCCESS:
                                sent_at[command_id] = now
                        heartbeat.touch()
                    finally:
                        connection.close()
                except pymysql.MySQLError as error:
                    log.warning("Database unavailable: %s", type(error).__name__)
            time.sleep(1)
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
