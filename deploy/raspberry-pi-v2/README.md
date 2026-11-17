# Smart Pool – bộ triển khai v2 tách biệt

Bộ đang hoạt động: `deploy/raspberry-pi-v2` trong repository; trên Pi là `/home/qnm/smartpool-v2/deploy/raspberry-pi-v2`. Project Docker `smartpool-v2`, network/container và volume riêng. Thư mục `deploy/raspberry-pi` cùng stack `smartpool` cũ được giữ để quay lui, đang dừng; không chạy đồng thời vì dùng chung cổng LAN 80/1883/8080.

Đã chuyển dữ liệu và áp dụng chính sách mật khẩu theo nhóm ngày 17/11/2026. Secret chỉ nằm trong file private/runtime trên Pi, không ghi giá trị vào tài liệu hoặc Git:

| Nhóm | Áp dụng |
| --- | --- |
| Quản trị | App role admin, MariaDB root, MQTT bridge |
| User/thiết bị | App role user, DB user smartpool, MQTT diagnostics và account ESP |

Admin app hỗ trợ 7–72 byte; user app 8–72 byte. Bulk reset giữ tài khoản/dữ liệu, ghi audit, thu hồi session và hủy lệnh pending. User mới tạo qua API vẫn có password do admin cung cấp; dùng bulk reset khi muốn áp lại chính sách chung cho toàn bộ user.

Mật khẩu dùng chung theo nhóm là lựa chọn vận hành hiện tại: các MQTT account thiết bị không còn secret riêng. Người biết password nhóm thiết bị và ID khác có thể đăng nhập dưới username đó; ACL theo username không ngăn được việc mạo danh này. Không coi diagnostics account hoặc các thiết bị là được cách ly bằng secret độc lập.

## Các file vận hành mới

- `~/smartpool-v2-endpoints.txt`: URL Quick Tunnel đang dùng, refresh 10 giây.
- `~/smartpool-v2-credentials.txt`: thông tin truy cập hiện tại, file 600.
- `~/smartpool-v2-mqtt-diagnostics.txt`: account MQTT Web diagnostics, file 600.
- `~/smartpool-v2-mqtt/DEVICE_ID.txt`: credential từng account ESP đã cấp.
- `.env` và `.env.mqtt-policy`: cấu hình private trên Pi, không chép vào Git.

File credential/endpoint của stack cũ không dùng cho deployment mới. Systemd mới là smartpool-v2-cloudflare-endpoints.service; cache exporter /var/lib/smartpool-v2; metrics cục bộ 20242. URL Cloudflare đổi sau chuyển stack.

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

Không chạy reset_database.php hoặc down -v để cập nhật. Dữ liệu hiện tại đã được chuyển, không cần chuyển lại hoặc tạo lại admin.

## MQTT và password policy

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
sudo python3 setup-passwords.py PI_LAN_IP
sudo bash pool.sh up PI_LAN_IP
sudo bash pool.sh admin
```

Nhập policy bằng prompt ẩn. init chỉ tạo file auth/account hệ thống, không tạo tài khoản app hoặc thiết bị giả. Volume MQTT external có tên riêng smartpool-v2_mqtt-auth/data, được deploy.sh tạo nếu chưa tồn tại. Không dùng volume smartpool_* của stack cũ trong cấu hình mới.

## Kiểm tra và quay lui

API health LAN/Cloudflare đã xác nhận HTTP 200; số lượng user/device/membership/sample giữ nguyên lúc chuyển; hash password ứng dụng đã đối chiếu theo policy. Chưa chạy collection Postman hoặc thử MQTT pub/sub/negative ACL.

Adminer vẫn tại http://192.168.60.87:8080, server db, database pool_db, DB user smartpool. Các API dùng URL đầy đủ trong file endpoint mới. Android/ESP hiện chưa tích hợp đầy đủ v2.

Backup trước chuyển: `/home/qnm/smartpool-rollbacks/isolate-20261117-230721/`, cần sudo. Stack cũ và volume cũ giữ nguyên, đang dừng. Muốn quay lui phải dừng stack/exporter mới rồi mới bật stack cũ; dữ liệu ghi sau chuyển không tự xuất hiện trong volume cũ. Không chạy hai stack cùng các cổng LAN hoặc xóa volume để quay lui.

Xem [database/API v2](../../docs/database-v2.md) và [giao thức broker](../../docs/mqtt-v2.md); đường dẫn vận hành hiện tại dùng thư mục này.
