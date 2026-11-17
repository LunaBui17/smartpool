# Firmware ESP32-S3 Smart Pool

[Hướng dẫn tổng thể](../README.md) · [Luồng toàn hệ thống](../docs/system-flow.md) · [Giao thức MQTT](../README_MQTT_ESP32.md) · [Triển khai Raspberry Pi](../deploy/raspberry-pi/README.md)

Firmware đọc cảm biến, điều khiển hai relay, tự động hóa bơm ngay trên ESP32-S3 và kết nối với backend Smart Pool:

- Đọc nhiệt độ từ DS18B20 và mực nước từ cảm biến analog.
- Điều khiển độc lập bơm vào và bơm ra qua MQTT hoặc trang web nội bộ.
- Lưu cấu hình và quy tắc tự động hóa trong NVS.
- Gửi mẫu đo tới API qua HTTP(S); lưu tạm tối đa 128 mẫu trong NVS khi chưa gửi được.
- Phát trạng thái và telemetry qua MQTT để ứng dụng Android theo dõi.
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
3. Nếu dùng Mosquitto trên Raspberry Pi, lấy endpoint mới nhất từ `~/smartpool-endpoints.txt` trên Pi. Cấu hình thông tin MQTT tương ứng trong `main/mqtt_credentials.h`; không chia sẻ mật khẩu hoặc tệp chứa thông tin xác thực.
4. Build và nạp firmware:

```bash
cd esp-firmware
idf.py build
idf.py -p COMx flash monitor
```

Thay `COMx` bằng cổng nối với bo mạch. Nếu tạo cấu hình mới mà chưa có `sdkconfig`, chọn target bằng `idf.py set-target esp32s3` trước khi build.

Thông tin Wi-Fi, API, MQTT và các quy tắc đã lưu trong NVS được giữ qua lần khởi động lại và có thể ghi đè giá trị mặc định trong firmware. Địa chỉ Quick Tunnel có thể thay đổi; hãy lấy địa chỉ hiện hành từ file endpoint trên Pi rồi cập nhật cấu hình ESP32 và Android.

## Trang cấu hình cục bộ

ESP32 phát điểm truy cập `SmartPool-Setup` (mặc định) để cấu hình kể cả khi chưa có Wi-Fi router. Kết nối điện thoại hoặc máy tính vào điểm truy cập, sau đó mở:

```text
http://192.168.4.1
```

Mật khẩu điểm truy cập mặc định là `12345678`; nên đổi mật khẩu trong `menuconfig` trước khi triển khai. Trang web cũng có thể mở bằng địa chỉ LAN của ESP32 khi thiết bị đang kết nối router.

Trang cấu hình cho phép xem trạng thái cảm biến và kết nối, bật/tắt cảm biến, điều khiển từng bơm hoặc dừng cả hai, cấu hình Wi-Fi/API/MQTT, quét mạng Wi-Fi và thiết lập tự động hóa. Các chức năng bị tắt khi build sẽ không khả dụng trên giao diện.

## Điều khiển và dữ liệu

- Lệnh MQTT cho bơm dùng topic `smartpool/cmd/bom_vao` và `smartpool/cmd/bom_ra`; payload `1` bật, `0` tắt. Lệnh bật bơm không được gửi dưới dạng retained.
- ESP32 phát trạng thái tại `smartpool/status` và số đo tại `smartpool/telemetry`. Trạng thái relay là trạng thái GPIO được điều khiển, không phải xác nhận bơm thực sự đang quay.
- Mẫu đo được gửi qua HTTP(S) để lưu lịch sử. Khi API chưa khả dụng, firmware xếp mẫu vào hàng đợi NVS và gửi lại khi có thể.
- Quy tắc tự động hóa chạy trên ESP32, không cần ứng dụng Android duy trì kết nối.
- Điều khiển từ xa yêu cầu MQTT và ESP32 trực tuyến. Điều khiển tại chỗ và tự động hóa hoạt động theo trạng thái cảm biến, cấu hình liên động và chế độ hiện tại của thiết bị.

Danh sách topic, cấu trúc payload, retained và xác nhận lệnh được mô tả trong [tài liệu giao thức MQTT](../README_MQTT_ESP32.md). Luồng dữ liệu, lưu trữ và xử lý khi mất kết nối nằm trong [sơ đồ toàn hệ thống](../docs/system-flow.md).

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
