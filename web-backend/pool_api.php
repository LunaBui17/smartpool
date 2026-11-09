<?php
/* Smart Pool API - đặt file tại thư mục XAMPP/htdocs/pool_api.php */
header('Content-Type: application/json; charset=utf-8');

mysqli_report(MYSQLI_REPORT_OFF);
$conn = new mysqli('localhost', 'root', '', 'pool_db');
if ($conn->connect_error) {
    http_response_code(500);
    echo json_encode(array('status' => 'error', 'message' => 'Không kết nối được database pool_db'));
    exit;
}
$conn->set_charset('utf8mb4');

$method = isset($_SERVER['REQUEST_METHOD']) ? $_SERVER['REQUEST_METHOD'] : 'GET';

if ($method === 'GET') {
    if (isset($_GET['commands']) && $_GET['commands'] === '1') {
        $result = $conn->query('SELECT revision, bom_vao, bom_ra FROM pool_commands WHERE id = 1 LIMIT 1');
        if (!$result) {
            http_response_code(500);
            echo json_encode(array('status' => 'error', 'message' => 'Không đọc được lệnh bơm; hãy nhập pool_db.sql'));
        } elseif ($row = $result->fetch_assoc()) {
            echo json_encode(array(
                'revision' => (int)$row['revision'],
                'bom_vao' => (int)$row['bom_vao'],
                'bom_ra' => (int)$row['bom_ra']
            ));
        } else {
            http_response_code(404);
            echo json_encode(array('status' => 'error', 'message' => 'Chưa có pool_commands id=1'));
        }
        if ($result) $result->free();
        $conn->close();
        exit;
    }
    $result = $conn->query('SELECT id, is_app_active, nhiet_do, ph, muc_nuoc, bom_control, van_control FROM pool_status WHERE id = 1 LIMIT 1');
    if (!$result) {
        http_response_code(500);
        echo json_encode(array('status' => 'error', 'message' => 'Lỗi đọc dữ liệu'));
        $conn->close();
        exit;
    }

    $row = $result->fetch_assoc();
    if (!$row) {
        http_response_code(404);
        echo json_encode(array('status' => 'error', 'message' => 'Chưa có bản ghi pool_status id=1'));
    } else {
        // Ép kiểu để Android/ESP32 nhận được số JSON, không phải chuỗi.
        $row['id'] = (int)$row['id'];
        $row['is_app_active'] = (int)$row['is_app_active'];
        $row['nhiet_do'] = (float)$row['nhiet_do'];
        $row['ph'] = (float)$row['ph'];
        $row['muc_nuoc'] = (int)$row['muc_nuoc'];
        $row['bom_control'] = (int)$row['bom_control'];
        $row['van_control'] = (int)$row['van_control'];
        echo json_encode($row, JSON_UNESCAPED_UNICODE);
    }
    $result->free();
    $conn->close();
    exit;
}

if ($method === 'POST') {
    $raw = file_get_contents('php://input');
    $data = json_decode($raw, true);
    if (!is_array($data) || count($data) === 0) {
        http_response_code(400);
        echo json_encode(array('status' => 'error', 'message' => 'JSON rỗng hoặc không hợp lệ'));
        $conn->close();
        exit;
    }

    if (array_key_exists('command', $data)) {
        $command = $data['command'];
        if (!is_array($command) || !isset($command['bom_vao'], $command['bom_ra']) ||
            !is_int($command['bom_vao']) || !is_int($command['bom_ra']) ||
            ($command['bom_vao'] !== 0 && $command['bom_vao'] !== 1) ||
            ($command['bom_ra'] !== 0 && $command['bom_ra'] !== 1) ||
            ($command['bom_vao'] === 1 && $command['bom_ra'] === 1)) {
            http_response_code(400);
            echo json_encode(array('status' => 'error', 'message' => 'Lệnh bơm phải có bom_vao/bom_ra bằng 0 hoặc 1; không bật cả hai'));
            $conn->close();
            exit;
        }
        $stmt = $conn->prepare('UPDATE pool_commands SET revision = revision + 1, bom_vao = ?, bom_ra = ? WHERE id = 1');
        if (!$stmt) {
            http_response_code(500);
            echo json_encode(array('status' => 'error', 'message' => 'Chưa có bảng pool_commands; hãy nhập pool_db.sql'));
            $conn->close();
            exit;
        }
        $pumpIn = $command['bom_vao'];
        $pumpOut = $command['bom_ra'];
        $stmt->bind_param('ii', $pumpIn, $pumpOut);
        $ok = $stmt->execute() && $stmt->affected_rows === 1;
        $stmt->close();
        if (!$ok) {
            http_response_code(500);
            echo json_encode(array('status' => 'error', 'message' => 'Không lưu được lệnh bơm'));
            $conn->close();
            exit;
        }
        echo json_encode(array('status' => 'accepted', 'message' => 'Đã lưu lệnh, chờ ESP nhận'));
        $conn->close();
        exit;
    }

    // Chỉ cho phép các cột được liệt kê dưới đây; không nhận tên cột tùy ý từ client.
    $integerFields = array('is_app_active', 'muc_nuoc', 'bom_control', 'van_control');
    $floatFields = array('nhiet_do', 'ph');
    $updates = array();

    foreach ($integerFields as $field) {
        if (array_key_exists($field, $data)) {
            if (!is_numeric($data[$field])) {
                http_response_code(400);
                echo json_encode(array('status' => 'error', 'message' => 'Giá trị không hợp lệ: ' . $field), JSON_UNESCAPED_UNICODE);
                $conn->close();
                exit;
            }
            $value = (int)$data[$field];
            if ($field === 'is_app_active' || $field === 'bom_control' || $field === 'van_control') {
                $value = ($value === 1) ? 1 : 0;
            }
            if ($field === 'muc_nuoc') {
                $value = ($value === 1) ? 1 : 0; // 1 = bình thường, 0 = cảnh báo
            }
            $updates[] = "`" . $field . "` = " . $value;
        }
    }

    foreach ($floatFields as $field) {
        if (array_key_exists($field, $data)) {
            if (!is_numeric($data[$field]) || !is_finite((float)$data[$field])) {
                http_response_code(400);
                echo json_encode(array('status' => 'error', 'message' => 'Giá trị không hợp lệ: ' . $field), JSON_UNESCAPED_UNICODE);
                $conn->close();
                exit;
            }
            $value = sprintf('%.3F', (float)$data[$field]);
            $updates[] = "`" . $field . "` = " . $value;
        }
    }

    // Fail-safe: khi app đóng/tạm rời màn hình, yêu cầu dừng bơm và đóng van.
    if (isset($data['is_app_active']) && (int)$data['is_app_active'] === 0) {
        $updates[] = '`bom_control` = 0';
        $updates[] = '`van_control` = 0';
    }

    if (count($updates) === 0) {
        http_response_code(400);
        echo json_encode(array('status' => 'error', 'message' => 'Không có trường dữ liệu hợp lệ để cập nhật'));
        $conn->close();
        exit;
    }

    // Samples sent again after an outage keep the same ID, so retries are idempotent.
    $hasSample = array_key_exists('sample_id', $data);
    if ($hasSample) {
        $sampleId = (string)$data['sample_id'];
        if (!preg_match('/^[0-9A-Fa-f]{16}$/', $sampleId)) {
            http_response_code(400);
            echo json_encode(array('status' => 'error', 'message' => 'Invalid sample_id'));
            $conn->close();
            exit;
        }
        $rawSql = 'NULL';
        if (array_key_exists('muc_nuoc_raw', $data)) {
            if (!is_numeric($data['muc_nuoc_raw']) ||
                (int)$data['muc_nuoc_raw'] < 0 || (int)$data['muc_nuoc_raw'] > 4095) {
                http_response_code(400);
                echo json_encode(array('status' => 'error', 'message' => 'Invalid muc_nuoc_raw'));
                $conn->close();
                exit;
            }
            $rawSql = (string)(int)$data['muc_nuoc_raw'];
        }
        $temperatureSql = array_key_exists('nhiet_do', $data)
            ? sprintf('%.3F', (float)$data['nhiet_do']) : 'NULL';
        $waterSql = array_key_exists('muc_nuoc', $data)
            ? (((int)$data['muc_nuoc'] === 1) ? '1' : '0') : 'NULL';
        if (!$conn->begin_transaction()) {
            http_response_code(500);
            echo json_encode(array('status' => 'error', 'message' => 'Cannot start transaction'));
            $conn->close();
            exit;
        }
        $historySql = "INSERT IGNORE INTO pool_history " .
            "(sample_id, nhiet_do, muc_nuoc, muc_nuoc_raw) VALUES ('" .
            strtoupper($sampleId) . "', " . $temperatureSql . ", " . $waterSql .
            ", " . $rawSql . ")";
        if (!$conn->query($historySql)) {
            $conn->rollback();
            http_response_code(500);
            echo json_encode(array('status' => 'error', 'message' => 'Cannot save history'));
            $conn->close();
            exit;
        }
        if ($conn->affected_rows === 0) {
            $conn->rollback();
            echo json_encode(array('status' => 'success', 'message' => 'Sample already saved'));
            $conn->close();
            exit;
        }
    }

    $sql = 'UPDATE pool_status SET ' . implode(', ', $updates) . ' WHERE id = 1';
    if (!$conn->query($sql)) {
        if ($hasSample) $conn->rollback();
        http_response_code(500);
        echo json_encode(array('status' => 'error', 'message' => 'Không cập nhật được database'));
        $conn->close();
        exit;
    }

    if ($conn->affected_rows === 0) {
        $check = $conn->query('SELECT id FROM pool_status WHERE id = 1 LIMIT 1');
        if (!$check || $check->num_rows === 0) {
            if ($hasSample) $conn->rollback();
            http_response_code(404);
            echo json_encode(array('status' => 'error', 'message' => 'Không tồn tại bản ghi pool_status id=1; hãy chạy file pool_db.sql'));
            if ($check) $check->free();
            $conn->close();
            exit;
        }
        if ($check) $check->free();
    }

    if ($hasSample && !$conn->commit()) {
        http_response_code(500);
        echo json_encode(array('status' => 'error', 'message' => 'Cannot commit history'));
        $conn->close();
        exit;
    }

    echo json_encode(array('status' => 'success', 'message' => 'Cập nhật thành công'), JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

http_response_code(405);
echo json_encode(array('status' => 'error', 'message' => 'Chỉ hỗ trợ GET và POST'));
$conn->close();
?>
