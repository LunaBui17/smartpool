USE pool_db;

ALTER TABLE pool_status
    DROP COLUMN ph,
    CHANGE COLUMN van_control bom_out_control TINYINT(1) NOT NULL DEFAULT 0;
