-- Smart Pool multi-device baseline. No legacy data import.
-- Application connections use UTC. Do not modify this file once recorded in the ledger.
CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(128) NOT NULL,
    student_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    role ENUM('admin','user') NOT NULL DEFAULT 'user',
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CHECK (is_active IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_sessions (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_sessions_user (user_id, expires_at),
    INDEX idx_sessions_expiry (expires_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS devices (
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    student_full_name VARCHAR(128) NOT NULL,
    student_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_user_id BIGINT UNSIGNED NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 0,
    registration_source ENUM('manual','ble') NOT NULL DEFAULT 'manual',
    registered_by BIGINT UNSIGNED NULL,
    registered_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_devices_owner (owner_user_id, device_id),
    FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (registered_by) REFERENCES users(id) ON DELETE SET NULL,
    CHECK (is_active IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device_users (
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    can_view TINYINT(1) NOT NULL DEFAULT 1,
    can_control TINYINT(1) NOT NULL DEFAULT 1,
    can_configure TINYINT(1) NOT NULL DEFAULT 1,
    granted_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (device_id,user_id),
    INDEX idx_members_user (user_id,device_id),
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (granted_by) REFERENCES users(id) ON DELETE SET NULL,
    CHECK (can_view IN (0,1) AND can_control IN (0,1) AND can_configure IN (0,1)
        AND can_control <= can_view AND can_configure <= can_view)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device_credentials (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    label VARCHAR(64) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at DATETIME(3) NULL,
    INDEX idx_credentials_device (device_id,revoked_at,id),
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device_configs (
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    temperature_min DECIMAL(6,2) NOT NULL DEFAULT 20,
    temperature_max DECIMAL(6,2) NOT NULL DEFAULT 35,
    water_adc_min SMALLINT UNSIGNED NOT NULL DEFAULT 2000,
    water_adc_max SMALLINT UNSIGNED NOT NULL DEFAULT 4095,
    water_cm_min DECIMAL(10,2) NOT NULL DEFAULT 0,
    water_cm_max DECIMAL(10,2) NOT NULL DEFAULT 100,
    interlock TINYINT(1) NOT NULL DEFAULT 1,
    automation_json JSON NULL,
    updated_by BIGINT UNSIGNED NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL,
    CHECK (temperature_min >= -55 AND temperature_max <= 125 AND temperature_min < temperature_max),
    CHECK (water_adc_min < water_adc_max AND water_adc_max <= 4095),
    CHECK (water_cm_min >= 0 AND water_cm_min < water_cm_max),
    CHECK (interlock IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device_boots (
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    boot_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    first_seen_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (device_id,boot_id),
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device_status (
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    boot_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_sequence BIGINT UNSIGNED NOT NULL,
    pump_in TINYINT(1) NOT NULL DEFAULT 0,
    pump_out TINYINT(1) NOT NULL DEFAULT 0,
    temperature_enabled TINYINT(1) NOT NULL DEFAULT 1,
    water_enabled TINYINT(1) NOT NULL DEFAULT 1,
    wifi_ssid VARCHAR(32) NULL,
    lan_ip VARCHAR(45) NULL,
    reported_config_revision BIGINT UNSIGNED NULL,
    reported_at_ms BIGINT UNSIGNED NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_seen_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (device_id,boot_id) REFERENCES device_boots(device_id,boot_id),
    CHECK (pump_in IN (0,1) AND pump_out IN (0,1) AND temperature_enabled IN (0,1) AND water_enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sensor_samples (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sample_id CHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    measured_at_ms BIGINT UNSIGNED NULL,
    time_quality ENUM('synced','unknown') NOT NULL,
    received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    temperature DECIMAL(6,2) NULL,
    water_adc SMALLINT UNSIGNED NULL,
    is_backfill TINYINT(1) NOT NULL DEFAULT 0,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    UNIQUE KEY uq_sample (device_id,sample_id),
    INDEX idx_samples_measured (device_id,measured_at_ms,id),
    INDEX idx_samples_received (device_id,received_at,id),
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    CHECK (temperature IS NULL OR temperature BETWEEN -55 AND 125),
    CHECK (water_adc IS NULL OR water_adc <= 4095),
    CHECK (temperature IS NOT NULL OR water_adc IS NOT NULL),
    CHECK (is_backfill IN (0,1)),
    CHECK ((time_quality='unknown' AND measured_at_ms IS NULL) OR (time_quality='synced' AND measured_at_ms IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device_commands (
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_by BIGINT UNSIGNED NULL,
    target ENUM('pump_in','pump_out') NOT NULL,
    enabled TINYINT(1) NOT NULL,
    status ENUM('pending','applied','rejected','expired','cancelled') NOT NULL DEFAULT 'pending',
    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    acknowledged_at DATETIME(3) NULL,
    UNIQUE KEY uq_command_request (device_id,request_id),
    INDEX idx_commands_device (device_id,requested_at,command_id),
    INDEX idx_commands_pending (status,expires_at),
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    FOREIGN KEY (requested_by) REFERENCES users(id) ON DELETE SET NULL,
    CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS activity_logs (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor_user_id BIGINT UNSIGNED NULL,
    actor_username VARCHAR(64) NOT NULL,
    source ENUM('backend','client','device') NOT NULL,
    action VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    occurred_at_ms BIGINT UNSIGNED NULL,
    received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    changes_json JSON NULL,
    INDEX idx_activity_device (device_id,id),
    INDEX idx_activity_actor (actor_user_id,id),
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
