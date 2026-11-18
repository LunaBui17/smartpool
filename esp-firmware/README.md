# Firmware ESP32-S3 Smart Pool

**Backend v2, runtime 005:** firmware gửi mẫu/status bằng HTTP device key, nhận `cmd/pump` theo device_id, kiểm tra TTL và chống lặp bằng sổ NVS. Task riêng poll `get_device_config`, lưu snapshot rồi báo revision/kết quả áp dụng; MQTT có heartbeat live 30 giây. Queue v3 giữ giờ đo khi đồng hồ hợp lệ; mẫu v1/v2 chuyển đổi vẫn unknown. Source mới chưa build/test/flash trong đợt này; BLE chưa triển khai. Xem [hợp đồng runtime, nâng cấp và quay lui NVS](../docs/esp-api-mqtt-runtime.md).

[Hướng dẫn tổng thể](../README.md) · [Luồng toàn hệ thống](../docs/system-flow.md) · [Giao thức MQTT v2](../docs/mqtt-v2.md) · [Triển khai Raspberry Pi v2](../deploy/raspberry-pi-v2/README.md)

Firmware đọc cảm biến, điều khiển hai relay, tự động hóa bơm ngay trên ESP32-S3 và kết nối với backend Smart Pool:

- Đọc nhiệt độ từ DS18B20 và mực nước từ cảm biến analog.
- Điều khiển độc lập bơm vào và bơm ra qua trang web nội bộ hoặc lệnh MQTT v2 từ worker trên Pi.
- Lưu cấu hình và quy tắc tự động hóa trong NVS.
- Gửi mẫu đo tới API qua HTTP(S); lưu tạm tối đa 128 mẫu trong NVS khi chưa gửi được.
- Phát trạng thái và telemetry qua MQTT topic riêng của thiết bị.
- Cung cấp điểm truy cập Wi-Fi và trang cấu hình cục bộ trên ESP32.

## Chân kết nối và cấu hình mặc định

| Chức năng | GPIO ESP32-S3 | Cấu hình mặc định |
| --- | --- | --- |
| DS18B20 DQ | GPIO9 | Tối đa 2 cảm biến trên cùng bus; điện trở kéo lên 4,7 kΩ tới 3,3 V |
| Cảm biến mực nước AO | GPIO10 | ADC 12 bit; điện áp đầu ra không vượt quá 3,3 V |
| Relay 1 / bơm vào | GPIO15 | Kích mức HIGH; tắt khi khởi động |
| Relay 2 / bơm ra | GPIO16 | Kích mức HIGH; tắt khi khởi động |

Chu kỳ lấy mẫu mặc định là 60 giây khi hai bơm tắt và 5 giây khi có bơm chạy. Ngưỡng ADC mực nước mặc định là 2000; cần hiệu chuẩn theo cảm biến thực tế. Có thể thay đổi chân, ngưỡng, chu kỳ lấy mẫu, mức kích relay và các tính năng trong `idf.py menuconfig`.

Nối chung GND giữa ESP32, cảm biến và đầu vào relay. Không cấp điện cho bơm qua GPIO; chọn nguồn và module relay phù hợp với tải. Kiểm tra điện áp ngõ ra của cảm biến trước khi nối vào ESP32.

## Cấu hình và chạy firmware

1. Cài đặt môi trường ESP-IDF và mở terminal đã nạp môi trường đó.
2. Tại thư mục `esp-firmware`, chạy `idf.py menuconfig` để kiểm tra cấu hình phần cứng, điểm truy cập, API và broker MQTT.
3. Đăng ký `SmartPool` (hoặc ID đã chọn trong `CONFIG_POOL_DEVICE_ID`) trong API v2, kích hoạt thiết bị, cấp `device_key` HTTP và tạo MQTT account bằng `sudo python3 mqtt-admin.py add-device SmartPool` trên Pi. Lưu key một lần vào `main/device_credentials.h` theo file `.example` (file này được Git bỏ qua). MQTT username tự bằng ID; password lấy từ file private `~/smartpool-v2-mqtt/SmartPool.txt` trên Pi và cấu hình trong `main/mqtt_credentials.h` hoặc trang local.
4. Build và nạp firmware:

```bash
cd esp-firmware
idf.py build
idf.py -p COMx flash monitor
```

Thay `COMx` bằng cổng nối với bo mạch. Nếu tạo cấu hình mới mà chưa có `sdkconfig`, chọn target bằng `idf.py set-target esp32s3` trước khi build.
File `.bin` sau khi build chứa key HTTP của thiết bị; không chia sẻ artifact hoặc đưa vào Git. Trên máy này bản build đã kiểm tra nằm tại `build-v53/ds18b20.bin`, được Git bỏ qua. Flash cần cổng USB của ESP; `COM1` hệ thống không chứng minh bo ESP đã kết nối.

Mặc định firmware dùng API `https://mysterious-witness-crown-glasgow.trycloudflare.com/pool_api/pool_api.php` và MQTT `wss://mysterious-witness-crown-glasgow.trycloudflare.com/mqtt` qua Cloudflare Quick Tunnel. Wi-Fi đã lưu trong NVS được giữ lại. Cấu hình v2 lưu sau đó có thể ghi đè mặc định; kiểm tra trang local sau khi flash. Khi Quick Tunnel đổi URL, lấy endpoint hiện hành từ `~/smartpool-v2-endpoints.txt` trên Pi và cập nhật cả API lẫn MQTT WSS.

## Trang cấu hình cục bộ

ESP32 phát điểm truy cập `SmartPool-Setup` (mặc định) để cấu hình kể cả khi chưa có Wi-Fi router. Kết nối điện thoại hoặc máy tính vào điểm truy cập, sau đó mở:

```text
http://192.168.4.1
```

Mật khẩu điểm truy cập mặc định là `12345678`; nên đổi mật khẩu trong `menuconfig` trước khi triển khai. Trang web cũng có thể mở bằng địa chỉ LAN của ESP32 khi thiết bị đang kết nối router.

Trang cấu hình cho phép xem trạng thái cảm biến và kết nối, bật/tắt cảm biến, điều khiển từng bơm hoặc dừng cả hai, cấu hình Wi-Fi/API/MQTT, quét mạng Wi-Fi và thiết lập tự động hóa. Các chức năng bị tắt khi build sẽ không khả dụng trên giao diện.

## Điều khiển và dữ liệu

- ESP32 phát trạng thái tại `smartpool/devices/SmartPool/status` và số đo tại `smartpool/devices/SmartPool/telemetry`. Trạng thái relay là trạng thái GPIO được điều khiển, không phải xác nhận bơm thực sự đang quay.
- Mẫu đo được gửi qua HTTP(S) để lưu lịch sử. Khi API chưa khả dụng, firmware xếp mẫu vào hàng đợi NVS và gửi lại khi có thể.
- Quy tắc tự động hóa chạy trên ESP32, không cần ứng dụng Android duy trì kết nối.
- Điều khiển tại chỗ, tự động hóa và lệnh bơm MQTT v2 hoạt động theo trạng thái cảm biến, cấu hình liên động và chế độ hiện tại của thiết bị.

Danh sách topic và ACL mới nằm trong [tài liệu MQTT v2](../docs/mqtt-v2.md). Luồng dữ liệu, lưu trữ và xử lý khi mất kết nối nằm trong [sơ đồ toàn hệ thống](../docs/system-flow.md).

## Kiểm tra

Từ thư mục `esp-firmware`, trong môi trường ESP-IDF, có thể chạy kiểm tra cấu hình:

```bash
python tests/check_feature_config.py
```

Kiểm tra logic tự động hóa trên máy host, không cần bo mạch:

```bash
mkdir -p build
cc -std=c11 -Wall -Wextra -Werror -Imain \
  main/pump_auto.c tests/test_pump_auto.c -o build/pump_auto_tests
./build/pump_auto_tests
```

Sau khi nạp firmware, kiểm tra trạng thái cảm biến, kết nối Wi-Fi/MQTT, trạng thái relay và việc gửi dữ liệu tới API trên trang cục bộ hoặc ứng dụng. Thử relay khi chưa nối tải trước khi vận hành bơm.
