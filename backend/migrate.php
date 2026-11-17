<?php
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/migration_lib.php';
$db=null;
try {
    $db=pool_v2_connect();pool_v2_lock($db);pool_v2_apply($db);
} catch(Throwable $error) {
    fwrite(STDERR,"Migration stopped. Legacy pool_* tables require reset_database.php --reset-all-smartpool; otherwise inspect schema/checksums. No data was reset automatically.\n");
    if($db)$db->close();exit(1);
}
$db->query("SELECT RELEASE_LOCK('smartpool_schema_migrate')");$db->close();
