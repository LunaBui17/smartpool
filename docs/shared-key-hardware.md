# Runtime 006: khóa HTTP chung và liên kết Device ID – MAC gốc

**Hợp đồng mới nhất 007/008:** [MAC khai báo trước, chỉ admin đăng ký bằng ID+MAC+khóa chung, xóa device giữ MAC](hardware-inventory-delete.md). ESP không còn được tự gắn MAC từ first contact. Canonical mapping là hardware_inventory, health mới schema_runtime=8. Phần cấu trúc 006 dưới đây là giai đoạn nền tảng.

Cập nhật source ngày 18/11/2026 theo yêu cầu: mọi ESP dùng cùng khóa HTTP, mỗi device_id liên kết một MAC eFuse gốc. Chưa build/test/flash, chạy migration hoặc deploy bản 006 lên Pi trong đợt này. Pi được xác nhận trước đây là runtime 005, không coi source mới đã hoạt động trên server.

## Mô hình dữ liệu và xác thực

Migration [006_shared_key_hardware.sql](../backend/migrations/006_shared_key_hardware.sql) thêm:

| Bảng | Nội dung |
| --- | --- |
| esp_shared_credentials | Hash SHA-256 của khóa chung, label, người tạo, thời gian tạo/thu hồi |
| device_hardware | device_id khóa chính, factory_mac duy nhất, nguồn/người/thời gian gắn, lần xác minh HTTP gần nhất |

MAC chuẩn hóa thành 6 byte hex viết hoa, có dấu hai chấm; không chấp nhận all-zero, broadcast/multicast. Serial lấy từ MAC: SP- + 12 hex không dấu hai chấm. MAC eFuse khác MAC Wi-Fi AP/STA/BLE; firmware luôn gửi MAC gốc qua pool_identity_mac().

ESP gửi X-Pool-Device-Key chứa **khóa chung** và body có device_id + factory_mac. API kiểm tra khóa chung chưa revoke → registry tồn tại/active → cặp ID/MAC. User/app vẫn dùng Bearer, không dùng khóa ESP để đăng nhập user.

- Admin phải điền factory_mac và shared_device_key khi register_device; có thể dùng bind_device_hardware kèm cùng hai trường cho ID legacy đã có.
- ESP không tự đăng ký hoặc gắn MAC. ID chưa được admin liên kết MAC bị từ chối; MAC khai báo trước nhưng device_id=null vẫn chưa được gửi dữ liệu.
- ID đã gắn MAC khác hoặc MAC đã thuộc ID khác: 409, không ghi đè. Cùng cặp gửi lại là idempotent. Chưa có API chuyển MAC sang ID khác/thay bo giữ ID.
- Gắn MAC + audit + thao tác của request cùng transaction. Nếu request bị rollback, không giữ một liên kết MAC dở dang. last_verified_at chỉ cập nhật khi request HTTP hợp lệ commit, không thay thế heartbeat/status để suy ra thiết bị online.
- Dữ liệu cũ không được gán MAC đoán từ IP/AP. Admin lấy MAC thật và gắn cho ID cũ; ESP chỉ báo dữ liệu sau khi mapping đã được tạo.

**Giới hạn của khóa chung:** người biết khóa và ID/MAC đã đăng ký có thể giả mạo payload; MAC là định danh công khai, không phải chứng cứ phần cứng bằng mật mã. Chỉ admin được tạo mapping, nhưng so sánh MAC không ngăn client chủ động giả MAC của một cặp hợp lệ.

## Cấp khóa chung một lần, dùng cho mọi ESP

Admin đăng nhập API; gửi Bearer admin và POST JSON:

```json
{"action":"create_shared_device_credential","label":"Smart Pool ESP fleet"}
```

Response 201 có credential_id, shared_device_key và scope=all_devices. Raw key chỉ trả lần này; DB giữ hash. Không log/export response hoặc ghi khóa vào tài liệu/Git. Nhập cùng shared_device_key vào ô khóa HTTP trên local của mọi ESP; vẫn lưu NVS qua trường device_key, không cần build riêng.

Khóa vẫn là 64 ký tự hex thường do server sinh, không dùng mật khẩu app/MQTT hoặc authtoken tunnel. Chưa tạo khóa thật hoặc chọn một giá trị bí mật trong đợt sửa source này.

Quản lý khóa:

```json
{"action":"list_shared_device_credentials"}
```

```json
{"action":"revoke_shared_device_credential","credential_id":123}
```

ID 123 là minh họa. Revoke ảnh hưởng **mọi ESP đang dùng khóa đó**, không phải riêng một ID. Khi rotate: tạo khóa chung mới → cấp lại cho tất cả bo qua local, reboot/xác nhận → revoke khóa cũ. Cho phép thời gian chồng lấp các khóa active để chuyển đổi; không tự phân phối khóa sang các bo.

create_device_credential cũ trả 410. device_credentials cũ được giữ để bảo toàn/rollback nhưng **không còn được dùng xác thực ESP trong 006**, kể cả row chưa revoke. list/revoke credential riêng cũ chỉ quản lý dữ liệu legacy, không thay đổi hiệu lực khóa chung.

## Gắn và xem MAC

Admin đăng ký mới bắt buộc factory_mac + shared_device_key trong body register_device (vẫn cần name/student_full_name/student_code). Với ID đã có:

```json
{"action":"bind_device_hardware","device_id":"NguyenVanA_123456","factory_mac":"AA:BB:CC:11:22:33","shared_device_key":"<khoa_chung_64_hex>"}
```

Địa chỉ trên chỉ minh họa; lấy MAC thật từ GET /api/config trên ESP hoặc log khởi động. bind_device_hardware chỉ admin được gọi. Không nhập MAC AP/STA thay MAC gốc.

Sau khi cấp khóa chung và flash firmware mới, ESP tự gửi:

```json
{"action":"device_identity","device_id":"NguyenVanA_123456","factory_mac":"AA:BB:CC:11:22:33"}
```

Đây là ví dụ request chẩn đoán; firmware vận hành tự gửi MAC trong get_device_config, report_status và upload_sample. HTTP ACK thủ công cũng cần factory_mac. Không có endpoint chỉ dựa MAC để bỏ qua khóa.

get_device/get_status và response device_identity trả hardware có factory_mac, factory_serial, binding_source, bound_by, bound_at, last_verified_at. list_devices/login thêm factory_mac/factory_serial cho các thiết bị được phép xem. Chưa có MAC thì các trường nullable; app không được tự dựng MAC từ ID.

## MQTT và firmware

MQTT vẫn dùng username theo ID và ACL hiện có; khóa HTTP chung không tự đổi mật khẩu MQTT. Không sửa policy/password/ACL broker trong đợt này.

- ESP đưa factory_mac vào status, telemetry, ACK và LWT.
- Worker chỉ nhận status/ACK khi MAC báo cáo khớp registry đã gắn và device active; worker không tự tạo liên kết qua MQTT.
- Worker đưa factory_mac đích vào cmd/pump. ESP kiểm tra cả device_id và MAC gốc của chính nó trước khi reserve/đổi relay. Lệnh thiếu MAC bị bỏ qua.
- Migration làm mất hiệu lực cache presence trước 006 (online/live_received_at reset), không xóa sensor samples/status/lịch sử. Cần HTTP hợp lệ để gắn MAC, rồi MQTT live mới để đủ readiness.
- Broker không tự xác thực MAC hoặc tra bảng hardware. Khách biết MQTT credential và MAC có thể giả payload; không tuyên bố ACL đã ngăn giả mạo phần cứng.

ID/key vẫn nhập qua AP local và giữ trong NVS. ID cố định sau lần cấp đầu; key chung thay cùng ID được, không cần build lại. Khóa chung trong NVS của mỗi bo gắn kèm MAC gốc để tránh dùng nhầm nguyên bản NVS giữa các bo; đây không phải cơ chế chống sao chép bằng mật mã.

## Nâng cấp và quay lui

Giữ backup DB/source/image trước nâng cấp. Dừng worker, build API/worker, chạy migrate.php bằng image mới, khởi động API/worker mới. Có thể dùng activate-control-worker.sh đã có; script không tự backup nên phải backup trước. Không reset/down -v/drop bảng.

Migration không sinh khóa hoặc MAC giả, không sửa 004/005, không xóa credential legacy hay dữ liệu nghiệp vụ. Sau migration cần admin tạo khóa chung và cấp vào các ESP. Client cũ không gửi MAC hoặc còn dùng khóa riêng sẽ bị từ chối; cần nâng firmware đồng bộ. Health mới có schema_runtime=6 và device_auth=shared_key_mac, không có nghĩa các bo đã được cấu hình khóa mới.

Quay lui phải đồng bộ API/worker/firmware và credential theo hợp đồng trước đó. Giữ bảng 006 để bảo toàn dữ liệu, không xóa schema_migrations. Server 005 chỉ hiểu khóa riêng và worker cũ không gửi MAC đích; firmware 006 kiểm tra MAC nên không thể coi quay lui riêng server là tương thích đầy đủ. Không xóa NVS/queue để né lỗi.

## Postman và việc app cần cập nhật

Import [collection khóa chung/MAC](../backend/postman/SmartPool.shared-key-hardware.postman_collection.json). Chỉ dùng fixture A/B đã đăng ký và active, MAC khác nhau, không gắn với bo thật. Điền api_url, device_a/b, factory_mac_a/b; admin_token, user_token, shared_device_key là biến local, không export. Request đầu tạo khóa chung là tùy chọn; không chạy nếu đã có khóa muốn dùng. Chưa chạy collection trong đợt này.

Kiểm tra: cùng khóa cho A/MAC A và B/MAC B đều được chấp nhận; ID A/MAC B bị 409; MAC A không gắn sang B; thiếu MAC 400; khóa sai/revoked 401; device inactive 403; user không gọi được bind hoặc quản lý khóa chung (403); gửi lại cùng cặp không nhân audit; rollback request không để lại binding; HTTP/MQTT MAC sai không đổi dữ liệu/relay. Revoke khóa chung chỉ thử trên hệ thống thử nghiệm vì ảnh hưởng cả nhóm bo.

[Collection runtime](../backend/postman/SmartPool.runtime.postman_collection.json) đã đổi ESP headers sang shared_device_key và thêm factory_mac. Collection v2 cũ còn luồng cấp khóa riêng chỉ là lịch sử, không chạy như acceptance cho 006.

MQTT Web dùng account **thiết bị thử nghiệm** và broker WS/WSS hiện có; khóa HTTP chung không phải mật khẩu MQTT. Gắn MAC bằng HTTP/admin trước, rồi publish QoS1, retain=false vào smartpool/devices/NguyenVanA_123456/status:

```json
{"device_id":"NguyenVanA_123456","factory_mac":"AA:BB:CC:11:22:33","boot_id":"0123456789abcdef0123456789abcdef","presence_sequence":1,"online":true}
```

HTTP report_status cũng dùng cùng ID/MAC/boot. Gửi request_control OFF qua Postman, quan sát cmd/pump có factory_mac đích, rồi publish ack/command của fixture:

```json
{"device_id":"NguyenVanA_123456","factory_mac":"AA:BB:CC:11:22:33","command_id":"<UUID-tu-response-request_control>","result":"applied"}
```

Thay ID/MAC/UUID bằng fixture thực; không giả trạng thái cho thiết bị sản xuất. Worker bỏ qua packet thiếu/sai MAC; retained snapshot không nâng readiness. Thử duplicate/TTL/quyền theo checklist runtime, không dùng tài khoản bridge trên trình duyệt để publish lệnh trực tiếp. Chưa chạy các bước MQTT Web này trong đợt sửa source.

Android hiện không sửa code. Luồng admin Scan new device sau này đọc factory_mac/factory_serial qua GATT, gọi register_device có MAC hoặc bind_device_hardware, rồi truyền **cùng khóa chung** cho các bo. Không gọi create_device_credential cho từng bo. Raw khóa không có API đọc lại; app phải có cơ chế quản lý/cấp khóa admin qua kênh bảo vệ, không nhúng vào APK/preferences/log hoặc tạo một khóa chung mới mỗi lần scan. Chỉ giữ khóa tạm trong RAM khi provisioning. Backend đã có liên kết MAC duy nhất cơ bản; claim mật mã, chuyển giao/thay bo và lịch sử rebind vẫn chưa triển khai. BLE và API tự động cấp account MQTT vẫn là phần riêng chưa làm.
