# Kiểm tra Smart Pool API v2 bằng Postman

**007/008:** [collection khóa/MAC](SmartPool.shared-key-hardware.postman_collection.json) hiện có inventory, admin register bắt buộc ID+MAC+khóa và thư mục **DELETE FIXTURE A**. A/B/C là ID thử nghiệm khác nhau, ban đầu chưa có registry. Xem [hướng dẫn mới nhất](../../docs/hardware-inventory-delete.md); chỉ chạy phần xóa trên fixture. Chưa chạy collection.

**Runtime 006:** dùng [collection khóa chung và MAC](SmartPool.shared-key-hardware.postman_collection.json) theo [hướng dẫn mới](../../docs/shared-key-hardware.md). Collection runtime cũng đã thêm factory_mac và shared_device_key cho ESP. Collection v2 cũ dùng khóa riêng chỉ còn tham khảo lịch sử, không phải acceptance cho 006. Chưa chạy collection mới.

Runtime 005 có [collection thủ công riêng](SmartPool.runtime.postman_collection.json) và [checklist ESP/API/MQTT Web + Android](../../docs/esp-api-mqtt-runtime.md). Chỉ dùng fixture riêng. Chưa chạy collection runtime. Lần chạy collection v2 ngày 17/11/2026 có lỗi auth và lỗi dây chuyền; cần xác nhận token local trước khi tiếp tục, không xem là đạt. Không xuất response chứa secret.

Import [SmartPool.v2.postman_collection.json](SmartPool.v2.postman_collection.json). Các collection v1 đã bỏ vì schema và API không còn tương thích.

1. Người vận hành áp dụng database v2, tạo admin mới theo [hướng dẫn đầy đủ](../../docs/database-v2.md).
2. Điền local api_url, admin_username, admin_password và test_user_password (8–72 byte). Ngoài LAN dùng HTTPS URL trong ~/smartpool-v2-endpoints.txt, thêm đường dẫn /pool_api/pool_api.php.
3. Dùng Collection Runner, 1 iteration, đúng thứ tự và không song song. Có 56 request, gồm các bước hậu tố a/b.
4. Collection tạo hai user và hai thiết bị thử nghiệm, mẫu cảm biến giả, status OFF giả và lệnh OFF chỉ lưu DB. Không publish MQTT hoặc bật bơm.
5. Kết thúc sẽ revoke key, khóa user test và inactive thiết bị; dữ liệu/audit thử vẫn còn. Không export token/key hoặc response chứa secret.

Bộ kiểm tra xác thực hai loại credential, quyền nhiều user/nhiều thiết bị, config revision, chống trùng/gửi bù, giờ đo chưa biết, phân trang, nhật ký các thành viên và thu hồi quyền. Bước ACK có TTL 60 giây, cần chạy liền mạch. Nếu dừng giữa chừng, tự cleanup các ID test đã tạo.

Xem [tài liệu v2](../../docs/database-v2.md) cho payload từng action, giới hạn MQTT, cấu hình MQTT Web và quy trình reset/rollback. Collection chưa được chạy trong đợt phát triển này.
