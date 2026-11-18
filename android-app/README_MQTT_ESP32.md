# Ứng dụng Android Smart Pool với backend v2

App dùng HTTPS API v2 qua Cloudflare Quick Tunnel. ESP kết nối MQTT WSS và HTTP với Pi; app không đăng nhập trực tiếp vào broker. Broker v2 không có tài khoản `android` hay quyền publish các topic điều khiển cũ.

## Chức năng đã tích hợp

- Đăng nhập bằng `login`, lưu Bearer token và vai trò `admin` hoặc `user`; đăng xuất bằng `logout`.
- Theo dõi thiết bị `SmartPool` bằng `get_status`, gồm trạng thái relay, cảm biến, IP LAN và mẫu đo gần nhất.
- Xem biểu đồ bằng `get_history` theo thời điểm Pi nhận dữ liệu. Nếu chưa hiệu chuẩn mực nước, biểu đồ hiển thị ADC thô.
- Xem nhật ký thiết bị bằng `list_activity`.
- Admin xem `list_users`, tạo tài khoản bằng `create_user`, đổi mật khẩu bằng `set_user_password`, khóa/mở bằng `set_user_active`. User mới được cấp quyền xem `SmartPool` bằng `set_device_member`.
- Dịch vụ nền đọc API v2 mỗi 30 giây để cảnh báo nhiệt độ khi thiết bị và mẫu đo còn mới.

`device_id` mặc định là `SmartPool`. Admin có thể sửa ID trong phần cài đặt; user cần được cấp quyền xem thiết bị tương ứng. Tài khoản đăng nhập app là tài khoản API, không phải tài khoản MQTT hay MariaDB.

## Giới hạn hiện tại

Admin có trang Tổng quan riêng: danh sách thiết bị, chủ sở hữu, trạng thái online, bơm và ô tìm theo ID. User chỉ nhận thiết bị được cấp quyền qua `list_devices`. Nút bơm gọi `request_control`; worker trên Pi kiểm tra quyền rồi phát lệnh MQTT theo `device_id`, ESP gửi ACK và trạng thái mới. Nút chỉ bật khi thiết bị online và worker báo sẵn sàng. Cấu hình cảm biến và interlock từ xa vẫn khóa. Nhật ký thao tác cũ trong SQLite chỉ lưu trên điện thoại; API v2 dùng một định dạng `record_activity` mới.

App dùng URL mặc định của tunnel đang chạy khi build. Quick Tunnel có thể đổi URL sau khi khởi động lại. Lấy `android_http_api_url` từ `~/smartpool-v2-endpoints.txt` trên Pi và nhập ở **Cấu hình API** trên màn hình đăng nhập hoặc phần cài đặt của app. URL hiện tại không phải địa chỉ cố định lâu dài.

## Build và kiểm tra

Trong `android-app`, chạy `./gradlew assembleDebug` trên Linux/macOS hoặc `.\gradlew.bat assembleDebug` trên Windows. APK debug nằm tại `app/build/outputs/apk/debug/app-debug.apk`.

Ngày 18/11/2026: build debug thành công; đã cài lên Android emulator, đăng nhập admin, đọc `SmartPool`, tải 37 mẫu lịch sử, và xem danh sách tài khoản/nhật ký qua API v2. Chưa cài trên điện thoại thật. Xem thêm [API v2](../docs/database-v2.md) và [triển khai Pi](../deploy/raspberry-pi-v2/README.md).
