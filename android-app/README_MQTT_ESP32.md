# Ứng dụng Android Smart Pool

**Tương thích backend:** app hiện tại chưa tích hợp API v2, role user, danh sách/chọn thiết bị và context device_id. Các hành vi admin/guest bên dưới mô tả client cũ; không dùng làm bằng chứng tương thích backend mới. Xem [hợp đồng v2](../docs/database-v2.md). BLE chưa triển khai.

[Hướng dẫn tổng thể](../README.md) · [Giao thức MQTT](../README_MQTT_ESP32.md) · [Luồng toàn hệ thống](../docs/system-flow.md) · [Triển khai Raspberry Pi](../deploy/raspberry-pi/README.md)

Ứng dụng đăng nhập và đọc số đo/lịch sử qua HTTPS API; điều khiển bơm, nhận trạng thái ESP32 và cấu hình thiết bị qua MQTT. Dịch vụ nền nhận telemetry để phát cảnh báo. Admin có thể tạo tài khoản admin/guest và quản lý guest; người dùng có thể xem biểu đồ và nhật ký thao tác.

## Nhật ký thao tác

Nhấn **Nhật ký thao tác** trên màn hình chính để xem tối đa 100 sự kiện mới nhất: thời gian, tài khoản, ID thiết bị, thao tác và kết quả. Guest xem nhật ký của mình; admin xem nhật ký các tài khoản.

- Ghi nhận lệnh bơm, bật/tắt cảm biến, khóa liên động, gửi cấu hình/tự động hóa, lưu kết nối MQTT, hiệu chuẩn, khôi phục mặc định và quản lý tài khoản.
- Lệnh bơm/cảm biến chỉ được ghi **Đã xác nhận** khi nhận trạng thái ESP32 khớp yêu cầu. Gửi cấu hình cần ACK khớp `req_id` và `action`. Khóa liên động chỉ ghi broker đã nhận, không khẳng định thiết bị đã áp dụng.
- Sự kiện lưu trước vào SQLite trên điện thoại; tự gửi qua API khi thêm sự kiện, trở lại màn hình chính, đọc API thành công hoặc mở nhật ký. Lỗi mạng không chặn điều khiển MQTT và không xóa sự kiện chưa đồng bộ.
- Hàng đợi gắn với tài khoản và URL API lúc thao tác. Chỉ gửi khi đăng nhập đúng tên tài khoản và dùng lại URL đó, không tự chuyển nhật ký sang máy chủ khác khi đổi URL.
- Nhật ký không chứa mật khẩu, token hoặc payload cấu hình. Đây là nhật ký do app báo cáo, không phải bằng chứng kiểm toán độc lập từ broker hay phản hồi vật lý của bơm.

Mục **Chưa đồng bộ** vẫn nằm trên điện thoại; nhấn **Đồng bộ và tải lại** để cập nhật. Nếu ứng dụng bị hệ điều hành dừng trước khi nhận kết quả, bản ghi yêu cầu chưa xác nhận được giữ nguyên, không suy đoán thành công. Gỡ app/xóa dữ liệu app sẽ xóa nhật ký cục bộ chưa gửi.

## Tab Trạng thái hệ thống

Màn hình chính có hai tab **Giám sát** và **Trạng thái hệ thống**, dùng được cho cả admin và guest. Tab trạng thái vẽ lưu đồ có các khối và mũi tên:

- Android → API trên Raspberry Pi → MariaDB.
- Android → MQTT/Mosquitto → ESP32-S3.
- ESP32 → cảm biến nhiệt độ/mực nước, hai bơm/relay, khóa liên động, Wi-Fi và quy tắc tự động của từng bơm.

Mỗi khối hiển thị trạng thái bằng chữ và màu: bật/Enabled, tắt/Disabled, không hỗ trợ, chờ xác nhận, lỗi kết nối hoặc chưa rõ. Trạng thái cập nhật từ cùng kết nối API/MQTT của màn hình giám sát, không tạo thêm kết nối và không gửi lệnh khi chuyển tab. Giữ tab đang chọn khi tái tạo màn hình.

MariaDB chỉ được đánh dấu truy vấn thành công khi app đọc được phản hồi API hợp lệ; API lỗi không đủ để kết luận database đã tắt. Khi mất liên lạc ESP hoặc chưa có trường dữ liệu, phần cứng hiển thị **Chưa rõ**, không suy đoán tắt. Khóa liên động dùng giá trị ESP báo, không dùng công tắc đã lưu cục bộ. Wi-Fi chỉ xác nhận ESP báo có IP LAN. Trạng thái bơm phản ánh relay do firmware báo, không xác minh motor thực sự quay.

Phần đầu hiển thị ID ESP báo khi đang liên lạc, nếu chưa có thì ghi rõ ID cấu hình trên app; có thời gian nhận status và kiểm tra API. Bản tin MQTT có thể là retained, thời gian nhận không phải thời gian đo/heartbeat mới. Tab không kiểm tra trực tiếp tiến trình Docker, Cloudflare hoặc tình trạng hệ điều hành Pi.

## Biểu đồ và khôi phục cấu hình

Trục X dùng `received_at_ms` từ API, hiển thị ngày/giờ theo múi giờ điện thoại. Khoảng cách điểm tương ứng thời gian thực tế, không phải thứ tự mẫu; một mẫu vẫn được vẽ bằng chấm. Đây là **thời điểm máy chủ nhận dữ liệu**, không phải thời điểm đo khi ESP gửi bù sau mất mạng. Mẫu thiếu timestamp bị bỏ qua kèm thông báo cập nhật API.

Cấu hình đã lưu trong app được giữ qua lần mở lại: kết nối API/MQTT, thông tin thiết bị, ngưỡng, hiệu chuẩn, khóa liên động, tab Settings và cảm biến đang xem biểu đồ. Khi mở biểu đồ từ thẻ cảm biến, thẻ được chọn có ưu tiên; khi tái tạo màn hình, giữ lựa chọn đang xem. Nội dung mới nhập chưa lưu không được bảo đảm khôi phục.

App **không tự gửi lại lệnh bật bơm** khi mở lại. Trạng thái bơm và cảm biến lấy từ ESP32; chờ MQTT kết nối và ESP online trước khi điều khiển. Phiên đăng nhập chỉ giữ qua lần kết thúc tiến trình khi chọn ghi nhớ và token còn hạn.

Để tự kiểm tra: lưu cấu hình/hiệu chuẩn, chọn tab, đóng rồi mở lại app mà không xóa dữ liệu; đối chiếu giá trị đã lưu và chờ trạng thái ESP32. Thử nhật ký khi API mất kết nối nhưng MQTT còn hoạt động, sau đó phục hồi API và tải lại nhật ký.

## Admin tạo admin khác

Trong **Settings → Tài khoản**, nhập tên/mật khẩu, chọn vai trò **Guest** hoặc **Admin**, rồi nhấn **Tạo tài khoản**. Mặc định là Guest. Admin mới có thể đăng nhập và quản lý như admin hiện có; API kiểm tra quyền admin của người tạo.

Danh sách hiển thị cả hai vai trò. Admin hiện có chỉ đọc, không sửa/xóa tại đây. Khi sửa guest, vai trò giữ nguyên guest, không dùng biểu mẫu sửa để nâng quyền. Admin đầu tiên vẫn được tạo bằng công cụ CLI trên Pi.

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
