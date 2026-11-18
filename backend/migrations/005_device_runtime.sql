-- Additive runtime contract. Keep baseline 004 unchanged.
CREATE TABLE IF NOT EXISTS control_worker_state (
    worker_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    connection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mqtt_connected TINYINT(1) NOT NULL DEFAULT 0,
    heartbeat_at DATETIME(3) NOT NULL,
    http_fresh_seconds SMALLINT UNSIGNED NOT NULL DEFAULT 90,
    presence_fresh_seconds SMALLINT UNSIGNED NOT NULL DEFAULT 75,
    CHECK (mqtt_connected IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device_mqtt_presence (
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    connection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    boot_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    presence_sequence BIGINT UNSIGNED NULL,
    online TINYINT(1) NULL,
    live_received_at DATETIME(3) NULL,
    snapshot_received_at DATETIME(3) NULL,
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    CHECK (online IS NULL OR online IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS command_dispatch (
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    last_attempt_at DATETIME(3) NULL,
    last_enqueued_at DATETIME(3) NULL,
    worker_connection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    FOREIGN KEY (command_id) REFERENCES device_commands(command_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS command_results (
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result ENUM('applied','rejected','expired','cancelled') NOT NULL,
    transport ENUM('http','mqtt','backend','worker') NOT NULL,
    audit_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (command_id) REFERENCES device_commands(command_id),
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    FOREIGN KEY (audit_event_id) REFERENCES activity_logs(event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS config_apply_reports (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    boot_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_sequence BIGINT UNSIGNED NOT NULL,
    revision BIGINT UNSIGNED NOT NULL,
    result ENUM('applied','error') NOT NULL,
    error_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_config_attempt (device_id,boot_id,attempt_sequence),
    INDEX idx_config_reports (device_id,id),
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
