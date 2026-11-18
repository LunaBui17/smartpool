# MQTT Smart Pool theo thiết bị

Broker trên Pi đã chuyển policy v2, bỏ account chung esp32/android. Xem [topic, ACL, quản lý account và MQTT Web](docs/mqtt-v2.md).

ESP dùng username đúng device_id; namespace smartpool/devices/{device_id}/. Bridge backend có quyền publish lệnh/config, app gọi API để kiểm tra quyền. Không cấp bridge cho Android/user. Diagnostics chỉ dùng probe; MQTT Web mô phỏng ESP cần account thiết bị thử nghiệm. Account theo ID không chứng minh secret độc lập; xem giới hạn policy trong tài liệu vận hành.

Source hiện có worker chuyển lệnh API sang MQTT và nhận ACK theo thiết bị. Runtime 005 bổ sung audit giao dịch, readiness HTTP/MQTT/worker và sổ lệnh NVS; ESP đồng bộ config qua HTTP, bỏ topic global. Bản mới chưa build/test/triển khai. BLE chưa triển khai; membership DB không tự đồng bộ thành ACL broker. Xem [hợp đồng runtime và việc Android cần làm](docs/esp-api-mqtt-runtime.md).
