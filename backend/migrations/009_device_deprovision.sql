CREATE TABLE IF NOT EXISTS device_delete_jobs (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    factory_mac CHAR(17) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_by BIGINT UNSIGNED NULL,
    actor_username VARCHAR(64) NOT NULL,
    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    status ENUM('requested','reset_confirmed','revoking','deleted') NOT NULL DEFAULT 'requested',
    reset_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_delivered_at DATETIME(3) NULL,
    reset_confirmed_at DATETIME(3) NULL,
    revoked_at DATETIME(3) NULL,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    archive_id BIGINT UNSIGNED NULL,
    INDEX idx_delete_device (device_id,status,requested_at),
    INDEX idx_delete_status (status,requested_at),
    FOREIGN KEY (requested_by) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (archive_id) REFERENCES device_archives(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
