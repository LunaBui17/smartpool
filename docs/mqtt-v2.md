# Broker MQTT v2 theo từng thiết bị

**Source 006, chưa deploy:** [khóa HTTP chung và ID–MAC](shared-key-hardware.md) yêu cầu factory_mac trong status/ACK/LWT, worker kiểm tra liên kết DB, cmd/pump có MAC đích để ESP đối chiếu. Broker/ACL/password không tự thay đổi và không tự xác thực MAC.

**Source runtime 005, chưa build/test/triển khai:** worker có giao dịch ACK + audit, quét TTL/quyền và presence theo phiên kết nối; ESP poll config qua HTTP, heartbeat live 30 giây, sổ lệnh NVS 32 entry. Chi tiết tại [hợp đồng runtime và MQTT Web](esp-api-mqtt-runtime.md). ACL hiện có giữ nguyên; phần ghi nhận triển khai broker bên dưới là lịch sử.

Broker trên Pi `192.168.60.87` đã cập nhật ngày 17/11/2026. Không thay database, API, firmware hoặc Android trong đợt này. Hai tài khoản chung `esp32`/`android` không được giữ trong file xác thực mới. ESP/app cũ sẽ không kết nối được bằng credential cũ.

## Mô hình quyền

| Tài khoản | Quyền |
| --- | --- |
| Username đúng `device_id` | Chỉ các topic của chính thiết bị đó; không được truy cập thiết bị khác |
| `_smartpool_bridge` | Dịch vụ backend đọc status/telemetry/ACK các thiết bị và publish pump/config desired; không đưa credential này cho Android/user |
| `_smartpool_diag` | Chỉ đọc/ghi `smartpool/diagnostics/_smartpool_diag/probe`, không có quyền thiết bị |

ACL sinh thành các dòng explicit cho từng username, không dùng wildcard toàn hệ thống cho ESP. ID phải là ASCII 1–64 ký tự, bắt đầu chữ/số; tài khoản hệ thống bắt đầu `_` nên không trùng ID hợp lệ.

User/admin của app không đăng nhập MQTT trực tiếp. App gọi API kiểm tra membership; `control-worker` trên Pi đọc lệnh bơm còn hạn, kiểm tra lại quyền và trạng thái thiết bị rồi phát vào broker bằng tài khoản bridge. Worker nhận ACK từ ESP và cập nhật lệnh trong database. Cấu hình `config/desired` chưa được worker đồng bộ. Broker không tự đọc bảng `device_users` hoặc tự hiểu token HTTP.

## Topic đã được cấp trong ACL

Prefix là `smartpool/devices/{device_id}`:

| Suffix | ESP | Bridge | Hợp đồng dự kiến để cập nhật firmware |
| --- | --- | --- | --- |
| `status` | Write | Read | Retained online/relay/boot/sequence/device_id; LWT offline |
| `telemetry` | Write | Read | Mẫu có device_id/sample_id/thời gian; không retained |
| `ack/command` | Write | Read | command_id/result, không retained |
| `ack/config` | Write | Read | Chỉ còn trong ACL; runtime 005 báo config_apply qua HTTP |
| `cmd/pump` | Read | Write | command_id, target pump_in/pump_out, enabled, expires_at_ms; QoS 1, không retained |
| `config/desired` | Read | Write | Chỉ còn trong ACL; runtime 005 tải get_device_config bằng HTTP |
| `diagnostics/probe` | Read/write | Không mặc định | Chẩn đoán riêng của account thiết bị |

Firmware publish status/telemetry và LWT theo `device_id`, dùng account cùng tên ID. Firmware subscribe `cmd/pump`, kiểm tra ID/TTL, reserve và ghi kết quả vào sổ NVS để chống lặp rồi gửi `ack/command`. Runtime 005 đồng bộ revision qua `get_device_config`/`report_status` HTTP, không dùng `config/desired`/`ack/config` dù ACL còn cấp các topic này. Status có boot_id/presence_sequence; snapshot retained khi subscribe không chứng minh online. User đọc readiness qua API, không dùng credential bridge.

Không cấp topic cấu hình Wi-Fi, topic đổi ID hoặc topic global cũ. Wi-Fi qua BLE và khôi phục offline vẫn chưa làm; không gửi password Wi-Fi lên retained MQTT.

## Quản lý trên Pi

Tại `~/smartpool-v2/deploy/raspberry-pi-v2`, bằng user quản trị Pi:

```bash
sudo python3 mqtt-admin.py status

# Chỉ sau khi ID này đã đăng ký và active trong database/API v2:
sudo python3 mqtt-admin.py add-device DEVICE_ID
sudo python3 mqtt-admin.py rotate-device DEVICE_ID
sudo python3 mqtt-admin.py revoke-device DEVICE_ID
```

Add/rotate kiểm tra registry active bằng API container đọc DB. Username MQTT chính là DEVICE_ID, không phải `esp32`. Credential riêng được ghi vào `~/smartpool-v2-mqtt/DEVICE_ID.txt`, thư mục 700/file 600, thuộc user gọi sudo. Password không xuất ra stdout/argv/environment. Không dùng key HTTP của ESP làm password MQTT.

Giai đoạn này cấp/thu hồi MQTT do operator thực hiện riêng: inactive thiết bị, thu hồi key HTTP hoặc gỡ membership trong API **chưa tự thu hồi account MQTT ESP**. Muốn chặn kết nối ESP ngay, chạy revoke-device. Re-enable DB không tự cấp lại account đã revoke. Worker tương lai phải kiểm tra quyền/active/TTL trước publish để quyền app được thực thi xuyên suốt.

Mỗi thay đổi account làm recreate broker để ngắt phiên đang dùng credential cũ ngay; mọi client MQTT sẽ mất kết nối ngắn. ESP điều khiển từ xa có thể tắt bơm theo fail-safe của firmware. Database/API/tunnel không bị restart bởi công cụ này. Nếu thay đổi file thành công nhưng restart thất bại, xử lý lỗi rồi recreate broker; credential `.pending.txt` có thể được giữ để phục hồi, không chia sẻ file đó.

```bash
# Khởi tạo policy v2 hoặc đồng bộ ACL từ source, giữ account đã cấp khi đã ở v2:
sudo python3 mqtt-admin.py init

# Chỉ khi công cụ báo cần hoàn tất restart:
sudo docker compose -f compose.yaml -f compose.cloudflare.yaml up -d --no-deps --force-recreate mqtt
```

`init` lần đầu thay policy cũ, tạo riêng bridge và diagnostics; lần sau giữ password/accounts. mqtt-init khi deploy cũng giữ registry/account v2 và render lại ACL, không khôi phục credential chung từ `.env`. Các giá trị MQTT_ESP_PASSWORD/MQTT_APP_PASSWORD cũ trong `.env` không còn được dùng. File auth/ACL nằm trong volume mqtt-auth, không nằm trong Git.

## MQTT Web từ LAN hoặc Internet

Trên Pi, đọc file bằng terminal riêng:

```bash
cat ~/smartpool-v2-mqtt-diagnostics.txt
cat ~/smartpool-v2-endpoints.txt
```

Không gửi nội dung password lên chat/Git/log. Trong MQTT Web:

- WSS URI: `mqtt_wss_uri` từ endpoint file; nếu nhập tách thì host là domain, port 443, TLS bật, path `/mqtt`.
- Username/password lấy từ file diagnostics; client ID tùy chọn nhưng phải duy nhất.
- Subscribe và publish `smartpool/diagnostics/_smartpool_diag/probe`, QoS 1, retain OFF.
- Account diagnostics không đọc telemetry/status hoặc gửi pump/config. Không dùng bridge account trên client Web của người dùng.

Broker TCP LAN vẫn mở `192.168.60.87:1883`; đó là TCP không mã hóa, trình duyệt MQTT Web cần WSS. Cloudflare domain có thể đổi; đọc lại endpoint file. Không có tài khoản ESP nào được tạo tự động trước khi đăng ký ID thiết bị.

Công cụ kiểm tra tùy chọn để **bạn chủ động chạy**, không được chạy trong đợt cập nhật broker này:

```bash
sudo python3 smoke-wss.py wss://DOMAIN/mqtt --credentials /home/qnm/smartpool-v2-mqtt-diagnostics.txt
```

Script chỉ dùng account diagnostics, không gửi lệnh thiết bị và không lấy credential từ Compose config. smoke-test.sh mặc định giờ chỉ kiểm tra API health, không tự chạy kiểm tra MQTT có xác thực.

## Backup và trạng thái triển khai

Backup trước thay broker: `/home/qnm/smartpool-rollbacks/mqtt-20261117-225431/`, chứa source config và mqtt-auth volume, quyền thư mục 700 cần sudo. Không xóa mqtt-data: retained bản tin global cũ có thể còn trong persistence nhưng ACL mới không cấp quyền truy cập chúng.

Quay lui cần phục hồi đồng bộ config + auth volume khi đã dừng broker, rồi recreate; không chỉ thay file ACL hoặc trộn password file cũ với ACL mới. Backup có thông tin nhạy cảm, không chép vào Git. Không thao tác database để rollback broker.

Đã khởi động broker mới và đọc trạng thái/log dịch vụ; chưa chạy pub/sub, negative ACL tests hoặc integration firmware/app. Cần bạn tự kiểm tra diagnostics, account thiết bị, chặn cross-device và chặn global topics trước khi dùng thực tế.
