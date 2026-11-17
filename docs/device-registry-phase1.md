# Registry giai đoạn 1 – tài liệu đã thay thế

Mô hình pool_devices/pool_device_permissions và API giai đoạn 1 đã được thay bằng database/API v2 nhiều thiết bị. Không áp dụng các lệnh hoặc collection giai đoạn 1 lên source hiện tại.

Xem [database và API v2](database-v2.md) để biết bảng users/devices/device_users, reset không giữ dữ liệu cũ, Postman và MQTT Web. Các migration 001–003 còn trong repository để truy vết lịch sử, không được runner v2 áp dụng.

Reset chỉ do người vận hành chủ động thực hiện; chưa chạy reset/triển khai trong đợt phát triển này. Client Android/ESP cũ chưa tương thích API v2.
