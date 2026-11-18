# Cấp ID/khóa qua local và kế hoạch BLE cho Android

**Luồng admin mới nhất:** [inventory MAC và xóa device giữ MAC](hardware-inventory-delete.md). Admin đăng ký bằng ID + MAC + shared_device_key; ESP không tự tạo mapping. Xóa server không tự reset ID đã lưu NVS.

**Hợp đồng mới 006:** [khóa HTTP dùng chung và liên kết ID–MAC trên database](shared-key-hardware.md). Cùng khóa cho mọi bo, ID/MAC riêng; create_device_credential cũ không còn dùng. Source đã cập nhật, migration 006 chưa áp dụng Pi.

Source cập nhật ngày 18/11/2026. Chưa build, chạy kiểm thử, nạp firmware hoặc gọi Pi trong đợt này. Android, BLE và backend không sửa trong đợt này.

## Một firmware cho nhiều ESP

Firmware mới không include `device_credentials.h` hoặc `mqtt_credentials.h`. Nếu các file này còn trên máy, giá trị trong đó không được dùng. Không cần tạo lại file mẫu đã bỏ. Cần build/nạp **một lần bản firmware mới này**; sau đó dùng cùng binary cho các bo cùng cấu hình phần cứng và cấp thông tin riêng qua local.

| Thông tin | Cách sử dụng |
| --- | --- |
| factory_serial | SP- + MAC eFuse gốc; tự đọc trên từng bo, không phải tài khoản |
| device_id | Admin đăng ký trên server; lưu trong NVS poolid/identity |
| device_key | Khóa HTTP chung cho mọi ESP; lưu cùng ID và MAC riêng của bo trong một blob NVS |
| MQTT username | Tự bằng device_id đang hoạt động |
| MQTT password | Nhập local, lưu NVS poolnet/mqtt_conn; độc lập khóa HTTP |
| Endpoint | Giá trị mặc định chung; API/broker URL vẫn thay qua local được |

Chưa có identity hợp lệ thì ESP phục vụ cảm biến/relay/web/AP local, không mở MQTT, gửi HTTP hoặc xếp mẫu mới vào queue server. Không tự dùng chung ID SmartPool cho mọi bo. Mẫu trước khi cấp ID không được hứa gửi bù.

CONFIG_POOL_DEVICE_ID chỉ còn giúp xác định chủ dữ liệu legacy khi nâng cấp; không phải ID runtime. Serial/MAC không thay thế xác thực bằng khóa.

### MAC phần cứng và tên AP

ESP đọc MAC eFuse gốc bằng esp_efuse_mac_get_default. `factory_mac` có dạng `AA:BB:CC:11:22:33`, serial tương ứng là `SP-AABBCC112233` (ví dụ minh họa). Serial/MAC gốc không lấy từ trường do người dùng nhập. Log khởi động có cả hai giá trị, không ghi khóa hoặc mật khẩu.

GET /api/config và /api/status trả thêm factory_mac, wifi_sta_mac, wifi_ap_mac; hai MAC interface lấy từ esp_wifi_get_mac và có thể khác MAC gốc. Nếu không đọc được interface thì trả null. Trang local hiển thị cả ba để đối chiếu; BLE sau này dùng factory_serial đọc tường minh, không dùng MAC quảng bá BLE.

Tên AP là tiền tố CONFIG_POOL_SETUP_AP_SSID + dấu gạch ngang + 6 hex cuối MAC gốc, ví dụ `SmartPool-Setup-112233`. Tiền tố dài được cắt tại ranh giới UTF-8 để tổng tên không vượt 32 byte. Hậu tố giúp tìm bo khi nhiều AP cùng tiền tố, không thay thế đối chiếu serial đầy đủ hoặc kiểm tra duy nhất trên server. Sau khi nạp bản mới, chọn lại AP có hậu tố; mật khẩu AP vẫn theo cấu hình hiện có, địa chỉ local vẫn http://192.168.4.1.

Source 006 đã có liên kết ID–MAC duy nhất trên database, nhưng chưa áp dụng migration lên Pi. MAC không phải secret/chứng cứ phần cứng bằng mật mã. Khi tự kiểm tra: so sánh serial/MAC gốc sau reboot, kiểm tra tên AP khớp hậu tố và dùng hai bo để đối chiếu serial đầy đủ.

## Cách cấu hình hiện tại

1. Nạp firmware mới, kết nối AP dạng **SmartPool-Setup-XXXXXX** (tiền tố theo cấu hình), mở **http://192.168.4.1 → Thiết bị, MQTT & HTTP → Đăng ký thiết bị**. Đối chiếu serial/MAC đầy đủ trước khi cấp ID và khóa.
2. Admin chuẩn bị khóa HTTP chung hợp lệ (create_shared_device_credential một lần nếu chưa có), đọc MAC gốc local rồi đăng nhập API bằng Postman/công cụ quản trị. Đăng ký ID duy nhất, MAC chưa gán và họ tên/mã sinh viên. Request cần Bearer admin:

```json
{
  "action":"register_device",
  "device_id":"NguyenVanA_123456",
  "factory_mac":"<MAC_goc_doc_tu_ESP>",
  "shared_device_key":"<khoa_HTTP_chung_64_hex>",
  "name":"Ho boi A",
  "student_full_name":"Nguyen Van A",
  "student_code":"123456",
  "registration_source":"manual"
}
```

3. Kích hoạt thiết bị. Khóa chung phải có trước bước đăng ký; chỉ tạo một lần và dùng lại cho các bo khác:

```json
{"action":"set_device_active","device_id":"NguyenVanA_123456","is_active":true}
```

```json
{"action":"create_shared_device_credential","label":"Smart Pool ESP fleet"}
```

Server chỉ trả shared_device_key thô khi tạo; nhập cùng giá trị đó vào ô device_key trên mọi bo. Giữ credential_id chung để revoke sau này; revoke tác động mọi bo dùng khóa. Không export response/ghi khóa vào ảnh/log/Git hoặc dùng mật khẩu app/MQTT thay khóa HTTP 64 hex thường.

4. Nhập ID và khóa trên AP, chọn **Lưu ID/khóa & khởi động lại**. Chỉ báo đã lưu sau NVS commit. ESP tạm ngừng remote rồi reboot. Nếu không tạo được task reboot, phản hồi restarting=false và UI yêu cầu khởi động lại thủ công; không nhận thêm provisioning trong lúc pending.
5. Kết nối lại AP/tải lại trang. ID thành chỉ đọc, ô khóa trống và có cờ đã lưu. Đây chỉ xác nhận cấu hình trong NVS, **chưa xác nhận server chấp nhận**. Cấu hình Wi-Fi router và API URL nếu cần.
6. MQTT vẫn cần người vận hành tạo account trên Pi theo ID, ví dụ `sudo python3 mqtt-admin.py add-device NguyenVanA_123456` trong thư mục deploy v2. Lấy password qua kênh private hiện có, nhập tại mục MQTT của ESP. Username tự theo ID; không dùng account bridge. Lưu MQTT cũng reboot. register_device không tự tạo account Mosquitto.
7. Xem status local và get_status trên server theo [hợp đồng runtime](esp-api-mqtt-runtime.md). Có HTTP mới, MQTT live đúng boot và worker khỏe mới coi remote control sẵn sàng. Admin cấp membership cho user qua API hiện có.

## ID, khóa và dữ liệu cũ

- ID 1–64 ký tự ASCII, bắt đầu chữ/số, phần sau cho phép chữ/số/dấu chấm/gạch dưới/gạch ngang. Họ tên có dấu nằm trong hồ sơ server, không dùng khoảng trắng/tiếng Việt có dấu làm ID.
- Sau lần cấp đầu, ID cố định. Đổi khóa **cùng ID** không cần build lại; nhập ID khác bị từ chối. Chưa có nút reset/chuyển bo sang hồ sơ khác, không tự xóa dữ liệu để làm việc đó.
- Rotate: admin tạo khóa chung mới → cấp lại qua AP cho mọi bo/reboot/xác nhận → revoke_shared_device_credential của khóa cũ. Để trống khóa trên một bo giữ khóa hiện tại; rotate HTTP không đổi MQTT. Xem quy trình và giới hạn tại tài liệu 006.
- Blob identity có MAC gốc để từ chối dùng nhầm NVS của bo khác. Blob hỏng/MAC khác gây lỗi, không tự erase. Đây không phải chống sao chép bằng mật mã; không clone NVS giữa các bo.
- Firmware không tự nhập khóa từ header cũ. Bo đang dùng SmartPool phải được cấp lại **đúng ID đó** và key hợp lệ qua local; có thể tạo credential mới nếu mất khóa cũ. MQTT connection NVS cũ được dùng lại nếu username khớp ID.
- Queue được gắn device_id trong NVS. Queue legacy chưa có owner mà còn mẫu chỉ được nhận cho ID legacy đã biết, giữ ID/sample/order/time. Có server snapshot/sổ lệnh cũ thì lần cấp đầu cũng phải khớp ID legacy. Không tự gửi dữ liệu/config cũ sang ID mới hoặc drop queue để vượt lỗi.
- ID legacy lấy từ settings/MQTT đã lưu; nếu không có thì dùng CONFIG_POOL_DEVICE_ID của bản nâng cấp. Khi nâng bo cũ có ID khác, giữ đúng giá trị cũ cho lần chuyển đổi; bo sạch NVS không phụ thuộc nó.
- Credential lưu bằng NVS hiện có, chưa thêm NVS encryption/secure boot. Local HTTP trong AP không có role admin/user của backend: người truy cập được AP có quyền thao tác local theo thiết kế hiện tại. Không gọi đây là xác thực admin trên ESP.

## Hợp đồng local

GET /api/config vẫn có broker/API URL và thêm:

```json
{
  "provisioning_version":1,
  "factory_serial":"SP-<12-hex-tu-eFuse>",
  "device_id":"",
  "provisioned":false,
  "device_key_set":false,
  "provisioning_pending":false,
  "local_provisioning_available":true
}
```

GET không trả device_key/password thô. provisioned=true nghĩa identity đã active sau boot; provisioning_pending=true nghĩa đã ghi, đang đợi reboot.

POST /api/config/identity, Content-Type application/json:

```json
{"device_id":"NguyenVanA_123456","device_key":"<device_key_do_server_cap>"}
```

Placeholder trên không phải khóa hợp lệ. Khóa thực phải 64 hex thường. Response có ok/restarting/restart_required/message, không trả lại khóa. Sai định dạng trả 400; ID khác/pending/NVS/queue không phù hợp trả 409. Chỉ ghi qua địa chỉ AP với Host/Origin cùng địa chỉ; ghi qua IP LAN của ESP trả 403. Không cung cấp CORS cho website ngoài. Trình duyệt dùng đúng http://192.168.4.1.

Không thay ID/key của task đang chạy giữa chừng: commit → ngừng remote → reboot. MQTT client ID theo serial; topic/LWT/payload và mọi HTTP upload/status/config/ACK dùng identity NVS sau boot.

## Ghi chú dev Android: Scan new device cho admin

**Chưa có BLE/GATT hoặc nút Android trong đợt này.** Hàm ESP dùng chung là pool_network_provision_device(device_id, device_key); transport BLE sau này tái sử dụng validate/lưu NVS/reboot, không tạo luồng cấu hình riêng.

1. Chỉ hiện **Scan new device** sau login role admin. Backend vẫn kiểm tra role khi đăng ký/kích hoạt/cấp key; ẩn nút không đủ phân quyền.
2. Xin quyền BLE theo phiên bản Android; quét/kết nối bo ở chế độ provisioning. Đọc factory_serial/version/capability/device_id/provisioned qua GATT. Không dùng MAC quảng bá BLE thay serial eFuse; không có characteristic đọc khóa đã lưu.
3. Admin chọn đúng bo, nhập tên/họ tên/mã sinh viên/ID. Bo đã provisioned phải đối soát hồ sơ cũ. Source 006 hỗ trợ register_device có factory_mac hoặc bind_device_hardware cho ID đã có, MAC duy nhất. Claim mật mã/lịch sử chuyển giao/rebind chưa có; không tự ghi ID khác hoặc coi MAC là chứng cứ mật mã.
4. App dùng Bearer admin đăng ký/kích hoạt/gắn MAC; cấp cùng khóa HTTP chung đã được quản lý an toàn cho các bo, không tạo key mới mỗi lần scan. Chỉ giữ key tạm trong RAM; không truyền admin token/DB/bridge credential sang ESP, không log response để khôi phục khóa.
5. Truyền ID/key/endpoint/Wi-Fi/MQTT qua **phiên BLE được bảo vệ**. Cần thiết kế pairing, điều kiện hiện diện vật lý/cửa sổ cấp lại, framing/MTU/chunking/ACK/retry. Hiện chưa có các cơ chế BLE đó; kết nối BLE không tự chứng minh truyền secret an toàn.
6. Backend hiện chưa có API cấp account MQTT cho app. Cần endpoint admin cấp/rotate/revoke broker account để onboarding hoàn toàn tự động; hiện vẫn thao tác vận hành riêng. Không nhúng bridge credential vào APK.
7. Chờ commit/reboot rồi kiểm tra API: HTTP mới, config revision, MQTT live, readiness. Phân biệt đã ghi ESP và đã online server. Khi lỗi, giữ trạng thái onboarding để tiếp tục; không tự xóa hồ sơ/gán thành công.
8. Admin cấp device_users/quyền sau đăng ký. User thường không có nút đăng ký mới; cấu hình lại Wi-Fi của thiết bị đã được cấp quyền là luồng riêng, kiểm tra can_configure và hiện diện gần bo. Quyền backend và phiên local/BLE không tự đồng bộ.

App không lưu key ESP/password MQTT vào preferences/log/analytics. Sau onboarding, app dùng Bearer + device_id gọi backend, không giữ credential ESP để điều khiển MQTT trực tiếp.

## Checklist bàn giao — chưa chạy

| Tình huống | Kết quả cần quan sát |
| --- | --- |
| Cùng binary trên hai bo mới | Serial khác; ID rỗng; local hoạt động, chưa HTTP/MQTT remote |
| Cấp A/key A và B/key B | Reboot giữ riêng identity; username/topic/LWT/payload đúng từng ID |
| GET config / POST response | Không thấy key/password thô |
| POST từ LAN hoặc sai Host/Origin | 403, không thay NVS |
| ID sai định dạng, key sai 64 hex | 400, không thay identity/reboot |
| NVS write/commit lỗi | Không báo thành công; giữ identity active trước đó |
| Đổi ID trên bo đã cấp | 409, không đổi owner queue/xóa config |
| Rotate key cùng ID | Reboot dùng key mới; queue/history giữ, MQTT không đổi |
| Key đúng định dạng nhưng sai server | HTTP bị từ chối, không gọi là online thành công |
| Nâng bo cũ có queue chưa gửi | Cấp lại đúng ID; không gán mẫu cũ sang ID mới |
| Mất nguồn khi save | Chỉ dùng snapshot NVS hợp lệ khi boot, không chạy nửa identity |
| Revoke key cũ sau rotation | Key cũ bị từ chối; key mới vẫn dùng được |

Không erase flash/NVS để thử trên bo có dữ liệu thực khi chưa có phương án sao lưu/phục hồi.
