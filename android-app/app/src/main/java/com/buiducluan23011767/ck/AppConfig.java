package com.buiducluan23011767.ck;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Quản lý toàn bộ cấu hình hệ thống Smart Pool:
 * - Thông tin thiết bị (Device ID, Device Name, Location)
 * - Ngưỡng cảnh báo (Nhiệt độ min/max, Mực nước min/max)
 * - Cấu hình WiFi (SSID, Password)
 * - Cấu hình MQTT Broker (HiveMQ Cloud) & Các Topic chia nhỏ
 * - Cấu hình HTTP REST API & Polling
 */
public class AppConfig {

    private static final String TAG = "AppConfig";
    private static final String PREF_NAME = "smartpool_settings";

    // Keys
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_DEVICE_LOCATION = "device_location";

    private static final String KEY_TEMP_MIN = "temp_min";
    private static final String KEY_TEMP_MAX = "temp_max";
    private static final String KEY_WATER_MIN = "water_min";
    private static final String KEY_WATER_MAX = "water_max";

    private static final String KEY_WIFI_SSID = "wifi_ssid";
    private static final String KEY_WIFI_PASS = "wifi_password";

    private static final String KEY_MQTT_BROKER = "mqtt_broker";
    private static final String KEY_MQTT_PORT = "mqtt_port";
    private static final String KEY_MQTT_USER = "mqtt_username";
    private static final String KEY_MQTT_PASS = "mqtt_password";
    private static final String KEY_MQTT_CLIENT_ID = "mqtt_client_id";

    // Topics tổng và các topic chia nhỏ
    private static final String KEY_TOPIC_CONFIG = "mqtt_topic_config";
    private static final String KEY_TOPIC_CONFIG_THRESHOLDS = "mqtt_topic_cfg_thresh";
    private static final String KEY_TOPIC_CONFIG_WIFI = "mqtt_topic_cfg_wifi";
    private static final String KEY_TOPIC_CONFIG_DEVICE = "mqtt_topic_cfg_dev";
    private static final String KEY_TOPIC_CONFIG_HTTP = "mqtt_topic_cfg_http";

    private static final String KEY_TOPIC_CMD_WIFI_SCAN = "mqtt_topic_cmd_wifi_scan";
    private static final String KEY_TOPIC_WIFI_SCAN_RESULT = "mqtt_topic_wifi_scan_res";
    private static final String KEY_TOPIC_ACK = "mqtt_topic_ack";

    private static final String KEY_TOPIC_TELEMETRY = "mqtt_topic_telemetry";
    private static final String KEY_TOPIC_STATUS = "mqtt_topic_status";
    private static final String KEY_TOPIC_CMD_PUMP_IN = "mqtt_topic_cmd_pump_in";
    private static final String KEY_TOPIC_CMD_PUMP_OUT = "mqtt_topic_cmd_pump_out";

    private static final String KEY_HTTP_API_URL = "http_api_url";
    private static final String KEY_HTTP_POLL_INTERVAL = "http_poll_interval";
    private static final String KEY_CONTROL_MODE = "control_mode";
    private static final String KEY_INTERLOCK_ENABLED = "interlock_enabled";

    // Default values
    public static final boolean DEFAULT_INTERLOCK_ENABLED = true;
    public static final String DEFAULT_DEVICE_ID = "ESP32S3_SMARTPOOL_01";
    public static final String DEFAULT_DEVICE_NAME = "Hệ thống Bể bơi Thông minh";
    public static final String DEFAULT_DEVICE_LOCATION = "Bể bơi sân vườn";

    public static final float DEFAULT_TEMP_MIN = 20.0f;
    public static final float DEFAULT_TEMP_MAX = 35.0f;
    public static final float DEFAULT_WATER_MIN = 20.0f;
    public static final float DEFAULT_WATER_MAX = 85.0f;

    public static final String DEFAULT_WIFI_SSID = "";
    public static final String DEFAULT_WIFI_PASS = "";

    // Cố định dùng HiveMQ Cloud qua TLS
    public static final String DEFAULT_MQTT_BROKER =
            "ssl://24660077a88741378be5ffe7f979f2f3.s1.eu.hivemq.cloud:8883";
    public static final int DEFAULT_MQTT_PORT = 8883;

    // Các topic chia nhỏ chuyên biệt
    public static final String DEFAULT_TOPIC_CONFIG = "smartpool/config";
    public static final String DEFAULT_TOPIC_CONFIG_THRESHOLDS = "smartpool/config/thresholds";
    public static final String DEFAULT_TOPIC_CONFIG_WIFI = "smartpool/config/wifi";
    public static final String DEFAULT_TOPIC_CONFIG_DEVICE = "smartpool/config/device";
    public static final String DEFAULT_TOPIC_CONFIG_HTTP = "smartpool/config/http";

    public static final String DEFAULT_TOPIC_CMD_WIFI_SCAN = "smartpool/cmd/wifi_scan";
    public static final String DEFAULT_TOPIC_WIFI_SCAN_RESULT = "smartpool/wifi/scan_result";
    public static final String DEFAULT_TOPIC_ACK = "smartpool/ack";

    public static final String DEFAULT_TOPIC_TELEMETRY = "smartpool/telemetry";
    public static final String DEFAULT_TOPIC_STATUS = "smartpool/status";
    public static final String DEFAULT_TOPIC_CMD_PUMP_IN = "smartpool/cmd/bom_vao";
    public static final String DEFAULT_TOPIC_CMD_PUMP_OUT = "smartpool/cmd/bom_ra";

    public static final String DEFAULT_HTTP_API_URL = "http://192.168.10.214/pool_api/pool_api.php";
    public static final int DEFAULT_HTTP_POLL_INTERVAL = 3;
    public static final String DEFAULT_CONTROL_MODE = "mqtt";

    private static AppConfig instance;
    private final SharedPreferences prefs;

    private AppConfig(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AppConfig getInstance(Context context) {
        if (instance == null) {
            instance = new AppConfig(context);
        }
        return instance;
    }

    // ================= DEVICE INFO =================
    public String getDeviceId() {
        return prefs.getString(KEY_DEVICE_ID, DEFAULT_DEVICE_ID);
    }

    public void setDeviceId(String value) {
        prefs.edit().putString(KEY_DEVICE_ID, value).apply();
    }

    public String getDeviceName() {
        return prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME);
    }

    public void setDeviceName(String value) {
        prefs.edit().putString(KEY_DEVICE_NAME, value).apply();
    }

    public String getDeviceLocation() {
        return prefs.getString(KEY_DEVICE_LOCATION, DEFAULT_DEVICE_LOCATION);
    }

    public void setDeviceLocation(String value) {
        prefs.edit().putString(KEY_DEVICE_LOCATION, value).apply();
    }

    // ================= THRESHOLDS =================
    public float getTempMin() {
        return prefs.getFloat(KEY_TEMP_MIN, DEFAULT_TEMP_MIN);
    }

    public void setTempMin(float value) {
        prefs.edit().putFloat(KEY_TEMP_MIN, value).apply();
    }

    public float getTempMax() {
        return prefs.getFloat(KEY_TEMP_MAX, DEFAULT_TEMP_MAX);
    }

    public void setTempMax(float value) {
        prefs.edit().putFloat(KEY_TEMP_MAX, value).apply();
    }

    public float getWaterLevelMin() {
        return prefs.getFloat(KEY_WATER_MIN, DEFAULT_WATER_MIN);
    }

    public void setWaterLevelMin(float value) {
        prefs.edit().putFloat(KEY_WATER_MIN, value).apply();
    }

    public float getWaterLevelMax() {
        return prefs.getFloat(KEY_WATER_MAX, DEFAULT_WATER_MAX);
    }

    public void setWaterLevelMax(float value) {
        prefs.edit().putFloat(KEY_WATER_MAX, value).apply();
    }

    // ================= INTERLOCK (KHÓA LIÊN ĐỘNG 2 BƠM) =================
    public boolean isInterlockEnabled() {
        return prefs.getBoolean(KEY_INTERLOCK_ENABLED, DEFAULT_INTERLOCK_ENABLED);
    }

    public void setInterlockEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_INTERLOCK_ENABLED, value).apply();
    }

    // ================= WIFI =================
    public String getWifiSsid() {
        return prefs.getString(KEY_WIFI_SSID, DEFAULT_WIFI_SSID);
    }

    public void setWifiSsid(String value) {
        prefs.edit().putString(KEY_WIFI_SSID, value).apply();
    }

    public String getWifiPassword() {
        return prefs.getString(KEY_WIFI_PASS, DEFAULT_WIFI_PASS);
    }

    public void setWifiPassword(String value) {
        prefs.edit().putString(KEY_WIFI_PASS, value).apply();
    }

    // ================= MQTT (BROKER & TÀI KHOẢN CHO PHÉP NGƯỜI DÙNG THAY ĐỔI) =================
    public String getMqttBroker() {
        return prefs.getString(KEY_MQTT_BROKER, DEFAULT_MQTT_BROKER);
    }

    public void setMqttBroker(String value) {
        prefs.edit().putString(KEY_MQTT_BROKER, value).apply();
    }

    public int getMqttPort() {
        return prefs.getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT);
    }

    public void setMqttPort(int value) {
        prefs.edit().putInt(KEY_MQTT_PORT, value).apply();
    }

    public String getMqttUsername() {
        String fallback = BuildConfig.HIVEMQ_USERNAME != null ? BuildConfig.HIVEMQ_USERNAME : "";
        return prefs.getString(KEY_MQTT_USER, fallback);
    }

    public void setMqttUsername(String value) {
        prefs.edit().putString(KEY_MQTT_USER, value).apply();
    }

    public String getMqttPassword() {
        String fallback = BuildConfig.HIVEMQ_PASSWORD != null ? BuildConfig.HIVEMQ_PASSWORD : "";
        return prefs.getString(KEY_MQTT_PASS, fallback);
    }

    public void setMqttPassword(String value) {
        prefs.edit().putString(KEY_MQTT_PASS, value).apply();
    }

    public String getMqttClientId() {
        return "SmartPool-" + getDeviceId();
    }

    // ================= TOPICS CHIA NHỎ (DO APP TỰ ĐỘNG ĐIỀU PHỐI HOÀN TOÀN) =================
    public String getTopicConfig() {
        return DEFAULT_TOPIC_CONFIG;
    }

    public String getTopicConfigThresholds() {
        return DEFAULT_TOPIC_CONFIG_THRESHOLDS;
    }

    public String getTopicConfigWifi() {
        return DEFAULT_TOPIC_CONFIG_WIFI;
    }

    public String getTopicConfigDevice() {
        return DEFAULT_TOPIC_CONFIG_DEVICE;
    }

    public String getTopicConfigHttp() {
        return DEFAULT_TOPIC_CONFIG_HTTP;
    }

    public String getTopicCmdWifiScan() {
        return DEFAULT_TOPIC_CMD_WIFI_SCAN;
    }

    public String getTopicWifiScanResult() {
        return DEFAULT_TOPIC_WIFI_SCAN_RESULT;
    }

    public String getTopicAck() {
        return DEFAULT_TOPIC_ACK;
    }

    public String getTopicTelemetry() {
        return DEFAULT_TOPIC_TELEMETRY;
    }

    public String getTopicStatus() {
        return DEFAULT_TOPIC_STATUS;
    }

    public String getTopicCmdPumpIn() {
        return DEFAULT_TOPIC_CMD_PUMP_IN;
    }

    public String getTopicCmdPumpOut() {
        return DEFAULT_TOPIC_CMD_PUMP_OUT;
    }

    // ================= HTTP API =================
    public String getHttpApiUrl() {
        return prefs.getString(KEY_HTTP_API_URL, DEFAULT_HTTP_API_URL);
    }

    public void setHttpApiUrl(String value) {
        prefs.edit().putString(KEY_HTTP_API_URL, value).apply();
    }

    public int getHttpPollIntervalSec() {
        return prefs.getInt(KEY_HTTP_POLL_INTERVAL, DEFAULT_HTTP_POLL_INTERVAL);
    }

    public void setHttpPollIntervalSec(int value) {
        prefs.edit().putInt(KEY_HTTP_POLL_INTERVAL, value).apply();
    }

    public String getControlMode() {
        String fallback = BuildConfig.CONTROL_MODE;
        return prefs.getString(KEY_CONTROL_MODE, fallback != null ? fallback : DEFAULT_CONTROL_MODE);
    }

    public void setControlMode(String value) {
        prefs.edit().putString(KEY_CONTROL_MODE, value).apply();
    }

    // ================= JSON BUILDERS (CHIA NHỎ TỪNG PHẦN) =================

    /**
     * JSON Cấu hình ngưỡng cảm biến (Nhiệt độ & Mực nước)
     * Topic đích: smartpool/config/thresholds
     */
    public JSONObject buildThresholdsJson() {
        return buildThresholdsJson(null);
    }

    public JSONObject buildThresholdsJson(String reqId) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("action", "UPDATE_THRESHOLDS");
            if (reqId == null || reqId.trim().isEmpty()) {
                reqId = "req_thresh_" + (System.currentTimeMillis() / 1000L);
            }
            obj.put("req_id", reqId);
            obj.put("timestamp", System.currentTimeMillis() / 1000L);
            obj.put("temp_min", getTempMin());
            obj.put("temp_max", getTempMax());
            obj.put("water_min", getWaterLevelMin());
            obj.put("water_max", getWaterLevelMax());
            obj.put("interlock", isInterlockEnabled() ? 1 : 0);
        } catch (JSONException e) {
            Log.e(TAG, "Lỗi tạo JSON thresholds", e);
        }
        return obj;
    }

    /**
     * JSON Cấu hình WiFi cho ESP32
     * Topic đích: smartpool/config/wifi
     */
    public JSONObject buildWifiJson() {
        return buildWifiJson(null);
    }

    public JSONObject buildWifiJson(String reqId) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("action", "UPDATE_WIFI");
            if (reqId == null || reqId.trim().isEmpty()) {
                reqId = "req_wifi_" + (System.currentTimeMillis() / 1000L);
            }
            obj.put("req_id", reqId);
            obj.put("timestamp", System.currentTimeMillis() / 1000L);
            obj.put("ssid", getWifiSsid());
            obj.put("password", getWifiPassword());
        } catch (JSONException e) {
            Log.e(TAG, "Lỗi tạo JSON WiFi", e);
        }
        return obj;
    }

    /**
     * JSON Cấu hình thông tin thiết bị
     * Topic đích: smartpool/config/device
     */
    public JSONObject buildDeviceJson() {
        return buildDeviceJson(null);
    }

    public JSONObject buildDeviceJson(String reqId) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("action", "UPDATE_DEVICE");
            if (reqId == null || reqId.trim().isEmpty()) {
                reqId = "req_dev_" + (System.currentTimeMillis() / 1000L);
            }
            obj.put("req_id", reqId);
            obj.put("timestamp", System.currentTimeMillis() / 1000L);
            obj.put("id", getDeviceId());
            obj.put("name", getDeviceName());
            obj.put("location", getDeviceLocation());
        } catch (JSONException e) {
            Log.e(TAG, "Lỗi tạo JSON Device", e);
        }
        return obj;
    }

    /**
     * JSON Cấu hình HTTP & Hệ thống
     * Topic đích: smartpool/config/http
     */
    public JSONObject buildHttpJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("action", "UPDATE_HTTP");
            obj.put("timestamp", System.currentTimeMillis() / 1000L);
            obj.put("api_url", getHttpApiUrl());
            obj.put("poll_interval_s", getHttpPollIntervalSec());
            obj.put("control_mode", getControlMode());
        } catch (JSONException e) {
            Log.e(TAG, "Lỗi tạo JSON HTTP", e);
        }
        return obj;
    }

    /**
     * Lệnh kích hoạt ESP32 quét WiFi
     * Topic đích: smartpool/cmd/wifi_scan
     */
    public JSONObject buildWifiScanCommandJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("cmd", "SCAN_WIFI");
            obj.put("timestamp", System.currentTimeMillis() / 1000L);
        } catch (JSONException e) {
            Log.e(TAG, "Lỗi tạo JSON WiFi scan cmd", e);
        }
        return obj;
    }

    /**
     * JSON Tổng hợp toàn bộ cấu hình
     * Topic đích: smartpool/config
     */
    public JSONObject toJsonObject() {
        return toJsonObject(null);
    }

    public JSONObject toJsonObject(String reqId) {
        JSONObject root = new JSONObject();
        try {
            root.put("action", "CONFIG_UPDATE_ALL");
            if (reqId == null || reqId.trim().isEmpty()) {
                reqId = "req_all_" + (System.currentTimeMillis() / 1000L);
            }
            root.put("req_id", reqId);
            root.put("timestamp", System.currentTimeMillis() / 1000L);

            // Device
            JSONObject dev = new JSONObject();
            dev.put("id", getDeviceId());
            dev.put("name", getDeviceName());
            dev.put("location", getDeviceLocation());
            root.put("device", dev);

            // WiFi
            JSONObject wifi = new JSONObject();
            wifi.put("ssid", getWifiSsid());
            wifi.put("password", getWifiPassword());
            root.put("wifi", wifi);

            // Thresholds
            JSONObject thresh = new JSONObject();
            thresh.put("temp_min", getTempMin());
            thresh.put("temp_max", getTempMax());
            thresh.put("water_min", getWaterLevelMin());
            thresh.put("water_max", getWaterLevelMax());
            thresh.put("interlock", isInterlockEnabled() ? 1 : 0);
            root.put("thresholds", thresh);

            // MQTT Info
            JSONObject mqtt = new JSONObject();
            mqtt.put("broker", getMqttBroker());
            mqtt.put("port", getMqttPort());
            mqtt.put("topic_config_thresholds", getTopicConfigThresholds());
            mqtt.put("topic_config_wifi", getTopicConfigWifi());
            mqtt.put("topic_config_device", getTopicConfigDevice());
            mqtt.put("topic_telemetry", getTopicTelemetry());
            mqtt.put("topic_status", getTopicStatus());
            mqtt.put("topic_cmd_pump_in", getTopicCmdPumpIn());
            mqtt.put("topic_cmd_pump_out", getTopicCmdPumpOut());
            root.put("mqtt", mqtt);

            // HTTP Info
            JSONObject http = new JSONObject();
            http.put("api_url", getHttpApiUrl());
            http.put("poll_interval_s", getHttpPollIntervalSec());
            root.put("http", http);

            root.put("control_mode", getControlMode());

        } catch (JSONException e) {
            Log.e(TAG, "Lỗi tạo JSONObject", e);
        }
        return root;
    }

    public String toJsonString(boolean pretty) {
        return toJsonString(pretty, null);
    }

    public String toJsonString(boolean pretty, String reqId) {
        JSONObject obj = toJsonObject(reqId);
        try {
            return pretty ? obj.toString(2) : obj.toString();
        } catch (JSONException e) {
            return obj.toString();
        }
    }

    /**
     * Khôi phục toàn bộ cấu hình về mặc định ban đầu.
     */
    public void resetToDefaults() {
        prefs.edit().clear().apply();
    }
}
