-- Additive phase 1. No legacy rows are reassigned or removed.
CREATE TABLE IF NOT EXISTS pool_devices (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    student_full_name VARCHAR(128) NOT NULL,
    student_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_user_id BIGINT UNSIGNED NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 0,
    registered_by BIGINT UNSIGNED NULL,
    registered_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_devices_owner (owner_user_id, id),
    CONSTRAINT fk_devices_owner FOREIGN KEY (owner_user_id) REFERENCES pool_users(id) ON DELETE SET NULL,
    CONSTRAINT fk_devices_registrar FOREIGN KEY (registered_by) REFERENCES pool_users(id) ON DELETE SET NULL,
    CONSTRAINT chk_device_active CHECK (is_active IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pool_device_permissions (
    device_fk BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    can_view TINYINT(1) NOT NULL DEFAULT 0,
    can_control TINYINT(1) NOT NULL DEFAULT 0,
    granted_by BIGINT UNSIGNED NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (device_fk, user_id),
    INDEX idx_device_permissions_user (user_id, device_fk),
    CONSTRAINT fk_permissions_device FOREIGN KEY (device_fk) REFERENCES pool_devices(id),
    CONSTRAINT fk_permissions_user FOREIGN KEY (user_id) REFERENCES pool_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_permissions_grantor FOREIGN KEY (granted_by) REFERENCES pool_users(id) ON DELETE SET NULL,
    CONSTRAINT chk_device_permissions CHECK (can_view IN (0, 1) AND can_control IN (0, 1) AND can_control <= can_view)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pool_device_credentials (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_fk BIGINT UNSIGNED NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    label VARCHAR(64) NOT NULL,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at DATETIME(3) NULL,
    INDEX idx_device_credentials_device (device_fk, revoked_at, id),
    CONSTRAINT fk_credentials_device FOREIGN KEY (device_fk) REFERENCES pool_devices(id),
    CONSTRAINT fk_credentials_creator FOREIGN KEY (created_by) REFERENCES pool_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pool_admin_audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_user_id BIGINT UNSIGNED NULL,
    actor_username VARCHAR(64) NOT NULL,
    device_fk BIGINT UNSIGNED NULL,
    action VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    changes_json JSON NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_admin_audit_device (device_fk, id),
    INDEX idx_admin_audit_actor (actor_user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
