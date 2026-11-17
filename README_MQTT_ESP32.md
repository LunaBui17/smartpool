# Giao thức MQTT Smart Pool: Android ↔ Mosquitto trên Pi ↔ ESP32-S3

**Giới hạn với backend v2:** tài liệu này mô tả MQTT topic chung của client hiện tại. Database/API đã chuyển sang nhiều thiết bị nhưng chưa có MQTT publisher hoặc ACL theo membership. Chưa nối luồng lệnh/config mới vào broker; xem [database/API v2](docs/database-v2.md).

Tài liệu mô tả giao thức giữa firmware ESP-IDF, Mosquitto trên Raspberry Pi và ứng dụng Android; xem [luồng toàn hệ thống](docs/system-flow.md) để phân biệt MQTT điều khiển với HTTP lưu/đọc dữ liệu.

## Kết nối

- Ngoài LAN: lấy `mqtt_wss_uri=wss://DOMAIN/mqtt` từ `~/smartpool-endpoints.txt` trên Pi. URI này dùng được cho ESP, Android và MQTTX.
- LAN: ESP dùng `mqtt://192.168.60.87:1883`; Android/MQTTX dùng `tcp://192.168.60.87:1883`.
- Broker là Mosquitto chạy trên Pi; Apache proxy `/mqtt` tới WebSocket port 9001. Cloudflare Quick Tunnel đưa WSS ra Internet, không biến broker thành broker Cloudflare.
- ESP dùng user `esp32`, Android/MQTTX dùng `android`. Password lấy từ cấu hình riêng trên Pi; tài khoản đăng nhập app không phải tài khoản MQTT.
- Trong ứng dụng, cấu hình Settings → MQTT bằng URI Mosquitto trên Pi trước khi thử điều khiển. Thay URL API không tự thay broker.
- Các bản tin chính dùng QoS 1. URL Quick Tunnel có thể đổi sau restart; file text không tự sửa cấu hình client.

## Topic và retained

| Topic | Chiều | Payload / retained |
| --- | --- | --- |
| `smartpool/cmd/bom_vao` | App → ESP | Chính xác một byte `0` hoặc `1`; false |
| `smartpool/cmd/bom_ra` | App → ESP | Chính xác một byte `0` hoặc `1`; false |
| `smartpool/cmd/temperature_enabled` | Client → ESP | `0`/`1`, bật/tắt cảm biến runtime; có thể retained |
| `smartpool/cmd/water_level_enabled` | Client → ESP | `0`/`1`, bật/tắt cảm biến runtime; có thể retained |
| `smartpool/status` | ESP → app | JSON online, relay, feature, thresholds, automation, network; true |
| `smartpool/telemetry` | ESP → app/service | Mẫu đo, đơn vị ADC, trạng thái; false |
| `smartpool/config/thresholds` | App → ESP | `UPDATE_THRESHOLDS`; true |
| `smartpool/config/wifi` | App → ESP | `UPDATE_WIFI`; true |
| `smartpool/config/device` | App → ESP | `UPDATE_DEVICE`; true |
| `smartpool/config/http` | App → ESP | `UPDATE_HTTP`; true |
| `smartpool/config` | App → ESP | `CONFIG_UPDATE_ALL`; true |
| `smartpool/config/automation` | App → ESP | `UPDATE_AUTOMATION`; bắt buộc false |
| `smartpool/ack` | ESP → app | Xác nhận cấu hình, khớp req_id; false |
| `smartpool/cmd/wifi_scan` | App → ESP | `{"cmd":"SCAN_WIFI"}`; false |
| `smartpool/wifi/scan_result` | ESP → app | Kết quả quét; false |

Broker dùng ACL trong `deploy/raspberry-pi/mosquitto.acl`: ESP đọc cmd/config và ghi status/telemetry/ack/scan; Android đọc status/telemetry/ack/scan và ghi cmd/config. Topic `smartpool/diagnostics/probe` dành cho smoke test, không điều khiển bơm.

## Điều khiển và xác nhận

App gửi payload `1` để bật, `0` để tắt, `retain=false`. Firmware bỏ lệnh ON retained khi nhận lại để tránh khởi động bơm từ bản tin cũ. Mỗi topic chỉ tác động kênh của nó. Interlock là cấu hình có thể bật/tắt; khi bật, ESP từ chối hai bơm ON đồng thời. Firmware mặc định interlock OFF, app mặc định ON và có thể đồng bộ xuống ESP.

MainActivity chỉ cho điều khiển khi đang hiển thị, broker connected và ESP báo online. Sau khi publish, app chờ `smartpool/status` trả trạng thái mong muốn; timeout thì báo chưa xác nhận. PUBACK của broker không chứng minh relay đã chuyển trạng thái. Trạng thái ESP là trạng thái điều khiển GPIO, chưa có phản hồi vật lý từ motor/tiếp điểm.

Ví dụ một phần status:

```json
{
  "online": 1,
  "bom_vao_control": 0,
  "bom_ra_control": 0,
  "temperature_enabled": 1,
  "water_level_enabled": 1,
  "interlock": 1,
  "network": {
    "ip": "192.168.60.42",
    "http_api_url": "https://DOMAIN/pool_api/pool_api.php"
  }
}
```

Status thực tế còn có feature, device, thresholds và automation. Last Will chỉ báo `online:0`, không kết luận các kênh local đã tắt. Broker phát Last Will sau khi phát hiện mất kết nối, không phải thông báo tức thời ngay lúc mất nguồn.

## Cấu hình và ACK

Gói config dùng action tương ứng topic, timestamp Unix giây và req_id để theo dõi. Firmware kiểm tra trường/giá trị và timestamp từng nhóm, lưu NVS rồi ACK. Không lấy ví dụ cấu hình để gửi tự động khi chỉ cần test kết nối vì chúng thay đổi thiết bị.

Ví dụ cấu trúc UPDATE_HTTP (thay timestamp bằng thời gian hiện tại khi thực sự áp dụng):

```json
{
  "action": "UPDATE_HTTP",
  "req_id": "req_http_unique",
  "timestamp": 1794891600,
  "api_url": "https://DOMAIN/pool_api/pool_api.php",
  "poll_interval_s": 3,
  "control_mode": "mqtt"
}
```

HTTP ở đây là endpoint upload telemetry; firmware đã bỏ polling lệnh HTTP. Chu kỳ lấy mẫu firmware vẫn do `POOL_SENSOR_IDLE_INTERVAL_MS` / `POOL_SENSOR_PUMP_INTERVAL_MS` quyết định, không đổi thành 3 giây chỉ vì trường `poll_interval_s`.

ACK có dạng:

```json
{
  "req_id": "req_http_unique",
  "action": "UPDATE_HTTP",
  "status": "SUCCESS",
  "message": "..."
}
```

ERROR là cấu hình bị từ chối/lưu lỗi. Settings đối chiếu req_id; ACK cấu hình không thay cơ chế status xác nhận lệnh bơm. Automation bắt buộc req_id và retain=false. Sync All có Wi-Fi SSID/password cùng rỗng thì bỏ qua nhóm Wi-Fi thay vì xóa mạng ESP đã lưu.

## Dữ liệu và nguồn hiển thị Android

MQTT telemetry không retained; cảm biến không có dữ liệu trả null. Nước dùng `water_unit: adc`, `muc_nuoc_raw` từ 0..4095 và trạng thái 0 thấp / 1 trong khoảng / 2 cao. HTTP lưu hợp đồng `muc_nuoc` nhị phân 0/1. App ánh xạ ADC sang cm theo hiệu chuẩn.

MainActivity đọc cảm biến qua GET API và trạng thái relay qua MQTT status. PoolMonitorService subscribe telemetry để cảnh báo nền. Không có MQTT consumer ghi MariaDB: ESP gửi thêm HTTPS POST để lưu dữ liệu/lịch sử, có hàng đợi NVS khi API lỗi.

Mất Wi-Fi/MQTT dừng kênh thuộc điều khiển từ xa; kênh đã điều khiển local hoặc chạy tự động có thể tiếp tục theo quy tắc ESP. Đổi Wi-Fi/chế độ router dừng cả hai kênh trước chuyển mạng. MQTT credential dùng chung của app chưa tách quyền admin/guest; role giao diện không thay ACL broker.

## Source để đối chiếu khi đổi giao thức

- [Decoder và kiểm tra payload](esp-firmware/main/pool_mqtt_protocol.c).
- [MQTT, status, ACK, ownership và upload](esp-firmware/main/pool_network.c).
- [AppConfig và JSON builders](android-app/app/src/main/java/com/buiducluan23011767/ck/AppConfig.java).
- [Settings và chờ ACK](android-app/app/src/main/java/com/buiducluan23011767/ck/SettingsActivity.java).
- [MainActivity và xác nhận trạng thái bơm](android-app/app/src/main/java/com/buiducluan23011767/ck/MainActivity.java).
