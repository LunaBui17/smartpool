# Ghi nhận triển khai runtime 005 — 18/11/2026

Đã áp dụng trên Pi `192.168.60.87`, thư mục `/home/qnm/smartpool-v2/deploy/raspberry-pi-v2`, project Docker `smartpool-v2`.

- Migration runner xác nhận 004 đã có và áp dụng `005_device_runtime.sql` thành công. Không reset database, không xóa volume hoặc thay credential.
- Đã build và triển khai API/worker mới. PHP kiểm tra cú pháp thành công. API healthy; health LAN và Cloudflare đều trả `api_version=2`, `schema_baseline=4`, `schema_runtime=5`.
- Worker kết nối broker; DB ghi `mqtt_connected=1`, heartbeat_age_seconds=0 tại lúc kiểm tra. MariaDB và Mosquitto tiếp tục hoạt động; tunnel giữ nguyên.
- Trước/sau: users=3, devices=2, sensor_samples=157, device_commands=3, activity_logs=21. Chưa chạy collection Postman, tạo fixture hoặc gửi lệnh relay.
- Lỗi quyền đọc PHP sau giải nén đã được sửa trong image chạy và Dockerfile: PHP/config 0644, thư mục web 0755. Đã recreate API từ image có bản sửa và kiểm tra healthy.

Backup tại `/home/qnm/smartpool-v2-rollbacks/runtime-005-20261118-151502/` (quyền private):

- `pool_db-before-005.sql.gz`: dump trước migration, đã kiểm tra gzip.
- `source-before-005.tar.gz`: source triển khai trước nâng cấp, không kèm `.env*`.
- `counts-before.txt`, `verification-after.txt`, `migration-005.log`, các log build.

Image trước nâng cấp được giữ với tag `smartpool-v2-api:before-runtime-005` và `smartpool-v2-control-worker:before-runtime-005`. Các tag này dùng cho lần nâng cấp này; không tự restore DB/drop bảng 005 khi chỉ cần quay lui code.

Giới hạn xác nhận: tại lúc kiểm tra, device `SmartPool` có HTTP age khoảng 18.951 giây, reported_config_revision=null, chưa có MQTT live presence. Không kết luận ESP online hoặc cấu hình đã được áp dụng. Firmware mới chưa build/flash; Android chưa sửa. Cần nạp firmware và thực hiện [checklist runtime](esp-api-mqtt-runtime.md) để xác nhận đầu cuối.
