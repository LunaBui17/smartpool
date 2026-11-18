<?php
function api_delete_job($db,$device) {
    $rows=api_query($db,"SELECT * FROM device_delete_jobs WHERE device_id=? AND status<>'deleted' ORDER BY requested_at DESC LIMIT 1",'s',array($device));
    return $rows?$rows[0]:null;
}
function api_request_device_delete($db,$user,$device,$data) {
    api_admin_shared_key($db,$user,$data);api_device($db,$device);
    $old=api_delete_job($db,$device);
    if($old)return array('deleted'=>false,'deletion'=>api_delete_job_public($old),'duplicate'=>true);
    $hardware=api_hardware($db,$device);
    if(!$hardware)api_fail(409,'Cần admin gắn MAC gốc trước khi yêu cầu reset/xóa thiết bị');
    $job=api_uuid();
    api_query($db,'INSERT INTO device_delete_jobs(job_id,device_id,factory_mac,requested_by,actor_username) VALUES (?,?,?,?,?)',
        'sssis',array($job,$device,$hardware['factory_mac'],$user['id'],$user['username']));
    api_query($db,'UPDATE devices SET is_active=0 WHERE device_id=?','s',array($device));
    api_cancel_pending($db,$device,null,'device_delete_requested');
    api_log($db,$user,$device,'device_delete_requested',array('job_id'=>$job,'factory_mac'=>$hardware['factory_mac']));
    return array('deleted'=>false,'duplicate'=>false,'deletion'=>api_delete_job_public(api_delete_job($db,$device)),
        'message'=>'Đang chờ ESP xóa cấu hình; chưa thu hồi broker hoặc xóa registry');
}
function api_delete_job_public($row) {
    unset($row['reset_token_hash']);
    foreach(array('requested_by','archive_id') as $field)if($row[$field]!==null)$row[$field]=(int)$row[$field];
    return api_public($row);
}
function api_issue_reset_command($db,$device) {
    $rows=api_query($db,"SELECT * FROM device_delete_jobs WHERE device_id=? AND status='requested' ORDER BY requested_at LIMIT 1 FOR UPDATE",'s',array($device));
    if(!$rows)return null;
    $row=$rows[0];$token=bin2hex(random_bytes(32));
    api_query($db,'UPDATE device_delete_jobs SET reset_token_hash=?,last_delivered_at=UTC_TIMESTAMP(3) WHERE job_id=?',
        'ss',array(hash('sha256',$token),$row['job_id']));
    return array('job_id'=>$row['job_id'],'device_id'=>$device,'factory_mac'=>$row['factory_mac'],
        'reset_token'=>$token,'expires_at_ms'=>(int)floor(microtime(true)*1000)+60000);
}
// A one-time nonce receipt is usable after the ESP has erased its normal credentials.
function api_device_reset_receipt($db,$data) {
    $id=api_text($data,'job_id',36,'/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/D');
    $token=api_text($data,'reset_token',64,'/^[a-f0-9]{64}$/D');$mac=api_factory_mac($data);
    $rows=api_query($db,'SELECT * FROM device_delete_jobs WHERE job_id=? FOR UPDATE','s',array($id));
    if(!$rows||$rows[0]['factory_mac']!==$mac||$rows[0]['reset_token_hash']===null||
        !hash_equals($rows[0]['reset_token_hash'],hash('sha256',$token)))api_fail(401,'Reset receipt không hợp lệ');
    $job=$rows[0];$result=$data['result']??null;
    if(!in_array($result,array('cleared','error'),true))api_fail(400,'Reset result không hợp lệ');
    if($result==='error') {
        $code=$data['error_code']??null;
        if(!in_array($code,array('RELAY_STOP_FAILED','NVS_CLEAR_FAILED','WIFI_RESET_FAILED'),true))api_fail(400,'Reset error code không hợp lệ');
        if($job['status']==='requested')api_query($db,'UPDATE device_delete_jobs SET last_error_code=? WHERE job_id=?','ss',array($code,$id));
        return array('job_id'=>$id,'reset_recorded'=>false);
    }
    if($job['status']==='requested') {
        api_query($db,"UPDATE device_delete_jobs SET status='reset_confirmed',reset_confirmed_at=UTC_TIMESTAMP(3),last_error_code=NULL WHERE job_id=?",'s',array($id));
        api_log($db,array('username'=>$job['device_id']),null,'device_reset_confirmed',array('job_id'=>$id,'device_id'=>$job['device_id'],'factory_mac'=>$mac),'device','reported');
    }
    return array('job_id'=>$id,'reset_recorded'=>true,'duplicate'=>$job['status']!=='requested');
}
