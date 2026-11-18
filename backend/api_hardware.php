<?php
function api_factory_mac($data) {
    $mac=strtoupper(api_text($data,'factory_mac',17,'/^[a-fA-F0-9]{2}(:[a-fA-F0-9]{2}){5}$/D'));
    if($mac==='00:00:00:00:00:00'||(hexdec(substr($mac,0,2))&1))api_fail(400,'MAC gốc phải là địa chỉ unicast hợp lệ');
    return $mac;
}
function api_require_shared_key($db,$key) {
    if(!is_string($key)||!preg_match('/^[a-f0-9]{64}$/D',$key))api_fail(401,'Khóa HTTP dùng chung không hợp lệ');
    $rows=api_query($db,'SELECT id FROM esp_shared_credentials WHERE key_hash=? AND revoked_at IS NULL LOCK IN SHARE MODE','s',array(hash('sha256',$key)));
    if(!$rows)api_fail(401,'Khóa HTTP dùng chung không hợp lệ hoặc đã thu hồi');
}
function api_admin_shared_key($db,$user,$data) {
    api_admin($user);
    $key=api_text($data,'shared_device_key',64,'/^[a-f0-9]{64}$/D');
    api_require_shared_key($db,$key);
}
function api_hardware($db,$device) {
    $rows=api_query($db,'SELECT factory_mac,binding_source,bound_by,bound_at,last_verified_at FROM hardware_inventory WHERE device_id=?','s',array($device));
    if(!$rows)return null;
    $row=$rows[0];$row['factory_serial']='SP-'.str_replace(':','',$row['factory_mac']);
    if($row['bound_by']!==null)$row['bound_by']=(int)$row['bound_by'];
    return $row;
}
// Caller owns the devices row lock. Never rebind implicitly or overwrite an ID/MAC pair.
function api_bind_hardware($db,$device,$mac,$actor=null) {
    if($actor===null)api_fail(403,'Chỉ admin được liên kết thiết bị với MAC');
    api_admin($actor);
    $rows=api_query($db,'SELECT factory_mac FROM hardware_inventory WHERE device_id=? FOR UPDATE','s',array($device));
    if($rows) {
        if($rows[0]['factory_mac']!==$mac)api_fail(409,'Device ID đã gắn với MAC gốc khác');
        return false;
    }
    $inventory=api_query($db,'SELECT device_id FROM hardware_inventory WHERE factory_mac=? FOR UPDATE','s',array($mac));
    if($inventory&&$inventory[0]['device_id']!==null)api_fail(409,'MAC gốc đã đăng ký cho Device ID khác');
    if($inventory) {
        api_query($db,"UPDATE hardware_inventory SET device_id=?,binding_source='admin',bound_by=?,bound_at=UTC_TIMESTAMP(3),last_verified_at=NULL WHERE factory_mac=?",
            'sis',array($device,$actor['id'],$mac));
    } else {
        api_query($db,"INSERT INTO hardware_inventory(factory_mac,device_id,declared_by,binding_source,bound_by,bound_at) VALUES (?,?,?,'admin',?,UTC_TIMESTAMP(3))",
            'ssii',array($mac,$device,$actor['id'],$actor['id']));
    }
    api_log($db,$actor,$device,'device_hardware_bound',
        array('factory_mac'=>$mac,'factory_serial'=>'SP-'.str_replace(':','',$mac),'binding_source'=>'admin'));
    return true;
}
function api_shared_credentials($db,$user,$action,$data) {
    api_admin($user);
    if($action==='create_shared_device_credential') {
        $label=api_text($data,'label',64);$key=bin2hex(random_bytes(32));
        api_query($db,'INSERT INTO esp_shared_credentials(key_hash,label,created_by) VALUES (?,?,?)','ssi',array(hash('sha256',$key),$label,$user['id']));
        $id=(int)$db->insert_id;
        api_log($db,$user,null,'shared_device_credential_created',array('credential_id'=>$id,'label'=>$label));
        return array('credential_id'=>$id,'shared_device_key'=>$key,'scope'=>'all_devices');
    }
    if($action==='list_shared_device_credentials')return array('scope'=>'all_devices','credentials'=>array_map('api_public',
        api_query($db,'SELECT id,label,created_by,created_at,revoked_at FROM esp_shared_credentials ORDER BY id DESC')));
    $id=api_int($data,'credential_id');
    $rows=api_query($db,'SELECT revoked_at FROM esp_shared_credentials WHERE id=? FOR UPDATE','i',array($id));
    if(!$rows)api_fail(404,'Không tìm thấy khóa HTTP dùng chung');
    if($rows[0]['revoked_at']===null) {
        api_query($db,'UPDATE esp_shared_credentials SET revoked_at=UTC_TIMESTAMP(3) WHERE id=?','i',array($id));
        api_log($db,$user,null,'shared_device_credential_revoked',array('credential_id'=>$id,'scope'=>'all_devices'));
    }
    return array('credential_id'=>$id,'revoked'=>true,'scope'=>'all_devices');
}


function api_inventory_row($row) {
    $row['available']=$row['device_id']===null;
    $row['status']=$row['available']||!(int)($row['device_active']??0)?'deactive':'active';
    $row['factory_serial']='SP-'.str_replace(':','',$row['factory_mac']);
    foreach(array('declared_by','bound_by') as $field)if($row[$field]!==null)$row[$field]=(int)$row[$field];
    unset($row['device_active']);return $row;
}
function api_inventory($db,$user,$action,$data) {
    api_admin($user);
    if($action==='declare_hardware') {
        $mac=api_factory_mac($data);
        $rows=api_query($db,'SELECT factory_mac FROM hardware_inventory WHERE factory_mac=? FOR UPDATE','s',array($mac));
        if(!$rows) {
            api_query($db,'INSERT INTO hardware_inventory(factory_mac,declared_by) VALUES (?,?)','si',array($mac,$user['id']));
            api_log($db,$user,null,'hardware_declared',array('factory_mac'=>$mac));
        }
        $row=api_query($db,'SELECT h.*,d.is_active AS device_active FROM hardware_inventory h LEFT JOIN devices d ON d.device_id=h.device_id WHERE h.factory_mac=?','s',array($mac))[0];
        return array('duplicate'=>(bool)$rows,'hardware'=>api_inventory_row($row));
    }
    $limit=api_int($data,'limit',50,1,100);
    $after=isset($data['after_mac'])?strtoupper(api_text($data,'after_mac',17,'/^[a-fA-F0-9]{2}(:[a-fA-F0-9]{2}){5}$/D')):'';
    $available=api_bool($data,'available_only',false);
    $rows=api_query($db,'SELECT h.*,d.is_active AS device_active FROM hardware_inventory h LEFT JOIN devices d ON d.device_id=h.device_id WHERE h.factory_mac>? '
        .($available?'AND h.device_id IS NULL ':'').'ORDER BY h.factory_mac LIMIT ?','si',array($after,$limit+1));
    $more=count($rows)>$limit;if($more)array_pop($rows);
    return array('hardware'=>array_map('api_inventory_row',$rows),'next_after_mac'=>$more?end($rows)['factory_mac']:null);
}
