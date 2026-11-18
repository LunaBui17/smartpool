<?php
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
require_once __DIR__ . '/database.php';
require_once __DIR__ . '/api_core.php';
require_once __DIR__ . '/api_actions.php';
require_once __DIR__ . '/api_data.php';
require_once __DIR__ . '/api_runtime.php';
$db = null;
try {
    $db = pool_database_connect();
    mysqli_report(MYSQLI_REPORT_ERROR | MYSQLI_REPORT_STRICT);
    if ($db->connect_errno) throw new RuntimeException('Database unavailable');
    $db->set_charset('utf8mb4');
    $db->query("SET time_zone = '+00:00'");
    $method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
    if ($method === 'GET' && ($_GET['action'] ?? '') === 'health') {
        $db->query('SELECT device_id FROM devices LIMIT 1');
        $db->query('SELECT id FROM users LIMIT 1');
        $db->query('SELECT command_id FROM command_results LIMIT 1');
        $db->query('SELECT worker_id FROM control_worker_state LIMIT 1');
        $db->query('SELECT device_id FROM device_mqtt_presence LIMIT 1');
        $db->query('SELECT command_id FROM command_dispatch LIMIT 1');
        $db->query('SELECT id FROM config_apply_reports LIMIT 1');
        echo api_json(array('status'=>'success','api_version'=>2,'schema_baseline'=>4,'schema_runtime'=>5));
        exit;
    }
    if ($method !== 'POST') { header('Allow: POST, GET'); api_fail(405,'API v2 dùng POST JSON; GET chỉ hỗ trợ action=health'); }
    $raw = file_get_contents('php://input', false, null, 0, 65537);
    if (strlen($raw)>65536) api_fail(413,'Request tối đa 64 KiB');
    try { $data=json_decode($raw,true,32,JSON_THROW_ON_ERROR); }
    catch (JsonException $ignored) { api_fail(400,'JSON không hợp lệ'); }
    if (!is_array($data)||!$data||array_is_list($data)) api_fail(400,'Cần JSON object');
    $db->begin_transaction();
    $response=api_dispatch_action($db,$data);
    $db->commit();
    $created=in_array($data['action'],array('register_device','create_user','create_device_credential'),true)
        ||($data['action']==='upload_sample'&&!$response['duplicate']);
    http_response_code($created?201:200);
    echo api_json(array_merge(array('status'=>'success','api_version'=>2),$response));
} catch (Throwable $error) {
    if ($db) { try {$db->rollback();} catch (Throwable $ignored) {} }
    $status=$error instanceof PoolApiError?$error->status:((int)$error->getCode()===1062?409:500);
    http_response_code($status);
    $message=$error instanceof PoolApiError?$error->getMessage():($status===409?'Định danh đã tồn tại':'Không xử lý được yêu cầu; kiểm tra schema và database');
    echo json_encode(array('status'=>'error','api_version'=>2,'message'=>$message),JSON_UNESCAPED_UNICODE);
} finally { if($db) $db->close(); }
