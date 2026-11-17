# Backend Smart Pool trên Raspberry Pi 4 B

[Luồng toàn hệ thống ESP → Pi → Android](../../docs/system-flow.md) · [README tổng thể](../../README.md)

Bộ triển khai chạy API PHP/Apache, MariaDB 11.4 và Mosquitto 2 bằng Docker Compose trên **hệ điều hành 64-bit**. Dữ liệu database và MQTT dùng volume bền vững; các dịch vụ tự chạy lại khi Pi khởi động. Không cần XAMPP hoặc HiveMQ Cloud cho cấu hình LAN này.

**Tunnel mặc định hiện là Cloudflare Quick Tunnel**, miễn phí cho thử nghiệm và không cần tài khoản/token. Trên ESP thực tế, HTTPS và MQTT WSS đã kết nối thành công qua Cloudflare. URL ngẫu nhiên tự ghi vào **`~/smartpool-endpoints.txt`**, cập nhật mỗi 10 giây và sau khi tunnel khởi động lại. Xem phần Cloudflare bên dưới.

## Script quản lý chung

Sau khi chép mã lên Pi bằng `send-to-pi.ps1`, dùng một script để quản lý backend, MariaDB, MQTT, tunnel và service ghi địa chỉ:

```bash
cd ~/smartpool/deploy/raspberry-pi
sudo bash pool.sh up 192.168.60.87   # lần đầu; Cloudflare không cần token
sudo bash pool.sh admin             # tạo admin API lần đầu

sudo bash pool.sh up                # bật lại hoặc triển khai mã mới
sudo bash pool.sh stop              # dừng toàn bộ, giữ dữ liệu và mật khẩu
sudo bash pool.sh restart           # dừng rồi build/khởi động lại toàn bộ
sudo bash pool.sh status            # trạng thái container và URL Cloudflare
sudo bash pool.sh logs              # theo dõi log tất cả container
sudo bash pool.sh logs mqtt         # có thể chọn api, db, cloudflare
sudo bash pool.sh endpoints         # đọc file địa chỉ của tunnel đang dùng
sudo bash pool.sh tunnel            # bật/kiểm tra Cloudflare Quick Tunnel
sudo bash pool.sh backup            # sao lưu MariaDB
```

`Ctrl+C` thoát xem log, dịch vụ tiếp tục chạy. `up` gồm kiểm tra/cài Docker, build/chạy backend, smoke test rồi bật tunnel và exporter. Cloudflare Quick Tunnel là tunnel duy nhất của bộ triển khai. Các script riêng bên dưới vẫn dùng được.

`stop` giữ container, volume và mật khẩu; có thể gọi nhiều lần, kể cả khi chưa bật tunnel. Lệnh này cũng tắt tự khởi động exporter; `up` bật lại. `restart` làm ngắt kết nối thiết bị trong lúc dịch vụ khởi động lại, và URL Cloudflare có thể đổi.

## 1. Chép mã từ Windows lên Pi

Trong PowerShell tại `D:\esp\smartpool`:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\raspberry-pi\send-to-pi.ps1
```

Script chỉ đóng gói backend và cấu hình triển khai, không chép các file `.env*`, bản sao lưu hoặc thông tin MQTT cục bộ. Nhập mật khẩu SSH trực tiếp khi `scp` / `ssh` hỏi. Script mở phiên SSH tại thư mục triển khai; không dùng terminal này để gửi mật khẩu vào chat.

## 2. Cài và chạy trên Pi

```bash
cd ~/smartpool/deploy/raspberry-pi
uname -m                         # cần aarch64
sudo bash install-docker.sh
sudo bash deploy.sh 192.168.60.87
sudo bash cloudflare.sh start
sudo docker compose exec api php /opt/smartpool/create_admin.php
```

Script cài Docker dùng repository có chữ ký của Docker cho Debian/Raspberry Pi OS hoặc Ubuntu 64-bit. Nếu máy đã có Docker Engine và Compose thì giữ nguyên. Nếu apt báo xung đột Docker cũ, kiểm tra gói hiện có trước khi thay đổi; script không tự gỡ gói.

`deploy.sh` tự tạo mật khẩu ngẫu nhiên trong `.env` (quyền `600`), tạo database/tables ở lần chạy đầu, khởi động dịch vụ và kiểm tra API cùng MQTT publish/subscribe. Admin API được tạo tương tác, độc lập với tài khoản MQTT. Không dùng `docker compose config` không có `--quiet` khi chia sẻ log vì kết quả có thể chứa mật khẩu.

Chỉ cổng HTTP `80` và MQTT `1883` được mở trên IP LAN đã chỉ định. MariaDB không mở cổng ra mạng. Nếu cổng đã bị dịch vụ khác chiếm, sửa `HTTP_PORT` hoặc `MQTT_PORT` trong `.env`, rồi chạy lại. Nếu IP Pi thay đổi, cập nhật `PI_BIND_IP` và địa chỉ trong app/ESP; nên đặt DHCP reservation trên router.

## 3. Chuyển Android và ESP sang Pi

Đọc mật khẩu ngay trên terminal Pi bằng `sudo cat .env`; không đưa `.env` lên Git hoặc gửi vào chat.

| Thiết bị | Cấu hình | Giá trị |
| --- | --- | --- |
| Android, Settings → HTTP | API URL | `http://192.168.60.87/pool_api/pool_api.php` |
| Android, Settings → MQTT | Broker URI | `tcp://192.168.60.87:1883` |
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

SQL chỉ tự nhập khi volume database trống. Nếu chuyển dữ liệu từ XAMPP, xuất `pool_db` rồi nhập vào MariaDB trước khi tạo admin mới; dữ liệu phục hồi sẽ thay thế các bảng cùng tên. Không cần một MQTT consumer ghi database vì firmware hiện gửi mẫu đo vào API qua HTTP.

Backend cũng chạy được với XAMPP: chép cả `pool_api.php` và `database.php` vào cùng thư mục. Khi không có biến môi trường `POOL_DB_*`, kết nối vẫn dùng `localhost`, `root`, mật khẩu rỗng và `pool_db` như trước.

## 5. Cloudflare Quick Tunnel: miễn phí, URL ngẫu nhiên vào file text

```bash
cd ~/smartpool/deploy/raspberry-pi
sudo bash pool.sh tunnel
cat ~/smartpool-endpoints.txt
```

Không cần authtoken, thẻ hoặc domain riêng. Cloudflare chuyển tiếp HTTPS API và MQTT WSS trên cùng URL vào Apache; broker Mosquitto vẫn chạy trên Pi. File có dạng:

```text
updated_at=...
status=online
provider=cloudflare
api_url=https://random-name.trycloudflare.com
http_api_url=https://random-name.trycloudflare.com/pool_api/pool_api.php
android_http_api_url=https://random-name.trycloudflare.com/pool_api/pool_api.php
mqtt_wss_uri=wss://random-name.trycloudflare.com/mqtt
```

Nhập `http_api_url` cho ESP/Android/Postman; dùng `mqtt_wss_uri` cho ESP/Android/MQTTX. Username MQTT vẫn là `esp32` / `android`; mật khẩu không đổi khi chuyển tunnel. MariaDB chỉ mở trong mạng Docker, không publish cổng ra LAN hoặc Quick Tunnel; dùng API hoặc công cụ bên trong container để truy cập.

`pool.sh up` giữ container đang chạy nếu cấu hình không đổi; không tạo thêm một tunnel mỗi lần gọi. Nếu tiến trình Cloudflare khởi động lại, domain có thể đổi. Service `smartpool-cloudflare-endpoints.service` tự phát hiện thế hệ container mới và cập nhật file mỗi 10 giây; khi dừng/ngắt kết nối, file ghi offline và bỏ URL cũ. File thuộc tài khoản gọi `sudo` (ví dụ `qnm`), quyền `600`, không chứa mật khẩu.

**Khi domain đổi, cần cập nhật cấu hình URL của ESP, Android, MQTTX và Postman.** File text không tự gửi cấu hình tới thiết bị ở mạng khác. Docker và exporter tự chạy sau reboot nếu trước đó chưa bị dừng chủ động bằng `pool.sh stop`.

```bash
sudo bash pool.sh status
sudo bash pool.sh logs cloudflare
sudo python3 smoke-wss.py wss://DOMAIN_TRONG_FILE/mqtt
sudo bash pool.sh stop
sudo bash pool.sh up
```

Cloudflare Quick Tunnels dành cho thử nghiệm/phát triển, không có cam kết uptime. Tên miền ổn định cho triển khai lâu dài cần một named tunnel và domain phù hợp. [Tài liệu Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/).

## 6. Cấu hình ESP và Android từ ngoài mạng

Dùng `http_api_url` và `mqtt_wss_uri` trong `~/smartpool-endpoints.txt`. MQTT user `esp32` dành cho ESP, `android` dành cho app/MQTTX; mật khẩu lấy từ `.env` hoặc `~/smartpool-credentials.txt` nếu đã reset trên Pi. Giữ topic `smartpool/...` mặc định. Postman dùng URL HTTP API đầy đủ có `/pool_api/pool_api.php`.

Firmware cần hỗ trợ lưu URI `wss://`. Mở trang local của ESP, lưu URL HTTP API, rồi lưu broker WSS cùng username/password; lưu MQTT sẽ khởi động lại ESP. ESP cần Internet qua cổng `443` và đồng bộ NTP để xác minh chứng chỉ. HTTPS và WSS dùng chung backend Apache/Mosquitto trên Pi, không cần VPN trên ESP.

Kiểm tra từ Pi qua domain đang có trong file, không gửi lệnh bơm:

```bash
sudo python3 smoke-wss.py wss://DOMAIN_TRONG_FILE/mqtt
```

Script đăng nhập cả hai tài khoản và thử publish/subscribe topic `smartpool/diagnostics/probe`. Nếu URL Quick Tunnel đổi sau reboot, cập nhật lại URI trên ESP, Android, MQTTX và Postman; file text không tự sửa các client. Compose hiện không publish cổng MariaDB ra host.
