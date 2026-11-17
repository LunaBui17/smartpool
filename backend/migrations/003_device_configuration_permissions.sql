-- Additive extension: existing users are NOT silently granted configuration access.
-- IF NOT EXISTS permits retry after DDL committed but before the ledger was saved.
ALTER TABLE pool_device_permissions
    ADD COLUMN IF NOT EXISTS can_configure TINYINT(1) NOT NULL DEFAULT 0 AFTER can_control;

SET @pool_configure_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'pool_device_permissions'
          AND CONSTRAINT_NAME = 'chk_device_configure' AND CONSTRAINT_TYPE = 'CHECK'),
    'DO 0',
    'ALTER TABLE pool_device_permissions ADD CONSTRAINT chk_device_configure CHECK (can_configure IN (0, 1) AND can_configure <= can_view)'
);
PREPARE pool_configure_statement FROM @pool_configure_ddl;
EXECUTE pool_configure_statement;
DEALLOCATE PREPARE pool_configure_statement;
