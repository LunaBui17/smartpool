# Đối chiếu MQTT Android ↔ broker ↔ ESP32

Đã đọc mã thực tế tại `../android-app/app/src/main/java/com/buiducluan23011767/ck/` và tài liệu `../README_MQTT_ESP32.md`. Firmware dùng ESP-IDF 5.5.6; GPIO relay 15/16, DS18B20 9, ADC 10; hai bơm hoạt động độc lập. Username/password mặc định trong `mqtt.properties` khớp `main/mqtt_credentials.h`; không đưa bí mật vào log/tài liệu. App có thể đổi tài khoản bằng SharedPreferences. Chưa kết nối broker thật để kiểm tra ACL.

## Những chỗ lệch đã xử lý

| Vấn đề | Căn cứ Android | Firmware mới |
| --- | --- | --- |
| App chờ ACK 10 giây, firmware cũ chỉ nhận `0/1` | `AppConfig.build*Json`, `SettingsActivity.handleAckMessage` | Nhận JSON, kiểm tra và lưu NVS trước khi ACK đúng `req_id`/`action` |
| Thiếu cấu hình tổng hợp/thiết bị/HTTP | `AppConfig.toJsonObject`, `buildDeviceJson`, `buildHttpJson` | Kiểm tra toàn bộ gói rồi lưu một blob NVS; lỗi không áp dụng một phần |
| JSON lớn bị chia nhiều MQTT event | `AppConfig.toJsonString(true, reqId)` | Ghép đúng offset/topic, tối đa 4096 byte; bỏ frame quá cỡ hoặc sai thứ tự |
| App yêu cầu quét Wi-Fi | `SettingsActivity.triggerWifiScan`, `handleWifiScanResult` | Quét ngoài callback MQTT, trả mảng `{ssid,rssi}` không retain |
| App thay cả hai số đo khi nhận telemetry | `MainActivity.handleMqttMessage` | Thay đổi relay chỉ gửi status MQTT; lần đọc cảm biến mới gửi telemetry, tránh xóa số đo bằng gói trạng thái có null |
| App nhận ba trạng thái nước | `MainActivity.updateWaterLevel` | `muc_nuoc`: 0 dưới min, 1 trong khoảng gồm hai biên, 2 trên max; thêm ADC thô |
| Retained riêng/tổng hợp đến sai thứ tự | App retain các cấu hình | Timestamp riêng từng nhóm; từ chối gói cũ hoặc retained cùng timestamp nhưng khác nội dung; gói lặp đúng nội dung vẫn ACK và không ghi flash lại |
| Wi-Fi retained gây reconnect lặp | `buildWifiJson` | Không áp dụng lại yêu cầu đã xử lý sau chỉnh sửa local; yêu cầu mới chỉ reconnect khi credentials thực tế khác |

## Topic và payload

Namespace hiện tại dùng chung cho một thiết bị. Device ID chỉ là metadata, không phân tuyến lệnh đến nhiều ESP32.

| App → ESP32 | Payload |
| --- | --- |
| `smartpool/cmd/bom_vao`, `smartpool/cmd/bom_ra` | Đúng một byte `1`/`0`; QoS 1, retain=false; bỏ retained ON, nhận OFF |
| `smartpool/cmd/temperature_enabled`, `smartpool/cmd/water_level_enabled` | Đúng một byte `1`/`0`; có thể retain |
| `smartpool/config/thresholds` | `action:"UPDATE_THRESHOLDS"`, `req_id`, `timestamp`, `temp_min`, `temp_max`, `water_min`, `water_max`; tùy chọn `interlock:0/1` |
| `smartpool/config/wifi` | `action:"UPDATE_WIFI"`, `req_id`, `timestamp`, `ssid`, `password` |
| `smartpool/config/device` | `action:"UPDATE_DEVICE"`, `req_id`, `timestamp`, `id`, `name`, `location` |
| `smartpool/config/http` | `action:"UPDATE_HTTP"`, `timestamp`, `api_url`; `req_id` tùy chọn |
| `smartpool/config` | `action:"CONFIG_UPDATE_ALL"`, `req_id`, `timestamp`, các object `device`, `wifi`, `thresholds`, `http` theo đúng cấu trúc app |
| `smartpool/cmd/wifi_scan` | `cmd:"SCAN_WIFI"`, timestamp tùy chọn; retain=false |

Cấu hình dùng timestamp Unix nguyên, tối đa năm 2099. Nhiệt độ -55..125°C, nước **ADC thô 0..4095**, min < max. SSID 1..32 byte; mật khẩu rỗng hoặc 8..63 byte. ID tối đa 64 byte; tên/vị trí tối đa 128 byte UTF-8; URL HTTP(S) tối đa 256 byte; req_id tối đa 96 byte. Gói cũ thiếu req_id vẫn được xử lý, nhưng app mới cần req_id để nhận ACK. Retained rỗng xóa cấu hình trên broker không xóa NVS.

Ví dụ ngưỡng (thay timestamp bằng Unix time mới hơn lần lưu trước):

```json
{"action":"UPDATE_THRESHOLDS","req_id":"req_thresh_example","timestamp":1794445200,"temp_min":20,"temp_max":35,"water_min":1500,"water_max":3500,"interlock":0}
```

App tạo timestamp/request ID theo giây. Các thay đổi trực tiếp cùng timestamp được áp dụng theo thứ tự nhận, hỗ trợ gạt nhanh công tắc. Bản retained phát lại cùng timestamp nhưng khác nội dung bị trả ERROR để không ghi đè thiết lập hiện tại. Nếu một nhóm của Sync All cũ hoặc không hợp lệ, firmware không áp dụng cả gói.

`smartpool/ack` QoS 1, không retain:

```json
{"req_id":"req_thresh_example","action":"UPDATE_THRESHOLDS","status":"SUCCESS","message":"Saved to NVS...","water_unit":"adc","interlock":0}
```

ACK SUCCESS xác nhận **đã lưu NVS**, không xác nhận mật khẩu Wi-Fi đúng hoặc HTTP truy cập được. Trước khi đổi Wi-Fi, firmware đợi broker PUBACK của ACK tối đa 3 giây rồi mới reconnect. App vẫn có thể timeout nếu mạng mất đúng lúc ACK được gửi. Cấu hình Wi-Fi chờ áp dụng được lưu để tiếp tục sau reboot; AP local vẫn dùng được để sửa credentials.

`smartpool/status` giữ `online`, `bom_vao_control`, `bom_ra_control` cho xác nhận bơm 7 giây của Android; thêm metadata `device`, `thresholds`, `water_unit:"adc"`, `interlock:0/1` theo cấu hình thực tế. Status QoS 1 retained; telemetry/ACK/scan QoS 1 không retained. LWT chỉ có `online:0`, vì bơm local/tự động có thể vẫn chạy khi mất broker.

Ngưỡng từ Android dùng **phân loại cảnh báo**, không thay quy tắc tự động từng bơm trên local web và không tự bật/ngắt bơm. Telemetry thêm `temperature_state`: 0 thấp, 1 trong khoảng, 2 cao. HTTP storage vẫn dùng `muc_nuoc` nhị phân theo ngưỡng Kconfig kèm ADC thô, giữ hợp đồng backend/hàng đợi lịch sử. Lệnh tay MQTT tạm dừng tự động của đúng bơm đó.

`api_url` từ app thay URL upload và lưu NVS, kể cả khi HTTP tắt trong menuconfig. `poll_interval_s`, `control_mode` và object `mqtt` của Sync All là cấu hình phía app: không đổi chu kỳ firmware 60 giây lúc nghỉ/5 giây lúc bơm, không bật lấy lệnh từ database, không đổi broker/topic firmware. URI/credentials vẫn cấu hình trong firmware. TLS tiếp tục kiểm tra CA; không dùng `setInsecure()` từ code Arduino mẫu.

## Các điểm còn ở phía Android

1. **Interlock mặc định bật:** `AppConfig.DEFAULT_INTERLOCK_ENABLED=true`; `MainActivity.setupListeners` chặn nút bơm thứ hai trước khi publish. Tắt công tắc này trong app để chạy đồng thời. Firmware nhận và lưu lựa chọn từ app: interlock=1 chỉ cho phép một bơm chạy, interlock=0 cho phép đồng thời; ACK/status báo giá trị thực tế. Quy tắc cũng áp dụng cho web local, tự động và khởi động.
2. **Timeout cảm biến 15 giây:** `MainActivity.SENSOR_TIMEOUT_MS=15000` không khớp chu kỳ nghỉ 60 giây. App sẽ báo dữ liệu cũ giữa hai lần đo. Nên sửa timeout theo chu kỳ, ví dụ 125 giây lúc nghỉ/15 giây lúc bơm; firmware giữ chu kỳ người dùng đã yêu cầu.
3. **Đơn vị ngưỡng nước:** UI ghi “% hoặc cm”, mặc định 20/85. Nhập ADC đã đo/hiệu chuẩn vào app. Nếu Sync All còn 20/85, phần lớn số đo ADC thực tế sẽ bị phân loại là tràn.
4. **URL và chế độ HTTP:** Android mặc định `http://192.168.10.214/pool_api/pool_api.php`, khác firmware `http://192.168.60.112/pool_api.php`. Sửa URL trước Sync All. Chọn MQTT để điều khiển bơm: firmware chỉ upload HTTP, không đọc lệnh PHP/database.
5. **ACK khi Settings reconnect:** đã bật automatic reconnect, bỏ nhánh tạo client gửi tạm và khóa nút gửi khi MQTT chưa kết nối. Vẫn nên đợi SUBACK trước khi publish cấu hình; timeout ACK không chứng minh ESP32 chưa lưu.
6. **SSID có dấu ngoặc:** app cắt tên tại `" ("` để bỏ RSSI. SSID thật có chuỗi này cần nhập tay hoặc sửa parser app để giữ SSID tách riêng RSSI.

Tài liệu gốc có chân 18/19, interlock bắt buộc, telemetry 2–5 giây và code Arduino bỏ kiểm tra TLS. Các đoạn này không phải cấu hình áp dụng cho firmware hiện tại. Mã Android nay đã được cập nhật khóa điều khiển theo kết nối MQTT; tài liệu MQTT ở thư mục gốc chưa được chỉnh sửa.

## Kiểm tra

`tests/test_mqtt_protocol.c` chạy C thật với payload Android: lệnh relay/sensor, retained ON, cấu hình từng phần/tổng hợp, req_id lỗi, ADC ba trạng thái, timestamp cũ/trùng, phân mảnh ở mọi vị trí, giới hạn bộ đệm. Dùng cJSON đi kèm ESP-IDF:

```sh
cc -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined -Imain -I/path/to/cJSON main/pool_mqtt_protocol.c tests/test_mqtt_protocol.c /path/to/cJSON/cJSON.c -lm -o build/mqtt_protocol_tests
./build/mqtt_protocol_tests
```

Build ESP-IDF và kiểm tra 512 tổ hợp Kconfig/10 cấu hình compiler đã đạt. Cần kiểm tra trên board: Android → broker → ESP32 → ACK/status, ACL config/ack/scan, credentials Wi-Fi đúng/sai, reboot giữ NVS, mất/kết nối lại broker, hai bơm đồng thời và nhịp telemetry 60/5 giây. Chưa flash hoặc gửi thử lên broker thật.

Khi bật interlock lúc cả hai bơm đang chạy, firmware dừng cả hai và tạm dừng tự động; cần lưu/áp dụng lại quy tắc hoặc reboot để tự động tiếp tục. Khi chỉ một bơm đang chạy thì giữ bơm đó. Bơm tự động còn lại chờ, không mất lượt kích hoạt hay thời gian chạy. Interlock đã lưu ưu tiên hơn cấu hình khởi động BOTH_ON: giữ cả hai OFF. Thiếu trường interlock trong payload giữ nguyên lựa chọn hiện tại.


## Sync All khi chưa nhập Wi-Fi trong app

Android có thể gửi object wifi với cả ssid và password là chuỗi rỗng. Trong CONFIG_UPDATE_ALL, firmware bỏ qua riêng nhóm Wi-Fi này, giữ nguyên credentials và timestamp Wi-Fi, vẫn kiểm tra/lưu các nhóm thiết bị, ngưỡng và HTTP rồi trả SUCCESS. Không ghi đè hoặc ngắt mạng hiện tại. SSID rỗng kèm mật khẩu có nội dung vẫn là lỗi; UPDATE_WIFI riêng vẫn bắt buộc SSID 1–32 byte. Các lỗi Wi-Fi nay được ACK bằng thông báo cụ thể. Test hồi quy dùng tests/fixtures/sync_all_empty_wifi.json từ payload thực tế.


## Khóa điều khiển theo MQTT

Theo lựa chọn người dùng, chỉ khóa điều khiển từ Android/MQTT; web local/AP vẫn hoạt động khi broker mất kết nối. App yêu cầu cả kết nối broker và trạng thái ESP32 online, khóa cả đường HTTP điều khiển khi thiếu MQTT, bỏ trạng thái/lệnh chờ cũ khi mất phiên. Các nút gửi cấu hình/quét của Settings khóa khi kết nối MQTT của màn hình đó chưa sẵn sàng; nút cấu hình broker vẫn dùng được. ESP32 kiểm tra kết nối cho cả ON/OFF và bật/tắt cảm biến từ MQTT; dừng nội bộ khi mất mạng và lệnh local không đi qua khóa remote này. Build firmware và host test trên các hàm điều khiển thật đã đạt. Chưa build APK vì máy thiếu Java/JDK; test Java đã thêm vào app nhưng chưa chạy.

