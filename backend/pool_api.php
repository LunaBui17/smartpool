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

function get_bearer_token()
{
    $authorization = isset($_SERVER['HTTP_AUTHORIZATION'])
        ? $_SERVER['HTTP_AUTHORIZATION'] : '';
    if ($authorization === '' && function_exists('getallheaders')) {
        $headers = getallheaders();
        if (is_array($headers)) {
            foreach ($headers as $name => $value) {
                if (strcasecmp($name, 'Authorization') === 0) {
                    $authorization = $value;
                    break;
                }
            }
        }
    }
    return preg_match('/^Bearer\s+([a-f0-9]{64})$/i', trim($authorization), $matches)
        ? $matches[1] : '';
}

function find_session_user($conn)
{
    $token = get_bearer_token();
    if ($token === '') return null;
    $tokenHash = hash('sha256', $token);
    $stmt = $conn->prepare(
        'SELECT u.id, u.role FROM pool_user_sessions s '
        . 'JOIN pool_users u ON u.id = s.user_id '
        . 'WHERE s.token_hash = ? AND s.expires_at > NOW() AND u.is_active = 1 LIMIT 1'
    );
    if (!$stmt) return null;
    $stmt->bind_param('s', $tokenHash);
    if (!$stmt->execute()) {
        $stmt->close();
        return null;
    }
    $stmt->bind_result($userId, $role);
    $user = $stmt->fetch() ? array('id' => (int)$userId, 'role' => $role) : null;
    $stmt->close();
    return $user;
}

if ($method === 'GET') {
    if (isset($_GET['history']) && $_GET['history'] === '1') {
        $limit = isset($_GET['limit']) ? filter_var($_GET['limit'], FILTER_VALIDATE_INT) : 100;
        if ($limit === false || $limit < 1 || $limit > 500) {
            http_response_code(400);
            echo json_encode(array('status' => 'error', 'message' => 'limit must be 1..500'));
            $conn->close();
            exit;
        }
        $result = $conn->query('SELECT sample_id, nhiet_do, muc_nuoc_raw, received_at FROM '
            . '(SELECT sample_id, nhiet_do, muc_nuoc_raw, received_at FROM pool_history '
            . 'WHERE nhiet_do IS NOT NULL OR muc_nuoc_raw IS NOT NULL '
            . 'ORDER BY received_at DESC, sample_id DESC LIMIT ' . (int)$limit . ') recent '
            . 'ORDER BY received_at ASC, sample_id ASC');
        if (!$result) {
            http_response_code(500);
            echo json_encode(array('status' => 'error', 'message' => 'Cannot read history'));
        } else {
            $rows = array();
            while ($row = $result->fetch_assoc()) {
                $row['nhiet_do'] = $row['nhiet_do'] === null ? null : (float)$row['nhiet_do'];
                $row['muc_nuoc_raw'] = $row['muc_nuoc_raw'] === null ? null : (int)$row['muc_nuoc_raw'];
                $rows[] = $row;
            }
            echo json_encode($rows, JSON_UNESCAPED_UNICODE);
            $result->free();
        }
        $conn->close();
        exit;
    }
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
        if ($row['nhiet_do'] === null) {
            $latestTemperature = $conn->query('SELECT nhiet_do FROM pool_history WHERE nhiet_do IS NOT NULL '
                . 'ORDER BY received_at DESC, sample_id DESC LIMIT 1');
            $temperatureRow = $latestTemperature ? $latestTemperature->fetch_assoc() : null;
            $row['nhiet_do'] = $temperatureRow ? (float)$temperatureRow['nhiet_do'] : null;
            if ($latestTemperature) $latestTemperature->free();
        } else {
            $row['nhiet_do'] = (float)$row['nhiet_do'];
        }
        $row['ph'] = (float)$row['ph'];
        $row['muc_nuoc'] = (int)$row['muc_nuoc'];
        $latestRaw = $conn->query('SELECT muc_nuoc_raw FROM pool_history WHERE muc_nuoc_raw IS NOT NULL '
            . 'ORDER BY received_at DESC, sample_id DESC LIMIT 1');
        $rawRow = $latestRaw ? $latestRaw->fetch_assoc() : null;
        $row['muc_nuoc_raw'] = $rawRow ? (int)$rawRow['muc_nuoc_raw'] : null;
        if ($latestRaw) $latestRaw->free();
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

    if (isset($data['action'])) {
        $action = (string)$data['action'];
        if ($action === 'login') {
            $conn->query('DELETE FROM pool_user_sessions WHERE expires_at <= NOW()');
            $username = isset($data['username']) ? trim((string)$data['username']) : '';
            $password = isset($data['password']) ? (string)$data['password'] : '';
            $rememberMe = isset($data['remember_me']) && $data['remember_me'] === true;
            if ($username === '' || $password === '') {
                http_response_code(400);
                echo json_encode(array('status' => 'error', 'message' => 'Thiếu tên đăng nhập hoặc mật khẩu'));
                $conn->close();
                exit;
            }
            $stmt = $conn->prepare(
                'SELECT id, password_hash, role FROM pool_users '
                . 'WHERE username = ? AND is_active = 1 LIMIT 1'
            );
            if (!$stmt) {
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Chưa cài đặt bảng tài khoản'));
                $conn->close();
                exit;
            }
            $stmt->bind_param('s', $username);
            if (!$stmt->execute()) {
                $stmt->close();
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không thể xác thực tài khoản'));
                $conn->close();
                exit;
            }
            $stmt->bind_result($userId, $passwordHash, $role);
            $found = $stmt->fetch();
            $stmt->close();
            if (!$found || !password_verify($password, $passwordHash)) {
                http_response_code(401);
                echo json_encode(array('status' => 'error', 'message' => 'Tên đăng nhập hoặc mật khẩu không đúng'));
                $conn->close();
                exit;
            }
            try {
                $token = bin2hex(random_bytes(32));
            } catch (Exception $e) {
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không tạo được phiên đăng nhập'));
                $conn->close();
                exit;
            }
            $tokenHash = hash('sha256', $token);
            $expiresIn = $rememberMe ? 2592000 : 43200;
            $expirationSql = $rememberMe
                ? 'DATE_ADD(NOW(), INTERVAL 30 DAY)'
                : 'DATE_ADD(NOW(), INTERVAL 12 HOUR)';
            $sessionStmt = $conn->prepare(
                'INSERT INTO pool_user_sessions (user_id, token_hash, expires_at) '
                . 'VALUES (?, ?, ' . $expirationSql . ')'
            );
            if (!$sessionStmt) {
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không lưu được phiên đăng nhập'));
                $conn->close();
                exit;
            }
            $sessionStmt->bind_param('is', $userId, $tokenHash);
            $saved = $sessionStmt->execute();
            $sessionStmt->close();
            if (!$saved) {
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không lưu được phiên đăng nhập'));
                $conn->close();
                exit;
            }
            echo json_encode(array(
                'status' => 'success',
                'username' => $username,
                'role' => $role,
                'token' => $token,
                'expires_in' => $expiresIn
            ), JSON_UNESCAPED_UNICODE);
            $conn->close();
            exit;
        }

        if ($action === 'create_guest') {
            $user = find_session_user($conn);
            if (!$user || $user['role'] !== 'admin') {
                http_response_code(403);
                echo json_encode(array('status' => 'error', 'message' => 'Chỉ admin được tạo tài khoản guest'));
                $conn->close();
                exit;
            }
            $username = isset($data['username']) ? trim((string)$data['username']) : '';
            $password = isset($data['password']) ? (string)$data['password'] : '';
            if (!preg_match('/^[A-Za-z0-9._-]{3,64}$/', $username)
                || strlen($password) < 8 || strlen($password) > 72) {
                http_response_code(400);
                echo json_encode(array(
                    'status' => 'error',
                    'message' => 'Tên tài khoản cần 3-64 ký tự chữ/số/._-; mật khẩu cần 8-72 byte'
                ), JSON_UNESCAPED_UNICODE);
                $conn->close();
                exit;
            }
            $passwordHash = password_hash($password, PASSWORD_DEFAULT);
            if ($passwordHash === false) {
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không mã hóa được mật khẩu'));
                $conn->close();
                exit;
            }
            $stmt = $conn->prepare(
                "INSERT INTO pool_users (username, password_hash, role) VALUES (?, ?, 'guest')"
            );
            if (!$stmt) {
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không tạo được tài khoản'));
                $conn->close();
                exit;
            }
            $stmt->bind_param('ss', $username, $passwordHash);
            $created = $stmt->execute();
            $duplicate = $stmt->errno === 1062;
            $stmt->close();
            if (!$created) {
                http_response_code($duplicate ? 409 : 500);
                echo json_encode(array(
                    'status' => 'error',
                    'message' => $duplicate ? 'Tên đăng nhập đã tồn tại' : 'Không tạo được tài khoản guest'
                ), JSON_UNESCAPED_UNICODE);
                $conn->close();
                exit;
            }
            echo json_encode(array('status' => 'success', 'username' => $username, 'role' => 'guest'));
            $conn->close();
            exit;
        }

        if ($action === 'list_users') {
            $user = find_session_user($conn);
            if (!$user || $user['role'] !== 'admin') {
                http_response_code(403);
                echo json_encode(array('status' => 'error', 'message' => 'Chỉ admin được xem danh sách tài khoản'));
                $conn->close();
                exit;
            }
            $result = $conn->query(
                "SELECT id, username, role, is_active, created_at FROM pool_users "
                . "WHERE role = 'guest' ORDER BY id ASC"
            );
            if (!$result) {
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không đọc được danh sách tài khoản'));
                $conn->close();
                exit;
            }
            $users = array();
            while ($row = $result->fetch_assoc()) {
                $row['id'] = (int)$row['id'];
                $row['is_active'] = (int)$row['is_active'];
                $users[] = $row;
            }
            $result->free();
            echo json_encode(array('status' => 'success', 'users' => $users), JSON_UNESCAPED_UNICODE);
            $conn->close();
            exit;
        }

        if ($action === 'save_user') {
            $user = find_session_user($conn);
            if (!$user || $user['role'] !== 'admin') {
                http_response_code(403);
                echo json_encode(array('status' => 'error', 'message' => 'Chỉ admin được quản lý tài khoản'));
                $conn->close();
                exit;
            }
            $userId = isset($data['user_id']) ? filter_var($data['user_id'], FILTER_VALIDATE_INT) : 0;
            $username = isset($data['username']) ? trim((string)$data['username']) : '';
            $password = isset($data['password']) ? (string)$data['password'] : '';
            $role = isset($data['role']) ? (string)$data['role'] : 'guest';
            $active = isset($data['is_active']) ? (bool)$data['is_active'] : true;
            if ($userId === false || $userId < 0
                || !preg_match('/^[A-Za-z0-9._-]{3,64}$/', $username)
                || $role !== 'guest'
                || ($password !== '' && (strlen($password) < 8 || strlen($password) > 72))
                || ($userId === 0 && $password === '')) {
                http_response_code(400);
                echo json_encode(array('status' => 'error', 'message' =>
                    'Thông tin không hợp lệ; tên đăng nhập 3-64 ký tự và mật khẩu mới 8-72 byte'));
                $conn->close();
                exit;
            }
            $passwordHash = null;
            if ($password !== '') {
                $passwordHash = password_hash($password, PASSWORD_DEFAULT);
                if ($passwordHash === false) {
                    http_response_code(500);
                    echo json_encode(array('status' => 'error', 'message' => 'Không mã hóa được mật khẩu'));
                    $conn->close();
                    exit;
                }
            }

            if (!$conn->begin_transaction()) {
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không bắt đầu được giao dịch tài khoản'));
                $conn->close();
                exit;
            }
            if ($userId > 0) {
                $currentStmt = $conn->prepare(
                    'SELECT role FROM pool_users WHERE id = ? FOR UPDATE'
                );
                if (!$currentStmt) {
                    $conn->rollback();
                    http_response_code(500);
                    echo json_encode(array('status' => 'error', 'message' => 'Không khóa được dữ liệu tài khoản'));
                    $conn->close();
                    exit;
                }
                $currentStmt->bind_param('i', $userId);
                if (!$currentStmt->execute()) {
                    $currentStmt->close();
                    $conn->rollback();
                    http_response_code(500);
                    echo json_encode(array('status' => 'error', 'message' => 'Không đọc được tài khoản cần sửa'));
                    $conn->close();
                    exit;
                }
                $currentStmt->bind_result($currentRole);
                $exists = $currentStmt->fetch();
                $currentStmt->close();
                if (!$exists) {
                    $conn->rollback();
                    http_response_code(404);
                    echo json_encode(array('status' => 'error', 'message' => 'Không tìm thấy tài khoản'));
                    $conn->close();
                    exit;
                }
                if ($currentRole !== 'guest') {
                    $conn->rollback();
                    http_response_code(403);
                    echo json_encode(array('status' => 'error', 'message' =>
                        'Tab này chỉ quản lý tài khoản guest'));
                    $conn->close();
                    exit;
                }
                $update = $conn->prepare(
                    'UPDATE pool_users SET username = ?, role = ?, is_active = ?, '
                    . 'password_hash = COALESCE(?, password_hash) WHERE id = ?'
                );
                if (!$update) {
                    $conn->rollback();
                    http_response_code(500);
                    echo json_encode(array('status' => 'error', 'message' => 'Không chuẩn bị được cập nhật tài khoản'));
                    $conn->close();
                    exit;
                }
                $activeValue = $active ? 1 : 0;
                $update->bind_param('ssisi', $username, $role, $activeValue, $passwordHash, $userId);
                $saved = $update->execute();
                $duplicate = $update->errno === 1062;
                $update->close();
            } else {
                $insert = $conn->prepare(
                    'INSERT INTO pool_users (username, password_hash, role, is_active) VALUES (?, ?, ?, ?)'
                );
                if (!$insert) {
                    $conn->rollback();
                    http_response_code(500);
                    echo json_encode(array('status' => 'error', 'message' => 'Không chuẩn bị được tạo tài khoản'));
                    $conn->close();
                    exit;
                }
                $activeValue = $active ? 1 : 0;
                $insert->bind_param('sssi', $username, $passwordHash, $role, $activeValue);
                $saved = $insert->execute();
                $duplicate = $insert->errno === 1062;
                $insert->close();
            }
            if (!$saved || !$conn->commit()) {
                $conn->rollback();
                http_response_code($duplicate ? 409 : 500);
                echo json_encode(array('status' => 'error', 'message' =>
                    $duplicate ? 'Tên đăng nhập đã tồn tại' : 'Không lưu được tài khoản'));
                $conn->close();
                exit;
            }
            echo json_encode(array('status' => 'success', 'username' => $username));
            $conn->close();
            exit;
        }

        if ($action === 'delete_user') {
            $user = find_session_user($conn);
            if (!$user || $user['role'] !== 'admin') {
                http_response_code(403);
                echo json_encode(array('status' => 'error', 'message' => 'Chỉ admin được xóa tài khoản'));
                $conn->close();
                exit;
            }
            $userId = isset($data['user_id']) ? filter_var($data['user_id'], FILTER_VALIDATE_INT) : false;
            if ($userId === false || $userId < 1) {
                http_response_code(400);
                echo json_encode(array('status' => 'error', 'message' => 'user_id không hợp lệ'));
                $conn->close();
                exit;
            }
            if ($userId === (int)$user['id']) {
                http_response_code(409);
                echo json_encode(array('status' => 'error', 'message' => 'Không thể xóa tài khoản đang đăng nhập'));
                $conn->close();
                exit;
            }
            if (!$conn->begin_transaction()) {
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không bắt đầu được giao dịch xóa tài khoản'));
                $conn->close();
                exit;
            }
            $target = $conn->prepare('SELECT role FROM pool_users WHERE id = ? FOR UPDATE');
            if (!$target) {
                $conn->rollback();
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không khóa được dữ liệu tài khoản'));
                $conn->close();
                exit;
            }
            $target->bind_param('i', $userId);
            if (!$target->execute()) {
                $target->close();
                $conn->rollback();
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không đọc được tài khoản cần xóa'));
                $conn->close();
                exit;
            }
            $target->bind_result($targetRole);
            $exists = $target->fetch();
            $target->close();
            if (!$exists) {
                $conn->rollback();
                http_response_code(404);
                echo json_encode(array('status' => 'error', 'message' => 'Không tìm thấy tài khoản'));
                $conn->close();
                exit;
            }
            if ($targetRole !== 'guest') {
                $conn->rollback();
                http_response_code(403);
                echo json_encode(array('status' => 'error', 'message' =>
                    'Tab này chỉ quản lý tài khoản guest'));
                $conn->close();
                exit;
            }
            $delete = $conn->prepare('DELETE FROM pool_users WHERE id = ?');
            if (!$delete) {
                $conn->rollback();
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không chuẩn bị được xóa tài khoản'));
                $conn->close();
                exit;
            }
            $delete->bind_param('i', $userId);
            $deleted = $delete->execute() && $delete->affected_rows === 1;
            $delete->close();
            if (!$deleted || !$conn->commit()) {
                $conn->rollback();
                http_response_code(500);
                echo json_encode(array('status' => 'error', 'message' => 'Không xóa được tài khoản'));
                $conn->close();
                exit;
            }
            echo json_encode(array('status' => 'success'));
            $conn->close();
            exit;
        }

        if ($action === 'logout') {
            $token = get_bearer_token();
            $user = find_session_user($conn);
            if ($user && $token !== '') {
                $tokenHash = hash('sha256', $token);
                $stmt = $conn->prepare('DELETE FROM pool_user_sessions WHERE token_hash = ?');
                if ($stmt) {
                    $stmt->bind_param('s', $tokenHash);
                    $stmt->execute();
                    $stmt->close();
                }
            }
            echo json_encode(array('status' => 'success'));
            $conn->close();
            exit;
        }

        http_response_code(400);
        echo json_encode(array('status' => 'error', 'message' => 'Action không được hỗ trợ'));
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
    // Relay/sensor state notifications have a sample_id but no measured values.
    // Keep updating pool_status without adding an all-NULL history row.
    $hasHistory = $hasSample && (
        array_key_exists('nhiet_do', $data) ||
        array_key_exists('muc_nuoc', $data) ||
        array_key_exists('muc_nuoc_raw', $data)
    );
    if ($hasSample) {
        $sampleId = (string)$data['sample_id'];
        if (!preg_match('/^[0-9A-Fa-f]{16}$/', $sampleId)) {
            http_response_code(400);
            echo json_encode(array('status' => 'error', 'message' => 'Invalid sample_id'));
            $conn->close();
            exit;
        }
    }
    if ($hasHistory) {
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
        if ($hasHistory) $conn->rollback();
        http_response_code(500);
        echo json_encode(array('status' => 'error', 'message' => 'Không cập nhật được database'));
        $conn->close();
        exit;
    }

    if ($conn->affected_rows === 0) {
        $check = $conn->query('SELECT id FROM pool_status WHERE id = 1 LIMIT 1');
        if (!$check || $check->num_rows === 0) {
            if ($hasHistory) $conn->rollback();
            http_response_code(404);
            echo json_encode(array('status' => 'error', 'message' => 'Không tồn tại bản ghi pool_status id=1; hãy chạy file pool_db.sql'));
            if ($check) $check->free();
            $conn->close();
            exit;
        }
        if ($check) $check->free();
    }

    if ($hasHistory && !$conn->commit()) {
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
