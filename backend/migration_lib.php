<?php
function pool_v2_connect() {
    require_once __DIR__ . '/database.php';
    $db=pool_database_connect();
    mysqli_report(MYSQLI_REPORT_ERROR|MYSQLI_REPORT_STRICT);
    if($db->connect_errno)throw new RuntimeException('Database unavailable');
    $db->set_charset('utf8mb4');$db->query("SET time_zone='+00:00'");
    return $db;
}
function pool_v2_lock($db) {
    if((int)$db->query("SELECT GET_LOCK('smartpool_schema_migrate',10)")->fetch_row()[0]!==1)throw new RuntimeException('Migration busy');
}
function pool_v2_tables() {
    return array('users','user_sessions','devices','device_users','device_credentials','device_configs',
        'device_boots','device_status','sensor_samples','device_commands','activity_logs','schema_migrations');
}
function pool_legacy_tables() {
    return array('pool_status','pool_history','pool_commands','pool_users','pool_user_sessions','pool_activity_log',
        'pool_devices','pool_device_permissions','pool_device_credentials','pool_admin_audit_log','pool_schema_migrations');
}
function pool_v2_apply($db) {
    $tables=array_column($db->query('SHOW TABLES')->fetch_all(MYSQLI_NUM),0);
    if(array_intersect(pool_legacy_tables(),$tables))throw new RuntimeException('Legacy database requires explicit reset');
    $db->query('CREATE TABLE IF NOT EXISTS schema_migrations (version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, '
        . 'checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)) ENGINE=InnoDB');
    $files=glob(__DIR__.'/migrations/[0-9]*.sql');sort($files,SORT_STRING);
    foreach($files as $file) {
        if((int)substr(basename($file),0,3)<4)continue;
        $version=basename($file);$sql=str_replace("\r\n","\n",file_get_contents($file));$hash=hash('sha256',$sql);
        $check=$db->prepare('SELECT checksum FROM schema_migrations WHERE version=?');$check->bind_param('s',$version);$check->execute();
        $old=$check->get_result()->fetch_assoc();$check->close();
        if($old) {
            if(!hash_equals($old['checksum'],$hash))throw new RuntimeException('Migration checksum differs');
            echo "Already applied: $version\n";continue;
        }
        $db->multi_query($sql);
        do {$result=$db->store_result();if($result)$result->free();if(!$db->more_results())break;}while($db->next_result());
        // DDL commits implicitly; a failed run is retried without dropping completed tables.
        if($version==='004_multi_device.sql')foreach(pool_v2_tables() as $table)$db->query('SELECT * FROM `'.$table.'` LIMIT 0');
        $save=$db->prepare('INSERT INTO schema_migrations(version,checksum) VALUES (?,?)');$save->bind_param('ss',$version,$hash);$save->execute();$save->close();
        echo "Applied: $version\n";
    }
}
