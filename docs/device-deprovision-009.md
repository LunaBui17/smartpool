# Xóa server → reset ESP → thu hồi MQTT — runtime 009

Backend/worker, migrations 006–009 và dịch vụ lifecycle đã triển khai trên Pi ngày 18/11/2026. Health LAN/Cloudflare xác nhận schema_runtime=9; lifecycle active/enabled. Firmware reset mới **chưa build/flash và chưa kiểm tra trên bo thật**. Không gửi yêu cầu xóa/reset cho thiết bị thật trong lần triển khai này.

## Luồng hiện tại

1. Admin gửi delete_device với Bearer admin, device_id và shared_device_key. Server tạo job, vô hiệu hóa device và hủy command pending. Response deleted=false, kèm deletion.job_id/status=requested. Chưa xóa registry hoặc giải phóng MAC.
2. Firmware mới poll get_device_config khoảng 30 giây/lần. Riêng thiết bị đang chờ xóa vẫn được gọi action này khi inactive, nhưng phải có khóa chung và ID/MAC đã gắn. Server trả reset_command chứa job_id, MAC đích, nonce một lần và hạn lệnh. Không gửi khóa admin hoặc dùng topic MQTT chung để reset.
3. ESP kiểm tra ID/MAC/TTL, ghi reset marker bền vững, dừng cả relay và MQTT. Tạm dừng web local/automation/writer trước khi xóa để không có task ghi ngược cấu hình vào NVS.
4. Xóa namespace ứng dụng poolid (identity/key), poolnet (connection/config/automation/command ledger) và poolq (sensor queue/owner); gọi esp_wifi_restore để xóa cấu hình Wi-Fi trên flash. Không erase toàn partition/calibration hệ thống; MAC eFuse bất biến vẫn giữ.
5. ESP kết nối Wi-Fi tạm bằng cấu hình **RAM-only** để gửi acknowledge_device_reset sau khi đã xóa cấu hình lưu. Không lưu lại SSID/password cũ vào flash. Receipt dùng nonce của job vì credential bình thường đã bị xóa. Server giữ hash nonce, không log giá trị thô.
6. Receipt cleared hợp lệ chuyển job sang reset_confirmed. Dịch vụ root smartpool-device-lifecycle trên Pi: xóa account/ACL thiết bị qua mqtt-admin, gửi SIGHUP để reload broker, ngắt client ID chuẩn của bo bằng kết nối thay thế, rồi archive/xóa registry và gỡ ID khỏi MAC trong một transaction. Không restart broker hoặc đổi mật khẩu của các bo khác.
7. ESP xóa marker sau khi server nhận receipt, ngắt Wi-Fi và reboot. Sau boot chỉ còn AP cấu hình cục bộ, không tự nối lại Wi-Fi nhà/MQTT, identity NVS rỗng nên có thể cấp ID mới. MAC trong inventory trở lại null/deactive/available sau khi server finalize.

Trạng thái job: requested → reset_confirmed → revoking → deleted. Chỉ deleted mới có nghĩa archive/xóa server đã commit và bước thu hồi broker đã thành công. Cơ chế này dựa vào báo cáo từ firmware tin cậy, không phải chứng thực flash bằng mật mã; khóa chung/MAC công khai không chống client chủ động giả mạo như đã nêu trong hợp đồng 006.

## Offline, mất điện hoặc lỗi

- Offline/firmware cũ/chưa có MAC hoặc khóa chung đúng: không tự báo reset xong. Job vẫn chờ, device inactive và MAC chưa được giải phóng. Chưa có đường force-delete/cancel job tự động bỏ qua xác nhận.
- Chưa có mapping MAC: yêu cầu xóa trả 409, admin cần gắn MAC thật trước; không gửi reset mù theo một ID.
- Lỗi dừng relay/xóa NVS/Wi-Fi không tạo receipt cleared. Server lưu mã lỗi, giữ job chờ; không revoke/xóa trước khi xác nhận reset.
- Mất nguồn sau khi ghi marker: lần boot tiếp theo tiếp tục xóa cấu hình và khôi phục AP. Nếu Wi-Fi flash đã xóa nên không gửi được receipt, trang local có reset_pending=true; nhập Wi-Fi/giờ/URL API để gửi lại. Wi-Fi phục hồi chỉ lưu RAM, các thao tác cấp ID/điều khiển khác bị khóa trong lúc chờ.
- Nếu Quick Tunnel URL đổi, cập nhật HTTP URL qua AP để gửi receipt tới backend hiện tại. Không đưa nonce vào log/ảnh; GET local không trả token.
- Nếu broker hoặc bước archive lỗi: job ở revoking, dịch vụ thử lại. Registry/MAC chưa được giải phóng sớm. Ngắt điện giữa receipt và xóa marker không gây thực hiện lại thao tác điều khiển; receipt được chấp nhận idempotent cho cùng job.

## Theo dõi từ app/Postman

```json
{"action":"delete_device","device_id":"<ID_can_xoa>","shared_device_key":"<khoa_chung_64_hex>"}
```

Lệnh có thể xóa cấu hình/queue thật trên bo đã nạp firmware mới. Chỉ dùng ID thử nghiệm khi kiểm tra. Response HTTP 200 là tiếp nhận, không phải xóa xong. Lưu job_id không nhạy cảm và poll bằng Bearer admin:

```json
{"action":"get_device_delete_job","job_id":"<UUID_tu_response>"}
```

Admin UI cần hiện đang chờ ESP / đang thu hồi MQTT / đã xóa / lỗi cần xử lý. Không bỏ thiết bị khỏi danh sách chỉ vì response đầu tiên 200. Sau deleted, tải lại inventory; ID bị xóa khỏi registry, MAC giữ và available=true, lịch sử ở archive, user/account và thiết bị khác không bị xóa. Không tự phục hồi queue/config/lệnh cũ khi cấp lại bo.

BLE/app chưa sửa. Luồng cấp lại qua AP hiện có làm được sau khi reset hoàn tất; reset từ server không cần điện thoại ở gần khi ESP đang online với firmware mới. Nếu offline hoặc phục hồi receipt, cần hỗ trợ local như trên.

## Vận hành Pi

- Systemd: smartpool-device-lifecycle.service, chạy root để gọi Docker/công cụ account hiện có; không cấp docker.sock cho PHP/worker. Chỉ lấy job reset_confirmed/revoking từ DB, không có endpoint web để chạy shell tùy ý.
- `sudo systemctl status smartpool-device-lifecycle.service` và journalctl của unit để xem trạng thái. Log không in output chứa credential của subprocess.
- activate-control-worker.sh dừng unit/API/worker trước migrate rồi khởi động lại và cài unit. Không tự xóa DB. Sao lưu trước khi chạy.
- Khóa HTTP chung mới được tạo khi chưa có khóa active, lưu private `/home/qnm/smartpool-shared-device-key.txt`, mode 600, owner qnm. File JSON có shared_device_key/credential_id; người vận hành đọc riêng, không paste/export secret vào chat hoặc Git. Xóa một bo chỉ xóa bản sao key trên bo, **không revoke khóa chung toàn hệ thống**.
- Thu hồi broker áp dụng username riêng của device và client ID chuẩn theo factory_serial. Không gọi đây là chặn tuyệt đối mọi client giả mạo dùng credential chung/client ID tùy ý. Các giới hạn chính sách mật khẩu chung vẫn giữ.

## Ghi nhận triển khai

Backup: `/home/qnm/smartpool-v2-rollbacks/runtime-009-20261118-171314/`, gồm dump gzip đã kiểm tra, source cũ và log migration/build/verification. Image cũ được giữ tag before-runtime-009 cho API/worker. Chưa thử restore.

Trước/sau: users=3, devices=2, sensor_samples=161, commands=3. Audit tăng 21→22 do tạo khóa chung; không có device_delete_jobs hoặc device_archives (đều 0), inventory chưa có MAC được gán (0). Một khóa HTTP chung active. Worker mqtt_connected=1, heartbeat age=0; lifecycle running, enabled, NRestarts=0. Health LAN và Cloudflare đều success/schema_runtime=9.

Hai registry cũ cần admin gắn MAC thật và cấp khóa chung vào firmware mới; không tự gán MAC đoán hoặc nhập khóa cũ vào bảng khóa chung. Chưa build/test firmware, chưa chạy collection chức năng, chưa reset bo, chưa revoke account thiết bị thật trong lần triển khai này. Cần nạp firmware và kiểm tra trên bo thử nghiệm không nối tải trước khi dùng thao tác xóa thực tế.

Nếu quay lui: dừng lifecycle trước, giữ jobs/inventory/archive/backup và đối soát các job đã thực hiện; không đổi image cũ rồi tự khôi phục lệnh hoặc credential đã bị thu hồi. Việc reset bo đã hoàn tất không thể đảo ngược bằng rollback database.
