"""API v2 outbox. Broker delivery is not an ESP acknowledgement."""
import json
import logging
import os
import re
import time
import uuid
from pathlib import Path

import paho.mqtt.client as mqtt
import pymysql

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("control-worker")
HTTP_FRESH, PRESENCE_FRESH = 90, 75
sent_at = {}
heartbeat = Path("/run/smartpool-control/heartbeat")


def database():
    connection = pymysql.connect(
        host=os.environ.get("POOL_DB_HOST", "db"), user=os.environ.get("POOL_DB_USER", "smartpool"),
        password=os.environ["POOL_DB_PASSWORD"], database=os.environ.get("POOL_DB_NAME", "pool_db"),
        charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor, autocommit=False,
    )
    with connection.cursor() as cursor:
        cursor.execute("SET time_zone = '+00:00'")
    return connection


def finish(cursor, command, result, transport, reason=None):
    """Caller owns command lock. Outcome, audit and result ledger commit together."""
    if command["status"] != "pending":
        return
    cursor.execute("""UPDATE device_commands SET status=%s,
        acknowledged_at=CASE WHEN %s IN ('applied','rejected') THEN UTC_TIMESTAMP(3)
        ELSE acknowledged_at END WHERE command_id=%s AND status='pending'""",
                   (result, result, command["command_id"]))
    if not cursor.rowcount:
        return
    event = str(uuid.uuid4())
    source = "device" if transport == "mqtt" else "backend"
    changes = json.dumps(dict(command_id=command["command_id"], result=result,
                              transport=transport, reason=reason), separators=(",", ":"))
    cursor.execute("""INSERT INTO activity_logs
        (event_id,device_id,actor_username,source,action,result,changes_json)
        VALUES (%s,%s,%s,%s,'command_finished',%s,%s)""",
                   (event, command["device_id"], command["device_id"] if source == "device" else "system",
                    source, "reported" if source == "device" else "committed", changes))
    cursor.execute("""INSERT INTO command_results
        (command_id,device_id,result,transport,audit_event_id) VALUES (%s,%s,%s,%s,%s)""",
                   (command["command_id"], command["device_id"], result, transport, event))
    sent_at.pop(command["command_id"], None)


def locked_command(cursor, command_id, device_id=None):
    # Principal -> device -> command matches the API lock order.
    cursor.execute("SELECT requested_by,device_id FROM device_commands WHERE command_id=%s", (command_id,))
    candidate = cursor.fetchone()
    if not candidate or (device_id is not None and candidate["device_id"] != device_id):
        return None
    cursor.execute("SELECT role,is_active FROM users WHERE id=%s FOR UPDATE", (candidate["requested_by"],))
    user = cursor.fetchone()
    cursor.execute("SELECT is_active FROM devices WHERE device_id=%s FOR UPDATE", (candidate["device_id"],))
    device = cursor.fetchone()
    cursor.execute("""SELECT *,expires_at<=UTC_TIMESTAMP(3) AS expired FROM device_commands
        WHERE command_id=%s FOR UPDATE""", (command_id,))
    command = cursor.fetchone()
    if not command or command["status"] != "pending":
        return None
    if command["expired"]:
        finish(cursor, command, "expired", "worker", "ttl_elapsed")
        return None
    allowed = user and user["is_active"] and device and device["is_active"]
    if allowed and user["role"] != "admin":
        cursor.execute("""SELECT can_view,can_control FROM device_users
            WHERE device_id=%s AND user_id=%s FOR UPDATE""", (command["device_id"], command["requested_by"]))
        grant = cursor.fetchone()
        allowed = grant and grant["can_view"] and grant["can_control"]
    if not allowed:
        finish(cursor, command, "cancelled", "worker", "access_revoked")
        return None
    return command


def record_presence(cursor, connection_id, message, payload, device_id):
    cursor.execute("SELECT device_id FROM devices WHERE device_id=%s", (device_id,))
    if not cursor.fetchone():
        return
    if message.retain:
        # Retained snapshots do not refresh device liveness.
        cursor.execute("""INSERT INTO device_mqtt_presence(device_id,connection_id,snapshot_received_at)
            VALUES (%s,%s,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE snapshot_received_at=UTC_TIMESTAMP(3)""",
                       (device_id, connection_id))
        return
    online = payload.get("online")
    if type(online) is not bool:
        return
    if not online:
        cursor.execute("""INSERT INTO device_mqtt_presence(device_id,connection_id,online,live_received_at)
            VALUES (%s,%s,0,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE
            connection_id=VALUES(connection_id),online=0,live_received_at=UTC_TIMESTAMP(3)""",
                       (device_id, connection_id))
        return
    boot, sequence = payload.get("boot_id", ""), payload.get("presence_sequence")
    if (not isinstance(boot, str) or not re.fullmatch(r"[a-f0-9]{32}", boot)
            or type(sequence) is not int or not 1 <= sequence <= 9007199254740991):
        return
    cursor.execute("SELECT * FROM device_mqtt_presence WHERE device_id=%s FOR UPDATE", (device_id,))
    old = cursor.fetchone()
    if (old and old["connection_id"] == connection_id and old["boot_id"] == boot
            and old["presence_sequence"] is not None and sequence <= old["presence_sequence"]):
        return
    cursor.execute("""INSERT INTO device_mqtt_presence
        (device_id,connection_id,boot_id,presence_sequence,online,live_received_at)
        VALUES (%s,%s,%s,%s,1,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE
        connection_id=VALUES(connection_id),boot_id=VALUES(boot_id),presence_sequence=VALUES(presence_sequence),
        online=1,live_received_at=UTC_TIMESTAMP(3)""", (device_id, connection_id, boot, sequence))


def on_connect(client, userdata, flags, reason_code, properties):
    if reason_code == 0:
        client.subscribe([("smartpool/devices/+/ack/command", 1), ("smartpool/devices/+/status", 1)])
        log.info("MQTT connected; waiting for live device presence")


def on_message(client, userdata, message):
    parts = message.topic.split("/")
    if len(message.payload) > 16384 or parts[:2] != ["smartpool", "devices"] or len(parts) not in (4, 5):
        return
    try:
        payload = json.loads(message.payload)
        if not isinstance(payload, dict) or payload.get("device_id") != parts[2]:
            return
        with database() as connection:
            with connection.cursor() as cursor:
                if parts[3:] == ["status"]:
                    record_presence(cursor, userdata["connection_id"], message, payload, parts[2])
                elif parts[3:] == ["ack", "command"] and not message.retain:
                    command_id, result = payload.get("command_id"), payload.get("result")
                    if (result not in ("applied", "rejected") or not isinstance(command_id, str)
                            or not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", command_id)):
                        return
                    command = locked_command(cursor, command_id, parts[2])
                    if command:
                        finish(cursor, command, result, "mqtt")
            connection.commit()
    except (ValueError, UnicodeError, RecursionError, pymysql.MySQLError) as error:
        log.warning("Message not committed: %s", type(error).__name__)


def tick(client, connection_id):
    with database() as connection:
        with connection.cursor() as cursor:
            cursor.execute("""INSERT INTO control_worker_state
                (worker_id,connection_id,mqtt_connected,heartbeat_at,http_fresh_seconds,presence_fresh_seconds)
                VALUES ('control-worker',%s,%s,UTC_TIMESTAMP(3),%s,%s) ON DUPLICATE KEY UPDATE
                connection_id=VALUES(connection_id),mqtt_connected=VALUES(mqtt_connected),
                heartbeat_at=VALUES(heartbeat_at),http_fresh_seconds=VALUES(http_fresh_seconds),
                presence_fresh_seconds=VALUES(presence_fresh_seconds)""",
                           (connection_id, client.is_connected(), HTTP_FRESH, PRESENCE_FRESH))
            # Invalid commands cannot be starved behind an offline device's outbox.
            cursor.execute("""SELECT c.command_id FROM device_commands c
                LEFT JOIN users u ON u.id=c.requested_by LEFT JOIN devices d ON d.device_id=c.device_id
                LEFT JOIN device_users g ON g.device_id=c.device_id AND g.user_id=c.requested_by
                WHERE c.status='pending' AND (c.expires_at<=UTC_TIMESTAMP(3) OR u.id IS NULL OR d.device_id IS NULL
                OR u.is_active=0 OR d.is_active=0 OR (u.role<>'admin' AND
                (g.user_id IS NULL OR g.can_view=0 OR g.can_control=0)))
                ORDER BY c.expires_at,c.command_id LIMIT 100""")
            ids = [row["command_id"] for row in cursor.fetchall()]
            if client.is_connected():
                cursor.execute("""SELECT c.command_id FROM device_commands c
                    JOIN device_status s ON s.device_id=c.device_id
                    JOIN device_mqtt_presence p ON p.device_id=c.device_id AND p.boot_id=s.boot_id
                    WHERE c.status='pending' AND c.expires_at>UTC_TIMESTAMP(3) AND p.online=1
                    AND p.connection_id=%s AND s.last_seen_at>=UTC_TIMESTAMP(3)-INTERVAL %s SECOND
                    AND p.live_received_at>=UTC_TIMESTAMP(3)-INTERVAL %s SECOND
                    ORDER BY c.expires_at,c.command_id LIMIT 100""",
                               (connection_id, HTTP_FRESH, PRESENCE_FRESH))
                ids = list(dict.fromkeys(ids + [row["command_id"] for row in cursor.fetchall()]))
        connection.commit()
        for command_id in ids:
            with connection.cursor() as cursor:
                command = locked_command(cursor, command_id)
                if command and client.is_connected() and time.monotonic() - sent_at.get(command_id, 0) >= 2:
                    cursor.execute("""SELECT s.device_id FROM device_status s JOIN device_mqtt_presence p
                        ON p.device_id=s.device_id AND p.boot_id=s.boot_id
                        WHERE s.device_id=%s AND s.last_seen_at>=UTC_TIMESTAMP(3)-INTERVAL %s SECOND
                        AND p.connection_id=%s AND p.online=1
                        AND p.live_received_at>=UTC_TIMESTAMP(3)-INTERVAL %s SECOND""",
                                   (command["device_id"], HTTP_FRESH, connection_id, PRESENCE_FRESH))
                    if cursor.fetchone():
                        cursor.execute("""SELECT CAST(UNIX_TIMESTAMP(expires_at)*1000 AS UNSIGNED) AS expiry
                            FROM device_commands WHERE command_id=%s AND expires_at>UTC_TIMESTAMP(3)""", (command_id,))
                        expiry = cursor.fetchone()
                        if expiry:
                            payload = json.dumps(dict(device_id=command["device_id"], command_id=command_id,
                                                      target=command["target"], enabled=bool(command["enabled"]),
                                                      expires_at_ms=int(expiry["expiry"])), separators=(",", ":"))
                            info = client.publish(f"smartpool/devices/{command['device_id']}/cmd/pump", payload, qos=1, retain=False)
                            enqueued = info.rc == mqtt.MQTT_ERR_SUCCESS
                            cursor.execute("""INSERT INTO command_dispatch
                                (command_id,attempts,last_attempt_at,last_enqueued_at,worker_connection_id)
                                VALUES (%s,1,UTC_TIMESTAMP(3),IF(%s,UTC_TIMESTAMP(3),NULL),%s)
                                ON DUPLICATE KEY UPDATE attempts=attempts+1,last_attempt_at=UTC_TIMESTAMP(3),
                                last_enqueued_at=IF(%s,UTC_TIMESTAMP(3),last_enqueued_at),worker_connection_id=VALUES(worker_connection_id)""",
                                           (command_id, enqueued, connection_id, enqueued))
                            if enqueued:
                                sent_at[command_id] = time.monotonic()
            connection.commit()
    if client.is_connected():
        heartbeat.touch()


def main():
    credentials = Path("/auth/bridge.credentials").read_text().splitlines()
    if len(credentials) != 2 or credentials[0] != "_smartpool_bridge":
        raise RuntimeError("Invalid MQTT bridge credentials")
    while True:
        # A fresh client discards stale QoS outbox on reconnect.
        connection_id = str(uuid.uuid4())
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="smartpool-control-worker",
                             clean_session=True, userdata={"connection_id": connection_id})
        client.username_pw_set(credentials[0], credentials[1])
        client.on_connect, client.on_message = on_connect, on_message
        sent_at.clear()
        try:
            client.connect("mqtt", 1883, keepalive=15)
            last_tick = 0
            while True:
                rc = client.loop(timeout=0.2)
                if rc != mqtt.MQTT_ERR_SUCCESS:
                    break
                if time.monotonic() - last_tick >= 1:
                    tick(client, connection_id)
                    last_tick = time.monotonic()
        except (OSError, pymysql.MySQLError) as error:
            log.warning("Worker retry: %s", type(error).__name__)
        finally:
            client.disconnect()
            try:
                # TTL and permission sweep also runs without a broker connection.
                tick(client, connection_id)
            except pymysql.MySQLError as error:
                log.warning("Offline sweep unavailable: %s", type(error).__name__)
        time.sleep(2)


if __name__ == "__main__":
    main()
