<?php
class PoolApiError extends RuntimeException {
    public $status;
    public function __construct($status, $message) { parent::__construct($message); $this->status = $status; }
}
function api_fail($status, $message) { throw new PoolApiError($status, $message); }
function api_query($db, $sql, $types = '', $params = array()) {
    $stmt = $db->prepare($sql);
    try {
        if ($types !== '') $stmt->bind_param($types, ...$params);
        $stmt->execute();
        $result = $stmt->get_result();
        return $result ? $result->fetch_all(MYSQLI_ASSOC) : array();
    } finally { $stmt->close(); }
}
function api_text($data, $key, $max, $pattern = null) {
    $value = $data[$key] ?? null;
    if (!is_string($value)) api_fail(400, 'Thiếu hoặc sai trường ' . $key);
    $value = trim($value);
    if (!preg_match('/^.{1,' . $max . '}$/usD', $value) || preg_match('/[\x00-\x1f\x7f]/u', $value)
        || ($pattern && !preg_match($pattern, $value))) api_fail(400, 'Giá trị không hợp lệ: ' . $key);
    return $value;
}
function api_id($data) { return api_text($data, 'device_id', 64, '/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/D'); }
function api_int($data, $key, $default = null, $min = 1, $max = PHP_INT_MAX) {
    $value = array_key_exists($key, $data) ? $data[$key] : $default;
    if (!is_int($value) || $value < $min || $value > $max) api_fail(400, 'Số nguyên không hợp lệ: ' . $key);
    return $value;
}
function api_bool($data, $key, $default = null) {
    $value = array_key_exists($key, $data) ? $data[$key] : $default;
    if (!is_bool($value)) api_fail(400, 'Cần boolean: ' . $key);
    return $value ? 1 : 0;
}
function api_number($value, $min, $max, $key) {
    if ((!is_float($value) && !is_int($value)) || !is_finite((float)$value) || $value < $min || $value > $max) {
        api_fail(400, 'Số không hợp lệ: ' . $key);
    }
    return round((float)$value, 2);
}
function api_time($data, $key) {
    if (!isset($data[$key])) return null;
    return api_int($data, $key, null, 1577836800000, (int)floor(microtime(true) * 1000) + 300000);
}
function api_uuid() {
    $bytes = random_bytes(16); $bytes[6] = chr((ord($bytes[6]) & 15) | 64); $bytes[8] = chr((ord($bytes[8]) & 63) | 128);
    $hex = bin2hex($bytes);
    return substr($hex, 0, 8) . '-' . substr($hex, 8, 4) . '-' . substr($hex, 12, 4) . '-' . substr($hex, 16, 4) . '-' . substr($hex, 20);
}
function api_json($value) { return json_encode($value, JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION | JSON_THROW_ON_ERROR); }
function api_log($db, $actor, $device, $action, $changes = array(), $source = 'backend', $result = 'committed') {
    api_query($db, 'INSERT INTO activity_logs (event_id,device_id,actor_user_id,actor_username,source,action,result,changes_json) VALUES (?,?,?,?,?,?,?,?)',
        'ssisssss', array(api_uuid(), $device, $actor['id'] ?? null, $actor['username'], $source, $action, $result, api_json($changes)));
}
function api_user($db) {
    $auth = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if (!preg_match('/^Bearer ([a-f0-9]{64})$/iD', $auth, $match)) api_fail(401, 'Cần đăng nhập');
    $rows = api_query($db, 'SELECT u.id,u.username,u.full_name,u.role FROM user_sessions s JOIN users u ON u.id=s.user_id '
        . 'WHERE s.token_hash=? AND s.expires_at>UTC_TIMESTAMP(3) AND u.is_active=1 FOR UPDATE', 's', array(hash('sha256', $match[1])));
    if (!$rows) api_fail(401, 'Phiên hết hạn hoặc đã thu hồi');
    return $rows[0];
}
function api_admin($user) { if ($user['role'] !== 'admin') api_fail(403, 'Chỉ admin được quản trị'); }
function api_device($db, $device) {
    $rows = api_query($db, 'SELECT * FROM devices WHERE device_id=? FOR UPDATE', 's', array($device));
    if (!$rows) api_fail(404, 'Không tìm thấy thiết bị');
    return $rows[0];
}
function api_access($db, $user, $device, $permission = 'view') {
    if (!in_array($permission, array('view','control','configure'), true)) throw new RuntimeException('Unknown permission');
    $row = api_device($db, $device);
    if ($user['role'] === 'admin') {
        if ($permission !== 'view' && !(int)$row['is_active']) api_fail(403, 'Thiết bị đã vô hiệu hóa');
        return $row;
    }
    $grants = api_query($db, 'SELECT can_view,can_control,can_configure FROM device_users WHERE device_id=? AND user_id=?', 'si', array($device,$user['id']));
    if (!(int)$row['is_active'] || !$grants || !(int)$grants[0]['can_' . $permission]) api_fail(403, 'Không có quyền với thiết bị');
    return $row;
}
function api_esp($db, $device) {
    // X-Pool-Device-Key is deliberately independent of user Bearer sessions.
    $key = $_SERVER['HTTP_X_POOL_DEVICE_KEY'] ?? '';
    if (!preg_match('/^[a-f0-9]{64}$/D', $key)) api_fail(401, 'Credential ESP không hợp lệ');
    $row = api_device($db, $device);
    $rows = api_query($db, 'SELECT id FROM device_credentials WHERE device_id=? AND key_hash=? AND revoked_at IS NULL FOR UPDATE', 'ss', array($device,hash('sha256',$key)));
    if (!$rows) api_fail(401, 'Credential ESP không hợp lệ');
    if (!(int)$row['is_active']) api_fail(403, 'Thiết bị đã vô hiệu hóa');
    return $row;
}
function api_active_user($db, $id) {
    $rows = api_query($db, 'SELECT id,username,role,is_active FROM users WHERE id=? FOR UPDATE','i',array($id));
    if (!$rows || !(int)$rows[0]['is_active']) api_fail(400, 'Tài khoản không có hoặc đã khóa');
    return $rows[0];
}
function api_public($row) {
    foreach (array('id','user_id','actor_user_id','owner_user_id','registered_by','granted_by','revision','state_sequence','reported_config_revision','measured_at_ms','received_at_ms','reported_at_ms') as $field) {
        if (isset($row[$field])) $row[$field] = (int)$row[$field];
    }
    foreach (array('is_active','can_view','can_control','can_configure','pump_in','pump_out','temperature_enabled','water_enabled','interlock','is_backfill','enabled') as $field) {
        if (isset($row[$field])) $row[$field] = (bool)$row[$field];
    }
    foreach (array('temperature','temperature_min','temperature_max','water_cm_min','water_cm_max') as $field) {
        if (isset($row[$field])) $row[$field] = (float)$row[$field];
    }
    foreach (array('water_adc','water_adc_min','water_adc_max') as $field) { if (isset($row[$field])) $row[$field] = (int)$row[$field]; }
    unset($row['password_hash'],$row['token_hash'],$row['key_hash'],$row['payload_hash']);
    return $row;
}
function api_config($row) {
    $row = api_public($row);
    $row['automation'] = $row['automation_json'] === null ? array() : json_decode($row['automation_json'],true,512,JSON_THROW_ON_ERROR);
    unset($row['automation_json']);
    return $row;
}
