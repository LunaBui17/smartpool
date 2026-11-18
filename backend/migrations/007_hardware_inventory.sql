CREATE TABLE IF NOT EXISTS hardware_inventory (
    factory_mac CHAR(17) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
    declared_by BIGINT UNSIGNED NULL,
    declared_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    binding_source ENUM('admin','esp_first_contact') NULL,
    bound_by BIGINT UNSIGNED NULL,
    bound_at DATETIME(3) NULL,
    last_verified_at DATETIME(3) NULL,
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    FOREIGN KEY (declared_by) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (bound_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve existing 006 pairs; do not overwrite inventory on a partial-DDL retry.
INSERT IGNORE INTO hardware_inventory(factory_mac,device_id,declared_by,declared_at,binding_source,bound_by,bound_at,last_verified_at)
SELECT factory_mac,device_id,bound_by,bound_at,binding_source,bound_by,bound_at,last_verified_at FROM device_hardware;
