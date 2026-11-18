# Danh mục MAC, đăng ký và xóa thiết bị — source 007/008

**Runtime 009 đã deploy:** [xóa có reset ESP và thu hồi MQTT](device-deprovision-009.md) thay cách xóa ngay bên dưới. delete_device nay tạo job chờ ESP xác nhận; chỉ giải phóng ID/MAC sau khi reset và revoke thành công. Firmware mới chưa nạp/kiểm tra, các mô tả source 007/008 bên dưới là nền tảng archive/inventory.

Đây là hợp đồng mới nhất, bổ sung và thay các đoạn first-contact trong tài liệu 006. Chưa chạy migration/deploy Pi, build/test firmware hoặc gọi thử API trong đợt này.

## Quy tắc

| Tình trạng | device_id trong hardware_inventory | status | available |
| --- | --- | --- | --- |
| MAC khai báo trước | null | deactive | true |
| Đã gán ID, device chưa kích hoạt/bị vô hiệu hóa | Có ID | deactive | false |
| Đã gán ID và device active | Có ID | active | false |
| Đã xóa device | null | deactive | true |

MAC và ID không được trùng trong tập đang hoạt động. ID là khóa chính của devices; MAC là khóa chính hardware_inventory và device_id ở inventory có UNIQUE, cho phép nhiều hàng null. status/available được tính từ liên kết và is_active, không duy trì hai cờ có thể mâu thuẫn. **Deactive không luôn đồng nghĩa có thể thêm**: chỉ available=true khi chưa gán ID.

Chỉ admin đã đăng nhập được khai báo MAC, đăng ký/gắn/xóa thiết bị. register_device và bind_device_hardware yêu cầu thêm shared_device_key hợp lệ. ESP biết khóa chung cũng không tự tạo registry hoặc liên kết MAC. ID chưa có mapping bị từ chối 403; ID/MAC không khớp bị 409.

## Admin khai báo MAC trước

Bearer admin, POST JSON:

```json
{"action":"declare_hardware","factory_mac":"AA:BB:CC:11:22:33"}
```

MAC ví dụ phải thay bằng eFuse thật trên bo. Chưa gán ID thì response có device_id=null, status=deactive, available=true. Khai báo lặp là idempotent, không làm mất liên kết đã có. Đây là khai báo phần cứng, chưa phải thêm một device và chưa cần khóa chung.

```json
{"action":"list_hardware","available_only":true,"limit":50}
```

Phân trang dùng next_after_mac → after_mac. Bỏ available_only hoặc đặt false để xem cả đã gán/chưa gán. User thường không được xem danh mục toàn hệ thống.

## Admin thêm thiết bị

Admin lấy MAC qua local (sau này BLE), đặt ID, nhập khóa chung đã cấp trước đó. Gọi:

```json
{
  "action":"register_device",
  "device_id":"NguyenVanA_123456",
  "factory_mac":"AA:BB:CC:11:22:33",
  "shared_device_key":"<khoa_HTTP_chung_64_hex>",
  "name":"Ho boi A",
  "student_full_name":"Nguyen Van A",
  "student_code":"123456",
  "registration_source":"manual"
}
```

Placeholder không phải khóa thật. Khóa chung tạo một lần bằng create_shared_device_credential theo tài liệu 006; không tạo mới cho từng bo.

Các trường ID/MAC/key bắt buộc; thiếu/sai định dạng 400, khóa không còn hiệu lực 401, không phải admin 403, ID hoặc MAC đang được dùng 409. Hồ sơ + cấu hình mặc định + gán MAC + audit nằm trong một transaction; không để lại nửa device khi lỗi/tranh chấp đồng thời. MAC có thể được khai báo trước; nếu chưa khai báo, admin register sẽ tạo hàng MAC và gán trong cùng transaction.

Thiết bị mới vẫn inactive cho đến set_device_active riêng. Sau khi đăng ký thành công, cấp ID và cùng khóa chung vào NVS của ESP qua AP; cấu hình MQTT như tài liệu local. Có ID cũ chưa có MAC sau nâng cấp thì admin dùng bind_device_hardware kèm factory_mac và shared_device_key để bổ sung, ESP không được tự bổ sung.

## Xóa device, giữ MAC

Bearer admin và khóa chung:

```json
{"action":"delete_device","device_id":"NguyenVanA_123456","shared_device_key":"<khoa_HTTP_chung_64_hex>"}
```

Backend thực hiện trong một transaction:

1. Khóa device và hủy command pending, ghi kết quả/audit.
2. Lưu snapshot hồ sơ, trạng thái, lịch sử cảm biến, cấu hình, membership, credential metadata, commands/results và audit của device vào device_archives/device_archive_records. Đọc/lưu theo lô để không nạp toàn bộ lịch sử vào RAM.
3. Xóa các dữ liệu đang hoạt động của device và dòng devices. Không xóa tài khoản user hoặc quyền của họ trên thiết bị khác.
4. Giữ nguyên MAC trong hardware_inventory, đặt device_id/binding_source/bound_by/bound_at/last_verified_at về null. MAC trở lại deactive, available=true. Thông tin khai báo MAC ban đầu vẫn giữ.
5. Ghi audit backend device_deleted không còn FK tới device đã xóa, chứa archive_id để đối soát. Nếu lỗi bất kỳ bước nào, rollback cả archive/xóa/gỡ liên kết.

ID cũ không còn trong registry đang hoạt động, có thể đăng ký lại; MAC có thể gán cho ID mới. ID và liên kết cũ chỉ còn trong kho lịch sử/audit để bảo toàn truy vết, không làm MAC bị chiếm trong danh mục. Không tự khôi phục lệnh pending/lịch sử/membership vào device đăng ký lại.

API response có deleted=true, archive_id, factory_mac, hardware_available. Nếu device legacy chưa có MAC thì factory_mac=null, không bịa MAC để giữ. Admin xem metadata kho bằng list_device_archives (limit/before_id); API không trả raw snapshot/credential hashes. Phục hồi từ kho là thao tác vận hành có kiểm soát, chưa có API restore tự động. Lịch sử lớn cần đủ dung lượng cho bản lưu và transaction có thể lâu; lỗi không được xử lý bằng cách bỏ qua archive.

Xóa trên server **không tự xóa/reset NVS của ESP**, không tự revoke account Mosquitto và không thể rút lại gói MQTT đã gửi. Người vận hành dùng công cụ broker hiện có để revoke/rotate account cũ. Worker không phát/nhận trạng thái cho ID không còn registry; ESP vẫn kiểm tra TTL/MAC, nhưng không coi đây là cơ chế dừng bơm vật lý nguyên tử. Việc đổi ID trong ESP đã provisioned vẫn cần luồng deprovision/reprovision riêng chưa triển khai; không erase NVS/queue một cách tự động.

Trước khi dùng lại cùng ID/MAC, phải đối soát hàng đợi và snapshot cấu hình trên bo. Hiện chưa có registration generation xuyên suốt firmware/API để phân biệt tuyệt đối dữ liệu/gói cũ với lần đăng ký mới; nếu dùng lại nguyên ID/MAC mà chưa xử lý bo, queue cũ có thể được gửi lại và revision cũ có thể không khớp server. Kho archive bảo toàn dữ liệu phía server, không thay thế quy trình tái cấp bo.

## Migration, triển khai và quay lui

- 006 tạo khóa chung và bảng mapping ban đầu, giữ nguyên file/checksum.
- [007_hardware_inventory.sql](../backend/migrations/007_hardware_inventory.sql) tạo danh mục MAC nullable ID, sao chép các cặp 006 hiện có. device_hardware cũ giữ làm dữ liệu legacy; API/worker mới đọc hardware_inventory. Chạy lại khi DDL dở dang không ghi đè inventory đã có.
- [008_device_deletion_archive.sql](../backend/migrations/008_device_deletion_archive.sql) tạo kho lưu trữ. Migration không xóa device hoặc lịch sử; chỉ request delete_device rõ ràng của admin mới vào nhánh xóa.
- Sao lưu trước, build image, **dừng cả API và worker** để chặn ghi lúc chuyển mapping 006→007, chạy migrate.php bằng image mới, rồi khởi động API/worker. activate-control-worker.sh đã cập nhật thứ tự này nhưng không tự backup. Health mới báo schema_runtime=8.
- Không dùng reset/down -v. Chưa có deployment mới trên Pi. Khi quay lui không chỉ đổi image về 006 rồi coi mapping khớp: cần đối soát mapping được thêm/gỡ sau 007 và các ID đã xóa. Giữ inventory/archive, không drop bảng hoặc sửa checksum. Không tự phục hồi archive đè lên một ID đã được đăng ký lại.

## App và Postman cần theo luồng mới

Android chưa sửa code. Admin Scan new device: đọc MAC thật → tra inventory/available → nhập ID + khóa chung → register_device → kích hoạt/cấp NVS. Danh sách phải phân biệt inactive nhưng đã gán với MAC chưa gán. Nút xóa chỉ cho admin; thành công thì bỏ lựa chọn/cache của ID đã xóa và tải lại danh sách thiết bị/MAC. Không xóa tài khoản user; không lưu khóa vào log/preferences/APK. BLE, reset identity ESP và tự động cấp/revoke account broker vẫn là phần chưa làm.

[Collection Postman cập nhật](../backend/postman/SmartPool.shared-key-hardware.postman_collection.json) có khai báo MAC, đăng ký A/B, kiểm tra trùng ID/MAC, chặn user, và thư mục **DELETE FIXTURE A**. Chỉ dùng ID/MAC thử nghiệm, A/B/C khác nhau; A/B ban đầu chưa có registry, C luôn là ID trống cho kiểm tra lỗi. Chọn user_token là tài khoản thường; admin_token và shared_device_key là local variables. Request tạo khóa chung đầu tiên là tùy chọn, không cần nếu đã có khóa. Không chạy thư mục xóa với thiết bị thật. Chưa chạy collection này.

Checklist tự kiểm tra: khai báo MAC→null/deactive/available; register→chiếm MAC; trùng ID/MAC không tạo hàng thừa; user không thêm/xóa; xóa A→MAC A trống/ID A mất khỏi registry/archive có dữ liệu; B và các user còn nguyên; đăng ký lại MAC A với ID mới không nhận lịch sử/command cũ. Khi request xóa timeout, kiểm tra registry/archive trước khi thao tác lại.
