# Firmware ESP32-S3 Smart Pool

**Reset từ server, source mới:** khi admin yêu cầu xóa, ESP mới nhận lệnh qua HTTP, dừng relay/MQTT, xóa NVS ứng dụng/Wi-Fi flash, gửi receipt bằng Wi-Fi RAM tạm rồi ngắt mạng/reboot về AP chưa cấp ID. Backend 009 đã deploy; phần firmware này chưa build/flash/test. Xem [luồng reset, phục hồi mất điện và yêu cầu nạp firmware](../docs/device-deprovision-009.md).

**Khóa chung + MAC, source 006:** mọi bo nhập cùng khóa HTTP; ESP tự gửi MAC eFuse gốc cùng ID để server gắn/kiểm tra cặp duy nhất. Status/ACK/LWT MQTT có MAC; lệnh pump chỉ thực thi khi ID và MAC đích đều khớp bo. Xem [API khóa chung, migration và cách ghi MAC lên database](../docs/shared-key-hardware.md). Bản 006 chưa build/test/flash hoặc deploy Pi; các hướng dẫn cấp khóa riêng trước đây được thay bằng hợp đồng 006.

**Cấp thiết bị qua local:** ID/khóa HTTP và mật khẩu MQTT đã chuyển sang NVS, không còn lấy từ `device_credentials.h`/`mqtt_credentials.h`. Cùng một binary có thể dùng cho nhiều bo cùng cấu hình phần cứng: nhập ID/khóa do admin cấp tại **http://192.168.4.1 → Thiết bị, MQTT & HTTP → Đăng ký thiết bị**, rồi cấu hình MQTT/Wi-Fi. ID cố định sau lần cấp đầu, khóa cùng ID thay được không cần build lại. Source này chưa build/test/flash. Xem [hướng dẫn local, chuyển đổi bo cũ và ghi chú Android Scan new device/BLE](../docs/device-provisioning-local-and-ble.md).

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

### Giả lập cảm biến khi chưa có phần cứng

Trong `idf.py menuconfig` → **Sensor Sampling**, bật
**Simulate temperature and water sensor readings (demo only)**:

```text
CONFIG_POOL_SENSOR_SIMULATION=y
```

Mặc định tùy chọn này **tắt**. Đây là cấu hình khi build firmware, không phải
nút bật/tắt từ API/Android. Bật thêm DS18B20 và water sensor trong menu tương ứng,
đồng thời bật các cảm biến trên trang local nếu trước đó đã tắt và lưu trong NVS.

- Bỏ khởi tạo/đọc bus DS18B20 và ADC; không cần nối cảm biến.
- Nhiệt độ tăng/giảm trong khoảng **24–32 °C**, ADC **500–3500** theo chu kỳ 40 mẫu.
  Trạng thái nước bình thường/thấp vẫn dùng `CONFIG_WATER_LOW_THRESHOLD`.
- Giữ chu kỳ lấy mẫu idle/pumping và yêu cầu lấy mẫu hiện có; muốn demo nhanh hơn,
  giảm interval trong **Sensor Sampling**.
- Dữ liệu đi qua pipeline HTTP/MQTT, hàng đợi NVS và tự động hóa hiện có.
  Tắt riêng cảm biến vẫn ngừng cung cấp số đo của cảm biến đó.
- Log có nhãn `SIMULATED`; MQTT status và `/api/status` local có
  `sensor_simulation=true`. Trang web local hiển thị cảnh báo riêng.

**Relay/bơm không được giả lập.** Lệnh tay, trạng thái relay lúc khởi động và quy
tắc tự động hóa đã lưu vẫn có thể tác động phần cứng thật. Ngắt tải bơm khi demo.

API/database hiện chưa lưu nhãn giả lập theo từng mẫu; Android/lịch sử không tự
phân biệt được mẫu thật và giả. Chỉ dùng Device ID/tài khoản ESP dành riêng cho demo,
không trộn với dữ liệu thiết bị thật. Không dùng lại hàng đợi của thiết bị thật
khi đổi ID. Tắt tùy chọn không xóa dữ liệu demo đã lưu hoặc còn chờ gửi trong NVS.
Không sửa hợp đồng backend hoặc tự xóa hàng đợi trong thay đổi này.

Thay đổi giả lập này chưa được build, chạy kiểm thử hoặc nạp firmware.

### Thiết lập kết nối và nạp firmware

1. Cài đặt môi trường ESP-IDF và mở terminal đã nạp môi trường đó.
2. Tại thư mục `esp-firmware`, chạy `idf.py menuconfig` để kiểm tra cấu hình phần cứng, điểm truy cập, API và broker MQTT.
3. Không nhập ID/khóa/mật khẩu riêng vào header khi build. ID runtime lấy từ NVS; `CONFIG_POOL_DEVICE_ID` chỉ dành cho nhận diện dữ liệu legacy khi nâng cấp bo cũ. Giữ đúng ID cũ nếu còn queue/config chưa chuyển đổi.
4. Build và nạp firmware:

```bash
cd esp-firmware
idf.py build
idf.py -p COMx flash monitor
```

5. Sau khi nạp, cấp ID/khóa qua AP theo [hướng dẫn provisioning](../docs/device-provisioning-local-and-ble.md). Admin đăng ký/kích hoạt/cấp key HTTP trên server trước, nhập vào ESP rồi reboot. Tạo account MQTT theo ID bằng công cụ vận hành trên Pi, nhập mật khẩu ở trang local; username tự theo ID. Bo cũ cần cấp lại đúng ID/key; cấu hình MQTT NVS cũ được giữ nếu username khớp. Bo chưa có identity chỉ hoạt động local, chưa gửi dữ liệu lên server.

Thay `COMx` bằng cổng nối với bo mạch. Nếu tạo cấu hình mới mà chưa có `sdkconfig`, chọn target bằng `idf.py set-target esp32s3` trước khi build.
File `.bin` sau khi build chứa key HTTP của thiết bị; không chia sẻ artifact hoặc đưa vào Git. Trên máy này bản build đã kiểm tra nằm tại `build-v53/ds18b20.bin`, được Git bỏ qua. Flash cần cổng USB của ESP; `COM1` hệ thống không chứng minh bo ESP đã kết nối.

Mặc định firmware dùng API `https://mysterious-witness-crown-glasgow.trycloudflare.com/pool_api/pool_api.php` và MQTT `wss://mysterious-witness-crown-glasgow.trycloudflare.com/mqtt` qua Cloudflare Quick Tunnel. Wi-Fi đã lưu trong NVS được giữ lại. Cấu hình v2 lưu sau đó có thể ghi đè mặc định; kiểm tra trang local sau khi flash. Khi Quick Tunnel đổi URL, lấy endpoint hiện hành từ `~/smartpool-v2-endpoints.txt` trên Pi và cập nhật cả API lẫn MQTT WSS.

## Trang cấu hình cục bộ

ESP32 phát AP dạng `SmartPool-Setup-XXXXXX` (tiền tố theo menuconfig, hậu tố là 6 hex cuối MAC eFuse gốc) để cấu hình kể cả khi chưa có Wi-Fi router. Sau khi nạp bản mới cần chọn lại tên AP có hậu tố. Trang local hiển thị serial, MAC gốc và MAC Wi-Fi STA/AP để đối chiếu bo. Kết nối vào AP đó rồi mở:

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
