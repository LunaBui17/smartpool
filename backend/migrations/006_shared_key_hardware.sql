-- Preserve devices, samples, users and legacy credentials. New auth is explicit.
CREATE TABLE IF NOT EXISTS esp_shared_credentials (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    label VARCHAR(64) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at DATETIME(3) NULL,
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device_hardware (
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    factory_mac CHAR(17) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    binding_source ENUM('admin','esp_first_contact') NOT NULL,
    bound_by BIGINT UNSIGNED NULL,
    bound_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_verified_at DATETIME(3) NULL,
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    FOREIGN KEY (bound_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Cached presence predates MAC verification. Wait for a fresh, verified packet.
UPDATE device_mqtt_presence SET live_received_at=NULL, online=NULL;
