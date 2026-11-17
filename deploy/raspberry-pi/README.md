# Backend Smart Pool trên Raspberry Pi 4 B

Stack Docker Compose: PHP 8.3/Apache, MariaDB 11.4, Mosquitto, Adminer và Cloudflare Quick Tunnel. **Database/API v2 baseline 004 đã triển khai trên Pi ngày 17/11/2026**, health LAN/Cloudflare thành công; chưa chạy collection Postman. Android và firmware hiện tại chưa tương thích v2; chưa có BLE hoặc worker MQTT theo thiết bị. Admin mới lấy credential từ `~/smartpool-v2-admin.txt`, không dùng APP_PASSWORD cũ.

Tài liệu chính: [database/API v2, reset, Postman và MQTT Web](../../docs/database-v2.md). Không dùng hướng dẫn migration/collection giai đoạn 1 cho bản này.

## Chép source

Tại PowerShell trong D:\esp\smartpool:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\raspberry-pi\send-to-pi.ps1
```

Script chép backend/deploy và mở SSH; không chép .env hoặc backup. Nếu thư mục trên Pi thuộc root khiến tar báo Cannot utime/change mode, kiểm tra chủ sở hữu thư mục source và sửa đúng phạm vi; không đổi quyền đệ quy volume/database hoặc .env.

## Cài mới

Cần hệ điều hành 64-bit trên Pi, IP LAN ổn định. Trên DB trống:

```bash
cd ~/smartpool/deploy/raspberry-pi
sudo bash pool.sh up 192.168.60.87
sudo bash pool.sh admin
```

pool.sh tạo/giữ .env, cài Docker nếu cần, tạo schema mới, chạy migration, khởi động API/broker/Adminer, chạy smoke test có sẵn và bật tunnel/exporter. Mật khẩu DB/MQTT lấy từ .env, không đưa vào Git hoặc log. Admin được tạo tương tác, không seed mật khẩu vào source. Chưa có thiết bị/user sau cài mới; dùng API admin đăng ký và cấp thành viên.

**DB cũ có bảng pool_*:** migrate.php sẽ dừng, không tự reset. Thực hiện quy trình reset riêng trong tài liệu v2; lệnh đó xóa toàn bộ dữ liệu Smart Pool đã biết, không giữ/chuyển tài khoản, thiết bị, số đo hoặc nhật ký. Không dùng down -v. Dừng API cũ trước reset và tạo admin mới sau reset. Credential MQTT và mật khẩu tài khoản MariaDB không đổi khi reset bảng ứng dụng.

## Cập nhật API v2 thông thường

Sau khi đã chuyển sang schema v2, cập nhật mã mà không restart DB/MQTT/tunnel:
## 3. Chuyển Android và ESP sang Pi

Đọc mật khẩu ngay trên terminal Pi bằng `sudo cat .env`; không đưa `.env` lên Git hoặc gửi vào chat.

| Thiết bị | Cấu hình | Giá trị |
| --- | --- | --- |
| Android, Cài đặt → Thiết bị → Kết nối HTTP | API URL | `http://192.168.60.87/pool_api/pool_api.php` |
| Android, Cài đặt → Thiết bị → Kết nối MQTT | Broker URI | `tcp://192.168.60.87:1883` |
| Android | MQTT username/password | `android` / `MQTT_APP_PASSWORD` trong `.env` |
| ESP, `idf.py menuconfig` → Smart Pool Network | API URL (`POOL_API_URL`) | `http://192.168.60.87/pool_api/pool_api.php` |
| ESP, cùng menu | Broker URI (`POOL_MQTT_URI`) | `mqtt://192.168.60.87:1883` |
| ESP, `main/mqtt_credentials.h` | `SMARTPOOL_MQTT_USERNAME` / `SMARTPOOL_MQTT_PASSWORD` | `esp32` / `MQTT_ESP_PASSWORD` trong `.env` |

Bật HTTP telemetry, MQTT và Wi-Fi trong menuconfig; nếu chọn transport điều khiển thì chọn MQTT. Tạo `mqtt_credentials.h` từ file `.example` nếu chưa có, sau đó build/flash lại ESP. Đổi broker trong Settings Android không đổi broker của ESP. URL HTTP từng lưu trong NVS cũng có thể ghi đè menuconfig: sau khi ESP kết nối broker mới, lưu/đồng bộ URL HTTP từ app và kiểm tra URL ESP báo trong status. Giữ tên topic `smartpool/...` mặc định để khớp ACL.

ACL cho ESP đọc lệnh/cấu hình và ghi telemetry/status/ack; Android đọc trạng thái và ghi lệnh/cấu hình. Tài khoản `android` có quyền điều khiển và cấu hình thiết bị, nên chỉ cấp cho người được phép. Role guest trong API không tự giới hạn quyền tài khoản MQTT này.

HTTP và MQTT TCP trong cấu hình LAN này không mã hóa. Cloudflare bên dưới cung cấp HTTPS API và MQTT WSS để truy cập từ ngoài mạng.

## 4. Kiểm tra và vận hành

```bash
sudo docker compose ps
sudo docker compose logs --tail=100 api db mqtt
sudo bash smoke-test.sh
curl -f 'http://192.168.60.87/pool_api/pool_api.php?commands=1'
sudo bash backup.sh
```

Bài kiểm tra không gửi lệnh bơm hoặc chèn mẫu đo giả; MQTT dùng topic chẩn đoán riêng. Khi kiểm tra tích hợp, chờ ESP online, xem telemetry/lịch sử trong app rồi thử bơm bằng thao tác chủ động.

Cập nhật mã bằng `send-to-pi.ps1` rồi chạy lại `sudo bash deploy.sh`; `.env` và volume được giữ lại. Không dùng `docker compose down -v` vì tùy chọn `-v` xóa volume dữ liệu. Không đổi mật khẩu DB trong `.env` sau khi đã khởi tạo database mà chưa đổi mật khẩu tài khoản tương ứng trong MariaDB.

Sao lưu bằng `backup.sh` tạo `backups/pool_db-*.sql.gz`; giữ thêm bản sao `.env` ở nơi an toàn ngoài Pi. Phục hồi vào stack đã chạy (thay tên file đúng):

```bash
gzip -dc backups/pool_db-YYYYMMDD-HHMMSS.sql.gz | sudo docker compose exec -T db sh -ec 'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb -u "$MARIADB_USER" "$MARIADB_DATABASE"'
```

Schema ban đầu chỉ tự nhập khi volume database trống. Riêng migration nhật ký `backend/migrations/001_activity_log.sql` được `deploy.sh` áp dụng cả khi volume đã có dữ liệu, bằng `CREATE TABLE IF NOT EXISTS`, không xóa bảng cũ. Khi khôi phục từ bản sao lưu, dữ liệu phục hồi sẽ thay thế các bảng cùng tên. Không cần một dịch vụ nhận MQTT để ghi database vì firmware gửi mẫu đo vào API qua HTTP.

### Cập nhật nhật ký thao tác và quản lý admin

Sau khi chép backend và cấu hình triển khai mới lên Pi, chạy:

```bash
cd ~/smartpool/deploy/raspberry-pi
sudo docker compose -f compose.yaml -f compose.cloudflare.yaml build api
sudo docker compose -f compose.yaml -f compose.cloudflare.yaml run --rm --no-deps -T api php /opt/smartpool/migrate.php
sudo docker compose -f compose.yaml -f compose.cloudflare.yaml up -d --no-deps --no-build api
```

Runner chỉ chạy migration số 004 trở đi, kiểm tra checksum và không nhập dữ liệu legacy. Cài mới dùng pool_db.sql, cùng baseline 004. Không sửa migration đã áp dụng; thêm file phiên bản mới. DDL có implicit commit, xem hướng dẫn xử lý lỗi/rollback trong tài liệu v2.

## Script quản lý

```bash
sudo bash pool.sh up
sudo bash pool.sh stop
sudo bash pool.sh restart
sudo bash pool.sh status
sudo bash pool.sh logs api
sudo bash pool.sh logs mqtt
sudo bash pool.sh logs cloudflare
sudo bash pool.sh endpoints
sudo bash pool.sh backup
```

Ctrl+C chỉ thoát xem logs. stop giữ volume và .env nhưng dừng exporter; up bật lại. Container dùng restart: unless-stopped; Docker và exporter được cài để chạy sau reboot nếu chưa bị dừng chủ động.

## API và Adminer trong LAN

API v2: http://192.168.60.87/pool_api/pool_api.php. Kiểm tra read-only health bằng GET ?action=health; endpoint dữ liệu dùng POST JSON và xác thực. Health chỉ xác nhận DB/API cơ bản, không thay thế kiểm tra quyền/integration.

Adminer: http://192.168.60.87:8080, hệ thống MySQL, server db, database pool_db, user smartpool, password DB_PASSWORD trong .env. Adminer vào DB trực tiếp nên không có audit ứng dụng cho thay đổi SQL thủ công. MariaDB không publish cổng 3306 ra host.

## Cloudflare Quick Tunnel

```bash
sudo bash pool.sh tunnel
cat ~/smartpool-endpoints.txt
```

Không cần tài khoản/token. HTTPS API và WSS /mqtt đi chung domain ngẫu nhiên; Apache proxy WebSocket vào mqtt:9001. File endpoint cập nhật mỗi 10 giây, có api_url, http_api_url/android_http_api_url và mqtt_wss_uri. URL có thể đổi sau restart; file không tự sửa app/ESP/Postman.

API v2 dùng HTTP token user hoặc key ESP theo thiết bị. Broker đã chuyển account/ACL theo thiết bị; tài khoản chung esp32/android không còn dùng. Xem [broker MQTT v2](../../docs/mqtt-v2.md) và mqtt-admin.py để cấp account cho registry active. HTTP key không đăng nhập được MQTT; membership/inactive DB chưa tự thu hồi account ESP. Firmware phải cập nhật namespace mới trước khi kết nối.

## Kiểm tra do người vận hành thực hiện

- [Collection Postman v2](../../backend/postman/SmartPool.v2.postman_collection.json): hai user/hai thiết bị và số đo giả, không gửi lệnh MQTT.
- MQTT Web: dùng mqtt_wss_uri, WSS port 443, path /mqtt, credential từ ~/smartpool-mqtt-diagnostics.txt; subscribe/publish smartpool/diagnostics/_smartpool_diag/probe với retain OFF.
- smoke-test.sh hiện chỉ kiểm tra API v2 health; script smoke-wss.py dùng diagnostics và chỉ chạy khi người vận hành chủ động gọi.
- Firmware/app cũ chưa tích hợp payload v2, revision, danh sách/chọn thiết bị hoặc BLE. Xem danh sách công việc tiếp theo trong tài liệu v2.

## Sao lưu và quay lui

backup.sh tạo backups/pool_db-*.sql.gz với single-transaction; giữ bản sao bảo vệ ngoài Pi cùng .env và image/API tương ứng. Chưa có lịch tự xóa/archive. Không chỉ sửa DB_PASSWORD trong .env khi tài khoản MariaDB chưa đổi.

Sau reset, image/API cũ không thể khôi phục dữ liệu cũ; phải phục hồi backup tương ứng vào DB phù hợp. Tự tạo backup dữ liệu v2 phát sinh trước khi phục hồi. Không tự import đè database đang hoạt động hoặc xóa audit để dọn test.
