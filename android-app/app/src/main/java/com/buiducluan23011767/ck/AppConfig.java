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
 * - Cấu hình MQTT Broker & Các Topic chia nhỏ
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
    private static final String KEY_WATER_CALIBRATION_ADC_START = "water_calibration_adc_start";
    private static final String KEY_WATER_CALIBRATION_CM_START = "water_calibration_cm_start";
    private static final String KEY_WATER_CALIBRATION_ADC_END = "water_calibration_adc_end";
    private static final String KEY_WATER_CALIBRATION_CM_END = "water_calibration_cm_end";
    private static final String KEY_WATER_CALIBRATION_ADC_ZERO = "water_calibration_adc_zero";
    private static final String KEY_WATER_CALIBRATION_ADC_REFERENCE = "water_calibration_adc_reference";
    private static final String KEY_WATER_CALIBRATION_HEIGHT_CM = "water_calibration_height_cm";

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
    private static final String KEY_ESP_LOCAL_IP_ADDRESS = "esp_local_ip_address";
    private static final String KEY_CHART_SENSOR = "chart_sensor";
    private static final String KEY_SETTINGS_TAB = "settings_tab";

    // Default values
    public static final boolean DEFAULT_INTERLOCK_ENABLED = true;
    public static final String DEFAULT_DEVICE_ID = "";
    public static final String DEFAULT_DEVICE_NAME = "Hệ thống Bể bơi Thông minh";
    public static final String DEFAULT_DEVICE_LOCATION = "Bể bơi sân vườn";

    public static final float DEFAULT_TEMP_MIN = 20.0f;
    public static final float DEFAULT_TEMP_MAX = 35.0f;
    // Firmware compares water thresholds with the 12-bit ADC reading, not percent.
    public static final float DEFAULT_WATER_MIN = 2000.0f;
    public static final float DEFAULT_WATER_MAX = 4095.0f;

    public static final String DEFAULT_WIFI_SSID = "";
    public static final String DEFAULT_WIFI_PASS = "";

    public static final String DEFAULT_MQTT_BROKER = "";
    public static final int DEFAULT_MQTT_PORT = 443;

    // Các topic chia nhỏ chuyên biệt
    public static final String DEFAULT_TOPIC_CONFIG = "smartpool/config";
    public static final String DEFAULT_TOPIC_CONFIG_THRESHOLDS = "smartpool/config/thresholds";
    public static final String DEFAULT_TOPIC_CONFIG_WIFI = "smartpool/config/wifi";
    public static final String DEFAULT_TOPIC_CONFIG_DEVICE = "smartpool/config/device";
    public static final String DEFAULT_TOPIC_CONFIG_HTTP = "smartpool/config/http";
    public static final String DEFAULT_TOPIC_CONFIG_AUTOMATION = "smartpool/config/automation";

    public static final String DEFAULT_TOPIC_CMD_WIFI_SCAN = "smartpool/cmd/wifi_scan";
    public static final String DEFAULT_TOPIC_WIFI_SCAN_RESULT = "smartpool/wifi/scan_result";
    public static final String DEFAULT_TOPIC_ACK = "smartpool/ack";

    public static final String DEFAULT_TOPIC_TELEMETRY = "smartpool/telemetry";
    public static final String DEFAULT_TOPIC_STATUS = "smartpool/status";
    public static final String DEFAULT_TOPIC_CMD_PUMP_IN = "smartpool/cmd/bom_vao";
    public static final String DEFAULT_TOPIC_CMD_PUMP_OUT = "smartpool/cmd/bom_ra";
    public static final String DEFAULT_TOPIC_CMD_TEMPERATURE_ENABLED = "smartpool/cmd/temperature_enabled";
    public static final String DEFAULT_TOPIC_CMD_WATER_ENABLED = "smartpool/cmd/water_level_enabled";

    public static final String DEFAULT_HTTP_API_URL = "";
    public static final int DEFAULT_HTTP_POLL_INTERVAL = 3;

    private static AppConfig instance;
    private final SharedPreferences prefs;
    private final Context context;
    private DeviceContext.Selection preferenceScope;
    private SharedPreferences scopedPrefs;

    private AppConfig(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private synchronized SharedPreferences devicePrefs() {
        DeviceContext.Selection selection = DeviceContext.current();
        if (scopedPrefs == null || preferenceScope != selection) {
            String scope = selection == null ? "unselected"
                    : selection.session.server + "\n" + selection.session.userId + "\n" + selection.id;
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String key = android.util.Base64.encodeToString(digest,
                        android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
                scopedPrefs = context.getSharedPreferences("smartpool_device_" + key, Context.MODE_PRIVATE);
                preferenceScope = selection;
            } catch (java.security.NoSuchAlgorithmException error) {
                throw new IllegalStateException("Không thể tạo vùng lưu cấu hình thiết bị.", error);
            }
        }
        return scopedPrefs;
    }

    public static synchronized AppConfig getInstance(Context context) {
        if (instance == null) {
            instance = new AppConfig(context);
        }
        return instance;
    }

    void applyServerConfig(JSONObject config) throws JSONException {
        if (!getDeviceId().equals(config.getString("device_id")))
            throw new JSONException("Cấu hình thuộc thiết bị khác");
        long revision = config.getLong("revision");
        double tempMin = config.getDouble("temperature_min");
        double tempMax = config.getDouble("temperature_max");
        int adcMin = config.getInt("water_adc_min");
        int adcMax = config.getInt("water_adc_max");
        double cmMin = config.getDouble("water_cm_min");
        double cmMax = config.getDouble("water_cm_max");
        boolean interlock = config.getBoolean("interlock");
        if (revision <= 0 || !Double.isFinite(tempMin) || !Double.isFinite(tempMax)
                || tempMin < -55 || tempMax > 125 || tempMin >= tempMax
                || adcMin < 0 || adcMin >= adcMax || adcMax > 4095
                || !Double.isFinite(cmMin) || !Double.isFinite(cmMax)
                || cmMin < 0 || cmMin >= cmMax || cmMax > 100000) {
            throw new JSONException("Giá trị cấu hình server không hợp lệ");
        }
        devicePrefs().edit().putLong("config_revision", revision)
                .putFloat(KEY_TEMP_MIN, (float) tempMin).putFloat(KEY_TEMP_MAX, (float) tempMax)
                .putFloat(KEY_WATER_MIN, adcMin).putFloat(KEY_WATER_MAX, adcMax)
                .putInt(KEY_WATER_CALIBRATION_ADC_START, adcMin)
                .putInt(KEY_WATER_CALIBRATION_ADC_END, adcMax)
                .putFloat(KEY_WATER_CALIBRATION_CM_START, (float) cmMin)
                .putFloat(KEY_WATER_CALIBRATION_CM_END, (float) cmMax)
                .putBoolean(KEY_INTERLOCK_ENABLED, interlock).apply();
    }

    public String getChartSensor() {
        return devicePrefs().getString(KEY_CHART_SENSOR, "temperature");
    }

    public void setChartSensor(String sensor) {
        if (!"temperature".equals(sensor) && !"adc".equals(sensor)) {
            throw new IllegalArgumentException("Invalid chart sensor");
        }
        devicePrefs().edit().putString(KEY_CHART_SENSOR, sensor).apply();
    }

    public int getSettingsTab() {
        return devicePrefs().getInt(KEY_SETTINGS_TAB, 0);
    }

    public void setSettingsTab(int tab) {
        if (tab < 0 || tab > 2) throw new IllegalArgumentException("Invalid settings tab");
        devicePrefs().edit().putInt(KEY_SETTINGS_TAB, tab).apply();
    }

    // ================= DEVICE INFO =================
    public String getDeviceId() {
        DeviceContext.Selection selection = DeviceContext.current();
        return selection == null ? "" : selection.id;
    }

    public void setDeviceId(String value) {
        devicePrefs().edit().putString(KEY_DEVICE_ID, value).apply();
    }

    public String getDeviceName() {
        DeviceContext.Selection selection = DeviceContext.current();
        return selection == null ? "" : selection.name;
    }

    public void setDeviceName(String value) {
        devicePrefs().edit().putString(KEY_DEVICE_NAME, value).apply();
    }

    public String getDeviceLocation() {
        return devicePrefs().getString(KEY_DEVICE_LOCATION, "");
    }

    public void setDeviceLocation(String value) {
        devicePrefs().edit().putString(KEY_DEVICE_LOCATION, value).apply();
    }

    // ================= THRESHOLDS =================
    public float getTempMin() {
        return devicePrefs().getFloat(KEY_TEMP_MIN, DEFAULT_TEMP_MIN);
    }

    public void setTempMin(float value) {
        devicePrefs().edit().putFloat(KEY_TEMP_MIN, value).apply();
    }

    public float getTempMax() {
        return devicePrefs().getFloat(KEY_TEMP_MAX, DEFAULT_TEMP_MAX);
    }

    public void setTempMax(float value) {
        devicePrefs().edit().putFloat(KEY_TEMP_MAX, value).apply();
    }

    public float getWaterLevelMin() {
        return devicePrefs().getFloat(KEY_WATER_MIN, DEFAULT_WATER_MIN);
    }

    public void setWaterLevelMin(float value) {
        devicePrefs().edit().putFloat(KEY_WATER_MIN, value).apply();
    }

    public float getWaterLevelMax() {
        return devicePrefs().getFloat(KEY_WATER_MAX, DEFAULT_WATER_MAX);
    }

    public void setWaterLevelMax(float value) {
        devicePrefs().edit().putFloat(KEY_WATER_MAX, value).apply();
    }

    public float getWaterThresholdDisplayMin() {
        return getWaterThresholdDisplayValue(true);
    }

    public float getWaterThresholdDisplayMax() {
        return getWaterThresholdDisplayValue(false);
    }

    private float getWaterThresholdDisplayValue(boolean minimum) {
        if (!hasWaterLevelCalibration()) return Float.NaN;
        float minCm = waterAdcToCm(getWaterLevelMin());
        float maxCm = waterAdcToCm(getWaterLevelMax());
        return minimum ? Math.min(minCm, maxCm) : Math.max(minCm, maxCm);
    }

    public void setWaterThresholdsFromCm(float minCm, float maxCm) {
        if (!hasWaterLevelCalibration()) {
            throw new IllegalStateException("Water level calibration is required.");
        }
        float minAdc = Math.round(waterCmToAdc(minCm));
        float maxAdc = Math.round(waterCmToAdc(maxCm));
        devicePrefs().edit()
                .putFloat(KEY_WATER_MIN, Math.min(minAdc, maxAdc))
                .putFloat(KEY_WATER_MAX, Math.max(minAdc, maxAdc))
                .apply();
    }

    public boolean hasWaterLevelCalibration() {
        SharedPreferences device = devicePrefs();
        if (!device.contains(KEY_WATER_CALIBRATION_ADC_START)
                || !device.contains(KEY_WATER_CALIBRATION_CM_START)
                || !device.contains(KEY_WATER_CALIBRATION_ADC_END)
                || !device.contains(KEY_WATER_CALIBRATION_CM_END)) {
            return false;
        }
        int adcStart = getWaterCalibrationAdcStart();
        int adcEnd = getWaterCalibrationAdcEnd();
        float cmStart = getWaterCalibrationCmStart();
        float cmEnd = getWaterCalibrationCmEnd();
        return adcStart >= 0 && adcStart <= 4095
                && adcEnd > adcStart && adcEnd <= 4095
                && Float.isFinite(cmStart) && Float.isFinite(cmEnd)
                && cmEnd > cmStart;
    }

    private void migrateWaterCalibration() {
        if (prefs.contains(KEY_WATER_CALIBRATION_ADC_START)
                || !prefs.contains(KEY_WATER_CALIBRATION_ADC_ZERO)
                || !prefs.contains(KEY_WATER_CALIBRATION_ADC_REFERENCE)
                || !prefs.contains(KEY_WATER_CALIBRATION_HEIGHT_CM)) {
            return;
        }
        int adcStart = prefs.getInt(KEY_WATER_CALIBRATION_ADC_ZERO, -1);
        int adcEnd = prefs.getInt(KEY_WATER_CALIBRATION_ADC_REFERENCE, -1);
        float cmEnd = prefs.getFloat(KEY_WATER_CALIBRATION_HEIGHT_CM, Float.NaN);
        if (adcStart >= 0 && adcStart < adcEnd && adcEnd <= 4095
                && Float.isFinite(cmEnd) && cmEnd > 0) {
            prefs.edit()
                    .putInt(KEY_WATER_CALIBRATION_ADC_START, adcStart)
                    .putFloat(KEY_WATER_CALIBRATION_CM_START, 0)
                    .putInt(KEY_WATER_CALIBRATION_ADC_END, adcEnd)
                    .putFloat(KEY_WATER_CALIBRATION_CM_END, cmEnd)
                    .apply();
        }
    }

    public int getWaterCalibrationAdcStart() {
        return devicePrefs().getInt(KEY_WATER_CALIBRATION_ADC_START, -1);
    }

    public float getWaterCalibrationCmStart() {
        return devicePrefs().getFloat(KEY_WATER_CALIBRATION_CM_START, Float.NaN);
    }

    public int getWaterCalibrationAdcEnd() {
        return devicePrefs().getInt(KEY_WATER_CALIBRATION_ADC_END, -1);
    }

    public boolean isWaterAdcWithinCalibration(double adcRaw) {
        return hasWaterLevelCalibration() && Double.isFinite(adcRaw)
                && adcRaw >= getWaterCalibrationAdcStart()
                && adcRaw <= getWaterCalibrationAdcEnd();
    }

    public float getWaterCalibrationCmEnd() {
        return devicePrefs().getFloat(KEY_WATER_CALIBRATION_CM_END, Float.NaN);
    }

    public void setWaterLevelCalibration(int adcStart, float cmStart, int adcEnd, float cmEnd) {
        devicePrefs().edit()
                .putInt(KEY_WATER_CALIBRATION_ADC_START, adcStart)
                .putFloat(KEY_WATER_CALIBRATION_CM_START, cmStart)
                .putInt(KEY_WATER_CALIBRATION_ADC_END, adcEnd)
                .putFloat(KEY_WATER_CALIBRATION_CM_END, cmEnd)
                .apply();
    }

    public float waterAdcToCm(double adcRaw) {
        if (!hasWaterLevelCalibration() || !Double.isFinite(adcRaw)) return Float.NaN;
        int adcStart = getWaterCalibrationAdcStart();
        int adcEnd = getWaterCalibrationAdcEnd();
        double ratio = (adcRaw - adcStart) / (adcEnd - adcStart);
        ratio = Math.max(0, Math.min(1, ratio));
        return (float) (getWaterCalibrationCmStart()
                + ratio * (getWaterCalibrationCmEnd() - getWaterCalibrationCmStart()));
    }

    public float waterCmToAdc(double heightCm) {
        if (!hasWaterLevelCalibration() || !Double.isFinite(heightCm)) return Float.NaN;
        double cmStart = getWaterCalibrationCmStart();
        double cmEnd = getWaterCalibrationCmEnd();
        double ratio = (heightCm - cmStart) / (cmEnd - cmStart);
        ratio = Math.max(0, Math.min(1, ratio));
        return (float) (getWaterCalibrationAdcStart()
                + ratio * (getWaterCalibrationAdcEnd() - getWaterCalibrationAdcStart()));
    }

    // ================= INTERLOCK (KHÓA LIÊN ĐỘNG 2 BƠM) =================
    public boolean isInterlockEnabled() {
        return devicePrefs().getBoolean(KEY_INTERLOCK_ENABLED, DEFAULT_INTERLOCK_ENABLED);
    }

    public void setInterlockEnabled(boolean value) {
        devicePrefs().edit().putBoolean(KEY_INTERLOCK_ENABLED, value).apply();
    }

    public String getEspLocalIpAddress() {
        return devicePrefs().getString(KEY_ESP_LOCAL_IP_ADDRESS, "");
    }

    public void setEspLocalIpAddress(String value) {
        devicePrefs().edit().putString(KEY_ESP_LOCAL_IP_ADDRESS, value).apply();
    }

    // ================= WIFI =================
    public String getWifiSsid() {
        return devicePrefs().getString(KEY_WIFI_SSID, DEFAULT_WIFI_SSID);
    }

    public void setWifiSsid(String value) {
        devicePrefs().edit().putString(KEY_WIFI_SSID, value).apply();
    }

    public String getWifiPassword() {
        return devicePrefs().getString(KEY_WIFI_PASS, DEFAULT_WIFI_PASS);
    }

    public void setWifiPassword(String value) {
        devicePrefs().edit().putString(KEY_WIFI_PASS, value).apply();
    }

    // ================= MQTT (BROKER & TÀI KHOẢN CHO PHÉP NGƯỜI DÙNG THAY ĐỔI) =================
    public String getMqttBroker() {
        return devicePrefs().getString(KEY_MQTT_BROKER, DEFAULT_MQTT_BROKER);
    }

    public void setMqttBroker(String value) {
        devicePrefs().edit().putString(KEY_MQTT_BROKER, value).apply();
    }

    public int getMqttPort() {
        return devicePrefs().getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT);
    }

    public void setMqttPort(int value) {
        devicePrefs().edit().putInt(KEY_MQTT_PORT, value).apply();
    }

    public String getMqttUsername() {
        return devicePrefs().getString(KEY_MQTT_USER, "");
    }

    public void setMqttUsername(String value) {
        devicePrefs().edit().putString(KEY_MQTT_USER, value).apply();
    }

    public String getMqttPassword() {
        return devicePrefs().getString(KEY_MQTT_PASS, "");
    }

    public void setMqttPassword(String value) {
        devicePrefs().edit().putString(KEY_MQTT_PASS, value).apply();
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

    public String getTopicConfigAutomation() {
        return DEFAULT_TOPIC_CONFIG_AUTOMATION;
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

    public String getTopicCmdTemperatureEnabled() {
        return DEFAULT_TOPIC_CMD_TEMPERATURE_ENABLED;
    }

    public String getTopicCmdWaterEnabled() {
        return DEFAULT_TOPIC_CMD_WATER_ENABLED;
    }

    // ================= HTTP API =================
    public String getHttpApiUrl() {
        return prefs.getString(KEY_HTTP_API_URL, DEFAULT_HTTP_API_URL);
    }

    public void setHttpApiUrl(String value) {
        if (!value.equals(getHttpApiUrl())) UserSession.clear(context);
        prefs.edit().putString(KEY_HTTP_API_URL, value).apply();
    }

    public int getHttpPollIntervalSec() {
        return prefs.getInt(KEY_HTTP_POLL_INTERVAL, DEFAULT_HTTP_POLL_INTERVAL);
    }

    public void setHttpPollIntervalSec(int value) {
        prefs.edit().putInt(KEY_HTTP_POLL_INTERVAL, value).apply();
    }

    public String getControlMode() {
        return "mqtt";
    }

    public void setControlMode(String value) {
        prefs.edit().putString(KEY_CONTROL_MODE, "mqtt").apply();
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
        return buildHttpJson(null);
    }

    public JSONObject buildHttpJson(String reqId) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("action", "UPDATE_HTTP");
            if (reqId != null && !reqId.trim().isEmpty()) {
                obj.put("req_id", reqId);
            }
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
