<?php
// Operator-only bulk reset. Passwords enter through stdin, never argv or logs.
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
if($argc!==2||$argv[1]!=='--from-stdin'){fwrite(STDERR,"Usage: php reset_role_passwords.php --from-stdin (admin password, then user password, one per line)\n");exit(2);}
require_once __DIR__.'/migration_lib.php';
require_once __DIR__.'/api_core.php';
$db=null;
try {
    $admin=rtrim((string)fgets(STDIN),"\r\n");$ordinary=rtrim((string)fgets(STDIN),"\r\n");
    if(strlen($admin)<7||strlen($ordinary)<8||strlen($admin)>72||strlen($ordinary)>72||str_contains($admin,"\0")||str_contains($ordinary,"\0"))throw new RuntimeException('Invalid policy');
    $db=pool_v2_connect();$db->begin_transaction();
    $users=api_query($db,'SELECT id,username,role FROM users ORDER BY id FOR UPDATE');
    foreach($users as $user) {
        $hash=password_hash($user['role']==='admin'?$admin:$ordinary,PASSWORD_DEFAULT);
        api_query($db,'UPDATE users SET password_hash=? WHERE id=?','si',array($hash,$user['id']));
        api_log($db,array('username'=>'system'),null,'operator_password_reset',array('user_id'=>(int)$user['id'],'role'=>$user['role']));
    }
    api_query($db,'DELETE FROM user_sessions');
    api_query($db,"UPDATE device_commands SET status='cancelled' WHERE status='pending'");
    $db->commit();echo 'Role passwords updated; sessions revoked; users preserved: '.count($users).PHP_EOL;
}catch(Throwable $error){if($db){try{$db->rollback();}catch(Throwable $ignored){}}fwrite(STDERR,"Role password reset failed; check input and database.\n");exit(1);}
finally{unset($admin,$ordinary);if($db)$db->close();}
