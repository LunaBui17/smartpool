<?php
// Whitelisted schema names only; cursor/chunking avoids loading a complete history into PHP RAM.
function api_archive_device_rows($db,$archive,$device,$table,$pk,$commandChild=false) {
    $after=in_array($pk,array('id','user_id'),true)?'0':'';$count=0;
    do {
        $from='`'.$table.'` t';
        $where='t.device_id=?';
        if($commandChild) {
            $from.=' JOIN device_commands c ON c.command_id=t.command_id';$where='c.device_id=?';
        }
        $rows=api_query($db,'SELECT t.* FROM '.$from.' WHERE '.$where.' AND t.`'.$pk.'`>? ORDER BY t.`'.$pk.'` LIMIT ? FOR UPDATE',
            'ssi',array($device,$after,500));
        foreach($rows as $row) {
            $after=(string)$row[$pk];
            api_query($db,'INSERT INTO device_archive_records(archive_id,source_table,row_key,record_json) VALUES (?,?,?,?)',
                'isss',array($archive,$table,$after,api_json($row)));
            $count++;
        }
    } while(count($rows)===500);
    return $count;
}
function api_finalize_delete_device($db,$user,$device) {
    api_device($db,$device); // Holds the device lock until the entire archive/delete commits.
    $hardware=api_hardware($db,$device);
    api_cancel_pending($db,$device,null,'device_deleted');
    api_query($db,'INSERT INTO device_archives(original_device_id,factory_mac,deleted_by) VALUES (?,?,?)',
        'ssi',array($device,$hardware['factory_mac']??null,$user['id']));
    $archive=(int)$db->insert_id;$count=0;
    $tables=array('devices'=>'device_id','device_users'=>'user_id','device_credentials'=>'id',
        'device_configs'=>'device_id','device_boots'=>'boot_id','device_status'=>'device_id',
        'sensor_samples'=>'id','device_commands'=>'command_id','activity_logs'=>'id',
        'device_mqtt_presence'=>'device_id','config_apply_reports'=>'id',
        'device_hardware'=>'device_id','hardware_inventory'=>'factory_mac');
    foreach($tables as $table=>$pk)$count+=api_archive_device_rows($db,$archive,$device,$table,$pk);
    foreach(array('command_results','command_dispatch') as $table)$count+=api_archive_device_rows($db,$archive,$device,$table,'command_id',true);
    foreach(array('command_results','command_dispatch') as $table)
        api_query($db,'DELETE t FROM `'.$table.'` t JOIN device_commands c ON c.command_id=t.command_id WHERE c.device_id=?','s',array($device));
    foreach(array('device_status','device_boots','sensor_samples','device_configs','device_users','device_credentials',
        'device_mqtt_presence','config_apply_reports','device_hardware','device_commands','activity_logs') as $table)
        api_query($db,'DELETE FROM `'.$table.'` WHERE device_id=?','s',array($device));
    // Keep the physical MAC, declaration and serial; clear the active assignment.
    api_query($db,'UPDATE hardware_inventory SET device_id=NULL,binding_source=NULL,bound_by=NULL,bound_at=NULL,last_verified_at=NULL WHERE device_id=?','s',array($device));
    api_query($db,'DELETE FROM devices WHERE device_id=?','s',array($device));
    api_query($db,'UPDATE device_archives SET record_count=? WHERE id=?','ii',array($count,$archive));
    api_log($db,$user,null,'device_deleted',array('original_device_id'=>$device,'factory_mac'=>$hardware['factory_mac']??null,'archive_id'=>$archive,'record_count'=>$count));
    return array('deleted'=>true,'device_id'=>$device,'archive_id'=>$archive,
        'factory_mac'=>$hardware['factory_mac']??null,'hardware_available'=>$hardware!==null);
}
function api_list_device_archives($db,$user,$data) {
    api_admin($user);$limit=api_int($data,'limit',50,1,100);$before=api_int($data,'before_id',PHP_INT_MAX);
    $rows=api_query($db,'SELECT id,original_device_id,factory_mac,deleted_by,deleted_at,record_count FROM device_archives WHERE id<? ORDER BY id DESC LIMIT ?',
        'ii',array($before,$limit+1));
    $more=count($rows)>$limit;if($more)array_pop($rows);
    foreach($rows as &$row){$row['id']=(int)$row['id'];$row['record_count']=(int)$row['record_count'];if($row['deleted_by']!==null)$row['deleted_by']=(int)$row['deleted_by'];}unset($row);
    // Do not expose archived credential hashes or complete raw snapshots through the app API.
    return array('archives'=>$rows,'next_before_id'=>$more?end($rows)['id']:null);
}
