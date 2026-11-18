<?php
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require_once __DIR__.'/migration_lib.php';
require_once __DIR__.'/api_core.php';
require_once __DIR__.'/api_runtime.php';
require_once __DIR__.'/api_hardware.php';
require_once __DIR__.'/api_deprovision.php';
require_once __DIR__.'/api_device_delete.php';
$db=null;
try {
    $db=pool_v2_connect();$db->begin_transaction();$action=$argv[1]??'';
    if($action==='--bootstrap-shared-key') {
        $keys=api_query($db,'SELECT id FROM esp_shared_credentials WHERE revoked_at IS NULL LIMIT 1');
        if($keys)throw new RuntimeException('Already configured');
        $result=api_shared_credentials($db,array('id'=>null,'username'=>'operator','role'=>'admin'),'create_shared_device_credential',array('label'=>'Pi fleet bootstrap'));
        $db->commit();echo api_json($result),PHP_EOL; // Operator redirects stdout directly to a private file.
    } elseif($action==='--next') {
        $rows=api_query($db,"SELECT job_id,device_id,factory_mac FROM device_delete_jobs WHERE status IN ('reset_confirmed','revoking') ORDER BY requested_at LIMIT 1 FOR UPDATE");
        $job=$rows?$rows[0]:null;
        if($job)api_query($db,"UPDATE device_delete_jobs SET status='revoking' WHERE job_id=?",'s',array($job['job_id']));
        $db->commit();echo api_json(array('job'=>$job)),PHP_EOL;
    } elseif($action==='--finalize') {
        $id=$argv[2]??'';if(!preg_match('/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/D',$id))throw new RuntimeException('Invalid job');
        $rows=api_query($db,'SELECT device_id FROM device_delete_jobs WHERE job_id=?','s',array($id));
        if(!$rows)throw new RuntimeException('Unknown job');
        api_device($db,$rows[0]['device_id']);
        $job=api_query($db,'SELECT * FROM device_delete_jobs WHERE job_id=? FOR UPDATE','s',array($id))[0];
        if($job['status']!=='revoking')throw new RuntimeException('Reset not confirmed');
        $hardware=api_hardware($db,$job['device_id']);
        if(!$hardware||$hardware['factory_mac']!==$job['factory_mac'])throw new RuntimeException('Binding changed');
        $actor=array('id'=>$job['requested_by'],'username'=>$job['actor_username'],'role'=>'admin');
        $result=api_finalize_delete_device($db,$actor,$job['device_id']);
        api_query($db,"UPDATE device_delete_jobs SET status='deleted',revoked_at=UTC_TIMESTAMP(3),archive_id=?,last_error_code=NULL WHERE job_id=?",'is',array($result['archive_id'],$id));
        $db->commit();echo api_json(array('completed'=>true,'job_id'=>$id,'archive_id'=>$result['archive_id'])),PHP_EOL;
    } else throw new RuntimeException('Unknown action');
} catch(Throwable $error) {
    if($db){try{$db->rollback();}catch(Throwable $ignored){}}
    fwrite(STDERR,"Lifecycle operation failed; no secret details logged.\n");exit(1);
} finally {if($db)$db->close();}
