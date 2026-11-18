# Smart Pool – bộ triển khai v2 tách biệt

Bộ đang hoạt động: `deploy/raspberry-pi-v2` trong repository; trên Pi là `/home/qnm/smartpool-v2/deploy/raspberry-pi-v2`. Project Docker `smartpool-v2`, network/container và volume riêng. Stack Docker cũ đã được xóa khi cài mới ngày 18/11/2026; không chạy đồng thời hai stack vì dùng chung cổng LAN 80/1883/8080.

Sau khi xóa volume cũ và cài mới ngày 18/11/2026, DB root, DB user và các account MQTT dùng mật khẩu mặc định `luanbui`. Secret runtime nằm trong file private trên Pi. Tài khoản admin API ban đầu là `admin`, mật khẩu `luanbui`:

| Nhóm | Áp dụng |
| --- | --- |
| Quản trị | App role admin, MariaDB root, MQTT bridge |
| User/thiết bị | App role user, DB user smartpool, MQTT diagnostics và account ESP |

Admin và user app hỗ trợ 7–72 byte. Bulk reset giữ tài khoản/dữ liệu, ghi audit, thu hồi session và hủy lệnh pending. User mới tạo qua API vẫn có password do admin cung cấp; dùng bulk reset khi muốn áp lại chính sách chung cho toàn bộ user.

Mật khẩu dùng chung theo nhóm là lựa chọn vận hành hiện tại: các MQTT account thiết bị không còn secret riêng. Người biết password nhóm thiết bị và ID khác có thể đăng nhập dưới username đó; ACL theo username không ngăn được việc mạo danh này. Không coi diagnostics account hoặc các thiết bị là được cách ly bằng secret độc lập.

## Các file vận hành mới

- `~/smartpool-v2-endpoints.txt`: URL Quick Tunnel đang dùng, refresh 10 giây.
- `~/smartpool-v2-credentials.txt`: thông tin truy cập hiện tại, file 600.
- `~/smartpool-v2-mqtt-diagnostics.txt`: account MQTT Web diagnostics, file 600.
- `~/smartpool-v2-mqtt/DEVICE_ID.txt`: credential từng account ESP đã cấp.
- `.env` và `.env.mqtt-policy`: cấu hình private trên Pi, không chép vào Git.

File credential/endpoint của stack cũ không dùng cho deployment mới. Systemd mới là smartpool-v2-cloudflare-endpoints.service; cache exporter /var/lib/smartpool-v2; metrics cục bộ 20242. URL Cloudflare có thể đổi sau mỗi lần tunnel khởi động lại.

## Điều khiển bơm qua MQTT

App gọi API `request_control` theo quyền của từng user. `control-worker` đọc lệnh còn hạn, kiểm tra lại quyền, phát MQTT vào topic riêng của thiết bị và ghi ACK từ ESP vào database. API trả `remote_control_ready` khi worker đang kết nối broker và database; app chỉ bật nút bơm khi có báo cáo ESP mới và worker sẵn sàng.

Sau khi chép source mới lên Pi, chạy trong terminal Pi (nhập mật khẩu sudo tại terminal):

```bash
cd ~/smartpool-v2/deploy/raspberry-pi-v2
bash activate-control-worker.sh
```

`SmartPool02` đã đăng ký trong database nhưng chưa có ESP vật lý; app hiển thị chưa có báo cáo và khóa điều khiển thiết bị này cho tới khi được cấp key, MQTT account và nạp firmware đúng `device_id`.

## Gửi source và cập nhật

Từ PowerShell tại gốc repository:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\raspberry-pi-v2\send-to-pi.ps1
```

Trên Pi:

```bash
cd ~/smartpool-v2/deploy/raspberry-pi-v2
sudo bash pool.sh status
sudo bash pool.sh up
sudo bash pool.sh logs api
sudo bash pool.sh logs mqtt
sudo bash pool.sh endpoints
sudo bash pool.sh backup
```

Cập nhật riêng API:

```bash
sudo docker compose -f compose.yaml -f compose.cloudflare.yaml build api
sudo docker compose -f compose.yaml -f compose.cloudflare.yaml run --rm --no-deps -T api php /opt/smartpool/migrate.php
sudo docker compose -f compose.yaml -f compose.cloudflare.yaml up -d --no-deps --no-build api
```

Không chạy reset_database.php hoặc down -v để cập nhật. Sau khi đã tạo admin, không chạy lại `pool.sh admin`.

## MQTT và password policy

Để đăng ký ESP `SmartPool` sau khi cài mới và cấp key HTTP/MQTT, chạy một lần trên Pi (đổi thông tin sinh viên theo thiết bị thực):

```bash
cd ~/smartpool-v2/deploy/raspberry-pi-v2
bash finish-esp-setup.sh SmartPool HoTenVietLien MSSV
```

Script rebuild API để chuyển tiếp Bearer token tới PHP, kích hoạt thiết bị, ghi key HTTP một lần vào `~/smartpool-v2-device-keys/SmartPool.txt` (quyền 600), rồi tạo account MQTT. Đưa key vào `esp-firmware/main/device_credentials.h` trên máy build và build lại firmware; không đưa key vào Git.

```bash
sudo python3 mqtt-admin.py status
sudo python3 mqtt-admin.py add-device DEVICE_ID
sudo python3 mqtt-admin.py revoke-device DEVICE_ID
sudo python3 mqtt-admin.py policy-refresh
```

Add cần device_id đã đăng ký/active trong API v2. Account dùng password nhóm user/thiết bị trong .env.mqtt-policy; rotate-device áp lại giá trị policy hiện tại, không sinh secret ngẫu nhiên. policy-refresh áp lại mọi account và xuất credential mới; thao tác này recreate broker, làm các client mất kết nối ngắn. API membership chưa tự đồng bộ thành account MQTT; worker publish lệnh/config chưa có.

Bulk reset password ứng dụng theo file policy, do operator chủ động chạy:

```bash
sudo sh -c 'docker compose -f compose.yaml -f compose.cloudflare.yaml exec -T api php /opt/smartpool/reset_role_passwords.php --from-stdin < .env.mqtt-policy'
```

Không chỉ sửa DB_PASSWORD/DB_ROOT_PASSWORD trong .env trên volume DB đã khởi tạo; tài khoản MariaDB phải đổi đồng bộ. Script setup-passwords.py chỉ dùng cài mới, từ chối ghi đè config có sẵn.

## Cài mới trên Pi khác

```bash
sudo python3 setup-passwords.py PI_LAN_IP --default  # DB root, DB user, MQTT: luanbui
sudo bash pool.sh up PI_LAN_IP
sudo bash pool.sh admin
```

`--default` tạo policy `luanbui` cho cả hai nhóm; bỏ tùy chọn này để nhập policy bằng prompt ẩn. Mật khẩu admin API tạo riêng bằng `pool.sh admin`; nhập `luanbui` nếu muốn dùng cùng mặc định. init chỉ tạo file auth/account hệ thống, không tạo tài khoản app hoặc thiết bị giả. Volume MQTT external có tên riêng smartpool-v2_mqtt-auth/data, được deploy.sh tạo nếu chưa tồn tại. Không dùng volume smartpool_* của stack cũ trong cấu hình mới.

## Kiểm tra và quay lui

Ngày 18/11/2026: API health LAN báo HTTP 200, DB/API healthy, MQTT diagnostics đăng nhập và pub/sub qua WSS thành công. Chưa chạy collection Postman hoặc thử negative ACL.

Adminer vẫn tại http://192.168.60.87:8080, server db, database pool_db, DB user smartpool. Các API dùng URL đầy đủ trong file endpoint mới. Android/ESP hiện chưa tích hợp đầy đủ v2.

Volume cũ đã được xóa theo yêu cầu cài mới; không còn bản quay lui bằng Docker volume trên Pi này. Sao lưu DB bằng `sudo bash pool.sh backup` trước khi thay đổi dữ liệu hoặc cấu hình lớn.

Xem [database/API v2](../../docs/database-v2.md) và [giao thức broker](../../docs/mqtt-v2.md); đường dẫn vận hành hiện tại dùng thư mục này.
