<?php
function api_update_config($db,$user,$device,$data) {
    $revision=api_int($data,'expected_revision');
    $row=api_query($db,'SELECT * FROM device_configs WHERE device_id=? FOR UPDATE','s',array($device))[0];
    if((int)$row['revision']!==$revision)api_fail(409,'Cấu hình đã thay đổi; tải lại revision');
    $settings=$data['settings']??null;
    $allowed=array('temperature_min','temperature_max','water_adc_min','water_adc_max','water_cm_min','water_cm_max','interlock','automation');
    if(!is_array($settings)||!$settings||array_diff(array_keys($settings),$allowed))api_fail(400,'settings chứa trường không hỗ trợ');
    $next=$row;
    foreach(array('temperature_min','temperature_max','water_cm_min','water_cm_max') as $field) {
        if(array_key_exists($field,$settings))$next[$field]=api_number($settings[$field],str_starts_with($field,'temperature')?-55:0,
            str_starts_with($field,'temperature')?125:100000,$field);
    }
    foreach(array('water_adc_min','water_adc_max') as $field)if(array_key_exists($field,$settings))$next[$field]=api_int($settings,$field,null,0,4095);
    if(array_key_exists('interlock',$settings))$next['interlock']=api_bool($settings,'interlock');
    if(array_key_exists('automation',$settings))$next['automation_json']=api_json(api_validate_automation($settings['automation']));
    foreach(array(array('temperature_min','temperature_max'),array('water_adc_min','water_adc_max'),array('water_cm_min','water_cm_max')) as $pair) {
        if((float)$next[$pair[0]]>=(float)$next[$pair[1]])api_fail(400,'Ngưỡng dưới phải nhỏ hơn ngưỡng trên');
    }
    api_query($db,'UPDATE device_configs SET revision=revision+1,temperature_min=?,temperature_max=?,water_adc_min=?,water_adc_max=?,water_cm_min=?,water_cm_max=?,interlock=?,automation_json=?,updated_by=? WHERE device_id=?',
        'ddiiddisis',array($next['temperature_min'],$next['temperature_max'],$next['water_adc_min'],$next['water_adc_max'],
            $next['water_cm_min'],$next['water_cm_max'],$next['interlock'],$next['automation_json'],$user['id'],$device));
    api_log($db,$user,$device,'config_updated',array('from_revision'=>$revision,'to_revision'=>$revision+1,'changed_fields'=>array_keys($settings)));
    return array('config'=>api_config(api_query($db,'SELECT * FROM device_configs WHERE device_id=?','s',array($device))[0]),
        'delivery'=>'stored_only','message'=>'Đã lưu cấu hình mong muốn; chưa xác nhận ESP áp dụng');
}

function api_esp_action($db,$device,$action,$data) {
    if($action==='device_identity')return array('device_id'=>$device);
    if($action==='get_device_config')return array('config'=>api_config(api_query($db,'SELECT * FROM device_configs WHERE device_id=?','s',array($device))[0]));
    if($action==='acknowledge_command')return api_command_ack($db,$device,$data);
    if($action==='upload_sample') {
        $sampleId=strtoupper(api_text($data,'sample_id',16,'/^[a-fA-F0-9]{16}$/D'));
        $measured=api_time($data,'measured_at_ms');$quality=$data['time_quality']??null;
        if(($quality!=='synced'&&$quality!=='unknown')||($quality==='synced')!==($measured!==null))api_fail(400,'time_quality không khớp thời điểm đo');
        $temperature=isset($data['temperature'])?api_number($data['temperature'],-55,125,'temperature'):null;
        $water=isset($data['water_adc'])?api_int($data,'water_adc',null,0,4095):null;
        if($temperature===null&&$water===null)api_fail(400,'Mẫu phải có ít nhất một số đo');
        $backfill=api_bool($data,'is_backfill');
        // Replay transport metadata is excluded: false -> true during retry is harmless.
        $digest=hash('sha256',api_json(array($sampleId,$measured,$quality,$temperature,$water)));
        $old=api_query($db,'SELECT * FROM sensor_samples WHERE device_id=? AND sample_id=? FOR UPDATE','ss',array($device,$sampleId));
        if($old) {
            if(!hash_equals($old[0]['payload_hash'],$digest))api_fail(409,'sample_id trùng nhưng nội dung khác');
            return array('duplicate'=>true,'sample'=>api_public($old[0]));
        }
        api_query($db,'INSERT INTO sensor_samples(device_id,sample_id,measured_at_ms,time_quality,temperature,water_adc,is_backfill,payload_hash) VALUES (?,?,?,?,?,?,?,?)',
            'ssisdiis',array($device,$sampleId,$measured,$quality,$temperature,$water,$backfill,$digest));
        return array('duplicate'=>false,'sample'=>api_public(api_query($db,'SELECT * FROM sensor_samples WHERE device_id=? AND sample_id=?','ss',array($device,$sampleId))[0]));
    }
    // Status is a live stream, not part of the offline sensor replay queue.
    if(array_key_exists('is_backfill',$data)&&$data['is_backfill']!==false)api_fail(400,'Không gửi bù trạng thái live');
    $boot=strtolower(api_text($data,'boot_id',32,'/^[a-fA-F0-9]{32}$/D'));
    $seq=api_int($data,'state_sequence',null,1,9007199254740991);
    $state=array();foreach(array('pump_in','pump_out','temperature_enabled','water_enabled') as $field)$state[$field]=api_bool($data,$field);
    $ssid=isset($data['wifi_ssid'])?api_text($data,'wifi_ssid',32):null;
    if($ssid!==null&&strlen($ssid)>32)api_fail(400,'SSID tối đa 32 byte');
    $ip=isset($data['lan_ip'])?api_text($data,'lan_ip',45):null;
    if($ip!==null&&!filter_var($ip,FILTER_VALIDATE_IP))api_fail(400,'IP không hợp lệ');
    $reported=api_time($data,'reported_at_ms');
    $configRevision=isset($data['reported_config_revision'])?api_int($data,'reported_config_revision'):null;
    $desired=api_query($db,'SELECT revision FROM device_configs WHERE device_id=?','s',array($device))[0];
    if($configRevision!==null&&$configRevision>(int)$desired['revision'])api_fail(400,'Revision báo cáo lớn hơn cấu hình server');
    $apply=$data['config_apply']??null;
    if($apply!==null&&!is_array($apply))api_fail(400,'config_apply không hợp lệ');
    if($apply!==null&&($apply['result']??null)==='applied'&&($apply['revision']??null)!==$configRevision)api_fail(400,'Revision applied phải khớp reported_config_revision');
    $digestFields=array($boot,$seq,$state,$ssid,$ip,$reported,$configRevision);
    if($apply!==null)$digestFields[]=$apply; // Preserve pre-005 digests when the optional report is absent.
    $digest=hash('sha256',api_json($digestFields));
    $old=api_query($db,'SELECT * FROM device_status WHERE device_id=? FOR UPDATE','s',array($device));
    $known=api_query($db,'SELECT boot_id FROM device_boots WHERE device_id=? AND boot_id=?','ss',array($device,$boot));
    if($old) {
        if($old[0]['boot_id']===$boot) {
            if($seq<(int)$old[0]['state_sequence'])api_fail(409,'Trạng thái cũ');
            if($seq===(int)$old[0]['state_sequence']) {
                if(!hash_equals($old[0]['payload_hash'],$digest))api_fail(409,'Sequence trùng nhưng nội dung khác');
                return array('duplicate'=>true,'state'=>api_public($old[0]));
            }
        } elseif($known)api_fail(409,'Không khôi phục trạng thái từ phiên boot cũ');
    }
    if(!$known)api_query($db,'INSERT INTO device_boots(device_id,boot_id) VALUES (?,?)','ss',array($device,$boot));
    if($apply!==null)api_config_report($db,$device,$boot,$apply);
    // Old clients omitting reported revision must not erase the last known revision in the same boot.
    if($configRevision===null&&$old&&$old[0]['boot_id']===$boot)$configRevision=$old[0]['reported_config_revision'];
    api_query($db,'INSERT INTO device_status(device_id,boot_id,state_sequence,pump_in,pump_out,temperature_enabled,water_enabled,wifi_ssid,lan_ip,reported_config_revision,reported_at_ms,payload_hash) '
        . 'VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE boot_id=VALUES(boot_id),state_sequence=VALUES(state_sequence),pump_in=VALUES(pump_in),pump_out=VALUES(pump_out),'
        . 'temperature_enabled=VALUES(temperature_enabled),water_enabled=VALUES(water_enabled),wifi_ssid=VALUES(wifi_ssid),lan_ip=VALUES(lan_ip),'
        . 'reported_config_revision=VALUES(reported_config_revision),reported_at_ms=VALUES(reported_at_ms),payload_hash=VALUES(payload_hash),last_seen_at=UTC_TIMESTAMP(3)',
        'ssiiiiissiis',array($device,$boot,$seq,$state['pump_in'],$state['pump_out'],$state['temperature_enabled'],$state['water_enabled'],$ssid,$ip,$configRevision,$reported,$digest));
    $relayChanged=!$old||((int)$old[0]['pump_in']!==$state['pump_in'])||((int)$old[0]['pump_out']!==$state['pump_out']);
    if($relayChanged)api_log($db,array('username'=>$device),$device,'relay_state_reported',array('pump_in'=>(bool)$state['pump_in'],'pump_out'=>(bool)$state['pump_out']), 'device','reported');
    return array('duplicate'=>false,'state'=>api_public(api_query($db,'SELECT * FROM device_status WHERE device_id=?','s',array($device))[0]));
}

function api_activity($db,$device,$data,$admin=false) {
    $limit=api_int($data,'limit',100,1,500);$before=api_int($data,'before_id',PHP_INT_MAX);
    $fields='id,event_id,device_id,actor_user_id,actor_username,source,action,result,request_id,occurred_at_ms,received_at,changes_json';
    $rows=$admin?api_query($db,'SELECT '.$fields.' FROM activity_logs WHERE source=\'backend\' AND id<? ORDER BY id DESC LIMIT ?','ii',array($before,$limit+1))
        :api_query($db,'SELECT '.$fields.' FROM activity_logs WHERE device_id=? AND id<? ORDER BY id DESC LIMIT ?','sii',array($device,$before,$limit+1));
    $more=count($rows)>$limit;if($more)array_pop($rows);
    $next=$more?(int)end($rows)['id']:null;
    foreach($rows as &$row){$row=api_public($row);$row['changes']=$row['changes_json']===null?null:json_decode($row['changes_json'],true);unset($row['changes_json']);
        if($row['occurred_at_ms']!==null)$row['occurred_at_ms']=(int)$row['occurred_at_ms'];}unset($row);
    return array('events'=>$rows,'next_before_id'=>$next);
}

function api_record_activity($db,$user,$device,$data) {
    $allowed=array('pump_requested'=>'control','pump_confirmed'=>'control','pump_failed'=>'control',
        'wifi_provisioning_requested'=>'configure','wifi_provisioning_confirmed'=>'configure','wifi_provisioning_failed'=>'configure');
    $eventAction=api_text($data,'event_action',64);
    if(!isset($allowed[$eventAction]))api_fail(400,'Loại nhật ký client không hợp lệ');
    api_access($db,$user,$device,$allowed[$eventAction]);
    $event=api_text($data,'event_id',36,'/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/D');
    $request=api_text($data,'request_id',36,'/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/D');
    $time=api_time($data,'occurred_at_ms');
    $old=api_query($db,'SELECT * FROM activity_logs WHERE event_id=? FOR UPDATE','s',array($event));
    if($old) {
        $r=$old[0];
        if($r['device_id']!==$device||(int)$r['actor_user_id']!==(int)$user['id']||$r['source']!=='client'||$r['action']!==$eventAction
            ||$r['request_id']!==$request||($r['occurred_at_ms']===null?null:(int)$r['occurred_at_ms'])!==$time)api_fail(409,'event_id trùng nhưng nội dung khác');
        return array('duplicate'=>true);
    }
    api_query($db,"INSERT INTO activity_logs(event_id,device_id,actor_user_id,actor_username,source,action,result,request_id,occurred_at_ms) VALUES (?,?,?,?,'client',?,'client_reported',?,?)",
        'ssisssi',array($event,$device,$user['id'],$user['username'],$eventAction,$request,$time));
    return array('duplicate'=>false);
}

function api_command($db,$user,$device,$action,$data) {
    if($action==='request_control') {
        $request=api_text($data,'request_id',36,'/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/D');
        $target=api_text($data,'target',8);
        if(!in_array($target,array('pump_in','pump_out'),true))api_fail(400,'Target bơm không hợp lệ');
        $on=api_bool($data,'enabled');$ttl=api_int($data,'ttl_seconds',30,1,60);
        api_expire_pending($db,$device);
        $old=api_query($db,'SELECT * FROM device_commands WHERE device_id=? AND request_id=? FOR UPDATE','ss',array($device,$request));
        if($old) {
            if((int)$old[0]['requested_by']!==(int)$user['id']||$old[0]['target']!==$target||(int)$old[0]['enabled']!==$on)api_fail(409,'request_id xung đột');
            return array('command'=>api_public($old[0]),'duplicate'=>true,'delivery'=>'not_published_by_api');
        }
        $pending=api_query($db,"SELECT command_id FROM device_commands WHERE device_id=? AND target=? AND status='pending' FOR UPDATE",'ss',array($device,$target));
        if($pending)api_fail(409,'Bơm đang có lệnh chờ xác nhận');
        $id=api_uuid();
        api_query($db,'INSERT INTO device_commands(command_id,device_id,request_id,requested_by,target,enabled,expires_at) '
            . 'VALUES (?,?,?,?,?,?,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)))','sssisii',array($id,$device,$request,$user['id'],$target,$on,$ttl));
        api_log($db,$user,$device,'control_requested',array('command_id'=>$id,'request_id'=>$request,'target'=>$target,'enabled'=>(bool)$on));
        return array('command'=>api_public(api_query($db,'SELECT * FROM device_commands WHERE command_id=?','s',array($id))[0]),
            'duplicate'=>false,'delivery'=>'not_published_by_api');
    }
    $id=api_text($data,'command_id',36,'/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/D');
    $rows=api_query($db,'SELECT * FROM device_commands WHERE command_id=? AND device_id=?','ss',array($id,$device));
    if(!$rows)api_fail(404,'Không tìm thấy lệnh của thiết bị');
    api_expire_pending($db,$device);
    $row=api_query($db,'SELECT * FROM device_commands WHERE command_id=? AND device_id=?','ss',array($id,$device))[0];
    $row['effective_status']=$row['status']==='pending'&&strtotime($row['expires_at'].' UTC')<microtime(true)?'expired':$row['status'];
    return array('command'=>api_public($row));
}
function api_command_ack($db,$device,$data) {
    $id=api_text($data,'command_id',36,'/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/D');
    $result=api_text($data,'result',8);
    if(!in_array($result,array('applied','rejected'),true))api_fail(400,'Kết quả ACK không hợp lệ');
    $rows=api_query($db,'SELECT *,expires_at<=UTC_TIMESTAMP(3) AS has_expired FROM device_commands WHERE device_id=? AND command_id=? FOR UPDATE','ss',array($device,$id));
    if(!$rows)api_fail(404,'Lệnh không thuộc thiết bị');
    $row=$rows[0];
    if($row['status']===$result)return array('duplicate'=>true);
    if($row['status']!=='pending')api_fail(409,'Lệnh đã kết thúc');
    if((int)$row['has_expired']) {
        api_runtime_transition($db,$row,'expired','backend','ttl_elapsed');
        return array('duplicate'=>false,'accepted'=>false,'command_id'=>$id,'result'=>'expired');
    }
    $requester=api_query($db,'SELECT role,is_active FROM users WHERE id=? FOR UPDATE','i',array($row['requested_by']));
    $allowed=$requester&&(bool)$requester[0]['is_active'];
    if($allowed&&$requester[0]['role']!=='admin') {
        $grants=api_query($db,'SELECT can_view,can_control FROM device_users WHERE device_id=? AND user_id=? FOR UPDATE','si',array($device,$row['requested_by']));
        $allowed=$grants&&(bool)$grants[0]['can_view']&&(bool)$grants[0]['can_control'];
    }
    if(!$allowed) {
        api_runtime_transition($db,$row,'cancelled','backend','access_revoked');
        return array('duplicate'=>false,'accepted'=>false,'command_id'=>$id,'result'=>'cancelled');
    }
    api_runtime_transition($db,$row,$result,'http');
    return array('duplicate'=>false,'command_id'=>$id,'result'=>$result);
}
