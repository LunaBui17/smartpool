-- Only an explicit admin API action archives/deletes a device. Migration deletes nothing.
CREATE TABLE IF NOT EXISTS device_archives (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    original_device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    factory_mac CHAR(17) CHARACTER SET ascii COLLATE ascii_bin NULL,
    deleted_by BIGINT UNSIGNED NULL,
    deleted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    record_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    INDEX idx_archive_device (original_device_id,id),
    FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device_archive_records (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    archive_id BIGINT UNSIGNED NOT NULL,
    source_table VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    record_json JSON NOT NULL,
    UNIQUE KEY uq_archive_record (archive_id,source_table,row_key),
    FOREIGN KEY (archive_id) REFERENCES device_archives(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
