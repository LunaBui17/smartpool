# Ứng dụng Android Smart Pool

[Hướng dẫn tổng thể](../README.md) · [Giao thức MQTT](../README_MQTT_ESP32.md) · [Luồng toàn hệ thống](../docs/system-flow.md) · [Triển khai Raspberry Pi](../deploy/raspberry-pi/README.md)

Ứng dụng đăng nhập và đọc số đo/lịch sử qua HTTPS API; điều khiển bơm, nhận trạng thái ESP32 và cấu hình thiết bị qua MQTT. Dịch vụ nền nhận telemetry để phát cảnh báo. Admin có thể quản lý tài khoản khách; người dùng có thể xem biểu đồ lịch sử và cấu hình kết nối.

## Bật/tắt cảm biến

Mỗi thẻ nhiệt độ và mực nước trên màn hình chính có công tắc bật/tắt đo trên ESP32. Công tắc chỉ thao tác được khi MQTT đã kết nối, ESP32 trực tuyến và firmware hỗ trợ cảm biến đó. Ứng dụng chờ trạng thái ESP32 xác nhận; nếu gửi lỗi hoặc quá thời gian chờ sẽ báo lỗi, không coi việc broker nhận lệnh là thành công.

Khi cảm biến tắt, thẻ hiển thị “Đã tắt” và không tạo cảnh báo trên màn hình từ số đo cũ của cảm biến đó. Thao tác này dừng đo, không ngắt nguồn điện cảm biến; không xóa lịch sử đã lưu. Có thể bật lại từ app hoặc web ESP32; trạng thái được đồng bộ qua MQTT. Việc tắt cảm biến cũng có thể ảnh hưởng các quy tắc tự động hóa đang dùng cảm biến đó.

## Tạo APK

Cần cài Android SDK và JDK theo cấu hình Gradle. Trên Linux hoặc macOS, chạy từ thư mục `android-app`:

```bash
./gradlew assembleDebug
```

Trên Windows:

```powershell
.\gradlew.bat assembleDebug
```

APK debug được tạo tại `app/build/outputs/apk/debug/app-debug.apk`. Đây là bản cài thử; phát hành chính thức cần tạo và bảo vệ khóa ký release riêng.

## Cấu hình kết nối

1. Trên màn hình đăng nhập, nhấn **Cấu hình API** để xem hoặc cập nhật URL API. Nếu nhập URL gốc, ứng dụng tự bổ sung `/pool_api/pool_api.php`.
2. Đăng nhập bằng tài khoản ứng dụng đã tạo trên Pi. Tài khoản ứng dụng không phải tài khoản MQTT hoặc MariaDB.
3. Với tài khoản admin, trong Settings → MQTT, nhập `mqtt_wss_uri` từ `~/smartpool-endpoints.txt` để kết nối Mosquitto trên Pi. Dùng tài khoản MQTT `android` và mật khẩu từ cấu hình riêng trên Pi; file endpoint không chứa mật khẩu.
4. Lưu cấu hình MQTT trong Settings. Đổi URL API không tự đổi broker MQTT; thông tin kết nối đã lưu được ưu tiên hơn giá trị mặc định của ứng dụng.

Để nhúng tài khoản MQTT mặc định khi build, sao chép `mqtt.properties.example` thành `mqtt.properties` rồi điền `mqtt.username` và `mqtt.password` bằng tài khoản ứng dụng trên Mosquitto. Không chia sẻ hoặc đưa tệp chứa mật khẩu lên Git.

Địa chỉ Quick Tunnel có thể thay đổi sau khi tunnel khởi động lại; lấy URL hiện hành từ file endpoint trên Pi. Chi tiết topic, payload và xác nhận lệnh nằm trong [tài liệu giao thức MQTT](../README_MQTT_ESP32.md).
