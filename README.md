# Smart Pool

Hệ thống dùng ESP32-S3 để đo nhiệt độ nước bằng DS18B20, đọc cảm biến mực nước dạng analog và điều khiển hai bơm qua relay. Ứng dụng Android và ESP trao đổi lệnh bơm qua HiveMQ Cloud (MQTT/TLS) hoặc XAMPP (HTTP), tùy cấu hình. Dữ liệu mới nhất và lịch sử luôn được gửi đến API PHP trên XAMPP trong mạng nội bộ.

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

     ESP GPIO16 ───────────────────────────────→ IN1 relay
     ESP GPIO17 ───────────────────────────────→ IN2 relay
     ESP GND    ───────────────────────────────→ GND relay và GND cảm biến

     ESP 3V3 ──┬───────────────────────────────→ VDD DS18B20
               └── điện trở 4,7 kΩ ──┐
     ESP GPIO15 ──────────────────────┴────────→ DQ DS18B20
     ESP GND ──────────────────────────────────→ GND DS18B20

     ESP GPIO18 ←──────────────────────────────── AO cảm biến mực nước
     ESP GND ──────────────────────────────────→ GND cảm biến mực nước
```

(*) Chỉ cấp 5 V vào chân **5V/VIN nếu bo ESP32-S3 của bạn hỗ trợ**. Nếu dùng module ESP32-S3 trần, phải dùng bộ ổn áp 3,3 V phù hợp; không đưa 5 V vào chân 3V3. Không nối 12 V trực tiếp vào ESP, cảm biến hoặc bơm 5 V.

(**) Cấp nguồn cho module relay đúng điện áp ghi trên module. GPIO16 và GPIO17 chỉ điều khiển chân IN; không cấp dòng cho bơm. Firmware hiện chọn **kích mức HIGH**: GPIO HIGH bật relay, GPIO LOW tắt relay. Có thể đổi mức kích trong `menuconfig → Relay Configuration → Relay input active level`. Dùng tiếp điểm **COM–NO** để bơm mặc định tắt khi relay chưa hút. Sau khi nạp firmware, thử đèn relay khi chưa nối bơm: lệnh `0` phải làm đèn tắt, lệnh `1` làm đèn sáng. Nếu không đúng, dừng thử với tải và kiểm tra chốt chọn mức kích cùng điện áp chân IN.

Đường GND của ESP, relay và cảm biến cần chung mốc điện áp. Cho dòng bơm đi trên dây nguồn và dây GND riêng về nhánh cấp bơm, tránh đi qua dây GND của ESP. Nếu cảm biến mực nước được cấp 5 V, phải bảo đảm điện áp tại AO **không vượt 3,3 V** trước khi nối vào GPIO18; dùng mạch chia áp nếu cần. Nếu bo cảm biến hỗ trợ cấp 3,3 V, có thể cấp từ 3V3 của ESP.

| Thiết bị | Chân ESP32-S3 | Ghi chú |
| --- | --- | --- |
| DS18B20 DQ | GPIO15 | Điện trở kéo lên 4,7 kΩ từ DQ đến 3V3 |
| Relay 1 / bơm vào | GPIO16 | IN1, mức kích chọn trong Kconfig |
| Relay 2 / bơm ra | GPIO17 | IN2, mức kích chọn trong Kconfig |
| Cảm biến mực nước AO | GPIO18 | ADC2, tín hiệu tối đa 3,3 V |

## Cấu hình của project

Các mục firmware nằm trong `esp-firmware/main/Kconfig.projbuild` và chỉnh bằng `idf.py menuconfig`. `esp-firmware/sdkconfig.defaults` là giá trị mặc định cho cấu hình mới; `esp-firmware/sdkconfig` là cấu hình hiện có trên máy.

| Mục cấu hình | Mặc định | Cấu hình hiện có | Ý nghĩa |
| --- | --- | --- | --- |
| `DS18B20_GPIO` | 15 | 15 | Chân DQ của DS18B20 |
| `DS18B20_MAX_DEVICES` | 2 | 2 | Số cảm biến nhiệt tối đa trên bus |
| `DS18B20_READ_INTERVAL_MS` | 1000 ms | 10000 ms | Chu kỳ đọc và gửi mẫu |
| `WATER_LEVEL_GPIO` | 18 | 18 | Chân ADC đọc mực nước |
| `WATER_LOW_THRESHOLD` | 2000 | 2000 | ADC dưới ngưỡng nghĩa là thiếu nước |
| `RELAY_1_GPIO` / `RELAY_2_GPIO` | 16 / 17 | 16 / 17 | Hai chân điều khiển relay |
| `RELAY_ACTIVE_LEVEL` | HIGH | HIGH | Mức GPIO làm relay hút; có thể đổi sang LOW nếu module cần |
| `POOL_SETUP_AP_SSID` | `SmartPool-Setup` | `SmartPool-Setup` | Tên Wi-Fi cấu hình của ESP |
| `POOL_SETUP_AP_PASSWORD` | `12345678` | `12345678` | Mật khẩu Wi-Fi cấu hình |
| `POOL_API_URL` | `http://192.168.10.214/pool_api/pool_api.php` | Giống mặc định | Địa chỉ API XAMPP |
| `POOL_MQTT_URI` | `mqtts://24660077a88741378be5ffe7f979f2f3.s1.eu.hivemq.cloud:8883` | Giống mặc định | Địa chỉ HiveMQ Cloud của ESP |
| `POOL_CONTROL_TRANSPORT` | MQTT | MQTT | Chọn MQTT hoặc HTTP trong **Smart Pool Network → Pump control transport** |

Ứng dụng Android dùng HiveMQ Cloud qua `ssl://24660077a88741378be5ffe7f979f2f3.s1.eu.hivemq.cloud:8883` trong `MainActivity.java` và `PoolMonitorService.java`. API XAMPP vẫn ở `192.168.10.214`; nếu IP máy XAMPP thay đổi, sửa URL API trong firmware và `MainActivity.java` rồi triển khai lại. Có thể giữ địa chỉ bằng DHCP reservation trên router.

Mosquitto cục bộ không còn được firmware và ứng dụng sử dụng; `mqtt-broker/mosquitto.conf` chỉ là cấu hình cũ để tham khảo. API PHP dùng MySQL tại `localhost`, cơ sở dữ liệu `pool_db`, tài khoản `root` và mật khẩu rỗng; sửa `web-backend/pool_api.php` nếu cấu hình MySQL của bạn khác. API HTTP hiện dành cho mạng nội bộ.

## Cài đặt máy chủ và ứng dụng

1. Khởi động Apache và MySQL trong XAMPP. Nhập lại `web-backend/pool_db.sql` bằng phpMyAdmin. Tệp SQL tạo các bảng `pool_status`, `pool_history`, `pool_commands` và bản ghi `id = 1`; nhập lại không xóa dữ liệu cũ. Bảng `pool_commands` cần thiết cho chế độ HTTP.
2. Chép `web-backend/pool_api.php` vào `xampp/htdocs/pool_api/pool_api.php`. Trên điện thoại cùng mạng, mở `http://192.168.10.214/pool_api/pool_api.php` và kiểm tra API trả về JSON.
3. Tạo tài khoản MQTT trên HiveMQ Cloud với quyền đọc và ghi các topic của project. Sao chép `esp-firmware/main/mqtt_credentials.h.example` thành `esp-firmware/main/mqtt_credentials.h`, rồi điền `SMARTPOOL_MQTT_USERNAME` và `SMARTPOOL_MQTT_PASSWORD`. Tệp này được Git bỏ qua. Nếu hai giá trị còn rỗng, ESP vẫn gửi HTTP nhưng không khởi động MQTT.
4. Sao chép `android-app/mqtt.properties.example` thành `android-app/mqtt.properties`, rồi điền `hivemq.username` và `hivemq.password`. Nếu tệp đã có, giữ nguyên tài khoản và thêm `control.mode=mqtt` hoặc `control.mode=http`. Tệp này được Git bỏ qua. Tài khoản được nhúng vào APK; nên dùng tài khoản riêng có quyền phù hợp.
5. Trong thư mục `esp-firmware`, chạy `idf.py menuconfig` và chọn **Pump control transport** khớp với `control.mode` của Android; kiểm tra chân, ngưỡng ADC, URL API và URI MQTT. Sau đó build/flash ESP32-S3, build và cài lại ứng dụng Android. Mặc định cả hai phía dùng MQTT.
6. ESP và điện thoại cần truy cập XAMPP trong LAN qua cổng **80**. Khi dùng MQTT để điều khiển hoặc nhận telemetry trực tiếp, cả hai cần truy cập HiveMQ Cloud cổng **8883**; ESP cần đồng bộ giờ qua NTP (`pool.ntp.org`, UDP **123**) trước khi kiểm tra chứng chỉ TLS.

## Cấu hình Wi-Fi cho ESP

Khi chưa có thông tin Wi-Fi, ESP phát mạng **SmartPool-Setup**, mật khẩu mặc định **12345678**. Kết nối điện thoại vào mạng đó, mở `http://192.168.4.1`, chọn Wi-Fi từ danh sách quét được hoặc nhập SSID ẩn, rồi điền mật khẩu. ESP lưu thông tin Wi-Fi vào flash và tự kết nối sau những lần khởi động tiếp theo.

Khi kết nối thành công, ESP tắt mạng cấu hình. Trang cấu hình vẫn truy cập được bằng IP nội bộ của ESP, được in trong log dưới dạng `Wi-Fi got IP: ...`. Nếu mất Wi-Fi sau ba lần thử, ESP bật lại mạng cấu hình và tiếp tục thử kết nối mạng đã lưu mỗi **30 giây**; mạng cấu hình tắt khi ESP nhận lại IP nội bộ.

## Dữ liệu, điều khiển và lịch sử

ESP gửi `nhiet_do`, `muc_nuoc` và `muc_nuoc_raw` đến API bằng HTTP POST, đồng thời phát mẫu đo trực tiếp qua MQTT. Ứng dụng Android nhận MQTT khi kết nối và đọc API mỗi **3 giây**. `muc_nuoc = 1` khi ADC lớn hơn hoặc bằng `WATER_LOW_THRESHOLD`; `muc_nuoc = 0` khi thấp hơn ngưỡng. Nếu một cảm biến đọc lỗi, ESP không gửi giá trị mới của cảm biến đó trong mẫu hiện tại.

| MQTT topic | Dữ liệu |
| --- | --- |
| `smartpool/telemetry` | Nhiệt độ và mực nước từ ESP |
| `smartpool/status` | Trạng thái trực tuyến và trạng thái thực tế của hai bơm |
| `smartpool/cmd/bom_vao` | Lệnh `0`/`1` tắt/bật bơm 1 |
| `smartpool/cmd/bom_ra` | Lệnh `0`/`1` tắt/bật bơm 2 |

Firmware không cho hai bơm bật đồng thời. Khi mất Wi-Fi, hai relay được tắt. Ở chế độ MQTT, mất broker cũng tắt bơm. Ở chế độ HTTP, ESP hỏi `GET /pool_api/pool_api.php?commands=1` mỗi **3 giây** và tắt bơm nếu hai lần hỏi liên tiếp thất bại. `pool_status` giữ trạng thái mới nhất do ESP báo về; `pool_history` lưu lịch sử mẫu đo. HTTP telemetry tới XAMPP vẫn hoạt động ở cả hai chế độ; MQTT telemetry/status vẫn hoạt động nếu đã cấu hình tài khoản HiveMQ.

Ở chế độ HTTP, ứng dụng gửi `POST /pool_api/pool_api.php` với JSON `{"command":{"bom_vao":1,"bom_ra":0}}` để bật bơm vào; gửi cả hai giá trị bằng `0` để tắt. API lưu lệnh vào `pool_commands` và tăng `revision`; ESP chỉ áp dụng revision mới. Lần đọc đầu sau khi ESP khởi động, kết nối lại Wi-Fi hoặc khôi phục API chỉ lấy mốc revision để tránh bật bơm từ lệnh cũ. Hãy chờ log `HTTP command baseline` trước khi bấm lệnh mới. API HTTP hiện dành cho LAN và chưa có xác thực riêng.

Khi mất Wi-Fi hoặc API không phản hồi, ESP lưu mẫu chưa gửi vào hàng đợi NVS trong flash và gửi bù theo thứ tự khi có mạng. Hàng đợi vẫn còn sau khi khởi động lại; `sample_id` giúp API tránh ghi trùng. Hàng đợi chứa tối đa **128 mẫu**: khoảng **2 phút** nếu lấy mẫu mỗi 1 giây, hoặc khoảng **21 phút** với cấu hình hiện tại là 10 giây. Khi đầy, mẫu cũ nhất bị thay thế và ESP ghi cảnh báo. Cột `received_at` là thời điểm máy chủ **nhận** mẫu, không phải thời điểm ESP đo lúc đang mất mạng.

## Kiểm tra khi có lỗi

- **Không thấy DS18B20:** kiểm tra VDD 3,3 V, GND, DQ GPIO15 và điện trở kéo lên 4,7 kΩ. Khi chưa có cảm biến hoạt động, ESP tìm lại mỗi **5 giây** mà vẫn tiếp tục đọc mực nước. Sau khi tìm thấy, log có dạng `DS18B20[0]: 27.50 C`.
- **DS18B20 báo CRC hoặc 85 °C:** firmware đọc lại một lần sau **20 ms** nếu sai CRC và bỏ qua giá trị 85 °C. Nếu lỗi lặp lại, kiểm tra dây, nguồn cảm biến và nhiễu khi bơm chạy. Sau ba lần đọc lỗi liên tiếp, cảm biến tạm ngừng đọc; ESP sẽ tìm lại khi không còn cảm biến nào hoạt động.
- **Mực nước thay đổi bất thường:** xem giá trị `Water ADC` trong log, kiểm tra dây AO/GND và đo điện áp AO. GPIO18 thuộc ADC2; nếu tín hiệu vẫn không ổn định khi Wi-Fi hoạt động, chuyển dây AO sang chân ADC1 còn trống (GPIO1–10) và đổi `WATER_LEVEL_GPIO` trong `menuconfig`, rồi hiệu chỉnh lại ngưỡng.
- **Lỗi xuất hiện khi bật bơm 5 V:** kiểm tra điện áp 5 V tại bơm và điện áp cấp cho ESP đúng lúc khởi động bơm. Để dây cấp bơm tách khỏi dây cấp ESP; nếu dùng chung một bộ hạ áp, kiểm tra dòng đầu ra của bộ hạ áp hoặc dùng hai nhánh hạ áp như sơ đồ trên.
- **Không kết nối API:** kiểm tra IP máy XAMPP, Apache, đường dẫn PHP và tường lửa cổng 80. ESP in `HTTP API target` khi khởi động và `HTTP POST OK` khi gửi mẫu thành công.
- **Không kết nối HiveMQ Cloud:** kiểm tra tên đăng nhập/mật khẩu trong hai tệp cục bộ, quyền đọc/ghi topic, Internet, cổng 8883 và đồng bộ giờ NTP của ESP. ESP in lỗi MQTT/TLS khi không kết nối được.
