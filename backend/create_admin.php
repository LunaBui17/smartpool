<?php
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/migration_lib.php';
require_once __DIR__.'/api_core.php';
$db=null;$hidden=false;
try {
    $db=pool_v2_connect();pool_v2_lock($db);$db->begin_transaction();
    if((int)$db->query("SELECT COUNT(*) FROM users WHERE role='admin'")->fetch_row()[0]>0)throw new RuntimeException('Admin already exists');
    echo 'Admin username (3-64 ASCII characters): ';
    $username=trim((string)fgets(STDIN));
    if(!preg_match('/^[A-Za-z0-9._-]{3,64}$/D',$username))throw new RuntimeException('Invalid username');
    echo 'Full name: ';$fullName=trim((string)fgets(STDIN));
    $fullName=api_text(array('full_name'=>$fullName),'full_name',128);
    echo 'Admin password (hidden on Pi, 7-72 bytes): ';
    if(PHP_OS_FAMILY!=='Windows'&&function_exists('stream_isatty')&&stream_isatty(STDIN)) {
        system('stty -echo 2>/dev/null',$result);$hidden=$result===0;
    }
    try {$password=rtrim((string)fgets(STDIN),"\r\n");}
    finally {if($hidden){system('stty echo 2>/dev/null');$hidden=false;}echo "\n";}
    if(strlen($password)<7||strlen($password)>72||str_contains($password,"\0"))throw new RuntimeException('Invalid password');
    $hash=password_hash($password,PASSWORD_DEFAULT);unset($password);
    api_query($db,"INSERT INTO users(username,password_hash,full_name,role) VALUES (?,?,?,'admin')",'sss',array($username,$hash,$fullName));
    $id=(int)$db->insert_id;
    api_log($db,array('id'=>$id,'username'=>$username),null,'bootstrap_admin',array('user_id'=>$id));
    $db->commit();echo "Admin created.\n";
} catch(Throwable $error) {
    if($hidden)system('stty echo 2>/dev/null');
    if($db){try{$db->rollback();}catch(Throwable $ignored){}$db->close();}
    fwrite(STDERR,"Admin creation stopped. Check input, apply the v2 schema first, and ensure no admin exists.\n");exit(1);
}
$db->query("SELECT RELEASE_LOCK('smartpool_schema_migrate')");$db->close();
