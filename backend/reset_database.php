<?php
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
if($argc!==2||$argv[1]!=='--reset-all-smartpool') {
    fwrite(STDERR,"Usage: php reset_database.php --reset-all-smartpool\nDeletes ALL Smart Pool users, sessions, devices, samples, configs and logs in the configured database. No import or automatic backup.\n");exit(2);
}
require_once __DIR__.'/migration_lib.php';
$db=null;
try {
    $db=pool_v2_connect();pool_v2_lock($db);
    $tables=array_column($db->query('SHOW TABLES')->fetch_all(MYSQLI_NUM),0);
    $known=array_merge(pool_v2_tables(),pool_legacy_tables());
    if(array_diff($tables,$known))throw new RuntimeException('Unknown tables: stop before deletion');
    // Only this explicit CLI flag enters the destructive path. No Docker volumes are removed.
    $db->query('SET FOREIGN_KEY_CHECKS=0');
    try {foreach($known as $table)$db->query('DROP TABLE IF EXISTS `'.$table.'`');}
    finally {$db->query('SET FOREIGN_KEY_CHECKS=1');}
    pool_v2_apply($db);
    echo "Reset completed. Create a new admin and register devices again. No previous credentials or data were imported.\n";
} catch(Throwable $error) {
    fwrite(STDERR,"Reset stopped. Check for unknown tables, DB permissions or partial DDL. A failure after DROP may leave a partially reset database; no automatic recovery was attempted.\n");
    if($db)$db->close();exit(1);
}
$db->query("SELECT RELEASE_LOCK('smartpool_schema_migrate')");$db->close();
