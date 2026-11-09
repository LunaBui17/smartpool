#include <WiFi.h>
#include <WiFiClient.h>
#include <HTTPClient.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <math.h>

// =====================================================
// 1. WIFI
// =====================================================

const char* WIFI_SSID = "CMCC-nng5-5G";
const char* WIFI_PASSWORD = "123456788";

// =====================================================
// 2. MAY TINH CHAY XAMPP VA MQTT BROKER
// =====================================================

// Phải là IPv4 thật của máy tính chạy XAMPP/Mosquitto.
// ESP32 và máy tính cần kết nối cùng mạng LAN.
const char* PC_IP = "192.168.10.214";

const uint16_t MQTT_PORT = 1767;

// Đường dẫn API PHP.
const char* API_PATH = "/pool_api/pool_api.php";

// =====================================================
// 3. MQTT TOPICS
// PHẢI KHỚP VỚI MainActivity.java
// =====================================================

// Bơm từ bể phụ vào bể chính.
const char* TOPIC_CMD_BOM_VAO =
    "smartpool/cmd/bom_vao";

// Bơm từ bể chính ra ngoài.
const char* TOPIC_CMD_BOM_RA =
    "smartpool/cmd/bom_ra";

// Trạng thái ESP32 và hai máy bơm.
const char* TOPIC_STATUS =
    "smartpool/status";

// Dữ liệu nhiệt độ DS18B20.
const char* TOPIC_TELEMETRY =
    "smartpool/telemetry";

// =====================================================
// 4. KHAI BÁO CHÂN PHẦN CỨNG
// =====================================================

// DS18B20 nối DATA vào GPIO15.
const int DS18B20_PIN = 15;

// Relay 1: Bơm cấp từ bể phụ vào bể chính.
const int RELAY_BOM_VAO_PIN = 5;

// Relay 2: Bơm thoát từ bể chính ra ngoài.
const int RELAY_BOM_RA_PIN = 6;

// Phần lớn module relay kích mức LOW.
// Nếu relay của bạn kích mức HIGH, đổi thành false.
const bool RELAY_ACTIVE_LOW = true;

// =====================================================
// 5. DS18B20
// =====================================================

OneWire oneWire(DS18B20_PIN);
DallasTemperature temperatureSensor(&oneWire);

// Giá trị nhiệt độ hiện tại.
float waterTemperature = NAN;

// Trạng thái cảm biến.
bool sensorAvailable = false;

// =====================================================
// 6. WIFI + MQTT
// =====================================================

WiFiClient mqttNetworkClient;
PubSubClient mqttClient(mqttNetworkClient);

// =====================================================
// 7. CHU KỲ HOẠT ĐỘNG
// =====================================================

const unsigned long TELEMETRY_INTERVAL_MS = 2000UL;
const unsigned long MQTT_RETRY_INTERVAL_MS = 5000UL;
const unsigned long WIFI_RETRY_INTERVAL_MS = 5000UL;

// Giới hạn thời gian chạy mỗi bơm.
// Mặc định 60 giây để test.
// Có thể thay đổi sau khi kiểm tra hệ thống.
const unsigned long MAX_PUMP_RUNTIME_MS = 60000UL;

unsigned long lastTelemetryMs = 0;
unsigned long lastMqttRetryMs = 0;
unsigned long lastWifiRetryMs = 0;

unsigned long pumpVaoStartMs = 0;
unsigned long pumpRaStartMs = 0;

// =====================================================
// 8. TRẠNG THÁI HỆ THỐNG
// =====================================================

bool pumpVaoOn = false;
bool pumpRaOn = false;

bool mqttWasConnected = false;

// =====================================================
// 9. ĐIỀU KHIỂN RELAY
// =====================================================

void writeRelay(int pin, bool turnOn) {

  if (RELAY_ACTIVE_LOW) {
    digitalWrite(pin, turnOn ? LOW : HIGH);
  } else {
    digitalWrite(pin, turnOn ? HIGH : LOW);
  }
}

// Cập nhật hai relay theo trạng thái hiện tại.
void applyOutputs() {

  // Khóa liên động: tuyệt đối không bật đồng thời hai bơm.
  if (pumpVaoOn && pumpRaOn) {
    Serial.println("LOI: Hai bom cung bat. Tat ca bom!");

    pumpVaoOn = false;
    pumpRaOn = false;
  }

  writeRelay(RELAY_BOM_VAO_PIN, pumpVaoOn);
  writeRelay(RELAY_BOM_RA_PIN, pumpRaOn);

  Serial.printf(
    "Bom vao=%s | Bom ra=%s\n",
    pumpVaoOn ? "ON" : "OFF",
    pumpRaOn ? "ON" : "OFF"
  );
}

// Tắt cả hai bơm.
void stopAllPumps(const char* reason) {

  bool hadPumpRunning = pumpVaoOn || pumpRaOn;

  pumpVaoOn = false;
  pumpRaOn = false;

  applyOutputs();

  if (hadPumpRunning) {
    Serial.print("DA TAT BOM: ");
    Serial.println(reason);
  }

  if (mqttClient.connected()) {
    publishStatus();
  }
}

// =====================================================
// 10. GỬI TRẠNG THÁI QUA MQTT
// =====================================================

void publishStatus() {

  if (!mqttClient.connected()) {
    return;
  }

  StaticJsonDocument<192> doc;

  doc["online"] = 1;
  doc["bom_vao_control"] = pumpVaoOn ? 1 : 0;
  doc["bom_ra_control"] = pumpRaOn ? 1 : 0;

  char buffer[192];

  size_t length = serializeJson(
    doc,
    buffer,
    sizeof(buffer)
  );

  bool sent = mqttClient.publish(
    TOPIC_STATUS,
    reinterpret_cast<const uint8_t*>(buffer),
    length,
    true
  );

  Serial.printf(
    "MQTT STATUS: %s\n",
    sent ? "OK" : "FAILED"
  );
}

// =====================================================
// 11. XỬ LÝ LỆNH BẬT/TẮT BƠM
// Payload: 1 = ON, 0 = OFF
// =====================================================

void controlPump(bool isPumpVao, bool turnOn) {

  if (isPumpVao) {

    // Bật bơm cấp.
    if (turnOn) {

      // Không cho bật nếu bơm thoát đang chạy.
      if (pumpRaOn) {
        Serial.println(
          "TU CHOI: Bom ra dang chay, khong the bat bom vao!"
        );

        publishStatus();
        return;
      }

      if (!pumpVaoOn) {
        pumpVaoOn = true;
        pumpVaoStartMs = millis();
      }

    } else {

      pumpVaoOn = false;
    }

  } else {

    // Bật bơm thoát.
    if (turnOn) {

      // Không cho bật nếu bơm cấp đang chạy.
      if (pumpVaoOn) {
        Serial.println(
          "TU CHOI: Bom vao dang chay, khong the bat bom ra!"
        );

        publishStatus();
        return;
      }

      if (!pumpRaOn) {
        pumpRaOn = true;
        pumpRaStartMs = millis();
      }

    } else {

      pumpRaOn = false;
    }
  }

  applyOutputs();
  publishStatus();
}

// =====================================================
// 12. XỬ LÝ LỆNH MQTT
// =====================================================

void mqttCallback(
  char* topic,
  byte* payload,
  unsigned int length
) {

  String command;
  command.reserve(length);

  for (unsigned int i = 0; i < length; i++) {
    command += (char)payload[i];
  }

  command.trim();
  command.toUpperCase();

  Serial.println("--------------------------------");
  Serial.print("MQTT topic: ");
  Serial.println(topic);

  Serial.print("MQTT payload: ");
  Serial.println(command);

  bool turnOn;

  if (command == "1" || command == "ON") {
    turnOn = true;

  } else if (command == "0" || command == "OFF") {
    turnOn = false;

  } else {
    Serial.println("Lenh khong hop le.");
    return;
  }

  if (strcmp(topic, TOPIC_CMD_BOM_VAO) == 0) {

    controlPump(true, turnOn);

  } else if (strcmp(topic, TOPIC_CMD_BOM_RA) == 0) {

    controlPump(false, turnOn);

  } else {

    Serial.println("Topic khong duoc ho tro.");
  }
}

// =====================================================
// 13. KẾT NỐI MQTT BROKER
// =====================================================

void connectMqtt() {

  if (WiFi.status() != WL_CONNECTED) {
    return;
  }

  if (mqttClient.connected()) {
    return;
  }

  String clientId =
      "SmartPoolESP32-" +
      String(
        (uint32_t)(ESP.getEfuseMac() & 0xFFFFFFFF),
        HEX
      );

  // Last Will: báo ESP32 offline nếu mất kết nối đột ngột.
  const char* willPayload =
      "{\"online\":0,\"bom_vao_control\":0,\"bom_ra_control\":0}";

  Serial.print("Dang ket noi MQTT Broker... ");

  bool connected = mqttClient.connect(
    clientId.c_str(),
    TOPIC_STATUS,
    1,
    true,
    willPayload
  );

  if (connected) {

    Serial.println("THANH CONG");

    // An toàn: khi MQTT kết nối lại, tắt hai bơm trước.
    pumpVaoOn = false;
    pumpRaOn = false;
    applyOutputs();

    bool subVao = mqttClient.subscribe(
      TOPIC_CMD_BOM_VAO,
      1
    );

    bool subRa = mqttClient.subscribe(
      TOPIC_CMD_BOM_RA,
      1
    );

    Serial.printf(
      "Subscribe bom vao: %s\n",
      subVao ? "OK" : "FAILED"
    );

    Serial.printf(
      "Subscribe bom ra: %s\n",
      subRa ? "OK" : "FAILED"
    );

    mqttWasConnected = true;

    publishStatus();

  } else {

    Serial.print("THAT BAI, state = ");
    Serial.println(mqttClient.state());
  }
}

// =====================================================
// 14. ĐỌC NHIỆT ĐỘ DS18B20
// =====================================================

bool readTemperature(float &temperature) {

  // Yêu cầu DS18B20 thực hiện phép đo.
  temperatureSensor.requestTemperatures();

  // Đọc cảm biến đầu tiên trên bus OneWire.
  float value = temperatureSensor.getTempCByIndex(0);

  // Kiểm tra lỗi mất kết nối cảm biến.
  if (value == DEVICE_DISCONNECTED_C ||
      isnan(value) ||
      value < -55.0 ||
      value > 125.0) {

    Serial.println(
      "LOI: Khong doc duoc DS18B20. Kiem tra day noi!"
    );

    sensorAvailable = false;
    return false;
  }

  temperature = value;
  sensorAvailable = true;

  Serial.printf(
    "Nhiet do be chinh: %.2f C\n",
    temperature
  );

  return true;
}

// =====================================================
// 15. GỬI DỮ LIỆU NHIỆT ĐỘ QUA MQTT
// =====================================================

void publishTelemetryMqtt(float temperature) {

  if (!mqttClient.connected()) {
    return;
  }

  StaticJsonDocument<192> doc;

  doc["nhiet_do"] = temperature;
  doc["bom_vao_control"] = pumpVaoOn ? 1 : 0;
  doc["bom_ra_control"] = pumpRaOn ? 1 : 0;

  char buffer[192];

  size_t length = serializeJson(
    doc,
    buffer,
    sizeof(buffer)
  );

  bool sent = mqttClient.publish(
    TOPIC_TELEMETRY,
    reinterpret_cast<const uint8_t*>(buffer),
    length,
    false
  );

  Serial.printf(
    "Publish telemetry MQTT: %s\n",
    sent ? "OK" : "FAILED"
  );
}

// =====================================================
// 16. GỬI DỮ LIỆU QUA HTTP ĐẾN XAMPP / PHP
// =====================================================

void postTelemetryHttp(float temperature) {

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("HTTP: Wi-Fi chua ket noi.");
    return;
  }

  StaticJsonDocument<192> doc;

  doc["nhiet_do"] = temperature;
  doc["bom_vao_control"] = pumpVaoOn ? 1 : 0;
  doc["bom_ra_control"] = pumpRaOn ? 1 : 0;

  String jsonPayload;
  serializeJson(doc, jsonPayload);

  // Dùng cùng PC_IP với MQTT để tránh nhầm hai địa chỉ.
  String serverUrl =
      String("http://") + PC_IP + API_PATH;

  WiFiClient httpNetworkClient;
  HTTPClient http;

  http.setTimeout(5000);

  Serial.print("HTTP URL: ");
  Serial.println(serverUrl);

  if (!http.begin(httpNetworkClient, serverUrl)) {

    Serial.println("HTTP: Khong khoi tao duoc ket noi.");
    return;
  }

  http.addHeader(
    "Content-Type",
    "application/json"
  );

  int httpCode = http.POST(jsonPayload);

  Serial.printf(
    "HTTP POST code: %d\n",
    httpCode
  );

  if (httpCode >= 200 && httpCode < 300) {

    String response = http.getString();

    Serial.println("PHP response:");
    Serial.println(response);

    Serial.println("Gui du lieu HTTP thanh cong.");

  } else {

    Serial.print("HTTP error: ");
    Serial.println(http.errorToString(httpCode));
  }

  http.end();
}

// =====================================================
// 17. GIỚI HẠN THỜI GIAN CHẠY MÁY BƠM
// =====================================================

void checkPumpRuntime() {

  unsigned long now = millis();

  if (pumpVaoOn &&
      now - pumpVaoStartMs >= MAX_PUMP_RUNTIME_MS) {

    Serial.println(
      "CANH BAO: Bom cap da chay qua thoi gian gioi han!"
    );

    pumpVaoOn = false;
    applyOutputs();
    publishStatus();
  }

  if (pumpRaOn &&
      now - pumpRaStartMs >= MAX_PUMP_RUNTIME_MS) {

    Serial.println(
      "CANH BAO: Bom thoat da chay qua thoi gian gioi han!"
    );

    pumpRaOn = false;
    applyOutputs();
    publishStatus();
  }
}

// =====================================================
// 18. SETUP
// =====================================================

void setup() {

  Serial.begin(115200);
  delay(500);

  Serial.println();
  Serial.println("====================================");
  Serial.println("       SMART POOL - ESP32-S3");
  Serial.println("  2 BOM + 1 CAM BIEN DS18B20");
  Serial.println("====================================");

  // Khởi tạo relay.
  pinMode(RELAY_BOM_VAO_PIN, OUTPUT);
  pinMode(RELAY_BOM_RA_PIN, OUTPUT);

  // Mặc định tắt cả hai bơm.
  pumpVaoOn = false;
  pumpRaOn = false;

  applyOutputs();

  // Khởi tạo DS18B20.
  Serial.println();
  Serial.println("Khoi tao DS18B20...");

  temperatureSensor.begin();

  temperatureSensor.setResolution(11);

  temperatureSensor.setWaitForConversion(true);

  int sensorCount = temperatureSensor.getDeviceCount();

  Serial.printf(
    "So cam bien DS18B20 tim thay: %d\n",
    sensorCount
  );

  if (sensorCount == 0) {

    Serial.println(
      "CANH BAO: Khong tim thay DS18B20 tai GPIO15!"
    );

    sensorAvailable = false;

  } else {

    float firstTemperature;

    if (readTemperature(firstTemperature)) {
      waterTemperature = firstTemperature;
    }
  }

  // Kết nối Wi-Fi.
  Serial.println();
  Serial.print("Dang ket noi Wi-Fi: ");
  Serial.println(WIFI_SSID);

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  unsigned long startTime = millis();

  while (
    WiFi.status() != WL_CONNECTED &&
    millis() - startTime < 15000UL
  ) {

    delay(500);
    Serial.print(".");
  }

  if (WiFi.status() == WL_CONNECTED) {

    Serial.println();
    Serial.println("Wi-Fi DA KET NOI!");

    Serial.print("IP ESP32-S3: ");
    Serial.println(WiFi.localIP());

    Serial.print("IP may tinh: ");
    Serial.println(PC_IP);

  } else {

    Serial.println();
    Serial.println(
      "Wi-Fi chua ket noi. ESP32 se thu lai trong loop."
    );
  }

  // Cấu hình MQTT.
  mqttClient.setServer(PC_IP, MQTT_PORT);
  mqttClient.setCallback(mqttCallback);
  mqttClient.setKeepAlive(30);
  mqttClient.setSocketTimeout(3);
  mqttClient.setBufferSize(512);

  // Kết nối MQTT lần đầu.
  connectMqtt();

  lastTelemetryMs = millis();

  Serial.println();
  Serial.println("ESP32-S3 da khoi dong.");
}

// =====================================================
// 19. LOOP
// =====================================================

void loop() {

  unsigned long now = millis();

  // ---------------------------------------------------
  // A. KIỂM TRA WIFI
  // ---------------------------------------------------

  if (WiFi.status() != WL_CONNECTED) {

    // Mất Wi-Fi thì tắt cả hai bơm.
    stopAllPumps("Mat ket noi Wi-Fi");

    if (now - lastWifiRetryMs >= WIFI_RETRY_INTERVAL_MS) {

      lastWifiRetryMs = now;

      Serial.println("Thu ket noi lai Wi-Fi...");

      WiFi.reconnect();
    }

    return;
  }

  // ---------------------------------------------------
  // B. DUY TRÌ MQTT
  // ---------------------------------------------------

  if (mqttClient.connected()) {

    mqttClient.loop();

    mqttWasConnected = true;

  } else {

    // Mất MQTT thì dừng bơm để tránh điều khiển ngoài ý muốn.
    if (mqttWasConnected || pumpVaoOn || pumpRaOn) {

      mqttWasConnected = false;

      stopAllPumps("Mat ket noi MQTT");
    }

    if (now - lastMqttRetryMs >= MQTT_RETRY_INTERVAL_MS) {

      lastMqttRetryMs = now;

      connectMqtt();
    }
  }

  // ---------------------------------------------------
  // C. KIỂM TRA THỜI GIAN CHẠY BƠM
  // ---------------------------------------------------

  checkPumpRuntime();

  // ---------------------------------------------------
  // D. ĐỌC CẢM BIẾN VÀ GỬI DỮ LIỆU
  // ---------------------------------------------------

  if (now - lastTelemetryMs >= TELEMETRY_INTERVAL_MS) {

    lastTelemetryMs = millis();

    float newTemperature;

    if (readTemperature(newTemperature)) {

      waterTemperature = newTemperature;

      // Gửi nhiệt độ thật qua MQTT.
      publishTelemetryMqtt(waterTemperature);

      // Gửi nhiệt độ thật qua HTTP đến PHP.
      postTelemetryHttp(waterTemperature);

    } else {

      Serial.println(
        "Bo qua gui telemetry vi cam bien khong hop le."
      );
    }
  }
}