# Smart Pool

Deployment đang dùng: [deploy/raspberry-pi-v2](deploy/raspberry-pi-v2/README.md), trên Pi tại ~/smartpool-v2. Stack/thư mục cũ giữ để quay lui. Endpoint mới: ~/smartpool-v2-endpoints.txt.

Hệ thống ESP32-S3, Raspberry Pi 4 B và Android cho giám sát/điều khiển bể bơi. Pi chạy PHP/Apache, MariaDB và Mosquitto bằng Docker Compose; Cloudflare Quick Tunnel cung cấp HTTPS/WSS.

**Database/API là v2 nhiều thiết bị.** Migration 005 và API/worker mới đã [triển khai Pi ngày 18/11/2026](docs/deployment-runtime-005.md), xác nhận health LAN/Cloudflare và heartbeat worker. Source ESP có đồng bộ cấu hình HTTP, thời điểm đo trong queue NVS và chống lặp lệnh; chưa build/flash hoặc xác nhận đầu cuối. Các việc Android cần cập nhật được [ghi riêng](docs/esp-api-mqtt-runtime.md#các-việc-cần-làm-trên-android-chưa-sửa-code). BLE chưa triển khai. Lần chạy collection Postman v2 trước đó thất bại; chưa chạy lại collection trong lần triển khai này.

## Tài liệu chính

- [Runtime 009 đã deploy: xóa server → reset ESP → thu hồi MQTT](docs/device-deprovision-009.md). Firmware mới còn cần build/nạp; chưa thử reset trên bo thật.

- [Source 007/008: danh mục MAC, admin thêm ID/MAC duy nhất, xóa device giữ MAC](docs/hardware-inventory-delete.md) — chưa chạy migration/deploy Pi.

- [Source runtime 006: khóa HTTP chung, ID–MAC gốc và migration database](docs/shared-key-hardware.md) — chưa áp dụng Pi.

- [Cấp ID/khóa ESP qua local, firmware dùng chung và kế hoạch Scan new device/BLE cho admin](docs/device-provisioning-local-and-ble.md).

- [Mô hình database, API, reset, Postman và MQTT Web](docs/database-v2.md).
- [Luồng xử lý và phần tích hợp còn lại](docs/system-flow.md).
- [Triển khai Pi và quản lý Docker](deploy/raspberry-pi-v2/README.md).
- [Collection Postman v2](backend/postman/SmartPool.v2.postman_collection.json).
- [Runtime ESP/API/MQTT 005, nâng cấp, quay lui và checklist Android/Postman/MQTT Web](docs/esp-api-mqtt-runtime.md).
- [Firmware hiện tại](esp-firmware/README.md) và [giao thức MQTT hiện tại](README_MQTT_ESP32.md).

## Mô hình mới
- Giao diện sáng navy–cyan với thanh điều hướng **Tổng quan / Điều khiển / Hệ thống**; Nhật ký và Cài đặt ở thanh trên. Cài đặt được nhóm thành các phần mở/thu gọn.
- Mục **Hệ thống** hiển thị lưu đồ Android/API/MariaDB, MQTT/ESP32 và các cảm biến, bơm, Wi-Fi, khóa liên động, tự động hóa. Cập nhật theo API/MQTT, phân biệt bật/tắt, đang chờ và chưa rõ khi mất liên lạc.
- Nhật ký thao tác lưu cục bộ, đồng bộ về MariaDB qua API có xác thực. Guest xem nhật ký của mình, admin xem các tài khoản. Kết quả phân biệt yêu cầu, xác nhận, lỗi và hết thời gian chờ.
- Biểu đồ dùng ngày/giờ máy chủ nhận mẫu, giữ đúng khoảng thời gian giữa các điểm. App khôi phục cấu hình đã lưu và lựa chọn tab, không tự bật lại bơm.
- Admin có thể tạo admin khác hoặc guest. Tài khoản admin hiện có được bảo vệ khỏi sửa/xóa trong giao diện quản lý.
- [Lưu đồ trạng thái ứng dụng điện thoại](docs/system-flow.md#10-trạng-thái-ứng-dụng-điện-thoại) mô tả đăng nhập, màn hình và vòng chờ xác nhận lệnh.

Admin đăng nhập → danh sách thiết bị → đăng ký ID (nhập tay; sau này đọc BLE) → kích hoạt → cấp user vào từng thiết bị. User đăng nhập → danh sách thiết bị được cấp → chọn device_id cho mọi trạng thái, số đo, cấu hình, biểu đồ và nhật ký.

Quan hệ nhiều–nhiều nằm ở `device_users`. User có thể có nhiều thiết bị và một thiết bị có nhiều user, với các quyền `can_view`, `can_control`, `can_configure`. Admin quản trị toàn hệ thống. Gỡ membership không xóa tài khoản, dữ liệu hoặc quyền ở thiết bị khác.

Mỗi mẫu có `device_id + sample_id` duy nhất, giờ đo nullable và giờ server nhận riêng. Config có revision và tách khỏi trạng thái ESP đã báo. Audit backend ghi trong cùng giao dịch, phân biệt nhật ký client/device. Wi-Fi password dự kiến truyền trực tiếp BLE, không lưu trong server/log.

API cấu hình/lệnh hiện chỉ lưu dữ liệu: chưa có worker publish MQTT hoặc BLE. Broker đã tách account/ACL theo thiết bị; app user không được dùng bridge credential. Xem [broker MQTT v2](docs/mqtt-v2.md).

## Cài đặt và chuyển đổi

Database cũ không được tự nhập sang mô hình mới. Xem [quy trình reset có chủ đích](docs/database-v2.md#cai-moi-hoac-reset-database-cu) trong tài liệu v2 trước khi triển khai. `pool.sh up` và `migrate.php` không tự reset; CLI reset riêng xóa mọi dữ liệu Smart Pool khi người vận hành gọi với cờ xác nhận. Không dùng `down -v`.

Schema chuẩn: `backend/migrations/004_multi_device.sql`; `backend/pool_db.sql` là bootstrap cho DB trống. Migration 001–003 chỉ là lịch sử, runner v2 bỏ qua. Cần tạo lại admin, user, registry và key HTTP sau reset. Credential MQTT chưa liên thông với DB này.

## Sơ đồ đấu nối

Sơ đồ dưới đây dùng **một nguồn 12 V** và hai nhánh hạ áp riêng. Cả hai bơm trong sơ đồ là loại **5 V DC**. Chọn bộ hạ áp và nguồn 12 V theo dòng khởi động thực tế của bơm; nhãn 12 V–2 A của nguồn không cho biết bộ hạ áp 5 V có đủ dòng cho bơm hay không.

```text
                         NGUỒN 12 V
                      +12 V       GND
                        │          │
            ┌───────────┴──────────┴───────────┐
            │                                   │
     Bộ hạ áp A: 12 → 5 V                Bộ hạ áp B: 12 → 5 V
       nhánh cấp bơm                      nhánh điều khiển
            │                                   ├── bo ESP32-S3: chân 5V/VIN (*)
            ├── +5 V → COM1 relay               └── module relay: VCC 5 V (**)
            │             NO1 → (+) bơm 1
            ├── +5 V → COM2 relay
            │             NO2 → (+) bơm 2
            └── GND → (-) của hai bơm

     ESP GPIO15 ───────────────────────────────→ IN1 relay
     ESP GPIO16 ───────────────────────────────→ IN2 relay
     ESP GND    ───────────────────────────────→ GND relay và GND cảm biến

     ESP 3V3 ──┬───────────────────────────────→ VDD DS18B20
               └── điện trở 4,7 kΩ ──┐
     ESP GPIO9 ──────────────────────┴────────→ DQ DS18B20
     ESP GND ──────────────────────────────────→ GND DS18B20

     ESP GPIO10 ←──────────────────────────────── AO cảm biến mực nước
     ESP GND ──────────────────────────────────→ GND cảm biến mực nước
```

(*) Chỉ cấp 5 V vào chân **5V/VIN nếu bo ESP32-S3 của bạn hỗ trợ**. Nếu dùng module ESP32-S3 trần, phải dùng bộ ổn áp 3,3 V phù hợp; không đưa 5 V vào chân 3V3. Không nối 12 V trực tiếp vào ESP, cảm biến hoặc bơm 5 V.

(**) Cấp nguồn cho module relay đúng điện áp ghi trên module. GPIO15 và GPIO16 chỉ điều khiển chân IN; không cấp dòng cho bơm. Firmware hiện chọn **kích mức HIGH**: GPIO HIGH bật relay, GPIO LOW tắt relay. Có thể đổi mức kích trong `menuconfig → Relay Configuration → Relay input active level`. Dùng tiếp điểm **COM–NO** để bơm mặc định tắt khi relay chưa hút. Sau khi nạp firmware, thử đèn relay khi chưa nối bơm: lệnh `0` phải làm đèn tắt, lệnh `1` làm đèn sáng. Nếu không đúng, dừng thử với tải và kiểm tra chốt chọn mức kích cùng điện áp chân IN.

Đường GND của ESP, relay và cảm biến cần chung mốc điện áp. Cho dòng bơm đi trên dây nguồn và dây GND riêng về nhánh cấp bơm, tránh đi qua dây GND của ESP. Nếu cảm biến mực nước được cấp 5 V, phải bảo đảm điện áp tại AO **không vượt 3,3 V** trước khi nối vào GPIO10; dùng mạch chia áp nếu cần. Nếu bo cảm biến hỗ trợ cấp 3,3 V, có thể cấp từ 3V3 của ESP.

| Thiết bị | Chân ESP32-S3 | Ghi chú |
| --- | --- | --- |
| DS18B20 DQ | GPIO9 | Điện trở kéo lên 4,7 kΩ từ DQ đến 3V3 |
| Relay 1 / bơm vào | GPIO15 | IN1, mức kích chọn trong Kconfig |
| Relay 2 / bơm ra | GPIO16 | IN2, mức kích chọn trong Kconfig |
| Cảm biến mực nước AO | GPIO10 | ADC1, tín hiệu tối đa 3,3 V |

DS18B20 có thể cấp VDD bằng 5 V, nhưng dây DQ nối GPIO9 của ESP32-S3 phải được kéo lên **3V3**, không kéo lên 5 V. Nếu dùng module DS18B20 có điện trở kéo lên tích hợp, kiểm tra điện áp DQ khi bus rảnh và vị trí nối điện trở trên module trước khi cắm vào ESP.

## Cấu hình hiện tại trong source

| Thành phần | Cấu hình | Giá trị / nơi thay đổi |
| --- | --- | --- |
| Pi | IP LAN triển khai | `192.168.60.87`; `PI_BIND_IP` trong `.env` |
| ESP | Hai cảm biến và hai relay | Đều bật trong `esp-firmware/sdkconfig` |
| ESP | Chu kỳ lấy mẫu | 60 giây khi hai bơm tắt; 5 giây khi có bơm chạy |
| ESP | Relay lúc khởi động | Hai bơm OFF; mức kích HIGH trên GPIO15/16 |
| ESP | API và broker mặc định | Cloudflare trong `sdkconfig`, `sdkconfig.defaults`, `main/Kconfig.projbuild` |
| Android | API mặc định | Cloudflare trong `AppConfig.DEFAULT_HTTP_API_URL`; đổi ngay màn hình đăng nhập hoặc Settings |
| Android | Broker sử dụng | Mosquitto trên Pi; nhập `mqtt_wss_uri` trong Cài đặt → Thiết bị → Kết nối MQTT |
| Android | Chu kỳ đọc API | Mặc định 3 giây khi MainActivity đang hiển thị |
| MQTT | Người dùng | `esp32` cho firmware, `android` cho app/MQTTX |
| DB | Database / user | `pool_db` / `smartpool`, chỉ API dùng trong mạng Docker |

Tên miền đang được ghi trong source tại lần cập nhật tài liệu:

```text
https://division-unlikely-drinking-disks.trycloudflare.com/pool_api/pool_api.php
wss://division-unlikely-drinking-disks.trycloudflare.com/mqtt
```

Đây là địa chỉ của một phiên Quick Tunnel, **có thể hết hiệu lực sau khi tunnel khởi động lại**. Nguồn địa chỉ vận hành là `~/smartpool-endpoints.txt` trên Pi. Không suy ra tunnel đang online chỉ từ URL trong source.

## Khởi động và kết nối các thành phần

### 1. Pi: chạy backend, broker, database và tunnel

Chép mã từ Windows theo [hướng dẫn triển khai](deploy/raspberry-pi/README.md), rồi trên Pi:

```bash
cd ~/smartpool/deploy/raspberry-pi
sudo bash pool.sh up 192.168.60.87  # lần đầu; lần sau chỉ cần pool.sh up
sudo bash pool.sh admin            # chỉ khi chưa có admin
cat ~/smartpool-endpoints.txt
```

Lấy `android_http_api_url` cho Android, `http_api_url` cho ESP và `mqtt_wss_uri` cho cả hai. `api_url` chỉ là địa chỉ gốc; API đầy đủ phải có `/pool_api/pool_api.php`.

`mqtt-init` kết thúc sau khi tạo file tài khoản là bình thường. Các container `db`, `api`, `mqtt`, `cloudflare` dùng `restart: unless-stopped`; exporter systemd ghi địa chỉ mỗi 10 giây. `pool.sh stop` chủ động dừng stack và exporter; chạy `up` để bật lại.

### 2. ESP: Wi-Fi, API, MQTT và cảm biến

Trong ESP-IDF terminal:

```powershell
cd D:\esp\smartpool\esp-firmware
idf.py menuconfig
idf.py build
idf.py -p COM3 flash monitor
```

Kiểm tra URL API/URI MQTT và tài khoản `esp32` trong `main/mqtt_credentials.h`. `sdkconfig` quyết định bản build hiện tại; `sdkconfig.defaults` dùng khi tạo cấu hình mới. Cổng COM tùy máy.

ESP luôn có AP `SmartPool-Setup` (mật khẩu mặc định `12345678`); mở `http://192.168.4.1` để chọn Wi-Fi và cấu hình **MQTT & HTTP**. Cũng có thể mở bằng IP LAN của ESP khi cùng mạng. AP được giữ để cấu hình local ngay cả khi STA đã kết nối router.

Thông tin Wi-Fi, broker/credential, API và quy tắc đã lưu trong NVS có thể được sử dụng sau reboot; riêng cấu hình API/MQTT đã lưu sẽ ghi đè mặc định firmware. Lưu API áp dụng cho các upload tiếp theo; lưu MQTT làm ESP khởi động lại. Muốn đổi endpoint sau khi flash, kiểm tra trang local thay vì chỉ nhìn `sdkconfig`.

### 3. Android: đăng nhập và chọn đúng broker

1. Build/cài app theo [hướng dẫn Android](android-app/README_MQTT_ESP32.md). Máy build cần Android SDK và JDK phù hợp với cấu hình Gradle của dự án.
2. Trên màn hình đăng nhập, nhấn **Cấu hình API** để xem hoặc cập nhật địa chỉ máy chủ, rồi đăng nhập tài khoản đã tạo trên Pi. Nếu nhập URL gốc, app tự bổ sung `/pool_api/pool_api.php`.
3. Với admin, mở **Cài đặt → Thiết bị → Kết nối MQTT**: nhập `mqtt_wss_uri`, username `android`, password tương ứng trên Pi. **Đổi API không tự đổi broker MQTT.**
4. Kiểm tra app đã nối broker và nhận `smartpool/status` có `online:1` từ ESP. API trả JSON thành công chưa đủ để xác nhận điều khiển bơm hoạt động.

Tài khoản app (`admin`/`guest`), tài khoản MQTT và tài khoản MariaDB là ba cơ chế riêng. Lấy mật khẩu từ cấu hình riêng trên Pi; tài liệu không lưu mật khẩu triển khai.

## Các luồng chính

| Luồng | Đường đi | Kết quả |
| --- | --- | --- |
| Lấy mẫu | Cảm biến → ESP → HTTPS POST API → MariaDB | Lưu trạng thái và lịch sử; hàng đợi NVS khi gửi lỗi |
| Hiển thị số đo | Android → HTTPS GET API → MariaDB | MainActivity cập nhật cảm biến; màn biểu đồ đọc lịch sử |
| Cảnh báo nền | ESP → MQTT status/telemetry → PoolMonitorService | App đánh giá ngưỡng và phát thông báo khi cảm biến đang bật |
| Bật/tắt cảm biến | Công tắc trên app → MQTT cmd → ESP → MQTT status | Bật/tắt đo nhiệt độ và mực nước; chờ ESP xác nhận |
| Điều khiển bơm | Android → MQTT cmd → ESP → GPIO relay → MQTT status | App chờ trạng thái ESP báo về; không coi publish thành công là bơm đã bật |
| Cấu hình | Settings → MQTT config → ESP → NVS → ACK/status | Lưu ngưỡng, thiết bị, API, Wi-Fi, tự động hóa |
| Tự động hóa | Cảm biến → quy tắc trên ESP → relay | Chạy tại ESP, không cần app luôn mở |
| Đăng nhập | Android → HTTPS login → pool_users / pool_user_sessions | Token phiên; quản lý guest yêu cầu admin |

Xem [trình tự chi tiết và hợp đồng dữ liệu](docs/system-flow.md).

## Vận hành và kiểm tra

```bash
cd ~/smartpool/deploy/raspberry-pi
sudo bash pool.sh status
sudo bash pool.sh logs api          # có thể chọn mqtt, db, cloudflare
sudo bash pool.sh endpoints
sudo bash pool.sh backup
sudo bash pool.sh stop
sudo bash pool.sh up
```

- API: mở URL đầy đủ, hoặc chạy bộ [Postman](backend/postman/README.md). Request kiểm tra token đã thu hồi nhận HTTP 403 là kết quả mong đợi.
- MQTT: dùng MQTTX với `mqtt_wss_uri`, user `android`; hoặc trên Pi chạy `sudo python3 smoke-wss.py wss://DOMAIN_TRONG_FILE/mqtt`. Smoke test dùng topic diagnostics, không gửi lệnh bơm.
- ESP: tìm log `SNTP synchronized`, `MQTT connected`, `HTTP POST OK`; web `/api/status` cho biết lỗi HTTP/MQTT và trạng thái cảm biến.
- Khi tunnel đổi URL: cập nhật cả API lẫn broker trên ESP và Android, cùng URL trong MQTTX/Postman. File text không tự đẩy cấu hình ra client.
- Database nằm trong volume Docker. `pool.sh stop` giữ dữ liệu; `docker compose down -v` xóa volume. Không chỉ sửa mật khẩu DB trong `.env` nếu tài khoản trong MariaDB chưa được đổi tương ứng.

## Giới hạn cần hiểu khi đọc trạng thái

Trạng thái relay là mức điều khiển GPIO, chưa có cảm biến phản hồi để xác nhận bơm thực sự quay. Số đo từ API có thể là mẫu cũ; app hỏi API mỗi 3 giây không làm ESP đo mỗi 3 giây. `received_at` là giờ server nhận mẫu, kể cả mẫu gửi bù.

Interlock là cấu hình: firmware ban đầu cho phép hai bơm cùng chạy, còn app mặc định bật interlock và có thể đồng bộ xuống ESP. Khi bật interlock, ESP từ chối bật đồng thời. Mất Wi-Fi/MQTT dừng bơm đang thuộc điều khiển từ xa; điều khiển local và tự động hóa trên ESP có cơ chế riêng, không mặc định dừng chỉ vì mất broker.

Role admin/guest và token bảo vệ các API quản lý tài khoản, nhưng chưa bảo vệ toàn bộ API dữ liệu/lệnh cũ hoặc phân quyền riêng từng user app tại MQTT. Xem [ranh giới xác thực hiện tại](docs/system-flow.md#xac-thuc-va-ranh-gioi-quyen). Backend còn endpoint lệnh HTTP tương thích cũ; firmware hiện tại đã bỏ việc polling lệnh HTTP, nên không dùng endpoint đó để điều khiển ESP.

Hệ thống triển khai trên Raspberry Pi bằng Docker Compose: PHP/Apache, MariaDB và Mosquitto; Cloudflare Quick Tunnel cung cấp truy cập HTTPS/WSS từ ngoài mạng.
