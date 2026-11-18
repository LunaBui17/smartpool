# Kế hoạch tích hợp Android Smart Pool với API v2

Ngày lập: 17/11/2026. Cập nhật tiến độ: 18/11/2026.

## Tiến độ thực hiện

### Bổ sung đã duyệt: quản trị ba tab

Sau đợt nền tảng, người dùng ưu tiên lại phần admin: **Tổng quan / Thiết bị / Người
dùng** ở trang gốc, không phụ thuộc selected device. Đợt này chỉ sửa Android/tài
liệu theo API hiện có; không cho phép sửa PHP, SQL, firmware hoặc tự build/test.

- A001 [Plan:A.1]: root theo role, admin mặc định Tổng quan, user giữ danh sách; workspace riêng.
- A002 [Plan:A.2]: thiết bị có tìm kiếm/phân trang, đăng ký manual, active/inactive và membership.
- A003 [Plan:A.3]: tài khoản có phân trang, tạo user/admin, khóa/mở, đặt lại mật khẩu.
- A004 [Plan:A.4]: gán/kiểm tra/sửa/gỡ quyền từ cả phía thiết bị và phía user; đọc grant hiện có.
- A005 [Plan:A.5]: tài liệu và checklist hai user/hai thiết bị; không đánh dấu đã nghiệm thu runtime.

Các A-task có source triển khai; kiểm tra thủ công/build còn chờ người dùng.
Đây là phần ưu tiên lại của T005/T006/T016–T019, không phải hoàn thành toàn bộ
giai đoạn 2–7. Tổng quan danh mục ghi rõ số đã tải/còn trang, không dựng online.

Đã chốt hướng mở rộng sau: mã `user_code` tùy chỉnh và duy nhất, giữ khóa nội bộ tự
tăng; lưu trữ/xóa mềm có khôi phục; liên kết serial eFuse ESP32 với Device ID.
API/schema hiện chưa có các hợp đồng này; app không giả lập và không đổi khóa
thành “xóa”. Sửa hồ sơ/metadata và user xem thành viên vẫn chờ API được duyệt riêng.

### Đợt nền tảng

Người dùng đã cho phép bắt đầu bổ sung phần Android. Đợt này cập nhật source
giai đoạn 1 (T001–T006) và các call site cần thiết để cách ly phiên/thiết bị.
**Chưa nghiệm thu giai đoạn 1:** không build, kiểm thử, cài APK hoặc triển khai
theo yêu cầu người dùng. Checkbox tác vụ bên dưới chưa đánh dấu hoàn thành runtime.

Đã thêm API client có snapshot/guard, phiên gắn server/user ID, context thiết bị,
màn danh sách có phân trang và xác minh `get_device`; nối login/splash/Main,
chart/log/quản lý tài khoản vào context. Preferences tách theo server/user/device;
Main đọc cấu hình server trước điều khiển để không dùng hiệu chuẩn legacy.
Không nhúng MQTT username/password từ Gradle vào BuildConfig.

**Thay đổi có chủ đích:** tạm dừng service nền, khóa đường cấu hình/MQTT chung,
không hợp nhất nhật ký SQLite cũ vào màn v2. Chưa mở Settings cho user, chưa có
biểu mẫu update_config. Registry/membership được bổ sung trong đợt A001–A005 phía trên.
Xem [trạng thái và checklist hai user/hai thiết bị](../android-app/README_MQTT_ESP32.md).

Các mục “hiện trạng” dưới đây là **bản khảo sát ngày 17/11**, không phải mô tả
source sau cập nhật: worker điều khiển và firmware v2 đã được bổ sung bởi các
thay đổi tiếp theo trong repository. Không coi việc code đã có/worker container
đang chạy là bằng chứng điều khiển đã được kiểm tra đầu cuối.

Phạm vi: Android Java/XML và tài liệu trực tiếp liên quan. Không sửa PHP, SQL, firmware, broker, worker hoặc triển khai. Không build, chạy kiểm thử hay cài công cụ. Các đường dẫn source bên dưới tính từ gốc repository `smartpool`.

## 1. Căn cứ và hiện trạng thực tế

Đã đọc trực tiếp toàn bộ:

- `backend/migrations/004_multi_device.sql`, `backend/pool_api.php`, `backend/api_core.php`, `backend/api_actions.php`, `backend/api_data.php`.
- Toàn bộ request, body, header và script trong `backend/postman/SmartPool.v2.postman_collection.json`; chỉ đọc JSON, không chạy script/request/collection.
- `docs/database-v2.md`.
- Java Android: `LoginActivity`, `UserSession`, `SplashActivity`, `AppConfig`, `MainActivity`, `SettingsActivity`, `SensorChartActivity`, `HistoryPoint`, `OperationLog`, `OperationLogActivity`, `PoolMonitorService`, `MqttControlGate`, `SystemStatusView`, `ScreenInsets`.
- Manifest, cấu hình build của module app, các rule backup hiện có; đối chiếu các màn hình và điều hướng đang dùng.

Không đọc file credential riêng, không gọi Pi/API/MQTT. Trạng thái triển khai trên Pi do người dùng cung cấp, không phải kết quả kiểm tra runtime trong đợt lập kế hoạch.

### Các điểm không tương thích

| Phần | Code hiện tại | Hợp đồng v2 / thay đổi cần thiết |
| --- | --- | --- |
| Login | `LoginActivity.java:121` đọc role/username ở root và chỉ nhận admin/guest | Đọc `user.id/username/full_name/role`, role admin/user, token, expires_in, devices, next_after_device_id |
| Phiên | `UserSession.java:22` không lưu user ID hoặc server; restore ở dòng 53 vẫn nhận guest | Gắn phiên với URL API và user ID; vô hiệu phiên legacy; phân biệt remember và phiên trong bộ nhớ |
| Điều hướng | Login/Splash mở Main trực tiếp; không có danh sách thiết bị | Luôn mở danh sách sau login/restore; chọn thiết bị rồi mới vào workspace |
| Cấu hình cục bộ | `AppConfig.java:21` dùng một bộ SharedPreferences; có ID mặc định và domain Quick Tunnel cố định | Không suy ra thiết bị từ giá trị mặc định; tách cấu hình kết nối app với config thiết bị lấy từ server |
| Dashboard | `MainActivity.java:1134` GET v1, không Bearer/device_id, đọc nhiet_do/muc_nuoc | POST get_device/get_status/get_config, Bearer, device_id; parse trạng thái nullable và hai loại mẫu |
| Độ mới | `MainActivity.java:1172` lấy giờ điện thoại mỗi lần HTTP thành công làm giờ dữ liệu | Phân biệt giờ đo, giờ nhận, giờ kiểm tra HTTP; nhận lại cùng mẫu không làm mẫu mới hơn |
| MQTT/control | Main, Settings, service dùng topic chung; Main publish trực tiếp, chỉ kiểm tra kết nối/ESP online | Không dùng topic chung cho workspace v2; tạo yêu cầu qua API, không lách quyền bằng publish trực tiếp |
| Settings | `SettingsActivity.java:179` chặn mọi user không phải admin | Cho người có quyền xem đọc cấu hình; can_configure mới được sửa; quản trị tài khoản/thành viên vẫn admin |
| Water/config | App có ngưỡng ADC riêng và bốn giá trị hiệu chuẩn riêng; API có water_adc_min/max + water_cm_min/max | Không tự gộp sáu giá trị cũ sang bốn trường v2; dùng biểu mẫu v2 rõ cặp ADC↔cm, server là nguồn chính |
| Lưu config | Settings lưu local trước, gửi MQTT, chờ req_id/ACK; interlock còn sửa local ngay | get_config/update_config + expected_revision; bản nháp, desired và reported phải tách biệt |
| Chart | `SensorChartActivity.java:106` GET history array v1, chỉ 100 mẫu theo giờ nhận | POST get_history; samples, next_cursor, time_basis, khoảng thời gian; sort tăng dần để vẽ |
| Audit | `OperationLog.java:152` gửi events[]; `OperationLogActivity.java:79` không gửi device_id | V2 nhận từng record_activity với event_action thuộc allowlist; list_activity theo thiết bị, có before_id |
| Hiển thị audit | Dùng username/detail, mặc định occurred_at_ms=0 | Dùng actor_username/source/changes; occurred_at_ms có thể null, phải hiển thị giờ nhận UTC riêng |
| Admin | save_user/delete_user, guest, cờ active đọc như số; không phân trang | create_user/set_user_active/set_user_password/list_users; admin/user, boolean, next_after_id |
| Service | Main tự khởi động; service START_STICKY và subscribe chung, không có context tài khoản/thiết bị | Không được tiếp tục chạy và phát cảnh báo cho tài khoản/thiết bị cũ; giám sát MQTT v2 còn chờ hợp đồng |

### Hợp đồng đã đối chiếu từ PHP

- `pool_api.php:22`: mọi action dùng POST JSON; GET chỉ dành cho health. Body tối đa 64 KiB.
- `api_actions.php:4`: login trả trang đầu tối đa 50 thiết bị; list_devices nhận limit 1..100 và after_device_id. Admin thấy cả thiết bị inactive; user chỉ thấy thiết bị active được cấp view.
- `api_core.php:67`: admin có quyền view cả inactive, nhưng control/configure trên inactive vẫn bị chặn. Owner chỉ là metadata, không thay membership.
- `api_actions.php:148`: state có thể null; latest_measured_sample và last_received_sample tách biệt. get_status không trả can_view/control/configure; phải tải lại get_device để biết thay đổi quyền.
- Mẫu trong get_status có received_at là DATETIME UTC, không có sẵn received_at_ms như get_history. Không dùng optLong(...,0) thay cho parse thời gian.
- `api_data.php:2`: update_config nhận một phần settings trong allowlist và expected_revision; trả config đầy đủ, revision mới, delivery=stored_only.
- `api_data.php:119`: request_control chỉ có hai target pump_in/pump_out; get_command có effective_status để phản ánh expired dù status lưu vẫn pending.
- `api_data.php:85`: list_activity trả events + next_before_id; actor/source/changes theo v2. Backend tự audit cấu hình, lệnh, quản trị.
- `api_data.php:99`: record_activity không nhận events[], result/detail tùy ý hoặc actor tự khai. Kết quả client luôn client_reported.
- Boolean phải là JSON boolean, số phải là JSON number. ID/revision/timestamp dùng long ở Android; không giả định BIGINT luôn vừa int.

## 2. Giới hạn và quyết định cần duyệt

### B1 — User xem thành viên: chưa có hợp đồng phù hợp

Yêu cầu trước đó: user được xem các user khác cùng thiết bị.

`backend/api_actions.php:133` đặt list_device_members trong nhóm bắt buộc api_admin; user có can_view vẫn nhận 403. Android không thể tự khắc phục bằng dữ liệu cache, danh sách tài khoản toàn hệ thống hoặc suy diễn actor trong nhật ký.

Đề xuất: triển khai phần quản trị thành viên cho admin theo API hiện có; đánh dấu phần user xem thành viên là **chờ thay đổi API được duyệt riêng**. Nếu duyệt mở rộng backend sau này, API đọc chỉ trả thông tin thành viên phù hợp cho user có can_view, không trao quyền thêm/sửa/gỡ. Bản kế hoạch này không cho phép sửa PHP.

### B2 — Điều khiển/config chỉ mới đến server

- request_control trả delivery=not_published_by_api: UI ghi “Server đã ghi nhận yêu cầu, chưa chuyển tới ESP”; pending không làm đổi trạng thái bơm.
- update_config trả stored_only: UI ghi “Đã lưu cấu hình mong muốn”; không dùng thông báo “ESP đã áp dụng”.
- reported_config_revision và config_revision_reported_matching chỉ là revision ESP báo; không chứng minh độc lập phần cứng.
- Chuyển thiết bị chỉ dừng theo dõi lệnh trên app; không đồng nghĩa hủy lệnh trên server. API hiện không có action cancel_command.

### B3 — Chưa có action cho một số thao tác legacy

- Không có action điều khiển bật/tắt cảm biến. state chỉ báo temperature_enabled/water_enabled. Giữ hiển thị trạng thái nhưng khóa thao tác và giải thích.
- Không có API provisioning Wi-Fi, đổi ID/name/location của thiết bị đã đăng ký, hoặc gửi URL HTTP xuống ESP.
- Không có API sửa username/full_name/role tài khoản hiện hữu hoặc xóa vĩnh viễn tài khoản. UI phải dùng đúng tạo tài khoản, khóa/mở, đặt lại mật khẩu.
- Không có runtime automation (running/paused/reason/remaining_seconds), capability relay/sensor hoặc MQTT online trong get_status. Lưu đồ ghi “Chưa có báo cáo qua API v2” thay vì dựng trạng thái.

### B4 — Đề xuất cách ly MQTT legacy

Ngay giai đoạn 1, chặn publish cmd/config chung trong Main/Settings, ngừng tự khởi động service legacy và dừng instance/reconnect đang chạy. Không dùng telemetry/status chung làm dữ liệu của thiết bị v2, kể cả chỉ lọc ID trong payload. Không tự chuyển service sang polling HTTP, không thêm worker, topic hoặc ACL mới.

UI và tài liệu nêu rõ giám sát MQTT theo thiết bị, điều khiển vật lý và Wi-Fi BLE chưa hoàn thiện. Không tuyên bố đã hoàn tất yêu cầu HTTP+MQTT end-to-end.

### B5 — Ánh xạ nước theo đúng hợp đồng

Đề xuất biểu mẫu v2 thể hiện trực tiếp water_adc_min↔water_cm_min và water_adc_max↔water_cm_max. Công thức tuyến tính được tái sử dụng với config của thiết bị đang chọn. Không mang bộ hiệu chuẩn/ngưỡng legacy toàn cục sang server và không duy trì các trường “đã lưu” mà API không lưu.

ADC ngoài khoảng: hiển thị ADC/ngoài khoảng quy đổi, không bịa chiều cao bằng clamp. Automation vẫn gửi ngưỡng water bằng ADC. Nếu cần ngưỡng cảnh báo độc lập với hai điểm hiệu chuẩn như UI cũ, đó là yêu cầu bổ sung hợp đồng, phải duyệt trước.

## 3. Nguyên tắc triển khai

- Giữ Java 11, XML, Material/AppCompat, Volley, giao diện navy–cyan và ba mục Tổng quan / Điều khiển / Hệ thống. Không chuyển framework hay thêm dependency nếu chưa có lý do trực tiếp.
- Dùng nguồn yêu cầu từ tin nhắn này và hợp đồng PHP/SQL/Postman hiện hữu. Không có yêu cầu tạo constitution, topology, schema hoặc hợp đồng mới.
- Không có guideline chuyển framework Android/PHP phù hợp trong thư mục guidelines đã tra; đây là tích hợp API, không phải migration framework.
- Một ApiClient dùng lại Volley: POST envelope v2, Bearer snapshot, không cache response riêng tư, parse lỗi HTTP/body có kiểm soát. Không log request/header/raw response nhạy cảm.
- Ngữ cảnh bất biến mỗi request: normalizedApiUrl + userId + deviceId (nếu có) + sessionGeneration + deviceGeneration; biểu đồ có thêm queryGeneration.
- Trước mọi callback và xử lý 401, kiểm tra generation. Callback của phiên cũ không được đăng xuất hoặc cập nhật phiên mới. A→B→A cũng không nhận callback của lần A đầu.
- Hủy request theo tag, remove timer/poll, vô hiệu pending cục bộ, ngắt callback MQTT cũ và xóa trạng thái đang hiển thị khi đổi context.
- SharedPreferences kết nối app không là cấu hình thiết bị. Nếu cần lưu cache/view preference, khóa bằng server/userId/deviceId; không phục hồi cache chưa xác minh quyền.
- Đổi URL API được coi là đổi server context: dừng request, bỏ phiên/context cũ và đăng nhập lại; không tự gửi token cũ sang domain mới. Giữ hộp “Cấu hình API”; bỏ domain Quick Tunnel mặc định cố định, không ghi đè URL người dùng đã chọn.
- Nhớ đăng nhập: lưu token riêng tư theo server/user, hạn phiên, không lưu mật khẩu; loại dữ liệu phiên khỏi backup/transfer. Không nhớ: chỉ giữ phiên trong process, hết process phải đăng nhập lại.
- 401 của login là sai xác thực, không chạy vòng logout; 401 của phiên hiện tại xóa session và back stack riêng tư. Logout offline xóa local nhưng thông báo chưa xác nhận thu hồi trên server.
- 403 không đồng nghĩa hết phiên: khóa thao tác và refresh quyền/danh sách. Nếu còn view thì giữ màn chỉ đọc; nếu mất view/inactive với user thì rời thiết bị. 404 xử lý theo loại đối tượng, không mặc định logout. 409 hiển thị xung đột tương ứng, không tự ghi đè.
- Không gọi API ESP hoặc tạo/lưu device_key trong app. Không dùng MQTT credential làm Bearer hoặc ngược lại.
- Mỗi giai đoạn dừng sau khi báo thay đổi, hạn chế còn lại và checklist cho người dùng; không tự chạy checklist. Chỉ chuyển giai đoạn sau khi người dùng duyệt/cho tiếp tục.

## 4. Các giai đoạn và tác vụ

Quy ước: J = `android-app/app/src/main/java/com/buiducluan23011767/ck/`, R = `android-app/app/src/main/res/`. Đường dẫn cụ thể vẫn được ghi ở từng tác vụ. Các file mới dưới đây là đề xuất, chưa được tạo trong source.

### Giai đoạn 1 — Nền API, phiên, danh sách thiết bị và hàng rào legacy

**REQ-001, REQ-002, REQ-003, REQ-006, REQ-007, REQ-011.** Không phụ thuộc giai đoạn triển khai khác.

**1.1 API client và phiên v2**
- [ ] T001 [Plan:1.1] Tạo `android-app/app/src/main/java/com/buiducluan23011767/ck/ApiClient.java`: dùng Volley hiện có, API envelope/version, JSON đúng kiểu, Bearer snapshot, error HTTP phân loại, no-cache, request tag và guard generation; chỉ login không cần Bearer.
- [ ] T002 [Plan:1.1] Sửa `android-app/app/src/main/java/com/buiducluan23011767/ck/UserSession.java`, `LoginActivity.java`, `SplashActivity.java` cùng thư mục; đọc user object/admin-user, user ID/server/hạn, remember, login devices+cursor; đổi tuyến mở sau restore/login; wire rule phiên trong `android-app/app/src/main/AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`.

**1.2 Context và cách ly legacy**
- [ ] T003 [Plan:1.2] Tạo `android-app/app/src/main/java/com/buiducluan23011767/ck/DeviceContext.java`; sửa `android-app/app/src/main/java/com/buiducluan23011767/ck/AppConfig.java` tách URL/poll/view settings khỏi device config, không dùng default device, không tự nhập prefs legacy, không hard-code Quick Tunnel; invalidate phiên/request khi đổi URL.
- [ ] T004 [Plan:1.2] Chặn các đường MQTT/common-topic và GET/outbox v1 trong `android-app/app/src/main/java/com/buiducluan23011767/ck/MainActivity.java`, `SettingsActivity.java`, `PoolMonitorService.java`, `OperationLog.java`, `OperationLogActivity.java`, `SensorChartActivity.java` cùng thư mục trước khi mở tuyến v2; stop service/notification/reconnect cũ, khóa các màn chưa tích hợp với nhãn rõ, không cho thao tác cũ lọt qua listener hoặc tự khởi động.

**1.3 Danh sách và chọn thiết bị**
- [ ] T005 [US1] [Plan:1.3] Tạo `android-app/app/src/main/java/com/buiducluan23011767/ck/DeviceListActivity.java`, `android-app/app/src/main/res/layout/activity_device_list.xml`; đăng ký Manifest. Dùng trang login ban đầu hoặc list_devices khi restore/refresh, next_after_device_id, load-more, retry/empty/loading, metadata và quyền, admin thấy inactive.
- [ ] T006 [US1] [Plan:1.3] Sửa `android-app/app/src/main/java/com/buiducluan23011767/ck/MainActivity.java` và `android-app/app/src/main/res/layout/activity_main.xml`: vào bằng selected context đã get_device xác minh, hiện tên/ID và đổi thiết bị; rời context thì hủy request/timer/pending/callback, clear dữ liệu và back stack cần thiết. Sau GĐ1 chỉ có vỏ workspace và chức năng đã tích hợp, không hiển thị dữ liệu v1.

**Kiểm tra thủ công giao cho người dùng:** A chỉ thấy D1, B thấy D1/D2, admin thấy cả inactive. Thử có/không remember, restart process, đổi A→B, logout offline và đổi URL khi login đang chờ. Khi chọn D1→D2→D1 nhanh không xuất hiện số đo v1, pending hay thông báo cũ. Quyền xem không bị mặc định thành quyền điều khiển.

### Giai đoạn 2 — Tổng quan, lưu đồ và biểu đồ đọc dữ liệu v2

**REQ-002, REQ-003, REQ-004, REQ-011.** Phụ thuộc GĐ1.

**2.1 Trạng thái và config chỉ đọc**
- [ ] T007 [US2] [Plan:2.1] Sửa `android-app/app/src/main/java/com/buiducluan23011767/ck/MainActivity.java`, dùng `ApiClient.java`/`DeviceContext.java`, tạo model config tối thiểu `DeviceConfig.java` cùng thư mục; lấy get_device, get_status, get_config, nạp hiệu chuẩn đúng thiết bị, hiện state/sample null, giờ đo/nhận UTC, hai loại mẫu, desired/reported revision. Tái sử dụng `SystemStatusView.java`; không suy từ HTTP thành MQTT online, không dựng runtime automation/capability. Refresh get_device theo vòng polling/onResume vì get_status không phản ánh các cờ quyền.

**2.2 Biểu đồ có khoảng thời gian và cursor**
- [ ] T008 [US2] [Plan:2.2] Sửa `android-app/app/src/main/java/com/buiducluan23011767/ck/SensorChartActivity.java`, `HistoryPoint.java`, `android-app/app/src/main/res/layout/activity_sensor_chart.xml`: POST get_history với device_id, basis, from_ms/to_ms, cursor; giữ nguyên khoảng đã chụp khi tải trang tiếp; reset khi đổi bộ lọc/context, dedupe theo id/sample_id, sort thời gian tăng dần, bỏ giá trị null riêng từng sensor chứ không giả 0.

**Ngữ nghĩa thời gian:** thẻ chính ưu tiên latest_measured_sample và ghi rõ mốc đo. last_received_sample được trình bày riêng với giờ nhận/backfill/time_quality; nếu chỉ có mẫu chưa biết giờ đo, hiển thị đúng chú thích, không gán giờ nhận thành giờ đo. Mẫu thiếu một tham số không được lấy giá trị cũ khác mẫu mà bỏ nhãn thời gian. Giữ ngưỡng UI 15 giây hiện có để đánh dấu mẫu đã biết giờ đo là cũ, nhưng không gọi đó là bằng chứng thiết bị offline; độ mới HTTP dùng riêng is_recent_report của server. Mẫu chưa biết giờ đo không được tuyên bố là mới.

**Kiểm tra thủ công:** D1/D2 có số đo và cấu hình quy đổi khác nhau; chuyển nhanh khi mạng chậm. D1 có mẫu known-time cũ và unknown-time backfill vừa nhận; không đảo hai khái niệm. D2 chưa có state/samples phải “Chưa có báo cáo”, không OFF/0. Thử received/measured, giới hạn thời gian, trang tiếp, hai mẫu trùng timestamp; cursor query cũ không lẫn vào query mới. Dữ liệu tổng hợp phục vụ kiểm tra nếu người dùng chủ động chuẩn bị phải được gọi rõ là giả lập, không phải ESP thật.

### Giai đoạn 3 — Cấu hình theo quyền và revision

**REQ-003, REQ-005, REQ-009, REQ-011.** Phụ thuộc GĐ1–2.

**3.1 Đọc và biểu mẫu v2**
- [ ] T009 [US3] [Plan:3.1] Sửa `android-app/app/src/main/java/com/buiducluan23011767/ck/SettingsActivity.java`, `DeviceConfig.java`, `android-app/app/src/main/res/layout/activity_settings.xml`, `content_automation_tab.xml` cùng thư mục; nạp get_config theo context, read-only cho view, sửa khi can_configure, không chặn toàn màn theo isAdmin. Đổi biểu mẫu nước theo B5, tách bản nháp khỏi config server; thông tin ID đã đăng ký chỉ đọc.

**3.2 Lưu patch và xử lý xung đột**
- [ ] T010 [US3] [Plan:3.2] Thay đường lưu MQTT trong `android-app/app/src/main/java/com/buiducluan23011767/ck/SettingsActivity.java` và interlock trong `MainActivity.java` cùng thư mục bằng update_config+expected_revision. Automation gửi mảng đầy đủ các quy tắc hiện có khi sửa một kênh để không xóa kênh kia; chỉ patch trường đã sửa. 409 giữ bản nháp, tải bản mới để người dùng xem/sửa/gửi lại chủ động, không auto-resubmit. stored_only và reported matching được ghi nhãn riêng.

**3.3 Tách cài đặt app và các chức năng chưa hỗ trợ**
- [ ] T011 [Plan:3.3] Sửa `android-app/app/src/main/java/com/buiducluan23011767/ck/AppConfig.java`, `SettingsActivity.java`, `MainActivity.java` và layout liên quan: URL/poll là thiết lập app, không gửi xuống ESP; khóa quét/cấu hình Wi-Fi, đổi identity/location và “gửi tất cả MQTT”; bỏ preview chứa password và việc preview tự commit. Hiện SSID/IP ESP báo nếu có, thông báo BLE provisioning chưa triển khai.

**Kiểm tra thủ công:** A/B cùng sửa D1 từ revision N, người lưu sau nhận 409, bản nháp không mất và không ghi đè âm thầm. B chỉ view D2 thì không sửa được; thu hồi configure trên D1 trong lúc màn mở phải khóa khi refresh/403 nhưng vẫn giữ view. Lưu chỉ interlock không xóa automation; sửa một kênh không mất kênh còn lại. Đăng nhập lại và đổi thiết bị vẫn lấy đúng server config. Không có thông báo “ESP đã áp dụng” chỉ từ HTTP 200.

### Giai đoạn 4 — Yêu cầu điều khiển và theo dõi lệnh

**REQ-002, REQ-003, REQ-006, REQ-011.** Phụ thuộc GĐ1–3 để có context, trạng thái và ngữ nghĩa interlock.

**4.1 Tạo yêu cầu có cảnh báo giới hạn**
- [ ] T012 [US4] [Plan:4.1] Sửa `android-app/app/src/main/java/com/buiducluan23011767/ck/MainActivity.java`, `MqttControlGate.java`, `android-app/app/src/main/res/layout/activity_main.xml`: dùng request_control với UUID request_id, pump target, boolean, TTL; UI ghi “Yêu cầu bật/tắt”, có cảnh báo chưa có đường chuyển lệnh, không lấy broker/ESP online legacy làm điều kiện thay quyền API, không tự đổi trạng thái bơm. Khi chưa có state, không suy ra thao tác toggle từ false mặc định.

**4.2 Theo dõi command đúng trạng thái**
- [ ] T013 [US4] [Plan:4.2] Thêm theo dõi get_command có giới hạn vòng đời trong `android-app/app/src/main/java/com/buiducluan23011767/ck/MainActivity.java` và `ApiClient.java` cùng thư mục. Ưu tiên effective_status, hiển thị pending/applied/rejected/expired/cancelled; timeout mạng không tự thành rejected. Retry yêu cầu chưa rõ kết quả phải dùng cùng UUID/nội dung, không gia hạn hoặc tạo lệnh mới tự động. Dừng polling khi rời context; không phát sinh ACK hoặc cancel giả.

**Kiểm tra thủ công:** A yêu cầu trên D1 thấy pending và not_published_by_api, bơm không tự đổi ON. B view-only D2 bị chặn. Chờ TTL phải expired; admin gỡ control/inactive thì server có thể cancelled, app refresh quyền và không giữ nút điều khiển. Nếu mất view thì hiển thị thu hồi quyền, không bịa kết quả command không còn đọc được. applied/rejected chỉ kiểm chứng khi người dùng chủ động có ACK qua công cụ ngoài app; Android không gọi endpoint ESP và không cần lưu key ESP.

### Giai đoạn 5 — Nhật ký theo thiết bị và nguồn

**REQ-002, REQ-003, REQ-007, REQ-011.** Phụ thuộc GĐ1–2; nên làm sau GĐ3–4 để quan sát audit phát sinh từ các thao tác đã tích hợp.

**5.1 Nhật ký API làm nguồn chính**
- [ ] T014 [US5] [Plan:5.1] Sửa `android-app/app/src/main/java/com/buiducluan23011767/ck/OperationLogActivity.java`, `android-app/app/src/main/res/layout/activity_operation_log.xml`, `item_operation_log.xml` cùng thư mục: device_id, list_activity/next_before_id, actor_username, source, result, changes hiển thị theo allowlist; occurred_at_ms nullable và giờ nhận UTC có nhãn riêng; không biến committed hoặc client_reported thành xác nhận phần cứng.

**5.2 Không gửi lại hàng đợi v1 sang v2**
- [ ] T015 [Plan:5.2] Sửa `android-app/app/src/main/java/com/buiducluan23011767/ck/OperationLog.java` và các call site Main/Settings/OperationLogActivity cùng thư mục: ngừng đọc/gộp/đẩy outbox legacy trong tuyến v2. Giữ nguyên database legacy trên máy, không xóa hay tự chuyển actor/device; sử dụng audit backend đã tự sinh, không tạo audit client trùng. Chưa cần outbox v2 vì chưa có thao tác BLE/offline thực sự. Nếu sau này giữ/thêm outbox, phải có userId/server/device context và payload record_activity v2, đó là tác vụ cần duyệt riêng.

**Kiểm tra thủ công:** A/B cùng D1 đều xem được thao tác của nhau, actor/source rõ; B chuyển D2 không thấy D1. Backend events có occurred_at_ms null vẫn hiện thời gian hợp lệ, không năm 1970. Tải trang tiếp không trùng/mất. Đổi A→B hoặc URL trong lúc tải không hiển thị/đồng bộ hàng đợi A sang B.

### Giai đoạn 6 — Quản trị user, đăng ký thủ công, thành viên

**REQ-003, REQ-008, REQ-009, REQ-010, REQ-011.** Phụ thuộc nền GĐ1 và việc tách cài đặt thiết bị/quản trị ở GĐ3; không cần firmware hay MQTT publisher để làm các tác vụ API này.

**6.1 Quản trị tài khoản toàn hệ thống**
- [ ] T016 [US6] [Plan:6.1] Tạo `android-app/app/src/main/java/com/buiducluan23011767/ck/UserManagementActivity.java`, tái sử dụng/chuyển form `android-app/app/src/main/res/layout/content_users_tab.xml` và logic phù hợp từ Settings; thêm entry từ DeviceList/Manifest để admin không cần có thiết bị mới quản lý được tài khoản. list_users/after_id, create_user cần full_name, role admin/user, password 8..72 byte và student_code tùy chọn.
- [ ] T017 [US6] [Plan:6.1] Trong `android-app/app/src/main/java/com/buiducluan23011767/ck/UserManagementActivity.java` dùng set_user_active/set_user_password; bỏ nút xóa/sửa profile không có API, parse is_active boolean, hiển thị 409 tự khóa/admin cuối cùng. Đặt lại mật khẩu thu hồi phiên, kể cả phiên admin hiện tại nếu đổi chính mình.

**6.2 Đăng ký ID thủ công và active**
- [ ] T018 [US6] [Plan:6.2] Bổ sung form `android-app/app/src/main/res/layout/dialog_register_device.xml` và xử lý trong `android-app/app/src/main/java/com/buiducluan23011767/ck/DeviceListActivity.java`: tách hàm lấy/validate ID khỏi lời gọi register_device, dùng registration_source=manual, name/student_full_name/student_code, owner tùy chọn. Không tự đổi ID người dùng nhập thành ID khác; thiết bị tạo ra inactive, kích hoạt bằng set_device_active riêng. Không gọi create_device_credential, không giả BLE và không nhúng key.

**6.3 Thành viên theo thiết bị**
- [ ] T019 [US6] [Plan:6.3] Tạo `android-app/app/src/main/java/com/buiducluan23011767/ck/DeviceMembersActivity.java`, `android-app/app/src/main/res/layout/activity_device_members.xml`, thêm Manifest/entry workspace. Admin dùng list_device_members; chọn user active từ list_users có phân trang; set_device_member gửi đủ boolean, view luôn true và remove_device_member khi gỡ. Không sửa quyền của thiết bị khác, không xóa tài khoản.
- [ ] T020 [US6] [Plan:6.3] **BLOCKED B1, không tự sửa backend:** phần user đọc thành viên trong `android-app/app/src/main/java/com/buiducluan23011767/ck/DeviceMembersActivity.java` chỉ được nối sau khi hợp đồng backend được duyệt và triển khai riêng. Trước đó hiển thị rõ hạn chế, không hiện danh sách rỗng giả, không gọi list_users để thay thế.

**Kiểm tra thủ công:** Admin chưa có thiết bị vẫn tạo/khóa user được. A/B cùng D1, B còn có D2; gỡ A khỏi D1 không ảnh hưởng B hoặc D2. Hạ B trên D1 xuống view-only, chỉ D1 mất control/configure; khóa B thu hồi phiên trên mọi thiết bị. D1 inactive: user không còn truy cập, admin vẫn xem được metadata nhưng không control/configure. Trùng ID trả 409. Không có nút “quét BLE” thành công giả.

### Giai đoạn 7 — Tài liệu và bàn giao thủ công

**REQ-001 đến REQ-011.** Chốt sau các phần đã duyệt; cập nhật ghi chú tương thích ngay ở mỗi giai đoạn, không đợi cuối mới công bố giới hạn.

**7.1 Tài liệu tiếng Việt**
- [ ] T021 [Plan:7.1] Cập nhật `android-app/README_MQTT_ESP32.md`, `docs/system-flow.md`, các đoạn liên quan trong `README.md` và `docs/database-v2.md`: login→list→workspace, quyền theo thiết bị, desired/reported, clock/basis, source audit, trạng thái lệnh, manual-ID và các phần chờ BLE/firmware/MQTT. Không sửa tuyên bố trạng thái triển khai Pi khi chưa có bằng chứng mới.

**7.2 Bàn giao theo giai đoạn**
- [ ] T022 [Plan:7.2] Sau mỗi giai đoạn cung cấp danh sách file/thay đổi, hành vi được nối API, phần chưa hỗ trợ và hướng dẫn kiểm tra thủ công hai user/hai thiết bị trong `android-app/README_MQTT_ESP32.md`. Chỉ rà soát source/diff; ghi rõ chưa build/test, không tự chạy Gradle/Postman/service/deployment/tool install.

## 5. Tổ chức source dự kiến

Giữ package và các Activity hiện có. Chỉ thêm ApiClient, DeviceContext, DeviceConfig, DeviceListActivity, UserManagementActivity, DeviceMembersActivity và các layout thực sự cần. Không dựng tầng repository/use-case tổng quát, không chuyển Compose/MVVM/Retrofit/Room, không đổi build tool/JDK.

Settings chỉ quản lý thiết bị đang chọn; tài khoản là chức năng admin toàn hệ thống truy cập từ danh sách thiết bị. Header và mọi màn con đều có tên/ID. Mọi Intent/notification mở workspace phải kiểm tra phiên và context trước; không tự mở thiết bị mặc định từ notification cũ.

## 6. Kiểm tra thủ công do người dùng thực hiện

Đây là tiêu chí bàn giao, **không phải lệnh hoặc kiểm thử đã chạy**. Không bổ sung test runner/tooling trong đợt này.

### Fixture dùng chung

- Một admin hiện có; hai tài khoản thường A và B; hai thiết bị riêng cho kiểm tra D1 và D2.
- A: D1 đủ ba quyền. B: D1 đủ ba quyền, D2 chỉ view. Admin có quyền toàn hệ thống.
- D1/D2 có cấu hình nước khác nhau; chuẩn bị mẫu known-time, unknown-time/backfill và thiết bị chưa có state theo khả năng người dùng. Không cần credential ESP trong app.
- Không tự chạy toàn bộ collection Postman để “chuẩn bị” vì collection có bước khóa user/inactive thiết bị/revoke và để lại dữ liệu. Người dùng chủ động dùng dữ liệu kiểm tra riêng, không tác động ID thật.
- Để kiểm tra phân trang app với đúng hai thiết bị, người dùng có thể tạm đặt hằng page-size phát triển bằng 1 khi tự build; tương tự history/activity. Không thêm tùy chọn production chỉ để phục vụ kiểm tra, và phải phục hồi hằng sau khi kiểm tra.

### Các luồng bắt buộc

1. A→logout→B trên cùng điện thoại: không lộ lựa chọn, dữ liệu, pending, log hoặc notification A.
2. B chuyển D1→D2→D1 trong lúc request đang chờ: không có callback/401 cũ tác động màn hoặc phiên mới.
3. Admin thu hồi configure/control nhưng giữ view: workspace đổi sang chỉ đọc; thu hồi view/inactive thì rời context phù hợp. Admin vẫn xem inactive theo đúng PHP.
4. Cùng sửa config D1: 409 giữ bản nháp, không tự overwrite; D2 không đổi.
5. Unknown measurement time và backfill: không gọi giờ nhận là giờ đo, không làm dữ liệu đo cũ thành mới.
6. Yêu cầu command không làm bơm đổi ON, config stored_only không thành ESP-applied; hết TTL không tiếp tục pending vô hạn.
7. Nhật ký mọi thành viên D1 hiển thị actor/source; đổi D2 và đổi tài khoản không trộn dữ liệu.
8. URL Quick Tunnel thay đổi: người dùng sửa URL, đăng nhập lại; không tự gửi token của URL cũ.

### Tiêu chí kết thúc

- Hoàn thành phần Android chỉ khi người dùng đã duyệt từng giai đoạn và tự kiểm tra kết quả.
- Tách rõ “đã nối API trong source”, “người dùng đã xác nhận runtime”, “chờ firmware/MQTT/BLE” và “blocked do hợp đồng”.
- User xem thành viên chưa được đánh dấu hoàn thành khi B1 còn tồn tại. Điều khiển vật lý/MQTT isolation không được đánh dấu hoàn thành chỉ vì Android gọi được API.

## 7. Requirement Mapping

Các ID dưới đây chỉ dùng truy vết bản kế hoạch, không phải yêu cầu bổ sung ngoài phạm vi.

| REQ ID | Yêu cầu | Plan items | Bằng chứng triển khai dự kiến |
| --- | --- | --- | --- |
| REQ-001 | Login/phiên/Bearer/logout/remember/chuyển tài khoản | 1.1, 1.2, 1.3, 7.2 | ApiClient, UserSession, Login/Splash, DeviceList |
| REQ-002 | Danh sách/chọn thiết bị/phân trang/cách ly callback/cache | 1.2, 1.3, 2.1, 2.2, 4.2, 5.1, 7.2 | DeviceContext, DeviceList, Main, Chart, Log |
| REQ-003 | Quyền theo thiết bị và HTTP 401/403/404/409 | 1.1, 1.3, 2.1, 3.1, 3.2, 4.1, 6.1, 6.3 | ApiClient, permission refresh, Settings, admin screens |
| REQ-004 | Trạng thái/mẫu/thời gian/biểu đồ/hiệu chuẩn đúng thiết bị | 2.1, 2.2, 3.1, 7.2 | Main, DeviceConfig, HistoryPoint, SensorChart, diagram |
| REQ-005 | get/update_config, revision/409, desired khác reported | 2.1, 3.1, 3.2, 3.3 | DeviceConfig, Settings, Main interlock |
| REQ-006 | request_control/get_command, delivery/trạng thái thật, không bypass MQTT | 1.2, 4.1, 4.2, 7.1 | Main, MqttControlGate, service legacy guard |
| REQ-007 | Nhật ký device/actor/source/phân trang và không sync nhầm | 1.2, 5.1, 5.2, 7.2 | OperationLogActivity, OperationLog/call sites |
| REQ-008 | Admin tài khoản/đăng ký/active/thành viên | 6.1, 6.2, 6.3 | UserManagement, DeviceList, DeviceMembers |
| REQ-009 | ID thủ công có seam BLE, chưa provisioning | 3.3, 6.2, 7.1 | Registration form, Settings Wi-Fi disabled/info |
| REQ-010 | User xem thành viên cùng thiết bị, không quản trị | 6.3, 7.1 | T020 BLOCKED B1; cần duyệt hợp đồng riêng trước |
| REQ-011 | Tái sử dụng UI, không đổi backend/firmware, không secret/JDK/domain cố định, không tự test/deploy, tài liệu | 1.1, 1.2, 3.3, 6.2, 7.1, 7.2 | Source diff giới hạn Android/docs; checklist và báo cáo từng đợt |

**Tổng:** 11 nhóm yêu cầu được gắn kế hoạch; 17 plan items; 22 tác vụ dự kiến. T020 bị chặn rõ ràng. Chưa tác vụ triển khai nào được thực hiện hoặc kiểm chứng.
