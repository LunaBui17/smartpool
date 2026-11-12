package com.buiducluan23011767.ck;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    private View settingsRoot;
    private View toolbarContainer;

    private ImageButton btnBack;
    private ImageButton btnToolbarPreview;

    // Ngưỡng cảm biến
    private EditText edtTempMin;
    private EditText edtTempMax;
    private EditText edtWaterMin;
    private EditText edtWaterMax;
    private androidx.appcompat.widget.SwitchCompat switchSettingsInterlock;
    private Button btnSaveThresholds;

    // WiFi
    private Button btnScanWifi;
    private Button btnHiddenWifi;
    private TextView txtWifiScanStatus;
    private LinearLayout layoutWifiSelect;
    private Spinner spnScannedWifi;
    private LinearLayout layoutWifiManual;
    private TextView txtWifiSsidLabel;
    private EditText edtWifiSsid;
    private EditText edtWifiPass;
    private Button btnSaveWifi;

    // Thiết bị
    private EditText edtDeviceId;
    private EditText edtDeviceName;
    private EditText edtDeviceLocation;
    private Button btnSaveDevice;

    // Cấu hình MQTT Broker & Tài khoản (người dùng có thể đổi)
    private EditText edtMqttBroker;
    private EditText edtMqttUser;
    private EditText edtMqttPass;
    private Button btnSaveMqttConfig;
    private TextView txtSettingsMqttStatus;

    // HTTP
    private EditText edtHttpUrl;
    private EditText edtHttpPoll;
    private Spinner spnControlMode;
    private Button btnSaveHttpConfig;

    // Các nút tổng
    private Button btnSaveAndPublishMqtt;
    private Button btnPreviewJson;
    private Button btnResetDefaults;

    private AppConfig config;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MqttAsyncClient mqttClient;
    private boolean isMqttConnected = false;
    private final List<String> scannedWifiList = new ArrayList<>();
    private ArrayAdapter<String> wifiSpinnerAdapter;

    // Mechanism 2: Double check cấu hình qua ACK & Request ID
    private String pendingAckReqId = null;
    private ProgressDialog ackProgressDialog = null;
    private static final long ACK_TIMEOUT_MS = 10000L;
    private final Runnable ackTimeoutRunnable = this::handleAckTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        config = AppConfig.getInstance(this);

        initViews();
        applySystemBarInsets();
        setupControlModeSpinner();
        setupWifiSpinner();
        loadValuesToViews();
        setupListeners();

        refreshMqttActions();
        connectHiveMq();
    }

    private void initViews() {
        settingsRoot = findViewById(R.id.settingsRoot);
        toolbarContainer = findViewById(R.id.toolbarContainer);

        btnBack = findViewById(R.id.btnBack);
        btnToolbarPreview = findViewById(R.id.btnToolbarPreview);

        edtTempMin = findViewById(R.id.edtTempMin);
        edtTempMax = findViewById(R.id.edtTempMax);
        edtWaterMin = findViewById(R.id.edtWaterMin);
        edtWaterMax = findViewById(R.id.edtWaterMax);
        switchSettingsInterlock = findViewById(R.id.switchSettingsInterlock);
        btnSaveThresholds = findViewById(R.id.btnSaveThresholds);

        btnScanWifi = findViewById(R.id.btnScanWifi);
        btnHiddenWifi = findViewById(R.id.btnHiddenWifi);
        txtWifiScanStatus = findViewById(R.id.txtWifiScanStatus);
        layoutWifiSelect = findViewById(R.id.layoutWifiSelect);
        spnScannedWifi = findViewById(R.id.spnScannedWifi);
        layoutWifiManual = findViewById(R.id.layoutWifiManual);
        txtWifiSsidLabel = findViewById(R.id.txtWifiSsidLabel);
        edtWifiSsid = findViewById(R.id.edtWifiSsid);
        edtWifiPass = findViewById(R.id.edtWifiPass);
        btnSaveWifi = findViewById(R.id.btnSaveWifi);

        edtDeviceId = findViewById(R.id.edtDeviceId);
        edtDeviceName = findViewById(R.id.edtDeviceName);
        edtDeviceLocation = findViewById(R.id.edtDeviceLocation);
        btnSaveDevice = findViewById(R.id.btnSaveDevice);

        edtMqttBroker = findViewById(R.id.edtMqttBroker);
        edtMqttUser = findViewById(R.id.edtMqttUser);
        edtMqttPass = findViewById(R.id.edtMqttPass);
        btnSaveMqttConfig = findViewById(R.id.btnSaveMqttConfig);
        txtSettingsMqttStatus = findViewById(R.id.txtSettingsMqttStatus);

        edtHttpUrl = findViewById(R.id.edtHttpUrl);
        edtHttpPoll = findViewById(R.id.edtHttpPoll);
        spnControlMode = findViewById(R.id.spnControlMode);
        btnSaveHttpConfig = findViewById(R.id.btnSaveHttpConfig);

        btnSaveAndPublishMqtt = findViewById(R.id.btnSaveAndPublishMqtt);
        btnPreviewJson = findViewById(R.id.btnPreviewJson);
        btnResetDefaults = findViewById(R.id.btnResetDefaults);
    }

    private void applySystemBarInsets() {
        if (settingsRoot == null || toolbarContainer == null) return;
        final int headerTop = toolbarContainer.getPaddingTop();

        ViewCompat.setOnApplyWindowInsetsListener(settingsRoot, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );

            toolbarContainer.setPadding(
                    toolbarContainer.getPaddingLeft(),
                    headerTop + bars.top,
                    toolbarContainer.getPaddingRight(),
                    toolbarContainer.getPaddingBottom()
            );

            return insets;
        });

        ViewCompat.requestApplyInsets(settingsRoot);
    }

    private void setupControlModeSpinner() {
        String[] modes = new String[]{"MQTT (HiveMQ Cloud)", "HTTP (XAMPP / REST API)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                modes
        );
        spnControlMode.setAdapter(adapter);
    }

    private void setupWifiSpinner() {
        scannedWifiList.clear();
        scannedWifiList.add("-- Chọn mạng WiFi quét được --");
        wifiSpinnerAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                scannedWifiList
        );
        spnScannedWifi.setAdapter(wifiSpinnerAdapter);

        spnScannedWifi.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0 && position < scannedWifiList.size()) {
                    String selected = scannedWifiList.get(position);
                    // Lọc bỏ phần RSSI nếu có ví dụ: "MyWiFi (-65 dBm)"
                    int idx = selected.indexOf(" (");
                    String ssid = idx > 0 ? selected.substring(0, idx) : selected;
                    edtWifiSsid.setText(ssid);
                    txtWifiSsidLabel.setText("Tên mạng WiFi (Đã chọn từ danh sách):");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void loadValuesToViews() {
        edtDeviceId.setText(config.getDeviceId());
        edtDeviceName.setText(config.getDeviceName());
        edtDeviceLocation.setText(config.getDeviceLocation());

        edtMqttBroker.setText(config.getMqttBroker());
        edtMqttUser.setText(config.getMqttUsername());
        edtMqttPass.setText(config.getMqttPassword());

        edtTempMin.setText(String.valueOf(config.getTempMin()));
        edtTempMax.setText(String.valueOf(config.getTempMax()));
        edtWaterMin.setText(String.valueOf(config.getWaterLevelMin()));
        edtWaterMax.setText(String.valueOf(config.getWaterLevelMax()));
        switchSettingsInterlock.setChecked(config.isInterlockEnabled());

        edtWifiSsid.setText(config.getWifiSsid());
        edtWifiPass.setText(config.getWifiPassword());

        edtHttpUrl.setText(config.getHttpApiUrl());
        edtHttpPoll.setText(String.valueOf(config.getHttpPollIntervalSec()));

        if ("http".equalsIgnoreCase(config.getControlMode())) {
            spnControlMode.setSelection(1);
        } else {
            spnControlMode.setSelection(0);
        }
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnToolbarPreview.setOnClickListener(v -> showPreviewDialog());
        btnPreviewJson.setOnClickListener(v -> showPreviewDialog());

        switchSettingsInterlock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setInterlockEnabled(isChecked);
            Toast.makeText(
                    this,
                    isChecked ? "Đã BẬT khóa liên động (Chặn chạy 2 bơm cùng lúc)"
                            : "Đã TẮT khóa liên động (Cho phép bật 2 bơm đồng thời)",
                    Toast.LENGTH_SHORT
            ).show();
        });

        // 1. Nút Lưu riêng cho Ngưỡng cảm biến
        btnSaveThresholds.setOnClickListener(v -> saveAndPublishThresholds());

        // 2. Nút Quét mạng WiFi (Gửi lệnh MQTT xuống ESP32)
        btnScanWifi.setOnClickListener(v -> triggerWifiScan());

        // 3. Nút Nhập mạng ẩn (SSID ẩn)
        btnHiddenWifi.setOnClickListener(v -> {
            layoutWifiManual.setVisibility(View.VISIBLE);
            txtWifiSsidLabel.setText("Tên mạng WiFi ẩn (SSID nhập thủ công):");
            edtWifiSsid.requestFocus();
            Toast.makeText(this, "Đã chuyển sang chế độ nhập mạng WiFi ẩn thủ công", Toast.LENGTH_SHORT).show();
        });

        // 4. Nút Lưu riêng cho WiFi
        btnSaveWifi.setOnClickListener(v -> saveAndPublishWifi());

        // 5. Nút Lưu riêng cho Thông tin thiết bị
        btnSaveDevice.setOnClickListener(v -> saveAndPublishDevice());

        // 6. Nút Lưu riêng cho MQTT Broker & Tài khoản
        btnSaveMqttConfig.setOnClickListener(v -> saveMqttBrokerAndCredentials());

        btnSaveHttpConfig.setOnClickListener(v -> saveAndPublishHttp());

        // 7. Nút Gửi toàn bộ cấu hình đồng bộ (ALL)
        btnSaveAndPublishMqtt.setOnClickListener(v -> saveAndPublishAll());

        // 8. Khôi phục mặc định
        btnResetDefaults.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Khôi phục cài đặt gốc")
                    .setMessage("Bạn có chắc chắn muốn xóa toàn bộ cấu hình đã tùy chỉnh và quay về giá trị mặc định ban đầu?")
                    .setPositiveButton("Khôi phục", (dialog, which) -> {
                        config.resetToDefaults();
                        loadValuesToViews();
                        Toast.makeText(this, "Đã khôi phục cài đặt mặc định.", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Hủy", null)
                    .show();
        });
    }

    // ==================== KẾT NỐI HIVEMQ CLOUD ====================

    private void refreshMqttActions() {
        boolean ready = mqttClient != null && mqttClient.isConnected();
        for (android.view.View action : new android.view.View[]{btnSaveThresholds, btnScanWifi,
                btnSaveWifi, btnSaveDevice, btnSaveHttpConfig, btnSaveAndPublishMqtt}) {
            if (action != null) action.setEnabled(ready);
        }
    }

    private void connectHiveMq() {
        final String broker = config.getMqttBroker();
        final String username = config.getMqttUsername();
        final String password = config.getMqttPassword();

        try {
            if (mqttClient == null) {
                String clientId = "SettingsMgr-" + UUID.randomUUID().toString().substring(0, 8);
                mqttClient = new MqttAsyncClient(broker, clientId, new MemoryPersistence());

                mqttClient.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String uri) {
                        isMqttConnected = true;
                        Log.i(TAG, "HiveMQ connected");
                        subscribeScanResultTopic();
                        handler.post(() -> {
                            refreshMqttActions();
                            if (txtSettingsMqttStatus != null) {
                                txtSettingsMqttStatus.setText("✓ Trạng thái: Đã kết nối HiveMQ Cloud • Sẵn sàng");
                                txtSettingsMqttStatus.setTextColor(android.graphics.Color.rgb(22, 163, 74));
                            }
                        });
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        isMqttConnected = false;
                        Log.w(TAG, "HiveMQ connection lost", cause);
                        handler.post(() -> {
                            refreshMqttActions();
                            if (txtSettingsMqttStatus != null) {
                                txtSettingsMqttStatus.setText("⚠ Trạng thái: Mất kết nối HiveMQ • Đang thử lại...");
                                txtSettingsMqttStatus.setTextColor(android.graphics.Color.rgb(220, 38, 38));
                            }
                        });
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                        if (topic.equals(config.getTopicWifiScanResult())) {
                            handleWifiScanResult(payload);
                        } else if (topic.equals(config.getTopicAck())) {
                            handleAckMessage(payload);
                        }
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                    }
                });
            }

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(12);
            options.setKeepAliveInterval(30);

            if (username != null && !username.trim().isEmpty()) {
                options.setUserName(username.trim());
            }
            if (password != null && !password.isEmpty()) {
                options.setPassword(password.toCharArray());
            }

            mqttClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    isMqttConnected = true;
                    subscribeScanResultTopic();
                    handler.post(() -> {
                        refreshMqttActions();
                        if (txtSettingsMqttStatus != null) {
                            txtSettingsMqttStatus.setText("✓ Trạng thái: Đã kết nối HiveMQ Cloud • Sẵn sàng");
                            txtSettingsMqttStatus.setTextColor(android.graphics.Color.rgb(22, 163, 74));
                        }
                    });
                }

                @Override
                public void onFailure(IMqttToken token, Throwable exception) {
                    isMqttConnected = false;
                    Log.e(TAG, "Lỗi kết nối HiveMQ", exception);
                    handler.post(() -> {
                        refreshMqttActions();
                        if (txtSettingsMqttStatus != null) {
                            txtSettingsMqttStatus.setText("⚠ Trạng thái: Chưa kết nối được Broker (" + (exception != null ? exception.getMessage() : "") + ")");
                            txtSettingsMqttStatus.setTextColor(android.graphics.Color.rgb(220, 38, 38));
                        }
                    });
                }
            });

        } catch (MqttException e) {
            Log.e(TAG, "Lỗi khởi tạo MQTT client", e);
        }
    }

    private void subscribeScanResultTopic() {
        if (mqttClient == null || !mqttClient.isConnected()) return;
        try {
            mqttClient.subscribe(config.getTopicWifiScanResult(), 1);
            mqttClient.subscribe(config.getTopicAck(), 1);
            Log.i(TAG, "Đã subscribe topics: " + config.getTopicWifiScanResult() + " & " + config.getTopicAck());
        } catch (MqttException e) {
            Log.e(TAG, "Lỗi subscribe settings topics", e);
        }
    }

    // ==================== XỬ LÝ LỆNH QUÉT WIFI ====================

    private void triggerWifiScan() {
        txtWifiScanStatus.setText("⏳ Đang gửi lệnh quét WiFi tới ESP32 qua topic: " + config.getTopicCmdWifiScan() + "...");

        String payload = config.buildWifiScanCommandJson().toString();
        publishToMqtt(
                config.getTopicCmdWifiScan(),
                payload,
                false, // Không retain lệnh quét
                () -> {
                    txtWifiScanStatus.setText("📡 Đã gửi lệnh quét. ESP32 đang quét WiFi xung quanh...\nĐang lắng nghe topic: " + config.getTopicWifiScanResult());
                    Toast.makeText(this, "Đã gửi yêu cầu quét WiFi tới ESP32!", Toast.LENGTH_SHORT).show();

                    // Timeout gợi ý sau 8s nếu chưa có ESP32 thật
                    handler.postDelayed(() -> {
                        if (scannedWifiList.size() <= 1 && !isFinishing()) {
                            txtWifiScanStatus.setText("💡 Nếu chưa có ESP32 thật để quét, bạn có thể bấm 'Mạng ẩn (Nhập tay)' hoặc gửi JSON mẫu lên topic '" + config.getTopicWifiScanResult() + "': [\"WiFi_Nha\",\"WiFi_SanVuon\"]");
                        }
                    }, 8000L);
                },
                error -> txtWifiScanStatus.setText("❌ Không gửi được lệnh quét: " + error)
        );
    }

    /**
     * Nhận kết quả danh sách WiFi từ ESP32 qua topic smartpool/wifi/scan_result
     */
    private void handleWifiScanResult(String payload) {
        handler.post(() -> {
            try {
                List<String> foundSsids = new ArrayList<>();

                // Hỗ trợ cả định dạng mảng JSON ["SSID1", "SSID2"] hoặc đối tượng {"networks": [...]}
                if (payload.trim().startsWith("[")) {
                    JSONArray arr = new JSONArray(payload);
                    for (int i = 0; i < arr.length(); i++) {
                        Object item = arr.get(i);
                        if (item instanceof JSONObject) {
                            JSONObject obj = (JSONObject) item;
                            String ssid = obj.optString("ssid", "");
                            int rssi = obj.optInt("rssi", 0);
                            if (!ssid.isEmpty()) {
                                foundSsids.add(ssid + (rssi != 0 ? " (" + rssi + " dBm)" : ""));
                            }
                        } else {
                            foundSsids.add(item.toString());
                        }
                    }
                } else if (payload.trim().startsWith("{")) {
                    JSONObject obj = new JSONObject(payload);
                    JSONArray arr = obj.optJSONArray("networks");
                    if (arr == null) arr = obj.optJSONArray("ssids");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            Object item = arr.get(i);
                            if (item instanceof JSONObject) {
                                JSONObject net = (JSONObject) item;
                                String ssid = net.optString("ssid", "");
                                int rssi = net.optInt("rssi", 0);
                                if (!ssid.isEmpty()) {
                                    foundSsids.add(ssid + (rssi != 0 ? " (" + rssi + " dBm)" : ""));
                                }
                            } else {
                                foundSsids.add(item.toString());
                            }
                        }
                    }
                }

                if (!foundSsids.isEmpty()) {
                    scannedWifiList.clear();
                    scannedWifiList.add("-- Đã tìm thấy " + foundSsids.size() + " mạng (Bấm để chọn) --");
                    scannedWifiList.addAll(foundSsids);
                    wifiSpinnerAdapter.notifyDataSetChanged();

                    layoutWifiSelect.setVisibility(View.VISIBLE);
                    layoutWifiManual.setVisibility(View.VISIBLE);
                    txtWifiScanStatus.setText("✓ Đã nhận " + foundSsids.size() + " mạng WiFi từ ESP32. Chọn từ danh sách bên dưới:");
                    Toast.makeText(this, "Đã cập nhật danh sách WiFi từ ESP32!", Toast.LENGTH_SHORT).show();
                } else {
                    txtWifiScanStatus.setText("ESP32 không tìm thấy mạng WiFi nào xung quanh.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Lỗi parse kết quả WiFi scan", e);
                txtWifiScanStatus.setText("Nhận được phản hồi nhưng không đọc được danh sách WiFi: " + payload);
            }
        });
    }

    // ==================== LƯU & PUBLISH TỪNG TOPIC ====================

    /**
     * 1. Lưu và gửi Ngưỡng cảnh báo lên topic: smartpool/config/thresholds
     */
    private void saveAndPublishThresholds() {
        String tempMinStr = edtTempMin.getText().toString().trim();
        String tempMaxStr = edtTempMax.getText().toString().trim();
        String waterMinStr = edtWaterMin.getText().toString().trim();
        String waterMaxStr = edtWaterMax.getText().toString().trim();

        float tempMin = AppConfig.DEFAULT_TEMP_MIN;
        float tempMax = AppConfig.DEFAULT_TEMP_MAX;
        try {
            if (!tempMinStr.isEmpty()) tempMin = Float.parseFloat(tempMinStr);
            if (!tempMaxStr.isEmpty()) tempMax = Float.parseFloat(tempMaxStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Ngưỡng nhiệt độ phải là số hợp lệ", Toast.LENGTH_SHORT).show();
            return;
        }

        if (tempMin >= tempMax) {
            edtTempMin.setError("Nhiệt độ tối thiểu phải nhỏ hơn tối đa");
            return;
        }

        float waterMin = AppConfig.DEFAULT_WATER_MIN;
        float waterMax = AppConfig.DEFAULT_WATER_MAX;
        try {
            if (!waterMinStr.isEmpty()) waterMin = Float.parseFloat(waterMinStr);
            if (!waterMaxStr.isEmpty()) waterMax = Float.parseFloat(waterMaxStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Ngưỡng mực nước phải là số hợp lệ", Toast.LENGTH_SHORT).show();
            return;
        }

        if (waterMin >= waterMax) {
            edtWaterMin.setError("Mực nước cạn phải nhỏ hơn mức tràn");
            return;
        }

        // Lưu vào SharedPreferences
        config.setTempMin(tempMin);
        config.setTempMax(tempMax);
        config.setWaterLevelMin(waterMin);
        config.setWaterLevelMax(waterMax);
        config.setInterlockEnabled(switchSettingsInterlock.isChecked());

        // Gửi lên topic riêng của thresholds kèm req_id (Mechanism 2)
        String reqId = "req_thresh_" + (System.currentTimeMillis() / 1000L);
        String topic = config.getTopicConfigThresholds();
        String payload = config.buildThresholdsJson(reqId).toString();

        publishWithAckProgress(
                "Đang lưu ngưỡng cảnh báo",
                topic,
                payload,
                reqId,
                "Cấu hình ngưỡng an toàn"
        );
    }

    /**
     * 2. Lưu và gửi Cấu hình WiFi lên topic: smartpool/config/wifi (Mechanism 2)
     */
    private void saveAndPublishWifi() {
        String ssid = edtWifiSsid.getText().toString().trim();
        String pass = edtWifiPass.getText().toString();

        if (ssid.isEmpty()) {
            edtWifiSsid.setError("Vui lòng nhập hoặc chọn tên mạng WiFi");
            return;
        }

        // Lưu vào SharedPreferences
        config.setWifiSsid(ssid);
        config.setWifiPassword(pass);

        // Gửi lên topic riêng của WiFi kèm req_id (Mechanism 2)
        String reqId = "req_wifi_" + (System.currentTimeMillis() / 1000L);
        String topic = config.getTopicConfigWifi();
        String payload = config.buildWifiJson(reqId).toString();

        publishWithAckProgress(
                "Đang gửi cấu hình WiFi",
                topic,
                payload,
                reqId,
                "Cấu hình WiFi cho ESP32"
        );
    }

    /**
     * 3. Lưu và gửi Thông tin thiết bị lên topic: smartpool/config/device
     */
    private void saveAndPublishDevice() {
        String id = edtDeviceId.getText().toString().trim();
        String name = edtDeviceName.getText().toString().trim();
        String location = edtDeviceLocation.getText().toString().trim();

        if (id.isEmpty()) {
            edtDeviceId.setError("Vui lòng nhập Device ID");
            return;
        }

        config.setDeviceId(id);
        config.setDeviceName(name);
        config.setDeviceLocation(location);

        String reqId = "req_dev_" + (System.currentTimeMillis() / 1000L);
        String topic = config.getTopicConfigDevice();
        String payload = config.buildDeviceJson(reqId).toString();

        publishWithAckProgress(
                "Đang gửi thông tin thiết bị",
                topic,
                payload,
                reqId,
                "Thông tin thiết bị"
        );
    }

    /**
     * 4. Lưu Cấu hình MQTT Broker & Tài khoản (người dùng tự tùy chỉnh)
     */
    private void saveMqttBrokerAndCredentials() {
        String broker = edtMqttBroker.getText().toString().trim();
        String user = edtMqttUser.getText().toString().trim();
        String pass = edtMqttPass.getText().toString();

        if (broker.isEmpty()) {
            edtMqttBroker.setError("Vui lòng nhập Broker URI (ssl://... hoặc tcp://...)");
            return;
        }

        config.setMqttBroker(broker);
        config.setMqttUsername(user);
        config.setMqttPassword(pass);

        Toast.makeText(this, "Đã lưu Broker & Tài khoản MQTT. Đang kết nối lại...", Toast.LENGTH_SHORT).show();

        if (mqttClient != null) {
            try {
                if (mqttClient.isConnected()) mqttClient.disconnect();
                mqttClient.close();
            } catch (Exception ignored) {
            }
            mqttClient = null;
        }

        txtSettingsMqttStatus.setText("Trạng thái: Đang kết nối lại Broker...");
        txtSettingsMqttStatus.setTextColor(android.graphics.Color.rgb(217, 119, 6));

        refreshMqttActions();
        connectHiveMq();
    }

    private void saveAndPublishHttp() {
        String httpUrl = edtHttpUrl.getText().toString().trim();
        try {
            URI uri = new URI(httpUrl);
            if ((!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                edtHttpUrl.setError("Nhập URL HTTP hoặc HTTPS hợp lệ");
                return;
            }
        } catch (URISyntaxException error) {
            edtHttpUrl.setError("URL HTTP không hợp lệ");
            return;
        }

        int interval;
        try {
            interval = Integer.parseInt(edtHttpPoll.getText().toString().trim());
            if (interval <= 0) {
                edtHttpPoll.setError("Chu kỳ phải lớn hơn 0 giây");
                return;
            }
        } catch (NumberFormatException error) {
            edtHttpPoll.setError("Nhập chu kỳ bằng số nguyên dương");
            return;
        }

        if (mqttClient == null || !mqttClient.isConnected()) {
            Toast.makeText(this, "Chưa kết nối MQTT. Hãy kết nối rồi gửi lại cấu hình HTTP.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        config.setHttpApiUrl(httpUrl);
        config.setHttpPollIntervalSec(interval);
        config.setControlMode(spnControlMode.getSelectedItemPosition() == 1 ? "http" : "mqtt");

        String reqId = "req_http_" + UUID.randomUUID();
        publishWithAckProgress(
                "Đang gửi cấu hình HTTP",
                config.getTopicConfigHttp(),
                config.buildHttpJson(reqId).toString(),
                reqId,
                "Cấu hình HTTP"
        );
    }

    /**
     * 5. Gửi đồng bộ toàn bộ cấu hình (ALL) lên topic tổng: smartpool/config
     */
    private void saveAndPublishAll() {
        commitGeneralValues();

        // Đồng thời lưu broker, username, password nếu có thay đổi
        String broker = edtMqttBroker.getText().toString().trim();
        if (!broker.isEmpty()) {
            config.setMqttBroker(broker);
            config.setMqttUsername(edtMqttUser.getText().toString().trim());
            config.setMqttPassword(edtMqttPass.getText().toString());
        }

        String reqId = "req_all_" + (System.currentTimeMillis() / 1000L);
        String topic = config.getTopicConfig();
        String payload = config.toJsonString(true, reqId);

        publishWithAckProgress(
                "Đang gửi toàn bộ cấu hình",
                topic,
                payload,
                reqId,
                "Toàn bộ cấu hình hệ thống"
        );
    }

    private void commitGeneralValues() {
        // Cập nhật giá trị HTTP
        String httpUrl = edtHttpUrl.getText().toString().trim();
        if (!httpUrl.isEmpty()) config.setHttpApiUrl(httpUrl);

        try {
            int interval = Integer.parseInt(edtHttpPoll.getText().toString().trim());
            if (interval > 0) config.setHttpPollIntervalSec(interval);
        } catch (Exception ignored) {
        }

        String mode = (spnControlMode.getSelectedItemPosition() == 1) ? "http" : "mqtt";
        config.setControlMode(mode);
    }

    // ==================== DOUBLE CHECK MECHANISM 2: ACK & REQUEST ID ====================

    private void handleAckMessage(String payload) {
        handler.post(() -> {
            try {
                JSONObject json = new JSONObject(payload);
                String reqId = json.optString("req_id", "");
                String action = json.optString("action", "");
                String status = json.optString("status", "SUCCESS");
                String message = json.optString("message", "Đã lưu vào bộ nhớ Flash ESP32");

                if (pendingAckReqId != null && pendingAckReqId.equals(reqId)) {
                    handler.removeCallbacks(ackTimeoutRunnable);
                    pendingAckReqId = null;

                    if (ackProgressDialog != null && ackProgressDialog.isShowing()) {
                        ackProgressDialog.dismiss();
                    }

                    if ("SUCCESS".equalsIgnoreCase(status) || "OK".equalsIgnoreCase(status)) {
                        new AlertDialog.Builder(this)
                                .setTitle("✓ ESP32 XÁC NHẬN THÀNH CÔNG (DOUBLE CHECK)")
                                .setMessage("ESP32 đã lưu cấu hình an toàn vào bộ nhớ Flash NVS!\n\n"
                                        + "• Request ID: " + reqId + "\n"
                                        + (!action.isEmpty() ? ("• Hành động: " + action + "\n") : "")
                                        + "• Trạng thái: " + status + "\n"
                                        + "• Phản hồi từ ESP32: " + message + "\n"
                                        + "• Topic xác nhận: " + config.getTopicAck())
                                .setPositiveButton("Tuyệt vời", null)
                                .show();
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("⚠ ESP32 BÁO LỖI KHI XỬ LÝ")
                                .setMessage("ESP32 đã nhận được yêu cầu nhưng phản hồi thất bại:\n\n"
                                        + "• Request ID: " + reqId + "\n"
                                        + "• Chi tiết lỗi: " + message)
                                .setPositiveButton("Đóng", null)
                                .show();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi parse ACK message", e);
            }
        });
    }

    private void handleAckTimeout() {
        if (!isFinishing() && ackProgressDialog != null && ackProgressDialog.isShowing()) {
            ackProgressDialog.dismiss();
        }
        pendingAckReqId = null;

        new AlertDialog.Builder(this)
                .setTitle("⚠ CHƯA NHẬN ĐƯỢC XÁC NHẬN TỪ ESP32")
                .setMessage("Cấu hình đã gửi lên HiveMQ Cloud (dạng Retained message).\n\n"
                        + "Tuy nhiên, quá 10 giây chờ mà ESP32 chưa gửi gói tin ACK xác nhận qua topic '"
                        + config.getTopicAck() + "'.\n\n"
                        + "Nguyên nhân có thể do ESP32 đang mất nguồn hoặc chưa kết nối WiFi. ESP32 sẽ tự động nhận cấu hình này khi online trở lại.")
                .setPositiveButton("Đã hiểu", null)
                .show();
    }

    private void publishWithAckProgress(String title, String topic, String payload, String reqId, String successTitle) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            Toast.makeText(this, "Chưa kết nối MQTT. Hãy kết nối rồi gửi lại.", Toast.LENGTH_LONG).show();
            return;
        }
        pendingAckReqId = reqId;

        ackProgressDialog = new ProgressDialog(this);
        ackProgressDialog.setTitle(title);
        ackProgressDialog.setMessage("• Bước 1: Đang gửi lên HiveMQ Cloud (Retained)...\n"
                + "• Bước 2: Đang đợi ESP32 phản hồi xác nhận (Topic: " + config.getTopicAck() + ")\n\n"
                + "Request ID: " + reqId);
        ackProgressDialog.setCancelable(false);
        ackProgressDialog.show();

        handler.removeCallbacks(ackTimeoutRunnable);
        handler.postDelayed(ackTimeoutRunnable, ACK_TIMEOUT_MS);

        publishToMqtt(
                topic,
                payload,
                true,
                () -> {
                    Log.i(TAG, "Đã gửi lên MQTT Cloud với req_id=" + reqId + ", đang chờ ACK...");
                },
                error -> {
                    handler.removeCallbacks(ackTimeoutRunnable);
                    pendingAckReqId = null;
                    if (ackProgressDialog != null && ackProgressDialog.isShowing()) {
                        ackProgressDialog.dismiss();
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Gửi lên Cloud thất bại")
                            .setMessage("Lỗi: " + error + "\nTopic: " + topic)
                            .setPositiveButton("Đóng", null)
                            .show();
                }
        );
    }

    private void publishToMqtt(String topic, String payload, boolean retained, Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
        new Thread(() -> {
            try {
                MqttAsyncClient connectedClient = mqttClient;
                if (connectedClient == null || !connectedClient.isConnected()) {
                    handler.post(() -> onFailure.accept("MQTT chưa kết nối. Hãy kết nối rồi gửi lại."));
                    return;
                }

                MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                msg.setQos(1);
                msg.setRetained(retained);

                connectedClient.publish(topic, msg).waitForCompletion(5000L);

                handler.post(onSuccess);

            } catch (Exception e) {
                Log.e(TAG, "Lỗi publish MQTT", e);
                handler.post(() -> onFailure.accept(e.getMessage()));
            }
        }).start();
    }

    // ==================== XEM TRƯỚC JSON ====================

    private void showPreviewDialog() {
        commitGeneralValues();

        String threshJson = config.buildThresholdsJson().toString();
        String wifiJson = config.buildWifiJson().toString();
        String devJson = config.buildDeviceJson().toString();
        String allJson = config.toJsonString(true);

        StringBuilder sb = new StringBuilder();
        sb.append("=== 1. TOPIC: ").append(config.getTopicConfigThresholds()).append(" ===\n");
        sb.append(threshJson).append("\n\n");

        sb.append("=== 2. TOPIC: ").append(config.getTopicConfigWifi()).append(" ===\n");
        sb.append(wifiJson).append("\n\n");

        sb.append("=== 3. TOPIC: ").append(config.getTopicConfigDevice()).append(" ===\n");
        sb.append(devJson).append("\n\n");

        sb.append("=== 4. TOPIC TỔNG HỢP: ").append(config.getTopicConfig()).append(" ===\n");
        sb.append(allJson);

        String fullText = sb.toString();

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_preview_json, null);
        TextView txtTopic = dialogView.findViewById(R.id.txtDialogTopic);
        TextView txtContent = dialogView.findViewById(R.id.txtJsonContent);
        Button btnCopy = dialogView.findViewById(R.id.btnDialogCopy);
        Button btnClose = dialogView.findViewById(R.id.btnDialogClose);

        txtTopic.setText("Cấu trúc các JSON theo từng topic");
        txtContent.setText(fullText);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("SmartPool_Config_JSON", fullText);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Đã sao chép các chuỗi JSON vào bộ nhớ tạm", Toast.LENGTH_SHORT).show();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    @Override
    protected void onDestroy() {
        if (mqttClient != null) {
            try {
                if (mqttClient.isConnected()) mqttClient.disconnect();
                mqttClient.close();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }
}
