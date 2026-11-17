# MQTT Smart Pool theo thiết bị

Broker trên Pi đã chuyển policy v2, bỏ account chung esp32/android. Xem [topic, ACL, quản lý account và MQTT Web](docs/mqtt-v2.md).

ESP dùng username đúng device_id và password riêng; namespace smartpool/devices/{device_id}/. Bridge backend có quyền publish lệnh/config, app người dùng gọi API để kiểm tra quyền. Tài khoản bridge không được cấp cho Android/user. MQTT Web dùng diagnostics account riêng, không điều khiển thiết bị.

Firmware/Android hiện chưa tích hợp giao thức mới; kết nối bằng account cũ bị broker từ chối. Worker backend chưa triển khai, API vẫn chỉ lưu lệnh/cấu hình. BLE chưa triển khai. Không dùng các topic global cũ hoặc coi membership DB tự động đồng bộ thành ACL broker.
