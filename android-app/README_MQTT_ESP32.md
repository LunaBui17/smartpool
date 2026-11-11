# TÀI LIỆU ĐẶC TẢ GIAO THỨC MQTT - SMART POOL (ANDROID APP ↔ ESP32-S3)

> **Mục đích:** Tài liệu này tổng hợp toàn bộ các Topic, cấu trúc dữ liệu JSON, cơ chế bảo vệ và logic giao tiếp MQTT giữa ứng dụng Android **Smart Pool** và vi điều khiển **ESP32-S3**. Dựa vào tài liệu này, lập trình viên firmware ESP32 có thể cài đặt chính xác các hàm subscribe, publish và xử lý logic điều khiển tương thích hoàn toàn với App.

---

## 1. THÔNG TIN KẾT NỐI BROKER

- **Broker mặc định (HiveMQ Cloud):**
  - **URI:** `ssl://24660077a88741378be5ffe7f979f2f3.s1.eu.hivemq.cloud:8883`
  - **Port:** `8883` (SSL/TLS bảo mật)
  - **Tài khoản (Username & Password):** Cấu hình trong `mqtt.properties` / `BuildConfig` (Người dùng có thể thay đổi trong màn hình Cài đặt của App).
- **QoS:** Khuyến nghị `QoS 1` cho tất cả các bản tin điều khiển và cấu hình.
- **Retained Message:**
  - Các cấu hình (Ngưỡng, WiFi, Thiết bị) được App gửi với cờ **`retained = true`**. Khi ESP32 khởi động hoặc kết nối lại, ESP32 chỉ cần subscribe là sẽ **ngay lập tức nhận được cấu hình mới nhất** từ Broker mà không cần App phải đang mở.

---

## 2. BẢNG TỔNG HỢP CÁC TOPICS MQTT

| STT | Chiều giao tiếp | MQTT Topic | Định dạng dữ liệu | Retain | Mô tả chức năng |
| :--- | :--- | :--- | :--- | :---: | :--- |
| **1** | App ➔ ESP32 | `smartpool/config/thresholds` | JSON | **True** | Cấu hình ngưỡng an toàn (Nhiệt độ & Mực nước) |
| **2** | App ➔ ESP32 | `smartpool/config/wifi` | JSON | **True** | Cấu hình SSID & Mật khẩu WiFi cho ESP32 |
| **3** | App ➔ ESP32 | `smartpool/cmd/wifi_scan` | JSON | False | Lệnh yêu cầu ESP32 quét các mạng WiFi xung quanh |
| **4** | ESP32 ➔ App | `smartpool/wifi/scan_result` | JSON Array | False | ESP32 trả về danh sách mạng WiFi đã quét được |
| **5** | App ➔ ESP32 | `smartpool/cmd/bom_vao` | String (`"1"` / `"0"`) | False | Lệnh Bật / Tắt Bơm cấp (Bể phụ ➔ Bể chính) |
| **6** | App ➔ ESP32 | `smartpool/cmd/bom_ra` | String (`"1"` / `"0"`) | False | Lệnh Bật / Tắt Bơm thoát (Bể chính ➔ Thoát ngoài) |
| **7** | ESP32 ➔ App | `smartpool/status` | JSON | **True** | Trạng thái Online của ESP32 & trạng thái Relay 2 bơm |
| **8** | ESP32 ➔ App | `smartpool/telemetry` | JSON | False | Dữ liệu đo cảm biến thời gian thực (Nhiệt độ & Mực nước) |
| **9** | App ➔ ESP32 | `smartpool/config/device` | JSON | **True** | Cấu hình thông tin thiết bị (ID, Tên, Vị trí) |
| **10**| App ➔ ESP32 | `smartpool/config` | JSON | **True** | Gói cấu hình tổng hợp toàn bộ hệ thống (khi bấm Sync All) |
| **11**| ESP32 ➔ App | `smartpool/ack` | JSON | False | **Xác nhận cấu hình (ACK - Cơ chế 2):** Phản hồi khi ESP32 lưu xong Flash NVS |

---

## 3. CHI TIẾT CÁC GÓI TIN (PAYLOAD SPECIFICATION)

### 3.1. Cấu hình Ngưỡng cảm biến
- **Topic:** `smartpool/config/thresholds`
- **Người gửi:** Android App
- **Người nhận:** ESP32-S3 (Subscribe khi khởi động)
- **Cờ Retain:** `true`
- **Cấu trúc JSON (App gửi xuống ESP32):**
```json
{
  "action": "UPDATE_THRESHOLDS",
  "req_id": "req_thresh_1718000000",
  "timestamp": 1718000000,
  "temp_min": 20.0,
  "temp_max": 35.0,
  "water_min": 20.0,
  "water_max": 85.0,
  "interlock": 1
}
```
- **Xử lý trên ESP32 (Double Check Cơ chế 2):**
  - Nhận các giá trị float: `temp_min`, `temp_max`, `water_min`, `water_max`, và int `interlock` (`1` là BẬT khóa liên động, `0` là TẮT).
  - Lưu vào bộ nhớ flash không bay hơi (**NVS** hoặc **Preferences** trên ESP32) để không bị mất khi mất điện.
  - **Gửi ngay bản tin ACK** lên topic `smartpool/ack` chứa đúng `req_id` để App xác nhận thành công:
    `{"req_id": "req_thresh_1718000000", "action": "UPDATE_THRESHOLDS", "status": "SUCCESS", "message": "Đã lưu ngưỡng an toàn vào NVS"}`

---

### 3.2. Cấu hình WiFi cho ESP32
- **Topic:** `smartpool/config/wifi`
- **Người gửi:** Android App
- **Người nhận:** ESP32-S3
- **Cờ Retain:** `true`
- **Cấu trúc JSON (App gửi xuống ESP32):**
```json
{
  "action": "UPDATE_WIFI",
  "req_id": "req_wifi_1718000000",
  "timestamp": 1718000000,
  "ssid": "My_Home_WiFi",
  "password": "Password123"
}
```
- **Xử lý trên ESP32 (Double Check Cơ chế 2):**
  - Trích xuất `ssid`, `password` và `req_id`.
  - Lưu vào **Preferences/NVS**.
  - **Gửi bản tin ACK** lên topic `smartpool/ack`:
    `{"req_id": "req_wifi_1718000000", "action": "UPDATE_WIFI", "status": "SUCCESS", "message": "Đã lưu cấu hình WiFi vào NVS"}`
  - Sau đó tiến hành kết nối lại WiFi theo thông tin mới (`WiFi.begin(ssid, password)`).

---

### 3.3. Lệnh Quét mạng WiFi (WiFi Scan)
- **Topic:** `smartpool/cmd/wifi_scan`
- **Người gửi:** Android App
- **Người nhận:** ESP32-S3
- **Cấu trúc JSON:**
```json
{
  "cmd": "SCAN_WIFI",
  "timestamp": 1718000000
}
```
- **Xử lý trên ESP32:**
  - Nhận lệnh, thực hiện hàm `WiFi.scanNetworks()`.
  - Sau khi quét xong, đóng gói danh sách mạng và gửi lên topic `smartpool/wifi/scan_result`.

---

### 3.4. Kết quả quét mạng WiFi từ ESP32
- **Topic:** `smartpool/wifi/scan_result`
- **Người gửi:** ESP32-S3
- **Người nhận:** Android App (App hiển thị danh sách lên Spinner để người dùng bấm chọn)
- **Cấu trúc JSON (Khuyên dùng dạng kèm RSSI):**
```json
[
  {"ssid": "WiFi_Nha_Chinh", "rssi": -55},
  {"ssid": "WiFi_San_Vuon_5G", "rssi": -68},
  {"ssid": "SmartPool_AP", "rssi": -80}
]
```
*(App cũng hỗ trợ mảng đơn giản: `["WiFi_1", "WiFi_2", "WiFi_3"]`)*

---

### 3.5. Điều khiển Máy Bơm Cấp nước (Bể phụ ➔ Bể chính)
- **Topic:** `smartpool/cmd/bom_vao`
- **Người gửi:** Android App
- **Người nhận:** ESP32-S3
- **Payload:** Chuỗi văn bản thuần (String):
  - `"1"`: Bật bơm cấp.
  - `"0"`: Tắt bơm cấp.
- **Xử lý trên ESP32:**
  - Hỗ trợ bật đồng thời cả 2 bơm độc lập theo nhu cầu người dùng.
  - Kích hoạt Relay chân GPIO (ví dụ GPIO 18).
  - Gửi bản tin trạng thái mới ngay lập tức lên topic `smartpool/status`.

---

### 3.6. Điều khiển Máy Bơm Thoát nước (Bể chính ➔ Thoát ngoài)
- **Topic:** `smartpool/cmd/bom_ra`
- **Người gửi:** Android App
- **Người nhận:** ESP32-S3
- **Payload:** Chuỗi văn bản thuần (String):
  - `"1"`: Bật bơm thoát.
  - `"0"`: Tắt bơm thoát.
- **Xử lý trên ESP32:**
  - Kích hoạt Relay chân GPIO (ví dụ GPIO 19).
  - Gửi bản tin trạng thái mới ngay lập tức lên topic `smartpool/status`.

---

### 3.7. Trạng thái hoạt động của ESP32 & Hai máy bơm (Status)
- **Topic:** `smartpool/status`
- **Người gửi:** ESP32-S3
- **Người nhận:** Android App
- **Cờ Retain:** `true`
- **Thời điểm gửi:**
  - Khi ESP32 vừa kết nối thành công tới MQTT Broker.
  - Bất cứ khi nào trạng thái Relay của một trong hai bơm thay đổi.
  - Sử dụng làm **LWT (Last Will and Testament)**: Khi ESP32 mất nguồn hoặc mất mạng đột ngột, Broker sẽ tự động gửi `{"online": 0, "bom_vao_control": 0, "bom_ra_control": 0}`.
- **Cấu trúc JSON:**
```json
{
  "online": 1,
  "bom_vao_control": 0,
  "bom_ra_control": 1
}
```
- **Ý nghĩa các trường:**
  - `online`: `1` là ESP32 đang trực tuyến, `0` là ngoại tuyến.
  - `bom_vao_control`: `1` (Bơm cấp đang chạy), `0` (Bơm cấp đang tắt).
  - `bom_ra_control`: `1` (Bơm thoát đang chạy), `0` (Bơm thoát đang tắt).

---

### 3.8. Dữ liệu cảm biến đo đạc (Telemetry)
- **Topic:** `smartpool/telemetry`
- **Người gửi:** ESP32-S3
- **Người nhận:** Android App & Dịch vụ chạy ngầm `PoolMonitorService`
- **Tần suất gửi:** Định kỳ từ **2 đến 5 giây/lần**.
- **Cấu trúc JSON:**
```json
{
  "nhiet_do": 29.5,
  "muc_nuoc": 1
}
```
- **Ý nghĩa các trường:**
  - `nhiet_do`: Giá trị nhiệt độ đo từ cảm biến **DS18B20** (đơn vị: °C, số thực).
  - `muc_nuoc`: Trạng thái mực nước trong bể:
    - `1`: **Bình thường** (Nước nằm trong khoảng an toàn).
    - `0`: **Cảnh báo cạn nước** (Mực nước thấp hơn ngưỡng an toàn `water_min`).
    - `2`: **Cảnh báo tràn nước** (Mực nước cao hơn ngưỡng an toàn `water_max`).

---

### 3.9. Cấu hình thông tin thiết bị
- **Topic:** `smartpool/config/device`
- **Người gửi:** Android App
- **Người nhận:** ESP32-S3
- **Cờ Retain:** `true`
- **Cấu trúc JSON:**
```json
{
  "action": "UPDATE_DEVICE",
  "timestamp": 1718000000,
  "id": "ESP32S3_SMARTPOOL_01",
  "name": "Bể bơi Thông minh Sân thượng",
  "location": "Tầng 5"
}
```

---

## 4. CODE MẪU THAM KHẢO TRÊN ESP32-S3 (ARDUINO C++)

Dưới đây là khung chương trình C++ mẫu cho ESP32-S3 sử dụng thư viện **PubSubClient** và **ArduinoJson**:

```cpp
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>

// ----------------- CẤU HÌNH BROKER HIVEMQ -----------------
const char* mqtt_server = "24660077a88741378be5ffe7f979f2f3.s1.eu.hivemq.cloud";
const int mqtt_port = 8883;
const char* mqtt_user = "YOUR_HIVEMQ_USERNAME"; // Thay bằng username của bạn
const char* mqtt_pass = "YOUR_HIVEMQ_PASSWORD"; // Thay bằng password của bạn

// ----------------- CÁC TOPICS CHUẨN -----------------
const char* TOPIC_CFG_THRESHOLDS = "smartpool/config/thresholds";
const char* TOPIC_CFG_WIFI       = "smartpool/config/wifi";
const char* TOPIC_CMD_WIFI_SCAN  = "smartpool/cmd/wifi_scan";
const char* TOPIC_WIFI_SCAN_RES  = "smartpool/wifi/scan_result";
const char* TOPIC_CMD_PUMP_IN    = "smartpool/cmd/bom_vao";
const char* TOPIC_CMD_PUMP_OUT   = "smartpool/cmd/bom_ra";
const char* TOPIC_STATUS         = "smartpool/status";
const char* TOPIC_TELEMETRY      = "smartpool/telemetry";
const char* TOPIC_ACK            = "smartpool/ack"; // Cơ chế 2: Phản hồi xác nhận cấu hình

// ----------------- CHÂN GPIO RELAY -----------------
const int PIN_RELAY_PUMP_IN  = 18; // Bơm cấp
const int PIN_RELAY_PUMP_OUT = 19; // Bơm thoát

WiFiClientSecure espClient;
PubSubClient client(espClient);
Preferences prefs;

// Biến trạng thái
bool pumpInState = false;
bool pumpOutState = false;
float tempMin = 20.0f;
float tempMax = 35.0f;
float waterMin = 20.0f;
float waterMax = 85.0f;

// ----------------- HÀM GỬI TRẠNG THÁI STATUS (CƠ CHẾ 1: DOUBLE CHECK RELAY) -----------------
void publishStatus() {
    StaticJsonDocument<128> doc;
    doc["online"] = 1;
    doc["bom_vao_control"] = pumpInState ? 1 : 0;
    doc["bom_ra_control"] = pumpOutState ? 1 : 0;

    char buffer[128];
    serializeJson(doc, buffer);
    client.publish(TOPIC_STATUS, buffer, true); // Retained
}

// ----------------- HÀM PHẢN HỒI ACK (CƠ CHẾ 2: DOUBLE CHECK CẤU HÌNH) -----------------
void publishAck(const char* reqId, const char* action, const char* status, const char* message) {
    if (reqId == NULL || strlen(reqId) == 0) return;
    StaticJsonDocument<256> doc;
    doc["req_id"] = reqId;
    doc["action"] = action;
    doc["status"] = status;
    doc["message"] = message;

    char buffer[256];
    serializeJson(doc, buffer);
    client.publish(TOPIC_ACK, buffer, false);
    Serial.printf("Đã gửi ACK lên topic [%s]: req_id=%s, status=%s\n", TOPIC_ACK, reqId, status);
}

// ----------------- HÀM GỬI DỮ LIỆU TELEMETRY -----------------
void publishTelemetry(float temp, int waterLevel) {
    StaticJsonDocument<128> doc;
    doc["nhiet_do"] = temp;
    doc["muc_nuoc"] = waterLevel; // 1: bình thường, 0: cạn, 2: tràn

    char buffer[128];
    serializeJson(doc, buffer);
    client.publish(TOPIC_TELEMETRY, buffer, false);
}

// ----------------- XỬ LÝ LỆNH QUÉT WIFI -----------------
void handleWifiScan() {
    Serial.println("Đang quét WiFi xung quanh...");
    int n = WiFi.scanNetworks();
    
    DynamicJsonDocument doc(1024);
    JsonArray arr = doc.to<JsonArray>();

    for (int i = 0; i < n; ++i) {
        JsonObject net = arr.createNestedObject();
        net["ssid"] = WiFi.SSID(i);
        net["rssi"] = WiFi.RSSI(i);
    }

    char buffer[1024];
    serializeJson(doc, buffer);
    client.publish(TOPIC_WIFI_SCAN_RES, buffer, false);
    Serial.println("Đã gửi kết quả quét WiFi lên MQTT!");
}

// ----------------- MQTT CALLBACK -----------------
void callback(char* topic, byte* payload, unsigned int length) {
    String message;
    for (unsigned int i = 0; i < length; i++) {
        message += (char)payload[i];
    }
    Serial.printf("Nhận tin từ Topic [%s]: %s\n", topic, message.c_str());

    // 1. Nhận cấu hình ngưỡng (Mechanism 2)
    if (String(topic) == TOPIC_CFG_THRESHOLDS) {
        StaticJsonDocument<256> doc;
        DeserializationError err = deserializeJson(doc, message);
        if (!err) {
            const char* reqId = doc["req_id"] | "";
            tempMin = doc["temp_min"] | 20.0f;
            tempMax = doc["temp_max"] | 35.0f;
            waterMin = doc["water_min"] | 20.0f;
            waterMax = doc["water_max"] | 85.0f;

            prefs.begin("smartpool", false);
            prefs.putFloat("temp_min", tempMin);
            prefs.putFloat("temp_max", tempMax);
            prefs.putFloat("water_min", waterMin);
            prefs.putFloat("water_max", waterMax);
            prefs.end();
            Serial.printf("Đã cập nhật ngưỡng: Temp [%.1f - %.1f], Water [%.1f - %.1f]\n",
                          tempMin, tempMax, waterMin, waterMax);

            // Gửi ACK về cho App để hoàn tất Double Check
            publishAck(reqId, "UPDATE_THRESHOLDS", "SUCCESS", "Đã lưu ngưỡng an toàn vào NVS");
        }
    }
    // 2. Nhận cấu hình WiFi (Mechanism 2)
    else if (String(topic) == TOPIC_CFG_WIFI) {
        StaticJsonDocument<256> doc;
        if (!deserializeJson(doc, message)) {
            const char* reqId = doc["req_id"] | "";
            const char* ssid = doc["ssid"];
            const char* pass = doc["password"];
            prefs.begin("smartpool", false);
            prefs.putString("wifi_ssid", ssid);
            prefs.putString("wifi_pass", pass);
            prefs.end();
            Serial.println("Đã lưu WiFi mới. Chuẩn bị kết nối...");

            // Gửi ACK về cho App
            publishAck(reqId, "UPDATE_WIFI", "SUCCESS", "Đã lưu thông tin WiFi vào NVS");
            // WiFi.begin(ssid, pass);
        }
    }
    // 3. Nhận lệnh quét WiFi
    else if (String(topic) == TOPIC_CMD_WIFI_SCAN) {
        handleWifiScan();
    }
    // 4. Điều khiển Bơm Cấp nước (Mechanism 1: Thực thi Relay xong gửi publishStatus)
    else if (String(topic) == TOPIC_CMD_PUMP_IN) {
        if (message == "1") {
            pumpInState = true;
            digitalWrite(PIN_RELAY_PUMP_IN, HIGH);
        } else {
            pumpInState = false;
            digitalWrite(PIN_RELAY_PUMP_IN, LOW);
        }
        publishStatus(); // App nhận status này để xác nhận relay đã đóng/ngắt thành công
    }
    // 5. Điều khiển Bơm Thoát nước (Mechanism 1)
    else if (String(topic) == TOPIC_CMD_PUMP_OUT) {
        if (message == "1") {
            pumpOutState = true;
            digitalWrite(PIN_RELAY_PUMP_OUT, HIGH);
        } else {
            pumpOutState = false;
            digitalWrite(PIN_RELAY_PUMP_OUT, LOW);
        }
        publishStatus(); // App nhận status này để xác nhận relay đã đóng/ngắt thành công
    }
}

// ----------------- KẾT NỐI LẠI MQTT -----------------
void reconnectMQTT() {
    while (!client.connected()) {
        Serial.print("Đang kết nối HiveMQ...");
        String clientId = "ESP32S3-Pool-" + String(random(0xffff), HEX);

        // Đặt Last Will: nếu ESP32 mất kết nối đột ngột, Broker tự gửi status online = 0
        const char* willTopic = TOPIC_STATUS;
        const char* willPayload = "{\"online\":0,\"bom_vao_control\":0,\"bom_ra_control\":0}";

        if (client.connect(clientId.c_str(), mqtt_user, mqtt_pass, willTopic, 1, true, willPayload)) {
            Serial.println("ĐÃ KẾT NỐI!");

            // Đăng ký nhận tin các Topics điều khiển & cấu hình
            client.subscribe(TOPIC_CFG_THRESHOLDS);
            client.subscribe(TOPIC_CFG_WIFI);
            client.subscribe(TOPIC_CMD_WIFI_SCAN);
            client.subscribe(TOPIC_CMD_PUMP_IN);
            client.subscribe(TOPIC_CMD_PUMP_OUT);

            // Báo ESP32 online
            publishStatus();
        } else {
            Serial.printf("Lỗi kết nối MQTT, rc=%d. Thử lại sau 5s...\n", client.state());
            delay(5000);
        }
    }
}

void setup() {
    Serial.begin(115200);
    pinMode(PIN_RELAY_PUMP_IN, OUTPUT);
    pinMode(PIN_RELAY_PUMP_OUT, OUTPUT);
    digitalWrite(PIN_RELAY_PUMP_IN, LOW);
    digitalWrite(PIN_RELAY_PUMP_OUT, LOW);

    // Bỏ qua xác thực chứng chỉ CA nếu cần test nhanh qua TLS
    espClient.setInsecure();

    client.setServer(mqtt_server, mqtt_port);
    client.setCallback(callback);
}

unsigned long lastTelemetryTime = 0;

void loop() {
    if (!client.connected()) {
        reconnectMQTT();
    }
    client.loop();

    // Định kỳ gửi dữ liệu cảm biến mỗi 3 giây
    if (millis() - lastTelemetryTime >= 3000) {
        lastTelemetryTime = millis();
        
        // Đọc giá trị thật từ cảm biến DS18B20 và cảm biến mực nước ở đây
        float currentTemp = 28.5; // Ví dụ
        int currentWater = 1;     // 1: Bình thường, 0: Cạn, 2: Tràn

        publishTelemetry(currentTemp, currentWater);
    }
}
```

---

## 5. CÁC ĐIỂM QUAN TRỌNG CẦN LƯU Ý KHI LẬP TRÌNH ESP32

1. **Khóa bảo vệ bơm liên động (Interlock):**
   - Tuyệt đối không cho phép bật cùng lúc 2 bơm (Bơm cấp & Bơm thoát) để tránh xung đột hệ thống đường ống và cháy bơm.
2. **Lưu cấu hình vào NVS (Non-Volatile Storage):**
   - Khi nhận được gói tin từ `smartpool/config/thresholds` hoặc `smartpool/config/wifi`, hãy gọi hàm lưu vào `Preferences` để khi mất điện khởi động lại ESP32 vẫn giữ nguyên cấu hình.
3. **Cơ chế Last Will (LWT):**
   - Luôn đăng ký LWT vào topic `smartpool/status` với payload `{"online": 0, ...}` khi gọi `client.connect(...)`. Nhờ đó khi ESP32 bị rút điện hoặc rớt mạng, App Android sẽ lập tức nhận biết thiết bị đã Offline.
4. **Cờ Retained Message:**
   - Khi ESP32 gửi lên `smartpool/status`, hãy đặt `retained = true` để App vừa mở lên là đọc được ngay trạng thái của các bơm.
   - Khi gửi `smartpool/telemetry`, đặt `retained = false` để tránh lưu dữ liệu nhiệt độ cũ trên Broker.
