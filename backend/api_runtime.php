<?php
// Runtime 005: device readiness, atomic command outcomes, config-apply reports.
function api_runtime_transition($db,$row,$result,$transport,$reason=null) {
    if($row['status']!== 'pending') {
        if($row['status']===$result)return false;
        api_fail(409,'Lệnh đã kết thúc với kết quả khác');
    }
    api_query($db,'UPDATE device_commands SET status=?,acknowledged_at=CASE WHEN ? IN (\'applied\',\'rejected\') THEN UTC_TIMESTAMP(3) ELSE acknowledged_at END WHERE command_id=? AND status=\'pending\'',
        'sss',array($result,$result,$row['command_id']));
    $source=in_array($transport,array('http','mqtt'),true)?'device':'backend';
    $event=api_log($db,array('username'=>$source==='device'?$row['device_id']:'system'),$row['device_id'],
        'command_finished',array('command_id'=>$row['command_id'],'result'=>$result,'transport'=>$transport,'reason'=>$reason),$source,
        $source==='device'?'reported':'committed');
    api_query($db,'INSERT INTO command_results(command_id,device_id,result,transport,audit_event_id) VALUES (?,?,?,?,?)',
        'sssss',array($row['command_id'],$row['device_id'],$result,$transport,$event));
    return true;
}
function api_cancel_pending($db,$device=null,$user=null,$reason='access_revoked') {
    $sql="SELECT * FROM device_commands WHERE status='pending'";$args=array();$types='';
    if($device!==null){$sql.=' AND device_id=?';$args[]=$device;$types.='s';}
    if($user!==null){$sql.=' AND requested_by=?';$args[]=$user;$types.='i';}
    foreach(api_query($db,$sql.' ORDER BY command_id FOR UPDATE',$types,$args) as $row)api_runtime_transition($db,$row,'cancelled','backend',$reason);
}
function api_expire_pending($db,$device) {
    foreach(api_query($db,"SELECT * FROM device_commands WHERE device_id=? AND status='pending' AND expires_at<=UTC_TIMESTAMP(3) ORDER BY command_id FOR UPDATE",'s',array($device)) as $row) {
        api_runtime_transition($db,$row,'expired','backend','ttl_elapsed');
    }
}
function api_readiness($db,$user,$device,$state) {
    $workers=api_query($db,"SELECT *,TIMESTAMPDIFF(MICROSECOND,heartbeat_at,UTC_TIMESTAMP(3))/1000 AS age_ms FROM control_worker_state WHERE worker_id='control-worker'");
    $worker=$workers?$workers[0]:null;
    $healthy=$worker!==null&&(float)$worker['age_ms']>=0&&(float)$worker['age_ms']<=10000;
    $connected=$healthy&&(bool)$worker['mqtt_connected'];
    $httpLimit=$worker?(int)$worker['http_fresh_seconds']:90;
    $presenceLimit=$worker?(int)$worker['presence_fresh_seconds']:75;
    $httpAge=$state===null?null:(int)floor(microtime(true)*1000)-(int)$state['last_seen_at_ms'];
    $httpRecent=$httpAge!==null&&$httpAge>=0&&$httpAge<=$httpLimit*1000;
    $rows=api_query($db,'SELECT *,TIMESTAMPDIFF(MICROSECOND,live_received_at,UTC_TIMESTAMP(3))/1000 AS age_ms FROM device_mqtt_presence WHERE device_id=?','s',array($device['device_id']));
    $p=$rows?$rows[0]:null;$presence='unknown';$mqttRecent=false;
    if($p&&$worker&&$p['connection_id']===$worker['connection_id']&&$p['live_received_at']!==null) {
        if(!(bool)$p['online'])$presence='offline';
        elseif((float)$p['age_ms']<0||(float)$p['age_ms']>$presenceLimit*1000)$presence='stale';
        elseif($connected&&$state&&$p['boot_id']===$state['boot_id']){$presence='online';$mqttRecent=true;}
    }
    $can=$user['role']==='admin';
    if(!$can){$g=api_query($db,'SELECT can_view,can_control FROM device_users WHERE device_id=? AND user_id=?','si',array($device['device_id'],$user['id']));$can=$g&&(bool)$g[0]['can_view']&&(bool)$g[0]['can_control'];}
    $reasons=array();if(!(bool)$device['is_active'])$reasons[]='device_inactive';if(!$can)$reasons[]='control_denied';
    if(!$healthy)$reasons[]='worker_stale';if(!$connected)$reasons[]='worker_broker_disconnected';
    if(!$httpRecent)$reasons[]=$state===null?'http_report_missing':'http_report_stale';
    if(!$mqttRecent)$reasons[]='mqtt_presence_'.$presence;
    return array('ready'=>!$reasons,'reasons'=>$reasons,'http_report'=>array('recent'=>$httpRecent,'age_ms'=>$httpAge,'max_age_seconds'=>$httpLimit),
        'mqtt_presence'=>array('state'=>$presence,'age_ms'=>$p&&$p['age_ms']!==null?(int)$p['age_ms']:null,'max_age_seconds'=>$presenceLimit),
        'worker'=>array('healthy'=>$healthy,'broker_connected'=>$connected,'age_ms'=>$worker?(int)$worker['age_ms']:null));
}
function api_config_report($db,$device,$boot,$data) {
    $sequence=api_int($data,'attempt_sequence',null,1,9007199254740991);$revision=api_int($data,'revision');
    $result=$data['result']??null;$code=$data['error_code']??null;
    $codes=array('INVALID_CONFIG','FEATURE_UNAVAILABLE','NVS_WRITE_FAILED','RELAY_STOP_FAILED','REVISION_CONFLICT');
    if(!in_array($result,array('applied','error'),true)||($result==='applied'&&$code!==null)||($result==='error'&&!in_array($code,$codes,true)))api_fail(400,'Kết quả áp dụng cấu hình không hợp lệ');
    $desired=(int)api_query($db,'SELECT revision FROM device_configs WHERE device_id=?','s',array($device))[0]['revision'];
    if($revision>$desired)api_fail(400,'Revision chưa tồn tại trên server');
    $old=api_query($db,'SELECT revision,result,error_code FROM config_apply_reports WHERE device_id=? AND boot_id=? AND attempt_sequence=? FOR UPDATE','ssi',array($device,$boot,$sequence));
    if($old){if((int)$old[0]['revision']!==$revision||$old[0]['result']!==$result||$old[0]['error_code']!==$code)api_fail(409,'Config attempt trùng nhưng nội dung khác');return;}
    api_query($db,'INSERT INTO config_apply_reports(device_id,boot_id,attempt_sequence,revision,result,error_code) VALUES (?,?,?,?,?,?)',
        'ssiiss',array($device,$boot,$sequence,$revision,$result,$code));
    api_log($db,array('username'=>$device),$device,'config_apply_reported',array('boot_id'=>$boot,'attempt_sequence'=>$sequence,'revision'=>$revision,'result'=>$result,'error_code'=>$code),'device','reported');
}
