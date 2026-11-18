package com.buiducluan23011767.ck;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.Editable;
import android.text.TextWatcher;
import android.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SmartPool";

    private View rootScroll;
    private View alertBannerContainer;
    private TextView txtActionFeedback;
    private View protocolSectionTitle;
    private View protocolDetailsSection;
    private boolean technicalDetailsExpanded;
    private View interlockControlSection;
    private View dashboardContent;
    private View adminOverviewContent;
    private TextView txtAdminOverviewSummary;
    private TextView txtAdminOverviewNotice;
    private EditText edtAdminDeviceSearch;
    private LinearLayout adminDeviceList;
    private final Map<String, JSONObject> allDeviceStates = new HashMap<>();
    private long lastAdminRefreshMs;
    private View systemStatusContent;
    private View controlContent;
    private BottomNavigationView mainNavigation;
    private SystemStatusView systemStatusDiagram;
    private TextView txtSystemStatusUpdated;
    private TextView txtDevicePicker;
    private JSONArray availableDevices = new JSONArray();
    private final Map<Integer, String> ownerNames = new HashMap<>();
    private int selectedMainTab;
    private JSONObject reportedSystemStatus;
    private Boolean apiDatabaseOk;
    private boolean remoteControlReady;
    private long lastSystemStatusMs;
    private long lastApiCheckMs;

    private TextView txtHeaderSubtitle;
    private TextView btnLogout;
    private ImageButton btnOpenSettings;

    // Thông tin chung trên Header
    private TextView txtDeviceBadge;
    private TextView txtControlModeBadge;
    private TextView txtGeneralStatus;

    // Giám sát cảm biến
    private TextView txtAlert;
    private TextView txtTemp;
    private TextView txtTempThresholdInfo;
    private TextView txtTempStatus;

    private TextView txtWater;
    private TextView txtWaterThresholdInfo;
    private TextView txtWaterStatus;
    private SensorControl temperatureControl;
    private SensorControl waterControl;

    // Điều khiển bơm
    private TextView txtPumpInStatus;
    private TextView txtPumpOutStatus;
    private Button btnPumpIn;
    private Button btnPumpOut;
    private androidx.appcompat.widget.SwitchCompat switchInterlock;
    private TextView txtInterlockStatus;

    // Phần Cấu hình và Test MQTT - HTTP ở dưới
    private TextView txtMqttBadge;
    private TextView txtConnection;
    private TextView txtMqttCurrentBroker;
    private TextView txtMqttCurrentTopics;
    private TextView txtLocalWebAddress;
    private TextView txtHttpEndpointMatch;
    private Button btnGoToSettings;
    private Button btnOpenLocalWeb;

    private TextView txtUpdated;

    private RequestQueue requestQueue;
    private volatile MqttAsyncClient mqttClient;

    private ActivityResultLauncher<String> notificationPermissionLauncher;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private AppConfig config;
    private boolean isAdmin;

    private boolean updatingInterlockUi;
    private final java.util.concurrent.atomic.AtomicLong mqttSession = new java.util.concurrent.atomic.AtomicLong();
    private boolean activityVisible;
    private boolean mqttConnecting;
    private volatile boolean mqttConnected;
    private volatile boolean espOnline;
    private String espIpAddress = "";
    private String espHttpApiUrl = "";
    private boolean polling;

    private boolean pumpInOn;
    private boolean pumpOutOn;

    private boolean pumpInPending;
    private boolean pumpOutPending;
    private boolean desiredPumpInOn;
    private boolean desiredPumpOutOn;
    private boolean pumpInAckApplied;
    private boolean pumpOutAckApplied;
    private long pumpInRequestedAtMs;
    private long pumpOutRequestedAtMs;
    private String pumpInLog;
    private String pumpOutLog;

    private static final long PUMP_ACK_TIMEOUT_MS = 20000L;
    private final Runnable pumpInTimeoutRunnable = () -> handlePumpTimeout(true);
    private final Runnable pumpOutTimeoutRunnable = () -> handlePumpTimeout(false);

    private long lastTelemetryTimestampMs = 0L;
    private static final long SENSOR_TIMEOUT_MS = 120000L;
    private String lastSampleId = "";

    private double currentTemp = Double.NaN;
    private int currentWaterLevel = -1;
    private int currentWaterRaw = -1;
    private String lastHttpError = "";

    private long lastMqttAttemptMs;
    private String connectedBrokerUri = "";

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!activityVisible) {
                return;
            }

            if (availableDevices.length() > 0) getSensorData();
            if (isAdmin && selectedMainTab == 0
                    && System.currentTimeMillis() - lastAdminRefreshMs >= 15000) {
                loadAllDeviceStatuses();
            }

            long intervalMs = Math.max(1000L, config.getHttpPollIntervalSec() * 1000L);
            handler.postDelayed(this, intervalMs);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserSession.restore(this);
        if (!UserSession.isAuthenticated()) {
            UserSession.clear(this);
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        isAdmin = UserSession.isAdmin();

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> startMonitorService()
        );

        setContentView(R.layout.activity_main);

        config = AppConfig.getInstance(this);
        technicalDetailsExpanded = savedInstanceState != null
                && savedInstanceState.getBoolean("system_details_expanded", false);

        initViews();
        ScreenInsets.apply(findViewById(R.id.mainRoot));
        setupListeners();

        requestQueue = Volley.newRequestQueue(getApplicationContext());

        renderPumpStates();
        selectMainTab(savedInstanceState == null ? 0
                : savedInstanceState.getInt("main_tab", 0));
        requestNotificationPermissionAndStartService();
    }

    private void initViews() {
        rootScroll = findViewById(R.id.rootScroll);
        alertBannerContainer = findViewById(R.id.alertBannerContainer);
        txtActionFeedback = findViewById(R.id.txtActionFeedback);
        dashboardContent = findViewById(R.id.dashboardContent);
        adminOverviewContent = findViewById(R.id.adminOverviewContent);
        txtAdminOverviewSummary = findViewById(R.id.txtAdminOverviewSummary);
        txtAdminOverviewNotice = findViewById(R.id.txtAdminOverviewNotice);
        edtAdminDeviceSearch = findViewById(R.id.edtAdminDeviceSearch);
        adminDeviceList = findViewById(R.id.adminDeviceList);
        edtAdminDeviceSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderAdminOverview();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        systemStatusContent = findViewById(R.id.systemStatusContent);
        controlContent = findViewById(R.id.controlContent);
        mainNavigation = findViewById(R.id.mainNavigation);
        systemStatusDiagram = findViewById(R.id.systemStatusDiagram);
        txtSystemStatusUpdated = findViewById(R.id.txtSystemStatusUpdated);
        txtDevicePicker = findViewById(R.id.txtDevicePicker);
        txtDevicePicker.setOnClickListener(v -> showDevicePicker());
        mainNavigation.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.navOverview) selectMainTab(0);
            else if (item.getItemId() == R.id.navControl) selectMainTab(1);
            else if (item.getItemId() == R.id.navSystem) selectMainTab(2);
            else return false;
            return true;
        });

        txtHeaderSubtitle = findViewById(R.id.txtHeaderSubtitle);
        btnLogout = findViewById(R.id.btnLogout);
        btnOpenSettings = findViewById(R.id.btnOpenSettings);
        protocolSectionTitle = findViewById(R.id.protocolSectionTitle);
        protocolDetailsSection = findViewById(R.id.protocolDetailsSection);
        interlockControlSection = findViewById(R.id.interlockControlSection);

        // Header thông tin chung
        txtDeviceBadge = findViewById(R.id.txtDeviceBadge);
        txtControlModeBadge = findViewById(R.id.txtControlModeBadge);
        txtGeneralStatus = findViewById(R.id.txtGeneralStatus);

        txtAlert = findViewById(R.id.txtAlert);
        txtTemp = findViewById(R.id.txtTemp);
        txtTempThresholdInfo = findViewById(R.id.txtTempThresholdInfo);
        txtTempStatus = findViewById(R.id.txtTempStatus);

        txtWater = findViewById(R.id.txtWater);
        txtWaterThresholdInfo = findViewById(R.id.txtWaterThresholdInfo);
        txtWaterStatus = findViewById(R.id.txtWaterStatus);
        temperatureControl = new SensorControl(
                findViewById(R.id.switchTemperatureEnabled), "cảm biến nhiệt độ",
                config.getTopicCmdTemperatureEnabled());
        waterControl = new SensorControl(
                findViewById(R.id.switchWaterEnabled), "cảm biến mực nước",
                config.getTopicCmdWaterEnabled());

        txtPumpInStatus = findViewById(R.id.txtPumpInStatus);
        txtPumpOutStatus = findViewById(R.id.txtPumpOutStatus);
        btnPumpIn = findViewById(R.id.btnPumpIn);
        btnPumpOut = findViewById(R.id.btnPumpOut);
        switchInterlock = findViewById(R.id.switchInterlock);
        txtInterlockStatus = findViewById(R.id.txtInterlockStatus);

        // Đã chuyển xuống Card Cấu hình & Test MQTT - HTTP
        txtMqttBadge = findViewById(R.id.txtMqttBadge);
        txtConnection = findViewById(R.id.txtConnection);
        txtMqttCurrentBroker = findViewById(R.id.txtMqttCurrentBroker);
        txtMqttCurrentTopics = findViewById(R.id.txtMqttCurrentTopics);
        txtLocalWebAddress = findViewById(R.id.txtLocalWebAddress);
        txtHttpEndpointMatch = findViewById(R.id.txtHttpEndpointMatch);
        btnGoToSettings = findViewById(R.id.btnGoToSettings);
        btnOpenLocalWeb = findViewById(R.id.btnOpenLocalWeb);

        txtUpdated = findViewById(R.id.txtUpdated);

        btnLogout.setText(isAdmin ? "Admin • Đăng xuất" : "User • Đăng xuất");
        btnOpenSettings.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
        protocolSectionTitle.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
        protocolDetailsSection.setVisibility(isAdmin && technicalDetailsExpanded ? View.VISIBLE : View.GONE);
        updateTechnicalDetailsLabel();
        protocolSectionTitle.setOnClickListener(v -> {
            technicalDetailsExpanded = !technicalDetailsExpanded;
            protocolDetailsSection.setVisibility(technicalDetailsExpanded ? View.VISIBLE : View.GONE);
            updateTechnicalDetailsLabel();
        });
        interlockControlSection.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
        txtControlModeBadge.setText(isAdmin ? "Vai trò: Admin" : "Vai trò: User");
        renderAdminOverview();
    }

    private void setupListeners() {
        View.OnClickListener openSettingsAction = v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        };

        btnOpenSettings.setOnClickListener(openSettingsAction);
        btnGoToSettings.setOnClickListener(openSettingsAction);
        btnLogout.setOnClickListener(v -> logout());
        btnOpenLocalWeb.setOnClickListener(v -> openLocalWeb());
        findViewById(R.id.btnOpenOperationLog).setOnClickListener(v ->
                startActivity(new Intent(this, OperationLogActivity.class)));
        txtTemp.setOnClickListener(v -> openSensorChart(SensorChartActivity.SENSOR_TEMPERATURE));
        txtWater.setOnClickListener(v -> openSensorChart(SensorChartActivity.SENSOR_ADC));

        btnPumpIn.setOnClickListener(v -> {
            if (pumpInPending || !requireMqttControl()) return;
            if (config.isInterlockEnabled() && !pumpInOn && pumpOutOn) {
                showActionFeedback("Khóa liên động đang bật. Hãy tắt bơm thoát trước khi bật bơm cấp.", true);
                return;
            }
            boolean targetState = !pumpInOn;
            desiredPumpInOn = targetState;
            pumpInPending = true;
            pumpInAckApplied = false;
            pumpInRequestedAtMs = lastSystemStatusMs;
            pumpInLog = OperationLog.get(this).begin((targetState ? "Bật" : "Tắt") + " bơm cấp");
            showActionFeedback("Đang gửi lệnh cho bơm cấp...", false);
            renderPumpStates();

            handler.removeCallbacks(pumpInTimeoutRunnable);
            handler.postDelayed(pumpInTimeoutRunnable, PUMP_ACK_TIMEOUT_MS);

            sendPumpCommand(true, targetState);
        });

        btnPumpOut.setOnClickListener(v -> {
            if (pumpOutPending || !requireMqttControl()) return;
            if (config.isInterlockEnabled() && !pumpOutOn && pumpInOn) {
                showActionFeedback("Khóa liên động đang bật. Hãy tắt bơm cấp trước khi bật bơm thoát.", true);
                return;
            }
            boolean targetState = !pumpOutOn;
            desiredPumpOutOn = targetState;
            pumpOutPending = true;
            pumpOutAckApplied = false;
            pumpOutRequestedAtMs = lastSystemStatusMs;
            pumpOutLog = OperationLog.get(this).begin((targetState ? "Bật" : "Tắt") + " bơm thoát");
            showActionFeedback("Đang gửi lệnh cho bơm thoát...", false);
            renderPumpStates();

            handler.removeCallbacks(pumpOutTimeoutRunnable);
            handler.postDelayed(pumpOutTimeoutRunnable, PUMP_ACK_TIMEOUT_MS);

            sendPumpCommand(false, targetState);
        });

        switchInterlock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingInterlockUi) return;
            if (!requireMqttControl()) {
                updateInterlockUI(config.isInterlockEnabled());
                return;
            }
            config.setInterlockEnabled(isChecked);
            updateInterlockUI(isChecked);
            showActionFeedback(isChecked
                    ? "Đã bật khóa liên động."
                    : "Đã tắt khóa liên động.", false);

            // Nếu đang kết nối MQTT, gửi cập nhật cấu hình interlock lên topic thresholds
            if (mqttClient != null && mqttClient.isConnected()) {
                final String operation = OperationLog.get(this).begin(
                        (isChecked ? "Bật" : "Tắt") + " khóa liên động");
                try {
                    byte[] payload = config.buildThresholdsJson().toString().getBytes(StandardCharsets.UTF_8);
                    mqttClient.publish(config.getTopicConfigThresholds(), payload, 1, true,
                            null, new IMqttActionListener() {
                                @Override
                                public void onSuccess(IMqttToken token) {
                                    handler.post(() -> OperationLog.get(MainActivity.this)
                                            .finish(operation, "published", ""));
                                }

                                @Override
                                public void onFailure(IMqttToken token, Throwable error) {
                                    Log.e(TAG, "Không gửi được khóa liên động", error);
                                    handler.post(() -> {
                                        OperationLog.get(MainActivity.this).finish(operation,
                                                "failed", "Không gửi được cấu hình MQTT");
                                        showActionFeedback("Không gửi được khóa liên động.", true);
                                    });
                                }
                            });
                } catch (MqttException e) {
                    OperationLog.get(this).finish(operation, "failed", "Lỗi gửi MQTT");
                    Log.e(TAG, "Lỗi sync interlock sang MQTT", e);
                    showActionFeedback("Không gửi được khóa liên động.", true);
                }
            }
        });
    }

    private void refreshConfigUI() {
        txtDeviceBadge.setText("ID thiết bị: " + config.getDeviceId());
        String selectedName = config.getDeviceName();
        for (int i = 0; i < availableDevices.length(); i++) {
            JSONObject device = availableDevices.optJSONObject(i);
            if (device != null && config.getDeviceId().equals(device.optString("device_id"))) {
                selectedName = device.optString("name", selectedName);
                break;
            }
        }
        txtHeaderSubtitle.setText(selectedName + " • " + config.getDeviceLocation());

        txtControlModeBadge.setText(isAdmin ? "Vai trò: Admin" : "Vai trò: User");

        txtTempThresholdInfo.setText(String.format(
                Locale.getDefault(),
                "Ngưỡng: %.1f°C - %.1f°C",
                config.getTempMin(),
                config.getTempMax()
        ));

        if (config.hasWaterLevelCalibration()) {
            txtWaterThresholdInfo.setText(String.format(Locale.getDefault(),
                    "Ngưỡng: %.2f - %.2f cm",
                    config.getWaterThresholdDisplayMin(), config.getWaterThresholdDisplayMax()));
        } else {
            txtWaterThresholdInfo.setText("Ngưỡng mực nước: cần hiệu chuẩn");
        }

        updateInterlockUI(config.isInterlockEnabled());

        txtMqttCurrentBroker.setText("API v2: " + config.getHttpApiUrl());
        txtMqttCurrentTopics.setText("ESP gửi MQTT tới Pi; app đọc dữ liệu qua API theo thiết bị "
                + config.getDeviceId() + ".");

        updateGeneralStatusAndErrors();

        // Đánh giá lại cảnh báo nếu đã có số liệu nhiệt độ
        if (!Double.isNaN(currentTemp)) {
            evaluateAlerts();
        }
        if (currentWaterLevel >= 0) {
            updateWaterLevel(currentWaterLevel, currentWaterRaw);
        }
    }

    private void updateInterlockUI(boolean isChecked) {
        if (switchInterlock != null && switchInterlock.isChecked() != isChecked) {
            updatingInterlockUi = true;
            try { switchInterlock.setChecked(isChecked); }
            finally { updatingInterlockUi = false; }
        }
        if (txtInterlockStatus != null) {
            if (isChecked) {
                txtInterlockStatus.setText("BẬT: Chặn chạy 2 bơm cùng lúc (Bảo vệ đường ống)");
                txtInterlockStatus.setTextColor(Color.rgb(234, 88, 12));
            } else {
                txtInterlockStatus.setText("TẮT: Cho phép bật đồng thời cả 2 bơm");
                txtInterlockStatus.setTextColor(Color.rgb(100, 116, 139));
            }
        }
    }

    /**
     * Cập nhật thông tin chung và hiển thị lỗi tổng quan trên Header
     */
    private void updateGeneralStatusAndErrors() {
        StringBuilder statusBuilder = new StringBuilder();

        if (isAdmin) {
            if (!espOnline) statusBuilder.append("ℹ Chờ trạng thái ESP32 từ API v2. ");
            if (!lastHttpError.isEmpty()) {
                statusBuilder.append("⚠ HTTP API: ").append(lastHttpError).append(". ");
            }
        }

        // 3. Kiểm tra cảm biến (Double check độ tươi mới dữ liệu)
        boolean sensorStale = (lastTelemetryTimestampMs > 0
                && (System.currentTimeMillis() - lastTelemetryTimestampMs > SENSOR_TIMEOUT_MS));

        if (!temperatureControl.isDisabled() && Double.isNaN(currentTemp)) {
            statusBuilder.append("ℹ Chờ dữ liệu cảm biến. ");
        } else if (sensorStale) {
            if (!temperatureControl.isDisabled() || !waterControl.isDisabled()) {
                statusBuilder.append("⚠ Mất tín hiệu cảm biến (>15s chưa có số liệu mới). ");
            }
            if (!temperatureControl.isDisabled()) {
                txtTempStatus.setText("DS18B20 • Mất tín hiệu (>15s)");
                txtTempStatus.setTextColor(Color.rgb(220, 38, 38));
            }
            if (!waterControl.isDisabled()) {
                txtWaterStatus.setText("Mực nước • Mất tín hiệu (>15s)");
                txtWaterStatus.setTextColor(Color.rgb(220, 38, 38));
            }
        }

        if (statusBuilder.length() == 0) {
            txtGeneralStatus.setText("✓ Trạng thái chung: Hệ thống đang vận hành bình thường");
            txtGeneralStatus.setTextColor(Color.rgb(134, 239, 172)); // Xanh lá dịu
        } else {
            txtGeneralStatus.setText(statusBuilder.toString().trim());
            txtGeneralStatus.setTextColor(Color.rgb(254, 202, 202)); // Đỏ nhạt cảnh báo
        }
        renderSystemStatus();
    }

    private void requestNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            startMonitorService();
        }
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, PoolMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Không khởi động được dịch vụ giám sát", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!UserSession.isAuthenticated()) {
            logout();
            return;
        }
        activityVisible = true;
        reportedSystemStatus = null;
        lastSystemStatusMs = 0;
        apiDatabaseOk = null;
        remoteControlReady = false;
        lastApiCheckMs = 0;
        OperationLog.get(this).sync();
        espOnline = false;
        resetSensorControls();
        renderPumpStates();

        refreshConfigUI();

        // Kiểm tra nếu broker đổi thì ngắt và kết nối lại
        String targetBroker = config.getMqttBroker();
        if (!targetBroker.equals(connectedBrokerUri) && mqttClient != null) {
            closeMqtt();
        }

        txtMqttBadge.setText("Theo dõi thiết bị qua API v2");

        handler.removeCallbacks(pollRunnable);
        loadDeviceList();
        long intervalMs = Math.max(1000L, config.getHttpPollIntervalSec() * 1000L);
        handler.postDelayed(pollRunnable, intervalMs);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("main_tab", selectedMainTab);
        outState.putBoolean("system_details_expanded", technicalDetailsExpanded);
        super.onSaveInstanceState(outState);
    }

    private void updateTechnicalDetailsLabel() {
        if (protocolSectionTitle instanceof TextView) {
            ((TextView) protocolSectionTitle).setText(technicalDetailsExpanded
                    ? "Chi tiết kỹ thuật  ▴" : "Chi tiết kỹ thuật  ▾");
        }
    }

    @Override
    protected void onPause() {
        activityVisible = false;
        clearPendingPumpCommands();
        resetSensorControls();
        renderPumpStates();
        handler.removeCallbacks(pollRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        activityVisible = false;
        handler.removeCallbacksAndMessages(null);

        if (requestQueue != null) {
            requestQueue.cancelAll(TAG);
        }

        closeMqtt();
        super.onDestroy();
    }

    // ==================== MQTT ====================

    private void connectMqtt() {
        if (!activityVisible || mqttConnecting) {
            return;
        }

        final String broker = config.getMqttBroker();
        final String username = config.getMqttUsername();
        final String password = config.getMqttPassword();

        if (broker == null || broker.trim().isEmpty()) {
            txtMqttBadge.setText("MQTT: Chưa cấu hình Broker");
            txtMqttBadge.setTextColor(Color.rgb(251, 191, 36));
            updateGeneralStatusAndErrors();
            return;
        }

        if (mqttClient != null && mqttClient.isConnected()) {
            mqttConnected = true;
            updateMqttStatus();
            subscribeTopics();
            return;
        }

        espOnline = false;
        renderPumpStates();
        mqttConnecting = true;
        lastMqttAttemptMs = System.currentTimeMillis();

        txtMqttBadge.setText("MQTT: Đang kết nối Broker...");
        txtMqttBadge.setTextColor(Color.rgb(251, 191, 36));

        try {
            if (mqttClient == null) {
                String clientId = "SmartPoolUI-" + UUID.randomUUID().toString().substring(0, 8);
                mqttClient = new MqttAsyncClient(broker, clientId, new MemoryPersistence());

                final MqttAsyncClient callbackClient = mqttClient;
                mqttClient.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String uri) {
                        if (mqttClient != callbackClient) return;
                        mqttSession.incrementAndGet();
                        espOnline = false;
                        mqttConnected = true;
                        mqttConnecting = false;
                        connectedBrokerUri = broker;

                        handler.post(() -> {
                            if (activityVisible) {
                                updateMqttStatus();
                                subscribeTopics();
                            }
                        });
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        if (mqttClient != callbackClient) return;
                        mqttSession.incrementAndGet();
                        mqttConnected = false;
                        espOnline = false;
                        handler.post(() -> {
                            if (activityVisible) {
                                updateMqttStatus();
                            }
                        });
                        Log.e(TAG, "Mất kết nối MQTT", cause);
                    }

                    @Override
                    public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
                        if (mqttClient != callbackClient) return;
                        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                        handleMqttMessage(topic, payload);
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

            final MqttAsyncClient connectingClient = mqttClient;
            connectingClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    if (mqttClient != connectingClient) return;
                    mqttConnecting = false;
                    mqttConnected = true;
                    connectedBrokerUri = broker;

                    handler.post(() -> {
                        if (activityVisible) {
                            updateMqttStatus();
                            subscribeTopics();
                        }
                    });
                }

                @Override
                public void onFailure(IMqttToken token, Throwable error) {
                    if (mqttClient != connectingClient) return;
                    mqttConnecting = false;
                    mqttConnected = false;
                    Log.e(TAG, "Kết nối MQTT thất bại: " + error);

                    handler.post(() -> {
                        if (activityVisible) {
                            updateMqttStatus();
                        }
                    });
                }
            });

        } catch (MqttException e) {
            mqttConnecting = false;
            mqttConnected = false;
            Log.e(TAG, "Không tạo được MQTT client", e);
            updateMqttStatus();
        }
    }

    private void subscribeTopics() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            return;
        }

        try {
            String topicStatus = config.getTopicStatus();
            mqttClient.subscribe(topicStatus, 1);
        } catch (MqttException e) {
            Log.e(TAG, "Lỗi đăng ký topic", e);
        }
    }

    private void handleMqttMessage(String topic, String payload) {
        try {
            JSONObject json = new JSONObject(payload);

            if (topic.equals(config.getTopicStatus())) {
                final long statusSession = mqttSession.get();
                boolean online = json.optInt("online", 0) == 1;
                boolean in = json.optInt("bom_vao_control", 0) == 1;
                boolean out = json.optInt("bom_ra_control", 0) == 1;
                JSONObject network = json.optJSONObject("network");
                String reportedIpAddress = network == null ? "" : network.optString("ip", "").trim();
                final String ipAddress = isValidIpv4Address(reportedIpAddress) ? reportedIpAddress : "";
                final String httpApiUrl = network == null ? "" : network.optString("http_api_url", "").trim();
                final Boolean temperatureEnabled = readSensorEnabled(json, "temperature_enabled");
                final Boolean waterEnabled = readSensorEnabled(json, "water_level_enabled");
                final boolean temperatureSupported = json.optBoolean("temperature_supported", false);
                final boolean waterSupported = json.optBoolean("water_supported", false);

                handler.post(() -> {
                    if (statusSession != mqttSession.get() || !mqttConnected) return;
                    reportedSystemStatus = json;
                    lastSystemStatusMs = System.currentTimeMillis();
                    espOnline = online;
                    espIpAddress = online ? ipAddress : "";
                    espHttpApiUrl = online ? httpApiUrl : "";
                    if (online && !ipAddress.isEmpty()) {
                        config.setEspLocalIpAddress(ipAddress);
                    }
                    if (!online) {
                        clearPendingPumpCommands();
                        resetSensorControls();
                    } else {
                        temperatureControl.update(temperatureSupported, temperatureEnabled);
                        waterControl.update(waterSupported, waterEnabled);
                    }

                    // DOUBLE CHECK BƠM CẤP
                    if (pumpInPending) {
                        if (in == desiredPumpInOn) {
                            handler.removeCallbacks(pumpInTimeoutRunnable);
                            pumpInPending = false;
                            finishPumpLog(true, "confirmed", "ESP32 báo trạng thái khớp yêu cầu");
                            pumpInOn = in;
                            showActionFeedback("ESP32 đã xác nhận bơm cấp "
                                    + (in ? "đang bật." : "đã tắt."), false);
                        }
                    } else {
                        pumpInOn = in;
                    }

                    // DOUBLE CHECK BƠM THOÁT
                    if (pumpOutPending) {
                        if (out == desiredPumpOutOn) {
                            handler.removeCallbacks(pumpOutTimeoutRunnable);
                            pumpOutPending = false;
                            finishPumpLog(false, "confirmed", "ESP32 báo trạng thái khớp yêu cầu");
                            pumpOutOn = out;
                            showActionFeedback("ESP32 đã xác nhận bơm thoát "
                                    + (out ? "đang bật." : "đã tắt."), false);
                        }
                    } else {
                        pumpOutOn = out;
                    }

                    renderPumpStates();
                    updateMqttStatus();
                    updateGeneralStatusAndErrors();
                });

            }

        } catch (Exception e) {
            Log.e(TAG, "JSON MQTT không hợp lệ", e);
        }
    }

    private Boolean readSensorEnabled(JSONObject json, String key) throws org.json.JSONException {
        if (!json.has(key)) return null;
        int value = json.getInt(key);
        if (value != 0 && value != 1) {
            throw new org.json.JSONException("Invalid sensor state: " + key);
        }
        return value == 1;
    }

    private void resetSensorControls() {
        if (temperatureControl != null) temperatureControl.reset();
        if (waterControl != null) waterControl.reset();
    }

    private final class SensorControl {
        private final androidx.appcompat.widget.SwitchCompat toggle;
        private final String name;
        private final String topic;
        private Boolean enabled;
        private Boolean pendingTarget;
        private boolean supported;
        private boolean updatingUi;
        private int commandId;
        private String operationId;
        private final Runnable timeout = () -> fail("timeout", "Quá thời gian chờ ESP32 xác nhận ");

        SensorControl(androidx.appcompat.widget.SwitchCompat toggle, String name, String topic) {
            this.toggle = toggle;
            this.name = name;
            this.topic = topic;
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (!updatingUi) send(checked);
            });
        }

        boolean isDisabled() {
            return Boolean.FALSE.equals(enabled);
        }

        void update(boolean supported, Boolean enabled) {
            this.supported = supported;
            this.enabled = enabled;
            if (pendingTarget != null && pendingTarget.equals(enabled)) {
                handler.removeCallbacks(timeout);
                pendingTarget = null;
                OperationLog.get(MainActivity.this).finish(operationId, "confirmed",
                        "ESP32 báo trạng thái khớp yêu cầu");
                operationId = null;
                showActionFeedback("ESP32 đã xác nhận " + name
                        + (enabled ? " đang bật." : " đã tắt."), false);
            }
        }

        void reset() {
            handler.removeCallbacks(timeout);
            commandId++;
            if (pendingTarget != null) {
                OperationLog.get(MainActivity.this).finish(operationId, "interrupted",
                        "Rời màn hình hoặc kết nối thiết bị thay đổi");
            }
            operationId = null;
            if (pendingTarget != null && activityVisible) {
                showActionFeedback("Chưa xác nhận thay đổi " + name
                        + " do kết nối thiết bị thay đổi.", true);
            }
            pendingTarget = null;
            supported = false;
        }

        void render() {
            updatingUi = true;
            toggle.setChecked(Boolean.TRUE.equals(enabled));
            updatingUi = false;
            toggle.setEnabled(false);
            toggle.setText(enabled == null ? "Chờ thiết bị"
                    : !supported ? "Không hỗ trợ"
                    : "Chưa hỗ trợ v2");
            renderSystemStatus();
        }

        private void send(boolean target) {
            if (!requireMqttControl() || !supported || enabled == null || pendingTarget != null) {
                render();
                return;
            }
            pendingTarget = target;
            operationId = OperationLog.get(MainActivity.this).begin(
                    (target ? "Bật " : "Tắt ") + name);
            final int requestId = ++commandId;
            final long session = mqttSession.get();
            render();
            showActionFeedback("Đang " + (target ? "bật " : "tắt ") + name + "...", false);
            handler.postDelayed(timeout, PUMP_ACK_TIMEOUT_MS);
            try {
                mqttClient.publish(topic, (target ? "1" : "0").getBytes(StandardCharsets.UTF_8),
                        1, false, null, new IMqttActionListener() {
                            @Override
                            public void onSuccess(IMqttToken token) {
                                // Chờ trạng thái từ ESP32, không coi PUBACK là xác nhận cảm biến.
                            }

                            @Override
                            public void onFailure(IMqttToken token, Throwable error) {
                                Log.e(TAG, "Không gửi được lệnh cho " + name, error);
                                handler.post(() -> {
                                    if (requestId == commandId && session == mqttSession.get()) {
                                        fail("failed", "Không gửi được lệnh cho ");
                                    }
                                });
                            }
                        });
            } catch (MqttException e) {
                Log.e(TAG, "Không gửi được lệnh cho " + name, e);
                fail("failed", "Không gửi được lệnh cho ");
            }
        }

        private void fail(String result, String message) {
            if (pendingTarget == null) return;
            handler.removeCallbacks(timeout);
            pendingTarget = null;
            OperationLog.get(MainActivity.this).finish(operationId, result, message + name);
            operationId = null;
            render();
            showActionFeedback(message + name + ".", true);
        }
    }

    // ==================== ĐIỀU KHIỂN MÁY BƠM ====================

    private void sendPumpCommand(boolean pumpIn, boolean turnOn) {
        final String deviceId = config.getDeviceId();
        final String requestId = UUID.randomUUID().toString();
        JSONObject body = new JSONObject();
        try {
            body.put("action", "request_control");
            body.put("device_id", deviceId);
            body.put("request_id", requestId);
            body.put("target", pumpIn ? "pump_in" : "pump_out");
            body.put("enabled", turnOn);
            body.put("ttl_seconds", 25);
        } catch (org.json.JSONException e) {
            failPumpRequest(pumpIn, "Không tạo được lệnh điều khiển.");
            return;
        }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, config.getHttpApiUrl(), body,
                response -> {
                    if (!deviceId.equals(config.getDeviceId()) || !pumpPending(pumpIn)) return;
                    JSONObject command = response.optJSONObject("command");
                    String commandId = command == null ? "" : command.optString("command_id", "");
                    if (commandId.isEmpty()) {
                        failPumpRequest(pumpIn, "API không trả mã lệnh điều khiển.");
                        return;
                    }
                    pollPumpCommand(pumpIn, deviceId, commandId);
                }, error -> {
                    if (!deviceId.equals(config.getDeviceId()) || !pumpPending(pumpIn)) return;
                    int code = error.networkResponse == null ? 0 : error.networkResponse.statusCode;
                    failPumpRequest(pumpIn, code == 403 ? "Tài khoản không có quyền điều khiển thiết bị này."
                            : code == 409 ? "Bơm đang có lệnh chờ xác nhận."
                            : "Không gửi được yêu cầu điều khiển tới API.");
                }) {
            @Override public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + UserSession.getToken());
                return headers;
            }
        };
        request.setShouldCache(false);
        request.setRetryPolicy(new DefaultRetryPolicy(5000, 0, 1.0f));
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private boolean pumpPending(boolean pumpIn) {
        return pumpIn ? pumpInPending : pumpOutPending;
    }

    private void pollPumpCommand(boolean pumpIn, String deviceId, String commandId) {
        if (!deviceId.equals(config.getDeviceId()) || !pumpPending(pumpIn)) return;
        JSONObject body = new JSONObject();
        try {
            body.put("action", "get_command");
            body.put("device_id", deviceId);
            body.put("command_id", commandId);
        } catch (org.json.JSONException e) { return; }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, config.getHttpApiUrl(), body,
                response -> {
                    if (!deviceId.equals(config.getDeviceId()) || !pumpPending(pumpIn)) return;
                    JSONObject command = response.optJSONObject("command");
                    String result = command == null ? "" : command.optString("effective_status",
                            command.optString("status", ""));
                    if ("applied".equals(result)) {
                        if (pumpIn) pumpInAckApplied = true;
                        else pumpOutAckApplied = true;
                        getSensorData();
                    } else if ("rejected".equals(result) || "cancelled".equals(result)
                            || "expired".equals(result)) {
                        failPumpRequest(pumpIn, "ESP từ chối hoặc lệnh đã hết hạn.");
                    } else {
                        handler.postDelayed(() -> pollPumpCommand(pumpIn, deviceId, commandId), 1200);
                    }
                }, error -> {
                    if (deviceId.equals(config.getDeviceId()) && pumpPending(pumpIn))
                        handler.postDelayed(() -> pollPumpCommand(pumpIn, deviceId, commandId), 1500);
                }) {
            @Override public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + UserSession.getToken());
                return headers;
            }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void failPumpRequest(boolean pumpIn, String message) {
        handler.removeCallbacks(pumpIn ? pumpInTimeoutRunnable : pumpOutTimeoutRunnable);
        if (pumpIn) {
            pumpInPending = false;
            pumpInAckApplied = false;
        } else {
            pumpOutPending = false;
            pumpOutAckApplied = false;
        }
        finishPumpLog(pumpIn, "failed", message);
        renderPumpStates();
        showActionFeedback(message, true);
    }

    private void closeMqtt() {
        mqttSession.incrementAndGet();
        mqttConnected = false;
        espOnline = false;
        clearPendingPumpCommands();
        resetSensorControls();
        renderPumpStates();
        if (mqttClient == null) {
            return;
        }
        try {
            if (mqttClient.isConnected()) {
                mqttClient.disconnect();
            }
            mqttClient.close();
        } catch (MqttException e) {
            Log.e(TAG, "Lỗi đóng MQTT", e);
        } finally {
            mqttClient = null;
            mqttConnected = false;
            mqttConnecting = false;
            connectedBrokerUri = "";
        }
    }

    private void updateMqttStatus() {
        if (!mqttConnected || !espOnline) {
            clearPendingPumpCommands();
            resetSensorControls();
        }
        renderPumpStates();
        if (!mqttConnected) {
            txtMqttBadge.setText("MQTT: Mất kết nối Broker");
            txtMqttBadge.setTextColor(Color.rgb(220, 38, 38));
        } else if (!espOnline) {
            txtMqttBadge.setText("MQTT: Đã kết nối Broker • Chờ ESP32 online");
            txtMqttBadge.setTextColor(Color.rgb(217, 119, 6));
        } else {
            txtMqttBadge.setText("MQTT: Đã kết nối • ESP32 đang Online");
            txtMqttBadge.setTextColor(Color.rgb(22, 163, 74));
        }
        updateLocalWebAccess();
        updateGeneralStatusAndErrors();
    }

    private void updateLocalWebAccess() {
        boolean hasLanAddress = espOnline && isValidIpv4Address(espIpAddress);
        if (hasLanAddress) {
            txtLocalWebAddress.setText("IP ESP32 (LAN): " + espIpAddress
                    + "\nWeb local: http://" + espIpAddress + "/"
                    + "\nĐiện thoại cần cùng mạng Wi-Fi với ESP32.");
        } else {
            txtLocalWebAddress.setText("Chưa nhận IP LAN của ESP32."
                    + "\nWeb cấu hình dự phòng: http://192.168.4.1/"
                    + "\nĐể dùng, kết nối Wi-Fi SmartPool-Setup.");
        }

        if (!espOnline || espHttpApiUrl.isEmpty()) {
            txtHttpEndpointMatch.setText("Đối chiếu HTTP: đang chờ cấu hình từ ESP32.");
            txtHttpEndpointMatch.setTextColor(Color.rgb(148, 163, 184));
        } else if (config.getHttpApiUrl().trim().equals(espHttpApiUrl)) {
            txtHttpEndpointMatch.setText("HTTP API: app và ESP32 đang dùng cùng URL.");
            txtHttpEndpointMatch.setTextColor(Color.rgb(22, 163, 74));
        } else {
            txtHttpEndpointMatch.setText("HTTP API chưa khớp.\nApp: "
                    + config.getHttpApiUrl().trim() + "\nESP32: " + espHttpApiUrl);
            txtHttpEndpointMatch.setTextColor(Color.rgb(220, 38, 38));
        }
    }

    private void openLocalWeb() {
        String ipAddress = espOnline && isValidIpv4Address(espIpAddress)
                ? espIpAddress : "192.168.4.1";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + ipAddress + "/")));
        } catch (ActivityNotFoundException e) {
            showActionFeedback("Không tìm thấy trình duyệt để mở web local.", true);
            Log.e(TAG, "Không mở được web local ESP32", e);
        }
    }

    private void showActionFeedback(String message, boolean error) {
        txtActionFeedback.setText(message);
        txtActionFeedback.setTextColor(error ? Color.rgb(185, 28, 28) : Color.rgb(30, 64, 175));
        txtActionFeedback.setVisibility(View.VISIBLE);
    }

    private void openSensorChart(String sensor) {
        Intent intent = new Intent(this, SensorChartActivity.class);
        intent.putExtra(SensorChartActivity.EXTRA_SENSOR, sensor);
        startActivity(intent);
    }

    private void logout() {
        String token = UserSession.getToken();
        if (token != null && !token.isEmpty()) {
            JSONObject body = new JSONObject();
            try {
                body.put("action", "logout");
                JsonObjectRequest request = new JsonObjectRequest(
                        Request.Method.POST, config.getHttpApiUrl().trim(), body,
                        response -> { },
                        error -> Log.w(TAG, "Không thu hồi được phiên đăng nhập trên máy chủ", error)
                ) {
                    @Override
                    public java.util.Map<String, String> getHeaders() {
                        java.util.Map<String, String> headers = new java.util.HashMap<>();
                        headers.put("Authorization", "Bearer " + token);
                        return headers;
                    }
                };
                Volley.newRequestQueue(getApplicationContext()).add(request);
            } catch (org.json.JSONException e) {
                Log.e(TAG, "Không tạo được yêu cầu đăng xuất", e);
            }
        }
        UserSession.clear(this);
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    private static boolean isValidIpv4Address(String address) {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            int value = 0;
            for (int i = 0; i < part.length(); i++) {
                char digit = part.charAt(i);
                if (digit < '0' || digit > '9') return false;
                value = value * 10 + digit - '0';
            }
            if (value > 255) return false;
        }
        return true;
    }

    // ==================== HTTP API ====================

    private void loadDeviceList() {
        JSONObject body = new JSONObject();
        try { body.put("action", "list_devices"); }
        catch (org.json.JSONException e) { return; }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, config.getHttpApiUrl(), body,
                response -> {
                    availableDevices = response.optJSONArray("devices");
                    if (availableDevices == null) availableDevices = new JSONArray();
                    boolean selectedAllowed = false;
                    for (int i = 0; i < availableDevices.length(); i++) {
                        JSONObject device = availableDevices.optJSONObject(i);
                        if (device != null && config.getDeviceId().equals(device.optString("device_id"))) {
                            selectedAllowed = true;
                            break;
                        }
                    }
                    if (!selectedAllowed && availableDevices.length() > 0) {
                        JSONObject first = availableDevices.optJSONObject(0);
                        if (first != null) selectDevice(first.optString("device_id"));
                    }
                    updateDevicePicker();
                    refreshConfigUI();
                    if (isAdmin) {
                        loadOwnerNames();
                        loadAllDeviceStatuses();
                    }
                    if (availableDevices.length() > 0) getSensorData();
                },
                error -> txtDevicePicker.setText("Không tải được danh sách thiết bị. Chạm để thử lại.")) {
            @Override public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + UserSession.getToken());
                return headers;
            }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void loadOwnerNames() {
        JSONObject body = new JSONObject();
        try { body.put("action", "list_users"); }
        catch (org.json.JSONException e) { return; }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, config.getHttpApiUrl(), body,
                response -> {
                    ownerNames.clear();
                    JSONArray users = response.optJSONArray("users");
                    if (users != null) for (int i = 0; i < users.length(); i++) {
                        JSONObject user = users.optJSONObject(i);
                        if (user != null) ownerNames.put(user.optInt("id"),
                                user.optString("username") + " · " + user.optString("full_name"));
                    }
                    updateDevicePicker();
                    renderAdminOverview();
                }, error -> Log.w(TAG, "Không tải được thông tin chủ thiết bị", error)) {
            @Override public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + UserSession.getToken());
                return headers;
            }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void loadAllDeviceStatuses() {
        if (!isAdmin || requestQueue == null) return;
        lastAdminRefreshMs = System.currentTimeMillis();
        for (int i = 0; i < availableDevices.length(); i++) {
            JSONObject device = availableDevices.optJSONObject(i);
            if (device == null) continue;
            final String deviceId = device.optString("device_id");
            JSONObject body = new JSONObject();
            try {
                body.put("action", "get_status");
                body.put("device_id", deviceId);
            } catch (org.json.JSONException e) { continue; }
            JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, config.getHttpApiUrl(), body,
                    response -> {
                        allDeviceStates.put(deviceId, response.optJSONObject("state"));
                        renderAdminOverview();
                    }, error -> {
                        allDeviceStates.remove(deviceId);
                        renderAdminOverview();
                    }) {
                @Override public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Authorization", "Bearer " + UserSession.getToken());
                    return headers;
                }
            };
            request.setShouldCache(false);
            request.setTag(TAG);
            requestQueue.add(request);
        }
        renderAdminOverview();
    }

    private void renderAdminOverview() {
        if (!isAdmin || adminDeviceList == null) return;
        int online = 0;
        int running = 0;
        StringBuilder attention = new StringBuilder();
        for (int i = 0; i < availableDevices.length(); i++) {
            JSONObject device = availableDevices.optJSONObject(i);
            JSONObject state = device == null ? null : allDeviceStates.get(device.optString("device_id"));
            if (state != null && state.optBoolean("is_recent_report")) {
                online++;
                if (state.optBoolean("pump_in") || state.optBoolean("pump_out")) running++;
            } else if (device != null && attention.length() < 80) {
                if (attention.length() > 0) attention.append(", ");
                attention.append(device.optString("device_id"));
            }
        }
        int total = availableDevices.length();
        txtAdminOverviewSummary.setText(total == 0 ? "Chưa có thiết bị trong hệ thống."
                : total + " thiết bị · " + online + " đang báo trạng thái · "
                + (total - online) + " chưa online\nChạm thiết bị để xem chi tiết và điều khiển.");
        txtAdminOverviewNotice.setText(total == 0 ? "Thông báo: chưa có thiết bị để theo dõi."
                : attention.length() > 0 ? "Cần kiểm tra: " + attention + " chưa báo trạng thái từ ESP."
                : running > 0 ? "Thông báo: có " + running + " thiết bị đang chạy bơm."
                : "Thông báo: tất cả thiết bị đang kết nối và bơm đang tắt.");
        adminDeviceList.removeAllViews();
        String filter = edtAdminDeviceSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (int i = 0; i < availableDevices.length(); i++) {
            JSONObject device = availableDevices.optJSONObject(i);
            if (device == null) continue;
            String deviceId = device.optString("device_id");
            if (!deviceId.toLowerCase(Locale.ROOT).contains(filter)) continue;
            shown++;
            JSONObject state = allDeviceStates.get(deviceId);
            boolean recent = state != null && state.optBoolean("is_recent_report");
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            int pad = Math.round(16 * getResources().getDisplayMetrics().density);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = Math.round(10 * getResources().getDisplayMetrics().density);
            adminDeviceList.addView(card, cardParams);
            TextView title = adminLine(device.optString("name") + "  ·  " + deviceId, 17,
                    R.color.text_primary, true);
            card.addView(title);
            card.addView(adminLine("Chủ sở hữu: " + ownerNames.getOrDefault(
                    device.optInt("owner_user_id"), "chưa xác định"), 13,
                    R.color.text_secondary, false));
            String statusText = !device.optBoolean("is_active") ? "Thiết bị đã khóa"
                    : recent ? "● Đang online" : state == null ? "○ Chưa có báo cáo từ ESP"
                    : "○ Mất kết nối ESP";
            card.addView(adminLine(statusText, 14,
                    recent ? R.color.success : R.color.warning, true));
            if (recent) {
                String pumps = "Bơm cấp: " + (state.optBoolean("pump_in") ? "Bật" : "Tắt")
                        + "  ·  Bơm thoát: " + (state.optBoolean("pump_out") ? "Bật" : "Tắt");
                card.addView(adminLine(pumps, 13, R.color.text_secondary, false));
            }
            card.setClickable(true);
            card.setFocusable(true);
            card.setContentDescription(title.getText() + ". " + statusText);
            card.setOnClickListener(v -> {
                selectDevice(deviceId);
                mainNavigation.setSelectedItemId(R.id.navControl);
            });
        }
        if (shown == 0 && total > 0)
            adminDeviceList.addView(adminLine("Không tìm thấy ID thiết bị phù hợp.", 14,
                    R.color.text_secondary, false));
    }

    private TextView adminLine(String content, int size, int color, boolean bold) {
        TextView line = new TextView(this);
        line.setText(content);
        line.setTextSize(size);
        line.setTextColor(getColor(color));
        line.setPadding(0, 0, 0, Math.round(7 * getResources().getDisplayMetrics().density));
        if (bold) line.setTypeface(null, android.graphics.Typeface.BOLD);
        return line;
    }

    private void updateDevicePicker() {
        if (availableDevices.length() == 0) {
            txtDevicePicker.setText("Chưa có thiết bị được cấp quyền");
            return;
        }
        for (int i = 0; i < availableDevices.length(); i++) {
            JSONObject device = availableDevices.optJSONObject(i);
            if (device == null || !config.getDeviceId().equals(device.optString("device_id"))) continue;
            String label = "Thiết bị: " + device.optString("name") + " (" + device.optString("device_id") + ")";
            if (isAdmin) label += "\nChủ sở hữu: " + ownerNames.getOrDefault(
                    device.optInt("owner_user_id"), "chưa xác định");
            txtDevicePicker.setText(label + (availableDevices.length() > 1 ? "  ▾" : ""));
            return;
        }
    }

    private void showDevicePicker() {
        if (availableDevices.length() == 0) { loadDeviceList(); return; }
        String[] choices = new String[availableDevices.length()];
        for (int i = 0; i < choices.length; i++) {
            JSONObject device = availableDevices.optJSONObject(i);
            if (device == null) continue;
            choices[i] = device.optString("name") + " (" + device.optString("device_id") + ")";
            if (isAdmin) choices[i] += "\nChủ sở hữu: " + ownerNames.getOrDefault(
                    device.optInt("owner_user_id"), "chưa xác định");
        }
        new AlertDialog.Builder(this).setTitle("Chọn thiết bị")
                .setItems(choices, (dialog, which) -> {
                    JSONObject device = availableDevices.optJSONObject(which);
                    if (device != null) selectDevice(device.optString("device_id"));
                }).setNegativeButton("Đóng", null).show();
    }

    private void selectDevice(String deviceId) {
        if (deviceId.isEmpty() || deviceId.equals(config.getDeviceId())) return;
        clearPendingPumpCommands();
        config.setDeviceId(deviceId);
        reportedSystemStatus = null;
        lastSystemStatusMs = 0;
        lastSampleId = "";
        lastTelemetryTimestampMs = 0;
        currentTemp = Double.NaN;
        currentWaterLevel = -1;
        currentWaterRaw = -1;
        pumpInOn = false;
        pumpOutOn = false;
        espOnline = false;
        remoteControlReady = false;
        resetSensorControls();
        updateDevicePicker();
        refreshConfigUI();
        renderPumpStates();
        renderSystemStatus();
        getSensorData();
    }

    private void getSensorData() {
        if (polling || requestQueue == null) {
            return;
        }

        String url = config.getHttpApiUrl();
        if (url == null || url.trim().isEmpty()) {
            apiDatabaseOk = null;
            lastApiCheckMs = 0;
            txtConnection.setText("HTTP API: Chưa cấu hình URL");
            txtConnection.setTextColor(Color.rgb(148, 163, 184));
            lastHttpError = "Chưa cấu hình URL";
            updateGeneralStatusAndErrors();
            return;
        }

        polling = true;

        final String requestedDeviceId = config.getDeviceId();
        JSONObject body = new JSONObject();
        try {
            body.put("action", "get_status");
            body.put("device_id", requestedDeviceId);
        } catch (org.json.JSONException e) {
            polling = false;
            return;
        }
        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                url,
                body,
                response -> {
                    polling = false;
                    if (!requestedDeviceId.equals(config.getDeviceId())) { getSensorData(); return; }
                    if (url.equals(config.getHttpApiUrl())) {
                        apiDatabaseOk = "success".equals(response.optString("status"))
                                && response.optInt("api_version") == 2;
                        lastApiCheckMs = System.currentTimeMillis();
                    }
                    lastHttpError = "";
                    JSONObject state = response.optJSONObject("state");
                    JSONObject sample = response.optJSONObject("last_received_sample");
                    espOnline = state != null && state.optBoolean("is_recent_report", false);
                    espIpAddress = state == null ? "" : state.optString("lan_ip", "");
                    lastSystemStatusMs = state == null ? 0 : state.optLong("last_seen_at_ms");
                    reportedSystemStatus = state;
                    remoteControlReady = response.optBoolean("remote_control_ready", false);
                    if (state != null) {
                        pumpInOn = state.optBoolean("pump_in");
                        pumpOutOn = state.optBoolean("pump_out");
                        temperatureControl.update(true, state.optBoolean("temperature_enabled"));
                        waterControl.update(true, state.optBoolean("water_enabled"));
                        confirmPumpFromStatus();
                    }
                    double temp = sample == null ? Double.NaN : sample.optDouble("temperature", Double.NaN);
                    boolean validTemp = !Double.isNaN(temp) && !Double.isInfinite(temp);
                    int waterRaw = sample == null ? -1 : sample.optInt("water_adc", -1);
                    if (sample != null && !sample.optString("sample_id").equals(lastSampleId)) {
                        lastSampleId = sample.optString("sample_id");
                        lastTelemetryTimestampMs = System.currentTimeMillis();
                    }
                    currentTemp = validTemp ? temp : Double.NaN;
                    currentWaterRaw = waterRaw >= 0 && waterRaw <= 4095 ? waterRaw : -1;
                    currentWaterLevel = waterRaw >= 0
                            ? (waterRaw < config.getWaterLevelMin() ? 0
                            : waterRaw > config.getWaterLevelMax() ? 2 : 1) : -1;
                    updateTemperature(currentTemp);
                    updateWaterLevel(currentWaterLevel, currentWaterRaw);
                    updateTimestamp();
                    evaluateAlerts();
                    renderPumpStates();
                    updateLocalWebAccess();
                    renderSystemStatus();

                    txtConnection.setText("HTTP API: Đã kết nối • " + url);
                    txtConnection.setTextColor(Color.rgb(22, 163, 74));

                    updateGeneralStatusAndErrors();
                },
                error -> {
                    polling = false;
                    if (!requestedDeviceId.equals(config.getDeviceId())) { getSensorData(); return; }
                    if (error.networkResponse != null && error.networkResponse.statusCode == 401) {
                        UserSession.clear(this);
                        startActivity(new Intent(this, LoginActivity.class));
                        finish();
                        return;
                    }
                    if (url.equals(config.getHttpApiUrl())) {
                        apiDatabaseOk = false;
                        remoteControlReady = false;
                        lastApiCheckMs = System.currentTimeMillis();
                    }
                    lastHttpError = "Lỗi kết nối";
                    renderPumpStates();
                    Log.e(TAG, "Lỗi API v2 get_status: " + error);
                    txtConnection.setText("HTTP API: Không thể kết nối (" + error.getMessage() + ")");
                    txtConnection.setTextColor(Color.rgb(220, 38, 38));

                    updateGeneralStatusAndErrors();
                }
        ) {
            @Override
            public java.util.Map<String, String> getHeaders() {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Authorization", "Bearer " + UserSession.getToken());
                return headers;
            }
        };

        request.setShouldCache(false);
        request.setRetryPolicy(new DefaultRetryPolicy(4000, 0, 1.0f));
        request.setTag(TAG);
        requestQueue.add(request);
    }

    // ==================== HIỂN THỊ CẢM BIẾN & CẢNH BÁO ====================

    private void updateWaterLevel(int water, int waterRaw) {
        if (waterControl.isDisabled()) {
            txtWater.setText("Đã tắt");
            txtWater.setTextColor(Color.rgb(100, 116, 139));
            txtWaterStatus.setText("Cảm biến mực nước đang tắt");
            return;
        }
        boolean withinMeasuredRange = config.isWaterAdcWithinCalibration(waterRaw);
        if (withinMeasuredRange) {
            txtWater.setText(String.format(Locale.getDefault(), "%.1f cm",
                    config.waterAdcToCm(waterRaw)));
        } else {
            txtWater.setText(!config.hasWaterLevelCalibration() ? "Chưa hiệu chuẩn"
                    : waterRaw >= 0 ? "Ngoài khoảng đo" : "Chưa có dữ liệu");
        }
        if (!withinMeasuredRange) {
            txtWater.setTextColor(Color.rgb(100, 116, 139));
            txtWaterStatus.setText(config.hasWaterLevelCalibration()
                    ? "Số đo nằm ngoài khoảng cảm biến đã hiệu chuẩn"
                    : "Chưa có khoảng đo được hiệu chuẩn");
            return;
        }
        if (water == 1) {
            txtWater.setTextColor(Color.rgb(22, 163, 74));
            txtWaterStatus.setText("Nước trong khoảng an toàn");
        } else if (water == 0) {
            txtWater.setTextColor(Color.rgb(220, 38, 38));
            txtWaterStatus.setText("Cảnh báo: mực nước dưới ngưỡng thấp");
        } else if (water == 2) {
            txtWater.setTextColor(Color.rgb(220, 38, 38));
            txtWaterStatus.setText("Cảnh báo: mực nước vượt ngưỡng cao");
        } else {
            txtWater.setTextColor(Color.rgb(100, 116, 139));
            txtWaterStatus.setText("Chưa có trạng thái cảm biến nước");
        }
    }

    private void updateTemperature(double temp) {
        if (temperatureControl.isDisabled()) {
            txtTemp.setText("Đã tắt");
            txtTemp.setTextColor(Color.rgb(100, 116, 139));
            txtTempStatus.setText("Cảm biến nhiệt độ đang tắt");
            return;
        }
        if (Double.isNaN(temp) || Double.isInfinite(temp)) {
            return;
        }

        txtTemp.setText(String.format(Locale.getDefault(), "%.1f °C", temp));

        float min = config.getTempMin();
        float max = config.getTempMax();

        if (temp > max) {
            txtTemp.setTextColor(Color.rgb(220, 38, 38));
            txtTempStatus.setText("Nhiệt độ cao hơn ngưỡng cài đặt (" + max + "°C)");
        } else if (temp < min) {
            txtTemp.setTextColor(Color.rgb(2, 132, 199));
            txtTempStatus.setText("Nhiệt độ thấp hơn ngưỡng cài đặt (" + min + "°C)");
        } else {
            txtTemp.setTextColor(Color.rgb(15, 23, 42));
            txtTempStatus.setText("Nhiệt độ ổn định, an toàn");
        }
    }

    private void evaluateAlerts() {
        float tempMin = config.getTempMin();
        float tempMax = config.getTempMax();

        boolean tempAlert = false;
        StringBuilder alertMsg = new StringBuilder();

        if (!temperatureControl.isDisabled() && !Double.isNaN(currentTemp)) {
            if (currentTemp > tempMax) {
                tempAlert = true;
                alertMsg.append(String.format(Locale.getDefault(),
                        "⚠ CẢNH BÁO: Nhiệt độ quá cao (%.1f °C > Max %.1f °C). ", currentTemp, tempMax));
            } else if (currentTemp < tempMin) {
                tempAlert = true;
                alertMsg.append(String.format(Locale.getDefault(),
                        "⚠ CẢNH BÁO: Nhiệt độ quá thấp (%.1f °C < Min %.1f °C). ", currentTemp, tempMin));
            }
        }

        if (!waterControl.isDisabled() && currentWaterLevel == 0) {
            tempAlert = true;
            alertMsg.append("⚠ Mực nước cạn nguy hiểm, cần cấp nước!");
        } else if (!waterControl.isDisabled() && currentWaterLevel == 2) {
            tempAlert = true;
            alertMsg.append("⚠ Mực nước vượt ngưỡng cao đã cài đặt; kiểm tra cảm biến!");
        }

        if (tempAlert && !(isAdmin && selectedMainTab == 0)) {
            alertBannerContainer.setVisibility(View.VISIBLE);
            txtAlert.setText(alertMsg.toString());
        } else {
            alertBannerContainer.setVisibility(View.GONE);
        }
    }

    private void updateTimestamp() {
        String time = new SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(new Date());
        txtUpdated.setText("Cập nhật gần nhất: " + time);
    }

    private void selectMainTab(int tab) {
        selectedMainTab = tab;
        dashboardContent.setVisibility(tab == 0 && !isAdmin ? View.VISIBLE : View.GONE);
        adminOverviewContent.setVisibility(tab == 0 && isAdmin ? View.VISIBLE : View.GONE);
        txtDevicePicker.setVisibility(tab == 0 && isAdmin ? View.GONE : View.VISIBLE);
        controlContent.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        systemStatusContent.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        int menuId = tab == 0 ? R.id.navOverview : tab == 1 ? R.id.navControl : R.id.navSystem;
        mainNavigation.getMenu().findItem(menuId).setChecked(true);
        if (tab == 0 && isAdmin) {
            renderAdminOverview();
            if (System.currentTimeMillis() - lastAdminRefreshMs >= 15000)
                loadAllDeviceStatuses();
        }
        evaluateAlerts();
        renderSystemStatus();
        rootScroll.scrollTo(0, 0);
    }

    private void renderSystemStatus() {
        if (systemStatusDiagram == null || config == null) return;
        boolean apiReady = Boolean.TRUE.equals(apiDatabaseOk);
        boolean apiFailed = Boolean.FALSE.equals(apiDatabaseOk);
        boolean hasReport = reportedSystemStatus != null;
        boolean deviceFresh = apiReady && hasReport && espOnline;

        SystemStatusView.State summaryState = apiFailed ? SystemStatusView.State.ERROR
                : !apiReady ? SystemStatusView.State.UNKNOWN
                : deviceFresh ? SystemStatusView.State.ON : SystemStatusView.State.WAIT;
        String summaryText = apiFailed ? "Không truy cập API"
                : !apiReady ? "Đang kiểm tra"
                : deviceFresh ? "Đang hoạt động" : "Chờ trạng thái ESP";
        String summaryDetail = lastSystemStatusMs > 0
                ? "ESP báo lần cuối lúc " + statusTime(lastSystemStatusMs)
                : "Chưa có báo cáo từ ESP";
        SystemStatusView.Node summary = new SystemStatusView.Node(
                config.getDeviceId(), summaryText, summaryDetail, summaryState);

        List<SystemStatusView.Node> connections = new ArrayList<>();
        connections.add(new SystemStatusView.Node("App → Tunnel → API",
                apiReady ? "Đã kết nối" : apiFailed ? "Lỗi kết nối" : "Đang kiểm tra",
                "Kiểm tra lúc " + statusTime(lastApiCheckMs),
                apiReady ? SystemStatusView.State.ON : apiFailed
                        ? SystemStatusView.State.ERROR : SystemStatusView.State.UNKNOWN));
        connections.add(new SystemStatusView.Node("ESP → Backend",
                deviceFresh ? "Báo mới" : hasReport ? "Báo cáo cũ" : "Chưa xác minh",
                "Lần báo gần nhất: " + statusTime(lastSystemStatusMs),
                deviceFresh ? SystemStatusView.State.ON : hasReport
                        ? SystemStatusView.State.WAIT : SystemStatusView.State.UNKNOWN));

        List<SystemStatusView.Node> device = new ArrayList<>();
        device.add(deviceNode("Cảm biến nhiệt độ", "temperature_enabled", deviceFresh));
        device.add(deviceNode("Cảm biến nước", "water_enabled", deviceFresh));
        device.add(deviceNode("Bơm cấp", "pump_in", deviceFresh));
        device.add(deviceNode("Bơm thoát", "pump_out", deviceFresh));
        systemStatusDiagram.setSnapshot(summary, connections, device);
        txtSystemStatusUpdated.setText("Thiết bị: " + config.getDeviceId()
                + "  ·  API cập nhật lúc " + statusTime(lastApiCheckMs));
    }

    private SystemStatusView.Node deviceNode(String title, String key, boolean available) {
        if (!available || reportedSystemStatus == null || !reportedSystemStatus.has(key)) {
            return new SystemStatusView.Node(title, "Chưa xác minh", "",
                    SystemStatusView.State.UNKNOWN);
        }
        boolean enabled = reportedSystemStatus.optBoolean(key);
        return new SystemStatusView.Node(title, enabled ? "Đang bật" : "Đã tắt", "",
                enabled ? SystemStatusView.State.ON : SystemStatusView.State.OFF);
    }

    private String statusTime(long timestamp) {
        return timestamp == 0 ? "chưa có" : new SimpleDateFormat(
                "HH:mm:ss", Locale.getDefault()).format(new Date(timestamp));
    }

    // ==================== HIỂN THỊ TRẠNG THÁI BƠM (DOUBLE CHECK) ====================

    private void confirmPumpFromStatus() {
        if (!espOnline) return;
        if (pumpInPending && pumpInAckApplied && lastSystemStatusMs > pumpInRequestedAtMs
                && pumpInOn == desiredPumpInOn) {
            handler.removeCallbacks(pumpInTimeoutRunnable);
            pumpInPending = false;
            pumpInAckApplied = false;
            finishPumpLog(true, "confirmed", "ESP xác nhận và báo trạng thái bơm cấp khớp lệnh");
            showActionFeedback("ESP đã xác nhận bơm cấp.", false);
        }
        if (pumpOutPending && pumpOutAckApplied && lastSystemStatusMs > pumpOutRequestedAtMs
                && pumpOutOn == desiredPumpOutOn) {
            handler.removeCallbacks(pumpOutTimeoutRunnable);
            pumpOutPending = false;
            pumpOutAckApplied = false;
            finishPumpLog(false, "confirmed", "ESP xác nhận và báo trạng thái bơm thoát khớp lệnh");
            showActionFeedback("ESP đã xác nhận bơm thoát.", false);
        }
    }

    private void handlePumpTimeout(boolean isPumpIn) {
        if (!activityVisible) return;
        if (isPumpIn && pumpInPending) {
            finishPumpLog(true, "timeout", "Chưa nhận được trạng thái ESP32 khớp yêu cầu");
            pumpInPending = false;
            pumpInAckApplied = false;
            renderPumpStates();
            showActionFeedback("Quá thời gian chờ xác nhận bơm cấp. Hãy kiểm tra thiết bị.", true);
            updateGeneralStatusAndErrors();
        } else if (!isPumpIn && pumpOutPending) {
            finishPumpLog(false, "timeout", "Chưa nhận được trạng thái ESP32 khớp yêu cầu");
            pumpOutPending = false;
            pumpOutAckApplied = false;
            renderPumpStates();
            showActionFeedback("Quá thời gian chờ xác nhận bơm thoát. Hãy kiểm tra thiết bị.", true);
            updateGeneralStatusAndErrors();
        }
    }

    private boolean canControlDevice() {
        if (!Boolean.TRUE.equals(apiDatabaseOk) || !espOnline || !remoteControlReady) return false;
        for (int i = 0; i < availableDevices.length(); i++) {
            JSONObject device = availableDevices.optJSONObject(i);
            if (device != null && config.getDeviceId().equals(device.optString("device_id")))
                return device.optBoolean("can_control") && device.optBoolean("is_active");
        }
        return false;
    }

    private boolean requireMqttControl() {
        if (canControlDevice()) return true;
        showActionFeedback("Thiết bị, worker MQTT hoặc quyền điều khiển chưa sẵn sàng.", true);
        return false;
    }

    private void clearPendingPumpCommands() {
        handler.removeCallbacks(pumpInTimeoutRunnable);
        handler.removeCallbacks(pumpOutTimeoutRunnable);
        if (pumpInPending) finishPumpLog(true, "interrupted", "Rời màn hình hoặc mất kết nối");
        if (pumpOutPending) finishPumpLog(false, "interrupted", "Rời màn hình hoặc mất kết nối");
        pumpInPending = false;
        pumpOutPending = false;
        pumpInAckApplied = false;
        pumpOutAckApplied = false;
    }

    private void finishPumpLog(boolean pumpIn, String result, String detail) {
        String operation = pumpIn ? pumpInLog : pumpOutLog;
        if (operation != null) OperationLog.get(this).finish(operation, result, detail);
        if (pumpIn) pumpInLog = null;
        else pumpOutLog = null;
    }

    private void renderPumpStates() {
        if (btnPumpIn == null || btnPumpOut == null) {
            return;
        }

        temperatureControl.render();
        waterControl.render();
        updateTemperature(currentTemp);
        updateWaterLevel(currentWaterLevel, currentWaterRaw);
        evaluateAlerts();

        if (switchInterlock != null) switchInterlock.setEnabled(false);
        // 1. Bơm cấp nước
        if (pumpInPending) {
            btnPumpIn.setEnabled(false);
            btnPumpIn.setText(desiredPumpInOn ? "Đang bật bơm..." : "Đang tắt bơm...");
            btnPumpIn.setBackgroundResource(R.drawable.bg_button_secondary);
            btnPumpIn.setTextColor(getColor(R.color.text_secondary));
            txtPumpInStatus.setText("Trạng thái: Đang đợi ESP32 xác nhận (Double Check)...");
            txtPumpInStatus.setTextColor(Color.rgb(217, 119, 6));
        } else {
            btnPumpIn.setEnabled(canControlDevice());
            btnPumpIn.setText(!canControlDevice() ? "Chờ thiết bị / quyền điều khiển" : pumpInOn ? "Tắt bơm cấp" : "Bật bơm cấp");
            btnPumpIn.setBackgroundResource(!canControlDevice() ? R.drawable.bg_button_secondary
                    : pumpInOn ? R.drawable.bg_button_danger : R.drawable.bg_button_primary);
            btnPumpIn.setTextColor(getColor(canControlDevice() ? R.color.white : R.color.text_secondary));
            txtPumpInStatus.setText(!canControlDevice() ? "Chưa xác nhận trạng thái ESP32"
                    : "Trạng thái: " + (pumpInOn ? "Đang bật" : "Đã tắt"));
            txtPumpInStatus.setTextColor(canControlDevice() && pumpInOn
                    ? Color.rgb(220, 38, 38) : Color.rgb(100, 116, 139));
        }

        // 2. Bơm thoát nước
        if (pumpOutPending) {
            btnPumpOut.setEnabled(false);
            btnPumpOut.setText(desiredPumpOutOn ? "Đang bật bơm..." : "Đang tắt bơm...");
            btnPumpOut.setBackgroundResource(R.drawable.bg_button_secondary);
            btnPumpOut.setTextColor(getColor(R.color.text_secondary));
            txtPumpOutStatus.setText("Trạng thái: Đang đợi ESP32 xác nhận (Double Check)...");
            txtPumpOutStatus.setTextColor(Color.rgb(217, 119, 6));
        } else {
            btnPumpOut.setEnabled(canControlDevice());
            btnPumpOut.setText(!canControlDevice() ? "Chờ thiết bị / quyền điều khiển" : pumpOutOn ? "Tắt bơm thoát" : "Bật bơm thoát");
            btnPumpOut.setBackgroundResource(!canControlDevice() ? R.drawable.bg_button_secondary
                    : pumpOutOn ? R.drawable.bg_button_danger : R.drawable.bg_button_primary);
            btnPumpOut.setTextColor(getColor(canControlDevice() ? R.color.white : R.color.text_secondary));
            txtPumpOutStatus.setText(!canControlDevice() ? "Chưa xác nhận trạng thái ESP32"
                    : "Trạng thái: " + (pumpOutOn ? "Đang bật" : "Đã tắt"));
            txtPumpOutStatus.setTextColor(canControlDevice() && pumpOutOn
                    ? Color.rgb(220, 38, 38) : Color.rgb(100, 116, 139));
        }
        if (!canControlDevice()) {
            String unavailable = "ESP, worker MQTT hoặc quyền điều khiển chưa sẵn sàng.";
            txtPumpInStatus.setText(unavailable);
            txtPumpOutStatus.setText(unavailable);
        }
    }
}
