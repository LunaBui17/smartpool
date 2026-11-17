CREATE TABLE IF NOT EXISTS pool_activity_log (
    event_id CHAR(36) NOT NULL PRIMARY KEY,
    request_id CHAR(36) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    username VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    action VARCHAR(120) NOT NULL,
    result VARCHAR(16) NOT NULL,
    detail VARCHAR(256) NOT NULL DEFAULT '',
    occurred_at_ms BIGINT UNSIGNED NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_activity_user_time (user_id, occurred_at_ms),
    INDEX idx_activity_time (occurred_at_ms)
);
