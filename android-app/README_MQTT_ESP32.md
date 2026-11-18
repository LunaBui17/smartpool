# Ứng dụng Android Smart Pool với backend v2

## Tiến độ ngày 18/11/2026

Đợt đầu triển khai **giai đoạn 1: API, phiên và chọn thiết bị** của
[kế hoạch Android v2](../docs/android-api-v2-plan.md).
Chỉ thay source Android và tài liệu; **chưa build, kiểm thử, cài APK hoặc triển khai**.
Kết quả kiểm tra các phiên bản APK trước không xác nhận source mới này.

### Đợt tiếp theo: trang quản trị ba tab

Theo lựa chọn đã chốt, chỉ bổ sung Android theo API hiện có, không sửa backend,
database hoặc firmware. Admin đăng nhập/khôi phục phiên vào **Tổng quan · Thiết bị ·
Người dùng**; user thường vẫn vào danh sách thiết bị được phép.

- **Tổng quan:** thống kê active/inactive từ các trang danh mục đã tải, có nhãn
  “còn trang”; không coi số đã tải là tổng toàn hệ thống hoặc trạng thái online.
- **Thiết bị:** tìm trong các trang đã tải, phân trang, đăng ký thủ công, kích hoạt/
  vô hiệu hóa, xem thành viên, thêm/sửa/gỡ quyền xem–điều khiển–cấu hình.
  Thiết bị mới mặc định inactive; owner mặc định admin đăng ký, không tự cấp membership.
- **Người dùng:** phân trang/tìm trong danh sách đã tải, tạo user/admin với username,
  họ tên, mật khẩu và mã sinh viên tùy chọn; khóa/mở và đặt lại mật khẩu.
  ID nội bộ dùng long, không cho nhập tùy chỉnh.
- Từ user có thể chọn thiết bị để kiểm tra/gán/sửa/gỡ quyền. Picker tải các trang
  danh mục và đọc membership hiện có trước khi mở biểu mẫu; không mặc định ghi đè.
  API chưa có truy vấn “các thiết bị của user X” cho admin nên đây là chọn từng
  thiết bị để kiểm tra, không phải danh sách tự tổng hợp thiết bị đã gán.
- Mở thiết bị vào workspace riêng; Tổng quan trên workspace giờ hiển thị đúng
  thiết bị cho cả admin và user, không còn dùng màn tổng quan nhiều thiết bị cũ.
  Liên kết Người dùng trong Settings chuyển về tab quản trị toàn hệ thống.
- Rời trang đóng dialog/client và xóa mật khẩu biểu mẫu. Hủy request trên điện thoại
  không hoàn tác thao tác server đã nhận; tải lại trước khi gửi lại nếu chưa rõ kết quả.

**Đã chốt nhưng chờ API:** `user_code` riêng và duy nhất, giữ `users.id` tự tăng;
sửa hồ sơ; lưu trữ/xóa mềm và khôi phục (không gọi khóa là xóa); lưu và liên kết
serial phần cứng với Device ID. App không có thao tác giả lập các chức năng này.

ESP32 có MAC nhà máy trong eFuse, có thể đọc bằng `esp_efuse_mac_get_default()`
và chuẩn hóa thành serial `ESP32-<12 ký tự hex>`. Đề xuất liên kết duy nhất với
Device ID nghiệp vụ, giữ lịch sử thay board. Serial không phải credential, không
thay device key. Firmware hiện dùng Device ID cấu hình khi build; chưa có quy trình
tự nhận Device ID qua serial hay BLE. Đổi database một mình chưa tạo ra luồng đó.

### Đã thêm trong source

- Sau login/khôi phục phiên mở trang quản trị với admin, danh sách với user;
  không tự chọn ID mặc định.
- Dùng trang `devices` + cursor từ login; tải lại/tải thêm bằng `list_devices`,
  có loading, retry, danh sách rỗng và thiết bị inactive của admin.
- Trước khi mở workspace gọi `get_device` để xác minh lại ID, trạng thái và quyền.
- Phiên gắn với URL API chuẩn hóa, user ID, token và hạn; không nhớ chỉ giữ trong process.
  Phiên legacy thiếu server/user ID yêu cầu đăng nhập lại.
- Mỗi API client giữ snapshot phiên; mỗi request của thiết bị giữ đúng selection.
  Đổi thiết bị A → B → A không nhận callback của lần A cũ.
  Rời màn hình đóng queue, hủy callback/timer/chờ lệnh cục bộ.
- Token không gửi theo URL cấu hình mới; đổi server buộc đăng nhập lại.
  HTTP redirect không được tự theo để tránh chuyển credential sang đích khác.
- `401` của phiên hiện tại xóa phiên và back stack riêng tư. `403/404` khi đọc
  thiết bị đưa về danh sách để cập nhật quyền; `409` vẫn là lỗi xung đột, không ghi đè.
- Logout xóa phiên local trước và thử thu hồi đúng token trên đúng server cũ.
  Nếu offline/lỗi server, app thông báo chưa xác nhận thu hồi.
- Preferences thiết bị/view được tách theo server + user ID + device ID.
  Workspace đọc `get_config` và kiểm tra cấu hình trước khi mở điều khiển;
  không nhập hiệu chuẩn legacy toàn cục sang thiết bị mới.
- Không backup/transfer SharedPreferences và SQLite chứa phiên, credential/cache.
- Gradle không đọc và nhúng MQTT username/password từ file cấu hình vào APK.
- Nhật ký v2 chỉ hiển thị bản server của thiết bị đang chọn, không trộn SQLite legacy.
- Không có domain Quick Tunnel hoặc device ID mặc định bắt buộc. Cài mới cần nhập
  **Cấu hình API** ở màn đăng nhập; URL đã lưu không bị thay bằng domain trong source.

## Những luồng v2 được giữ lại

Workspace Main có Tổng quan / Điều khiển / Hệ thống cho một thiết bị;
quản trị nhiều thiết bị nằm ở trang gốc của admin.
`get_status`, `get_history`, `list_activity` gửi Bearer và đúng thiết bị.
Quyền thiết bị được tải lại định kỳ khi đang xem Main.

Bơm dùng `request_control` → worker Pi → MQTT riêng từng thiết bị → ESP ACK →
`get_command` và báo cáo trạng thái. API ghi nhận yêu cầu không phải ESP đã thực thi;
chỉ có trạng thái relay do firmware báo, chưa có cảm biến xác minh bơm cơ khí.
Rời workspace không hủy lệnh đã được server ghi nhận và không tự gửi OFF.

Admin dùng tab Người dùng toàn hệ thống để tạo user/admin, khóa/mở và đặt lại
mật khẩu. Tạo user không tự gán thiết bị; cấp membership riêng với quyền rõ ràng.
Khóa tài khoản/vô hiệu thiết bị có thể hủy lệnh pending trên server nhưng không
phải lệnh tắt relay. Backend chặn tự khóa và khóa admin hoạt động cuối cùng.

## Các phần còn chờ

- **Cấu hình v2 chưa có biểu mẫu lưu**: chưa `update_config`/xử lý revision conflict,
  chưa đồng bộ desired config xuống ESP. Các nút lưu cấu hình legacy, hiệu chuẩn,
  interlock, Wi-Fi và MQTT bị khóa cả UI lẫn đường publish.
- Settings hiện vẫn chỉ admin; mở cho user theo `can_configure` thuộc giai đoạn cấu hình.
- `user_code`, sửa hồ sơ/metadata, lưu trữ/khôi phục và liên kết serial chờ mở rộng API.
  `list_device_members` của backend vẫn admin-only; chưa hỗ trợ user xem thành viên.
- Biểu đồ và nhật ký vẫn giới hạn trang hiện có; bộ lọc thời gian, time_basis/cursor
  và ngữ nghĩa thời gian mẫu trên Tổng quan thuộc các giai đoạn tiếp theo.
- **Giám sát nền đang tạm khóa**, không tự khởi động. Service cũ nhận lệnh start cũng
  tự dừng, xóa notification cũ; chờ luồng giám sát v2 với context và admin bật/tắt.
- BLE chưa làm; không có giả lập scan hoặc provisioning Wi-Fi qua server.
  Cấu hình API trong app không tự đổi endpoint đã lưu trong ESP.
- Chưa kết luận đường điều khiển đã chạy đầu cuối. Nếu server làm mất Bearer header,
  source Android mới không tự sửa được Apache; cần kiểm tra JSON lỗi `401`.

## Checklist thủ công: hai user, hai thiết bị

Chuẩn bị qua API/công cụ quản trị hiện có: A được xem D1, B được xem D2,
D1 và D2 có tên/hiệu chuẩn khác nhau. Không cần thêm dữ liệu giả nếu chưa muốn
kiểm tra cursor. Không chia sẻ token, password hoặc device key.

1. Cài mới: chưa có URL thì phải báo cần cấu hình, không thử domain cố định.
   Nhập URL gốc hoặc URL PHP đầy đủ, đăng nhập A: chỉ có D1, chưa tự mở workspace.
2. Chọn D1: hiện đúng tên/ID; cảm biến dùng cấu hình server của D1.
   D2 chưa có mẫu/trạng thái phải chưa có dữ liệu, không lấy D1 thay thế.
3. Logout A, login B: chỉ có D2; không còn ID/cache/giá trị/pending của A.
   Tắt mạng trước logout: phiên local vẫn bị bỏ, thông báo server chưa xác nhận.
4. Thử có/không ghi nhớ: kết thúc process rồi mở lại.
   Có nhớ → trang gốc theo role; không nhớ → login.
   Khôi phục process từ màn Chart/Settings/Log khi chưa chọn thiết bị → trang gốc.
5. Login admin: vào Thiết bị để xem cả D1/D2, kể cả inactive; inactive không điều khiển.
   Chọn D1 → danh sách → D2 → D1 nhanh trong mạng chậm: không lẫn response.
   Rời màn hình khi lệnh pending: app ngừng chờ, không tuyên bố đã hủy lệnh server.
6. Trên server thu hồi phiên, quyền điều khiển/quyền xem hoặc vô hiệu hóa thiết bị.
   Sau request/poll tiếp theo: `401` về login; mất view → danh sách; mất control
   nhưng còn view → vẫn xem được, nút điều khiển bị khóa.
7. Đổi URL API khi đang đăng nhập: quay login, không gửi token cũ sang server mới.
   Logout/login B ngay khi response A còn chờ: không có callback A cập nhật B.
8. Với trên 50 thiết bị được phép: kiểm tra trang login, Tải thêm, tải lại khi
   đang ở trang sau, lỗi mạng rồi thử lại; không trùng/mất ID và cursor không lặp.
9. Xác nhận không có service/notification giám sát cũ tự chạy, không publish
   topic MQTT chung. Chart/Log chỉ hiện dữ liệu của thiết bị đang chọn.

Người dùng tự build bằng Android Studio hoặc Gradle như quy trình hiện có.
Chưa thực hiện checklist trong đợt này.

### Kiểm tra thêm trang quản trị (hai user A/B, hai thiết bị D1/D2)

1. Login admin phải vào Tổng quan với ba tab dưới; không cần chọn thiết bị để tạo user.
   User A/B không có thanh quản trị hoặc thao tác tạo/quản lý tài khoản.
2. Tạo A/B hoặc dùng tài khoản có sẵn; thử username trùng, mật khẩu không hợp lệ.
   Không có thông báo thành công nếu API lỗi. Không nhập user_code vào trường mã sinh viên.
3. Đăng ký D1/D2 thủ công nếu chưa có; đăng ký trùng phải báo lỗi. Mặc định inactive,
   admin vẫn xem được. Kích hoạt không tự cấp quyền và không tự bật bơm.
4. Từ Thiết bị D1 thêm A quyền xem; D2 thêm B quyền xem/điều khiển. Từ tab Người dùng,
   chọn A → chọn D2: biểu mẫu thể hiện chưa có membership; cấp quyền rồi mở lại phải
   đọc đúng quyền server. Sửa membership có sẵn phải nạp giá trị hiện tại.
5. Login A/B kiểm tra danh sách/quyền; dùng admin gỡ A khỏi D2: A vẫn có D1, B vẫn có
   D2 và tài khoản A không bị xóa. Thay đổi quyền trên Main được nhận ở poll tiếp theo.
6. Khóa/mở B: phiên cũ bị thu hồi khi khóa, mở lại dùng quyền đang có. Không hiện
   “đã lưu trữ/xóa/khôi phục”. Không tự khóa admin hiện tại; backend vẫn bảo vệ admin cuối.
   Đổi mật khẩu chính admin: app chuyển login; mật khẩu cũ bị từ chối.
7. Mở D1/D2: workspace chỉ có số đo/ID của thiết bị đó. Quay lại giữ tab quản trị;
   liên kết Người dùng trong Settings mở quản trị chung. Ra nền/logout khi đang tải
   picker/membership: không bật dialog cũ vào tài khoản khác.
8. Với trên 50 users/devices, kiểm tra Tải thêm và tìm kiếm trong các trang đã tải;
   Tổng quan không gọi số của trang đầu là tổng hệ thống. Ngắt mạng khi lưu rồi tải lại
   để xác định kết quả trước khi thử lại. Serial, user_code và lưu trữ chưa được ghi giả.
