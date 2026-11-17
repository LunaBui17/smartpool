# Kiểm tra database bằng Postman

Collection kiểm tra MariaDB qua API PHP; không kết nối trực tiếp cổng SQL và không gửi lệnh bơm hoặc mẫu cảm biến giả.

1. Trong Postman chọn **Import**, mở `SmartPool.postman_collection.json`.
2. Trong Variables của collection, `api_url` mặc định là API LAN `http://192.168.60.87/pool_api/pool_api.php`. Nếu truy cập từ ngoài mạng, lấy giá trị `http_api_url` trong `~/smartpool-endpoints.txt` trên Pi. URL Cloudflare có thể đổi khi tunnel khởi động lại nên cần cập nhật biến này.
3. Đặt `username=admin` và điền `password` bằng mật khẩu ứng dụng (`APP_PASSWORD`), không dùng mật khẩu database hoặc MQTT. Trên Pi xem `cat ~/smartpool-credentials.txt`. Chỉ lưu mật khẩu trong giá trị local/không chia sẻ của Postman, hoặc trong environment local riêng; không export mật khẩu lên Git.
4. Chọn **Run collection**, chạy cả 7 request theo thứ tự, 1 iteration.

| Request | Bảng được kiểm tra | Kết quả mong đợi |
| --- | --- | --- |
| 1 | `pool_status` | HTTP 200, `id=1`, có các trường cảm biến |
| 2 | `pool_history` | HTTP 200, mảng tối đa 10 mẫu; `[]` hợp lệ nếu chưa có mẫu |
| 3 | `pool_commands` | HTTP 200, có revision và trạng thái lệnh |
| 4 | `pool_users`, `pool_user_sessions` | HTTP 200, đăng nhập admin thành công và tạo token |
| 5 | `pool_users`, `pool_user_sessions` | HTTP 200, đọc danh sách guest bằng token; danh sách rỗng hợp lệ |
| 6 | `pool_user_sessions` | HTTP 200, xóa phiên vừa tạo |
| 7 | `pool_user_sessions` | HTTP 403: xác nhận token đã bị thu hồi; test này phải PASS |

Kiểm tra kết quả tại **Test Results** / kết quả Collection Runner. HTML hoặc lỗi tunnel cho biết cần kiểm tra tunnel/URL trước; HTTP 500 JSON cần xem log API và database. HTTP 401 ở bước login thường do sai mật khẩu ứng dụng. Nếu dừng sau bước 4 hoặc 5, chạy bước 6 để đăng xuất phiên thử nghiệm (phiên cũng tự hết hạn sau 12 giờ).

Collection kiểm tra các thao tác đọc, tạo và xóa phiên qua ứng dụng, không thay thế kiểm tra toàn vẹn mọi bảng trong MariaDB.

## API nhật ký và quản lý admin

Các request dưới đây không nằm trong collection 7 bước hiện có. Dùng POST JSON tới cùng `api_url`, kèm `Authorization: Bearer TOKEN` từ đăng nhập; không chia sẻ token.

| Action | Quyền | Nội dung |
| --- | --- | --- |
| `list_activity` | Người dùng đăng nhập | Trả `events` tối đa 100 sự kiện mới nhất; guest chỉ thấy sự kiện của mình, admin thấy mọi tài khoản |
| `record_activity` | Người dùng đăng nhập | Nhận `events` gồm 1–20 sự kiện; người thực hiện lấy từ phiên, gửi lại cùng `event_id` không tạo bản sao |
| `list_users` với `include_admins: true` | Admin | Trả cả admin và guest; mặc định vẫn chỉ trả guest để tương thích collection hiện có |
| `save_user` với `role: "admin"` | Admin | Chỉ dùng để tạo tài khoản mới; không cho sửa/xóa admin hiện có hoặc nâng quyền guest qua cập nhật |

Mỗi sự kiện gồm `event_id` và `request_id` dạng UUID, `device_id`, `action`, `result`, `detail`, `occurred_at_ms` dạng số nguyên mili giây. `result` nhận `requested`, `confirmed`, `failed`, `timeout`, `interrupted`, `saved`, `published`. Không gửi mật khẩu, token hoặc payload cấu hình trong sự kiện. Cần áp dụng migration `backend/migrations/001_activity_log.sql` trước khi sử dụng.

GET lịch sử giữ các trường cũ và bổ sung `received_at_ms` dạng số nguyên để vẽ trục thời gian. Đây là thời điểm MariaDB nhận mẫu, không phải thời điểm đo khi ESP gửi bù.
