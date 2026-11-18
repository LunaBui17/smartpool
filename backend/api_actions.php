<?php
require_once __DIR__ . '/api_core.php';

function api_devices($db,$user,$data) {
    $limit=api_int($data,'limit',50,1,100);
    $after=isset($data['after_device_id']) ? api_text($data,'after_device_id',64,'/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/D') : '';
    if ($user['role']==='admin') {
        $rows=api_query($db,'SELECT d.*,1 AS can_view,d.is_active AS can_control,d.is_active AS can_configure FROM devices d '
            . 'WHERE device_id>? ORDER BY device_id LIMIT ?','si',array($after,$limit+1));
    } else {
        $rows=api_query($db,'SELECT d.device_id,d.name,d.is_active,d.registered_at,m.can_view,m.can_control,m.can_configure FROM devices d '
            . 'JOIN device_users m ON m.device_id=d.device_id WHERE m.user_id=? AND m.can_view=1 AND d.is_active=1 '
            . 'AND d.device_id>? ORDER BY d.device_id LIMIT ?','isi',array($user['id'],$after,$limit+1));
    }
    $more=count($rows)>$limit; if($more) array_pop($rows);
    return array('devices'=>array_map('api_public',$rows),'next_after_device_id'=>$more ? end($rows)['device_id'] : null);
}

function api_validate_automation($rules) {
    if(!is_array($rules)||!array_is_list($rules)||count($rules)>2) api_fail(400,'automation phải là mảng tối đa 2 quy tắc');
    $channels=array(); $normalized=array();
    foreach($rules as $rule) {
        if(!is_array($rule)) api_fail(400,'Quy tắc tự động không hợp lệ');
        $channel=api_int($rule,'channel',null,1,2);
        if(in_array($channel,$channels,true)) api_fail(400,'Trùng channel tự động');
        $channels[]=$channel;
        $enabled=(bool)api_bool($rule,'enabled');
        $mode=api_text($rule,'stop_mode',9);
        if(!in_array($mode,array('duration','threshold'),true)) api_fail(400,'stop_mode không hợp lệ');
        $duration=api_int($rule,'duration_seconds',null,1,86400);
        $out=array('channel'=>$channel,'enabled'=>$enabled,'stop_mode'=>$mode,'duration_seconds'=>$duration);
        foreach(array('start_conditions','stop_conditions') as $group) {
            if(!isset($rule[$group])||!is_array($rule[$group])) api_fail(400,'Thiếu nhóm điều kiện');
            if(array_diff(array_keys($rule[$group]),array('water','temperature'))) api_fail(400,'Cảm biến điều kiện không hợp lệ');
            $out[$group]=array();
            foreach(array('water','temperature') as $sensor) {
                $condition=$rule[$group][$sensor]??array('enabled'=>false);
                if(!is_array($condition)) api_fail(400,'Điều kiện cảm biến không hợp lệ');
                $on=(bool)api_bool($condition,'enabled');
                if(!$on){$out[$group][$sensor]=array('enabled'=>false);continue;}
                $comparison=api_text($condition,'comparison',5);
                if(!in_array($comparison,array('below','above'),true)) api_fail(400,'comparison không hợp lệ');
                $threshold=api_number($condition['threshold']??null,$sensor==='water'?0:-55,$sensor==='water'?4095:125,'threshold');
                $out[$group][$sensor]=array('enabled'=>true,'comparison'=>$comparison,'threshold'=>$threshold);
            }
        }
        $starts=array_filter($out['start_conditions'],fn($x)=>$x['enabled']);
        $stops=array_filter($out['stop_conditions'],fn($x)=>$x['enabled']);
        if($enabled&&(!$starts||($mode==='threshold'&&!$stops))) api_fail(400,'Quy tắc bật/dừng chưa đầy đủ');
        if($mode==='threshold') foreach(array('water','temperature') as $sensor) {
            $a=$out['start_conditions'][$sensor];$b=$out['stop_conditions'][$sensor];
            if($a['enabled']&&$b['enabled']&&($a['comparison']===$b['comparison']
                ||($a['comparison']==='below'?$b['threshold']<=$a['threshold']:$b['threshold']>=$a['threshold']))) {
                api_fail(400,'Ngưỡng bật/dừng tự động xung đột');
            }
        }
        $normalized[]=$out;
    }
    usort($normalized,fn($a,$b)=>$a['channel']<=>$b['channel']);
    return $normalized;
}

function api_history($db,$device,$data) {
    $basis=$data['time_basis']??'received';
    if(!in_array($basis,array('received','measured'),true)) api_fail(400,'time_basis không hợp lệ');
    $limit=api_int($data,'limit',100,1,500);
    $from=api_int($data,'from_ms',0,0,4102444799999);$to=api_int($data,'to_ms',4102444799999,0,4102444799999);
    if($from>$to) api_fail(400,'Khoảng thời gian không hợp lệ');
    // Column is chosen exclusively from this internal allowlist.
    $time=$basis==='measured'?'measured_at_ms':'CAST(UNIX_TIMESTAMP(received_at)*1000 AS UNSIGNED)';
    $column=$basis==='measured'?'measured_at_ms':'received_at';
    $bound=$basis==='measured'?'?':'FROM_UNIXTIME(?/1000.0)';
    $args=array($device,$from,$to);$types='sii';
    $where='device_id=? AND '.$column.'>='.$bound.' AND '.$column.'<='.$bound;
    $cursor=$data['cursor']??null;
    $watermark=null;
    if($cursor!==null) {
        if(!is_string($cursor)||strlen($cursor)>2048) api_fail(400,'Cursor không hợp lệ');
        $decoded=base64_decode($cursor,true);
        $c=$decoded===false?null:json_decode($decoded,true);
        if(!is_array($c)||($c['device_id']??null)!==$device||($c['time_basis']??null)!==$basis
            ||($c['from_ms']??null)!==$from||($c['to_ms']??null)!==$to) api_fail(400,'Cursor khác truy vấn');
        $lastTime=api_int($c,'time_ms',null,0);$lastId=api_int($c,'id');$watermark=api_int($c,'watermark');
        $where.=' AND ('.$column.'<'.$bound.' OR ('.$column.'='.$bound.' AND id<?))';
        array_push($args,$lastTime,$lastTime,$lastId);$types.='iii';
    } else {
        $row=api_query($db,'SELECT COALESCE(MAX(id),0) AS watermark FROM sensor_samples WHERE device_id=?','s',array($device))[0];
        $watermark=(int)$row['watermark'];
    }
    $where.=' AND id<=?';$args[]=$watermark;$types.='i';
    $args[]=$limit+1;$types.='i';
    $rows=api_query($db,'SELECT id,device_id,sample_id,temperature,water_adc,measured_at_ms,time_quality,received_at,'
        . 'CAST(UNIX_TIMESTAMP(received_at)*1000 AS UNSIGNED) AS received_at_ms,is_backfill,'.$time.' AS page_time '
        . 'FROM sensor_samples WHERE '.$where.' ORDER BY '.$column.' DESC,id DESC LIMIT ?',$types,$args);
    $more=count($rows)>$limit;if($more)array_pop($rows);
    $next=null;
    if($more){$last=end($rows);$next=base64_encode(api_json(array('device_id'=>$device,'time_basis'=>$basis,'from_ms'=>$from,'to_ms'=>$to,
        'time_ms'=>(int)$last['page_time'],'id'=>(int)$last['id'],'watermark'=>$watermark)));}
    foreach($rows as &$row)unset($row['page_time']);unset($row);
    return array('device_id'=>$device,'time_basis'=>$basis,'order'=>'desc','samples'=>array_map('api_public',$rows),'next_cursor'=>$next);
}

function api_dispatch_action($db,$data) {
    $action=api_text($data,'action',64,'/^[a-z_]+$/D');
    if($action==='login') {
        $username=api_text($data,'username',64,'/^[A-Za-z0-9._-]{3,64}$/D');
        $password=$data['password']??null;
        if(!is_string($password)||strlen($password)>72||$password===''||str_contains($password,"\0"))api_fail(400,'Mật khẩu không hợp lệ');
        $rows=api_query($db,'SELECT id,username,password_hash,role,is_active,full_name FROM users WHERE username=? FOR UPDATE','s',array($username));
        if(!$rows||!(int)$rows[0]['is_active']||!password_verify($password,$rows[0]['password_hash']))api_fail(401,'Thông tin đăng nhập không đúng');
        $user=$rows[0];$remember=api_bool($data,'remember_me',false);$seconds=$remember?2592000:43200;
        $token=bin2hex(random_bytes(32));
        api_query($db,'INSERT INTO user_sessions(user_id,token_hash,expires_at) VALUES (?,?,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)))',
            'isi',array($user['id'],hash('sha256',$token),$seconds));
        return array_merge(array('token'=>$token,'expires_in'=>$seconds,'user'=>api_public($user)),api_devices($db,$user,array()));
    }
    $espActions=array('device_identity','upload_sample','report_status','get_device_config','acknowledge_command');
    if(in_array($action,$espActions,true)) {
        $device=api_id($data);api_esp($db,$device);
        return api_esp_action($db,$device,$action,$data);
    }
    $user=api_user($db);
    if($action==='logout') {
        $auth=$_SERVER['HTTP_AUTHORIZATION'];$token=substr($auth,7);
        api_query($db,'DELETE FROM user_sessions WHERE token_hash=?','s',array(hash('sha256',$token)));
        return array();
    }
    if($action==='list_devices')return api_devices($db,$user,$data);
    if(in_array($action,array('list_users','create_user','set_user_active','set_user_password','register_device','list_admin_audit'),true)) {
        api_admin($user);return api_admin_action($db,$user,$action,$data);
    }
    $device=api_id($data);
    if(in_array($action,array('set_device_active','set_device_owner','set_device_member','remove_device_member','list_device_members',
        'create_device_credential','revoke_device_credential','list_device_credentials'),true)) {
        api_admin($user);api_device($db,$device);return api_registry_action($db,$user,$device,$action,$data);
    }
    api_access($db,$user,$device,$action==='update_config'?'configure':($action==='request_control'?'control':'view'));
    if($action==='request_control'||$action==='get_command')return api_command($db,$user,$device,$action,$data);
    if($action==='get_device') {
        $row=api_device($db,$device);
        $grant=$user['role']==='admin'?array('can_view'=>1,'can_control'=>$row['is_active'],'can_configure'=>$row['is_active'])
            :api_query($db,'SELECT can_view,can_control,can_configure FROM device_users WHERE device_id=? AND user_id=?','si',array($device,$user['id']))[0];
        if($user['role']!=='admin')$row=array_intersect_key($row,array_flip(array('device_id','name','is_active','registered_at')));
        return array('device'=>api_public(array_merge($row,$grant)));
    }
    if($action==='get_history')return api_history($db,$device,$data);
    if($action==='get_status') {
        $rows=api_query($db,'SELECT *,CAST(UNIX_TIMESTAMP(last_seen_at)*1000 AS UNSIGNED) AS last_seen_at_ms FROM device_status WHERE device_id=?','s',array($device));
        $status=$rows?api_public($rows[0]):null;
        if($status){$status['last_seen_at_ms']=(int)$status['last_seen_at_ms'];$status['is_recent_report']=((int)floor(microtime(true)*1000)-$status['last_seen_at_ms'])<=120000;}
        $measured=api_query($db,'SELECT * FROM sensor_samples WHERE device_id=? AND measured_at_ms IS NOT NULL ORDER BY measured_at_ms DESC,id DESC LIMIT 1','s',array($device));
        $received=api_query($db,'SELECT * FROM sensor_samples WHERE device_id=? ORDER BY received_at DESC,id DESC LIMIT 1','s',array($device));
        $revision=(int)api_query($db,'SELECT revision FROM device_configs WHERE device_id=?','s',array($device))[0]['revision'];
        $readiness=api_readiness($db,$user,api_device($db,$device),$status);
        $apply=api_query($db,'SELECT boot_id,attempt_sequence,revision,result,error_code,received_at FROM config_apply_reports WHERE device_id=? ORDER BY id DESC LIMIT 1','s',array($device));
        return array('device_id'=>$device,'state'=>$status,'remote_control_ready'=>$readiness['ready'],'readiness'=>$readiness,
            'config_apply'=>$apply?api_public($apply[0]):null,
            'latest_measured_sample'=>$measured?api_public($measured[0]):null,
            'last_received_sample'=>$received?api_public($received[0]):null,'desired_config_revision'=>$revision,
            'config_revision_reported_matching'=>$status!==null&&$status['reported_config_revision']!==null&&$status['reported_config_revision']===$revision);
    }
    if($action==='get_config')return array('config'=>api_config(api_query($db,'SELECT * FROM device_configs WHERE device_id=?','s',array($device))[0]));
    if($action==='update_config')return api_update_config($db,$user,$device,$data);
    if($action==='list_activity')return api_activity($db,$device,$data);
    if($action==='record_activity')return api_record_activity($db,$user,$device,$data);
    api_fail(400,'Action không được hỗ trợ trong API v2');
}

function api_admin_action($db,$user,$action,$data) {
    if($action==='register_device') {
        $device=api_id($data);$name=api_text($data,'name',128);$fullName=api_text($data,'student_full_name',128);
        $student=api_text($data,'student_code',32,'/^[A-Za-z0-9._-]+$/D');
        $owner=api_int($data,'owner_user_id',(int)$user['id']);api_active_user($db,$owner);
        $source=$data['registration_source']??'manual';
        if(!in_array($source,array('manual','ble'),true))api_fail(400,'Nguồn đăng ký không hợp lệ');
        api_query($db,'INSERT INTO devices(device_id,name,student_full_name,student_code,owner_user_id,registered_by,registration_source) '
            . 'VALUES (?,?,?,?,?,?,?)','ssssiis',array($device,$name,$fullName,$student,$owner,$user['id'],$source));
        api_query($db,'INSERT INTO device_configs(device_id,updated_by) VALUES (?,?)','si',array($device,$user['id']));
        api_log($db,$user,$device,'device_registered',array('name'=>$name,'owner_user_id'=>$owner,'registration_source'=>$source));
        return array('device'=>api_public(api_device($db,$device)));
    }
    if($action==='list_users') {
        $limit=api_int($data,'limit',50,1,100);$after=api_int($data,'after_id',0,0);
        $rows=api_query($db,'SELECT id,username,full_name,student_code,role,is_active,created_at FROM users WHERE id>? ORDER BY id LIMIT ?',
            'ii',array($after,$limit+1));$more=count($rows)>$limit;if($more)array_pop($rows);
        return array('users'=>array_map('api_public',$rows),'next_after_id'=>$more?(int)end($rows)['id']:null);
    }
    if($action==='list_admin_audit')return api_activity($db,null,$data,true);
    if($action==='create_user') {
        $username=api_text($data,'username',64,'/^[A-Za-z0-9._-]{3,64}$/D');$fullName=api_text($data,'full_name',128);
        $student=isset($data['student_code'])?api_text($data,'student_code',32,'/^[A-Za-z0-9._-]+$/D'):null;
        $role=$data['role']??'user';if(!in_array($role,array('admin','user'),true))api_fail(400,'Role phải là admin hoặc user');
        $hash=api_password_hash($data,$role);
        api_query($db,'INSERT INTO users(username,password_hash,full_name,student_code,role) VALUES (?,?,?,?,?)','sssss',array($username,$hash,$fullName,$student,$role));
        $id=(int)$db->insert_id;
        api_log($db,$user,null,'user_created',array('user_id'=>$id,'username'=>$username,'role'=>$role));
        return array('user_id'=>$id);
    }
    $target=api_int($data,'user_id');
    // Lock the active admin set before the target to serialize last-admin decisions.
    $admins=api_query($db,"SELECT id FROM users WHERE role='admin' AND is_active=1 ORDER BY id FOR UPDATE");
    $rows=api_query($db,'SELECT id,username,role,is_active FROM users WHERE id=? FOR UPDATE','i',array($target));
    if(!$rows)api_fail(404,'Không tìm thấy tài khoản');
    if($action==='set_user_active') {
        $active=api_bool($data,'is_active');
        if(!$active&&$target===(int)$user['id'])api_fail(409,'Không tự khóa tài khoản đang dùng');
        if(!$active&&$rows[0]['role']==='admin'&&(int)$rows[0]['is_active']&&count($admins)<=1)api_fail(409,'Không khóa admin cuối cùng');
        api_query($db,'UPDATE users SET is_active=? WHERE id=?','ii',array($active,$target));
        if(!$active) {
            api_query($db,'DELETE FROM user_sessions WHERE user_id=?','i',array($target));
            api_cancel_pending($db,null,$target,'account_locked');
        }
        api_log($db,$user,null,'user_active_changed',array('user_id'=>$target,'is_active'=>(bool)$active));
    } else {
        $hash=api_password_hash($data,$rows[0]['role']);api_query($db,'UPDATE users SET password_hash=? WHERE id=?','si',array($hash,$target));
        api_query($db,'DELETE FROM user_sessions WHERE user_id=?','i',array($target));
        api_cancel_pending($db,null,$target,'password_changed');
        api_log($db,$user,null,'user_password_changed',array('user_id'=>$target));
    }
    return array('user_id'=>$target);
}
function api_password_hash($data,$role='user') {
    $password=$data['password']??null;
    $minimum=7;
    if(!is_string($password)||strlen($password)<$minimum||strlen($password)>72||str_contains($password,"\0"))api_fail(400,'Mật khẩu không đủ độ dài theo role');
    return password_hash($password,PASSWORD_DEFAULT);
}

function api_registry_action($db,$user,$device,$action,$data) {
    $row=api_device($db,$device);
    if($action==='list_device_members') {
        $rows=api_query($db,'SELECT m.*,u.username,u.full_name,u.is_active AS account_active FROM device_users m JOIN users u ON u.id=m.user_id '
            . 'WHERE m.device_id=? ORDER BY m.user_id','s',array($device));
        foreach($rows as &$r)$r['account_active']=(bool)$r['account_active'];unset($r);
        return array('device_id'=>$device,'members'=>array_map('api_public',$rows));
    }
    if($action==='list_device_credentials')return array('credentials'=>array_map('api_public',api_query($db,
        'SELECT id,label,created_at,created_by,revoked_at FROM device_credentials WHERE device_id=? ORDER BY id DESC','s',array($device))));
    $changes=array();$extra=array();
    if($action==='set_device_active') {
        $active=api_bool($data,'is_active');api_query($db,'UPDATE devices SET is_active=? WHERE device_id=?','is',array($active,$device));
        if(!$active)api_cancel_pending($db,$device,null,'device_inactive');
        $changes=array('before'=>(bool)$row['is_active'],'is_active'=>(bool)$active);
    } elseif($action==='set_device_owner') {
        $owner=api_int($data,'owner_user_id');api_active_user($db,$owner);
        api_query($db,'UPDATE devices SET owner_user_id=? WHERE device_id=?','is',array($owner,$device));
        $changes=array('before'=>$row['owner_user_id'],'owner_user_id'=>$owner);
    } elseif($action==='set_device_member') {
        $target=api_int($data,'user_id');$targetUser=api_active_user($db,$target);
        if($targetUser['role']==='admin')api_fail(400,'Admin đã có quyền toàn hệ thống; chỉ cấp membership cho user');
        $view=api_bool($data,'can_view');$control=api_bool($data,'can_control');$configure=api_bool($data,'can_configure');
        if(!$view)api_fail(400,'Membership cần can_view=true; dùng remove_device_member để gỡ');
        $old=api_query($db,'SELECT can_view,can_control,can_configure FROM device_users WHERE device_id=? AND user_id=?','si',array($device,$target));
        api_query($db,'INSERT INTO device_users(device_id,user_id,can_view,can_control,can_configure,granted_by) VALUES (?,?,?,?,?,?) '
            . 'ON DUPLICATE KEY UPDATE can_view=VALUES(can_view),can_control=VALUES(can_control),can_configure=VALUES(can_configure),granted_by=VALUES(granted_by)',
            'siiiii',array($device,$target,$view,$control,$configure,$user['id']));
        if(!$control)api_cancel_pending($db,$device,$target,'control_revoked');
        $changes=array('user_id'=>$target,'before'=>$old?$old[0]:null,'can_view'=>true,'can_control'=>(bool)$control,'can_configure'=>(bool)$configure);
    } elseif($action==='remove_device_member') {
        $target=api_int($data,'user_id');
        api_query($db,'DELETE FROM device_users WHERE device_id=? AND user_id=?','si',array($device,$target));
        api_cancel_pending($db,$device,$target,'membership_removed');
        $changes=array('user_id'=>$target,'removed'=>true);
    } elseif($action==='create_device_credential') {
        if(!(int)$row['is_active'])api_fail(409,'Kích hoạt thiết bị trước');
        $label=api_text($data,'label',64);$key=bin2hex(random_bytes(32));
        api_query($db,'INSERT INTO device_credentials(device_id,key_hash,label,created_by) VALUES (?,?,?,?)','sssi',array($device,hash('sha256',$key),$label,$user['id']));
        $id=(int)$db->insert_id;$changes=array('credential_id'=>$id,'label'=>$label);$extra=array('credential_id'=>$id,'device_key'=>$key);
    } elseif($action==='revoke_device_credential') {
        $id=api_int($data,'credential_id');
        $keys=api_query($db,'SELECT id FROM device_credentials WHERE id=? AND device_id=? FOR UPDATE','is',array($id,$device));
        if(!$keys)api_fail(404,'Credential không thuộc thiết bị');
        api_query($db,'UPDATE device_credentials SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)) WHERE id=?','i',array($id));
        $changes=array('credential_id'=>$id,'revoked'=>true);
    }
    api_log($db,$user,$device,$action,$changes);
    return array_merge(array('device'=>api_public(api_device($db,$device))),$extra);
}
