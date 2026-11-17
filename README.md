# Smart Pool

Hệ thống ESP32-S3, Raspberry Pi 4 B và Android cho giám sát/điều khiển bể bơi. Pi chạy PHP/Apache, MariaDB và Mosquitto bằng Docker Compose; Cloudflare Quick Tunnel cung cấp HTTPS/WSS.

**Database/API hiện là v2 nhiều thiết bị, không tương thích client một thiết bị cũ.** Đã triển khai baseline 004 trên Pi `192.168.60.87` ngày 17/11/2026, xác nhận health LAN/Cloudflare; chưa chạy collection Postman. Android/firmware đang trong repository chưa tích hợp v2, BLE chưa triển khai và MQTT còn topic/account chung.

## Tài liệu chính

- [Mô hình database, API, reset, Postman và MQTT Web](docs/database-v2.md).
- [Luồng xử lý và phần tích hợp còn lại](docs/system-flow.md).
- [Triển khai Pi và quản lý Docker](deploy/raspberry-pi/README.md).
- [Collection Postman v2](backend/postman/SmartPool.v2.postman_collection.json).
- [Firmware hiện tại](esp-firmware/README.md) và [giao thức MQTT hiện tại](README_MQTT_ESP32.md).

## Mô hình mới

Admin đăng nhập → danh sách thiết bị → đăng ký ID (nhập tay; sau này đọc BLE) → kích hoạt → cấp user vào từng thiết bị. User đăng nhập → danh sách thiết bị được cấp → chọn device_id cho mọi trạng thái, số đo, cấu hình, biểu đồ và nhật ký.

Quan hệ nhiều–nhiều nằm ở `device_users`. User có thể có nhiều thiết bị và một thiết bị có nhiều user, với các quyền `can_view`, `can_control`, `can_configure`. Admin quản trị toàn hệ thống. Gỡ membership không xóa tài khoản, dữ liệu hoặc quyền ở thiết bị khác.

Mỗi mẫu có `device_id + sample_id` duy nhất, giờ đo nullable và giờ server nhận riêng. Config có revision và tách khỏi trạng thái ESP đã báo. Audit backend ghi trong cùng giao dịch, phân biệt nhật ký client/device. Wi-Fi password dự kiến truyền trực tiếp BLE, không lưu trong server/log.

API cấu hình/lệnh hiện chỉ lưu dữ liệu: chưa có worker publish MQTT hoặc BLE. MQTT Web connect thành công không chứng minh quyền theo thiết bị đã được thực thi ở broker.

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

