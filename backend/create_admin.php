<?php
if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

mysqli_report(MYSQLI_REPORT_OFF);
$conn = new mysqli('localhost', 'root', '', 'pool_db');
if ($conn->connect_error) {
    fwrite(STDERR, "Cannot connect to pool_db: " . $conn->connect_error . PHP_EOL);
    exit(1);
}
$conn->set_charset('utf8mb4');

$result = $conn->query("SELECT COUNT(*) AS total FROM pool_users WHERE role = 'admin'");
if (!$result) {
    fwrite(STDERR, "Cannot read pool_users; import pool_db.sql first." . PHP_EOL);
    $conn->close();
    exit(1);
}
$row = $result->fetch_assoc();
$result->free();
if ((int)$row['total'] > 0) {
    fwrite(STDERR, "An admin account already exists; create additional accounts from the app." . PHP_EOL);
    $conn->close();
    exit(1);
}

echo 'Admin username (3-64 characters): ';
$username = trim((string)fgets(STDIN));
if (!preg_match('/^[A-Za-z0-9._-]{3,64}$/', $username)) {
    fwrite(STDERR, "Username may contain letters, numbers, dot, underscore and hyphen only." . PHP_EOL);
    $conn->close();
    exit(1);
}
echo 'Admin password (8-72 bytes): ';
$password = rtrim((string)fgets(STDIN), "\r\n");
if (strlen($password) < 8 || strlen($password) > 72) {
    fwrite(STDERR, "Password must be 8-72 bytes." . PHP_EOL);
    $conn->close();
    exit(1);
}

$passwordHash = password_hash($password, PASSWORD_DEFAULT);
$stmt = $conn->prepare("INSERT INTO pool_users (username, password_hash, role) VALUES (?, ?, 'admin')");
if (!$passwordHash || !$stmt) {
    fwrite(STDERR, "Could not prepare admin account creation." . PHP_EOL);
    if ($stmt) $stmt->close();
    $conn->close();
    exit(1);
}
$stmt->bind_param('ss', $username, $passwordHash);
if (!$stmt->execute()) {
    fwrite(STDERR, "Could not create admin: " . $stmt->error . PHP_EOL);
    $stmt->close();
    $conn->close();
    exit(1);
}

echo "Admin account created." . PHP_EOL;
$stmt->close();
$conn->close();
?>
