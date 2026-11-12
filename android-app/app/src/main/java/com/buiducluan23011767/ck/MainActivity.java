package com.buiducluan23011767.ck;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SmartPool";

    private View rootScroll;
    private View headerContainer;
    private View alertBannerContainer;

    private TextView txtHeaderSubtitle;
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
    private Button btnGoToSettings;

    private TextView txtUpdated;

    private RequestQueue requestQueue;
    private MqttAsyncClient mqttClient;

    private ActivityResultLauncher<String> notificationPermissionLauncher;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private AppConfig config;

    private boolean activityVisible;
    private boolean mqttConnecting;
    private boolean mqttConnected;
    private boolean espOnline;
    private boolean polling;

    private boolean pumpInOn;
    private boolean pumpOutOn;

    private boolean pumpInPending;
    private boolean pumpOutPending;
    private boolean desiredPumpInOn;
    private boolean desiredPumpOutOn;

    private static final long PUMP_ACK_TIMEOUT_MS = 7000L;
    private final Runnable pumpInTimeoutRunnable = () -> handlePumpTimeout(true);
    private final Runnable pumpOutTimeoutRunnable = () -> handlePumpTimeout(false);

    private long lastTelemetryTimestampMs = 0L;
    private static final long SENSOR_TIMEOUT_MS = 15000L;

    private double currentTemp = Double.NaN;
    private int currentWaterLevel = -1;
    private String lastHttpError = "";

    private long lastMqttAttemptMs;
    private String connectedBrokerUri = "";

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!activityVisible) {
                return;
            }

            getSensorData();

            if (!mqttConnected && !mqttConnecting
                    && System.currentTimeMillis() - lastMqttAttemptMs >= 5000L) {
                connectMqtt();
            }

            long intervalMs = Math.max(1000L, config.getHttpPollIntervalSec() * 1000L);
            handler.postDelayed(this, intervalMs);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> startMonitorService()
        );

        setContentView(R.layout.activity_main);

        config = AppConfig.getInstance(this);

        initViews();
        applySystemBarInsets();
        setupListeners();

        requestQueue = Volley.newRequestQueue(getApplicationContext());

        renderPumpStates();
        requestNotificationPermissionAndStartService();
    }

    private void initViews() {
        rootScroll = findViewById(R.id.rootScroll);
        headerContainer = findViewById(R.id.headerContainer);
        alertBannerContainer = findViewById(R.id.alertBannerContainer);

        txtHeaderSubtitle = findViewById(R.id.txtHeaderSubtitle);
        btnOpenSettings = findViewById(R.id.btnOpenSettings);

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
        btnGoToSettings = findViewById(R.id.btnGoToSettings);

        txtUpdated = findViewById(R.id.txtUpdated);
    }

    private void setupListeners() {
        View.OnClickListener openSettingsAction = v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        };

        btnOpenSettings.setOnClickListener(openSettingsAction);
        btnGoToSettings.setOnClickListener(openSettingsAction);

        btnPumpIn.setOnClickListener(v -> {
            if (pumpInPending) return;
            if (config.isInterlockEnabled() && !pumpInOn && pumpOutOn) {
                Toast.makeText(this, "Khóa liên động đang BẬT: Hãy tắt bơm thoát trước khi bật bơm cấp.", Toast.LENGTH_LONG).show();
                return;
            }
            boolean targetState = !pumpInOn;
            desiredPumpInOn = targetState;
            pumpInPending = true;
            renderPumpStates();

            handler.removeCallbacks(pumpInTimeoutRunnable);
            handler.postDelayed(pumpInTimeoutRunnable, PUMP_ACK_TIMEOUT_MS);

            sendPumpCommand(true, targetState);
        });

        btnPumpOut.setOnClickListener(v -> {
            if (pumpOutPending) return;
            if (config.isInterlockEnabled() && !pumpOutOn && pumpInOn) {
                Toast.makeText(this, "Khóa liên động đang BẬT: Hãy tắt bơm cấp trước khi bật bơm thoát.", Toast.LENGTH_LONG).show();
                return;
            }
            boolean targetState = !pumpOutOn;
            desiredPumpOutOn = targetState;
            pumpOutPending = true;
            renderPumpStates();

            handler.removeCallbacks(pumpOutTimeoutRunnable);
            handler.postDelayed(pumpOutTimeoutRunnable, PUMP_ACK_TIMEOUT_MS);

            sendPumpCommand(false, targetState);
        });

        switchInterlock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setInterlockEnabled(isChecked);
            updateInterlockUI(isChecked);
            Toast.makeText(
                    this,
                    isChecked ? "Đã BẬT khóa liên động (Chặn chạy 2 bơm cùng lúc)"
                            : "Đã TẮT khóa liên động (Cho phép bật đồng thời 2 bơm)",
                    Toast.LENGTH_SHORT
            ).show();

            // Nếu đang kết nối MQTT, gửi cập nhật cấu hình interlock lên topic thresholds
            if (mqttClient != null && mqttClient.isConnected()) {
                try {
                    byte[] payload = config.buildThresholdsJson().toString().getBytes(StandardCharsets.UTF_8);
                    mqttClient.publish(config.getTopicConfigThresholds(), payload, 1, true);
                } catch (Exception e) {
                    Log.e(TAG, "Lỗi sync interlock sang MQTT", e);
                }
            }
        });
    }

    private void refreshConfigUI() {
        txtDeviceBadge.setText(config.getDeviceId());
        txtHeaderSubtitle.setText(config.getDeviceName().toUpperCase(Locale.getDefault())
                + " • " + config.getDeviceLocation());

        txtControlModeBadge.setText("Chế độ: " + config.getControlMode().toUpperCase(Locale.getDefault()));

        txtTempThresholdInfo.setText(String.format(
                Locale.getDefault(),
                "Ngưỡng: %.1f°C - %.1f°C",
                config.getTempMin(),
                config.getTempMax()
        ));

        txtWaterThresholdInfo.setText(String.format(
                Locale.getDefault(),
                "Ngưỡng: %.0f%% - %.0f%%",
                config.getWaterLevelMin(),
                config.getWaterLevelMax()
        ));

        updateInterlockUI(config.isInterlockEnabled());

        txtMqttCurrentBroker.setText("Broker: HiveMQ Cloud (TLS 8883)");
        txtMqttCurrentTopics.setText(
                "• Topic Ngưỡng: " + config.getTopicConfigThresholds()
                        + "\n• Topic WiFi: " + config.getTopicConfigWifi()
                        + "\n• Topic Telemetry: " + config.getTopicTelemetry()
        );

        updateGeneralStatusAndErrors();

        // Đánh giá lại cảnh báo nếu đã có số liệu nhiệt độ
        if (!Double.isNaN(currentTemp)) {
            evaluateAlerts();
        }
    }

    private void updateInterlockUI(boolean isChecked) {
        if (switchInterlock != null && switchInterlock.isChecked() != isChecked) {
            switchInterlock.setChecked(isChecked);
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

        // 1. Kiểm tra kết nối MQTT
        if (!mqttConnected) {
            statusBuilder.append("⚠ Mất kết nối HiveMQ Broker. ");
        } else if (!espOnline) {
            statusBuilder.append("ℹ MQTT đã kết nối • Chờ ESP32 online. ");
        }

        // 2. Kiểm tra lỗi HTTP nếu có
        if (!lastHttpError.isEmpty() && "http".equalsIgnoreCase(config.getControlMode())) {
            statusBuilder.append("⚠ HTTP API: ").append(lastHttpError).append(". ");
        }

        // 3. Kiểm tra cảm biến (Double check độ tươi mới dữ liệu)
        boolean sensorStale = (lastTelemetryTimestampMs > 0
                && (System.currentTimeMillis() - lastTelemetryTimestampMs > SENSOR_TIMEOUT_MS));

        if (Double.isNaN(currentTemp)) {
            statusBuilder.append("ℹ Chờ dữ liệu cảm biến. ");
        } else if (sensorStale && mqttConnected) {
            statusBuilder.append("⚠ Mất tín hiệu cảm biến (>15s chưa có số liệu mới). ");
            txtTempStatus.setText("DS18B20 • Mất tín hiệu (>15s)");
            txtTempStatus.setTextColor(Color.rgb(220, 38, 38));
            txtWaterStatus.setText("Mực nước • Mất tín hiệu (>15s)");
            txtWaterStatus.setTextColor(Color.rgb(220, 38, 38));
        }

        if (statusBuilder.length() == 0) {
            txtGeneralStatus.setText("✓ Trạng thái chung: Hệ thống đang vận hành bình thường");
            txtGeneralStatus.setTextColor(Color.rgb(134, 239, 172)); // Xanh lá dịu
        } else {
            txtGeneralStatus.setText(statusBuilder.toString().trim());
            txtGeneralStatus.setTextColor(Color.rgb(254, 202, 202)); // Đỏ nhạt cảnh báo
        }
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

    private void applySystemBarInsets() {
        if (rootScroll == null || headerContainer == null) {
            return;
        }

        final int headerTop = headerContainer.getPaddingTop();
        final int bottom = rootScroll.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(rootScroll, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );

            headerContainer.setPadding(
                    headerContainer.getPaddingLeft(),
                    headerTop + bars.top,
                    headerContainer.getPaddingRight(),
                    headerContainer.getPaddingBottom()
            );

            view.setPadding(
                    view.getPaddingLeft(),
                    view.getPaddingTop(),
                    view.getPaddingRight(),
                    Math.max(bottom, bars.bottom)
            );

            return insets;
        });

        ViewCompat.requestApplyInsets(rootScroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;

        refreshConfigUI();

        // Kiểm tra nếu broker đổi thì ngắt và kết nối lại
        String targetBroker = config.getMqttBroker();
        if (!targetBroker.equals(connectedBrokerUri) && mqttClient != null) {
            closeMqtt();
        }

        connectMqtt();

        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
    }

    @Override
    protected void onPause() {
        activityVisible = false;
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
            return;
        }

        mqttConnecting = true;
        lastMqttAttemptMs = System.currentTimeMillis();

        txtMqttBadge.setText("MQTT: Đang kết nối Broker...");
        txtMqttBadge.setTextColor(Color.rgb(251, 191, 36));

        try {
            if (mqttClient == null) {
                String clientId = "SmartPoolUI-" + UUID.randomUUID().toString().substring(0, 8);
                mqttClient = new MqttAsyncClient(broker, clientId, new MemoryPersistence());

                mqttClient.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String uri) {
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
                        mqttConnected = false;
                        handler.post(() -> {
                            if (activityVisible) {
                                updateMqttStatus();
                            }
                        });
                        Log.e(TAG, "Mất kết nối MQTT", cause);
                    }

                    @Override
                    public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
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

            mqttClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
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
            String topicTelemetry = config.getTopicTelemetry();
            mqttClient.subscribe(topicStatus, 1);
            mqttClient.subscribe(topicTelemetry, 1);
        } catch (MqttException e) {
            Log.e(TAG, "Lỗi đăng ký topic", e);
        }
    }

    private void handleMqttMessage(String topic, String payload) {
        try {
            JSONObject json = new JSONObject(payload);

            if (topic.equals(config.getTopicStatus())) {
                boolean online = json.optInt("online", 0) == 1;
                boolean in = json.optInt("bom_vao_control", 0) == 1;
                boolean out = json.optInt("bom_ra_control", 0) == 1;

                handler.post(() -> {
                    if (!activityVisible) return;
                    espOnline = online;

                    // DOUBLE CHECK BƠM CẤP
                    if (pumpInPending) {
                        if (in == desiredPumpInOn) {
                            handler.removeCallbacks(pumpInTimeoutRunnable);
                            pumpInPending = false;
                            pumpInOn = in;
                            Toast.makeText(MainActivity.this,
                                    "✓ ESP32 đã xác nhận " + (in ? "BẬT" : "TẮT") + " Bơm cấp thành công!",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        pumpInOn = in;
                    }

                    // DOUBLE CHECK BƠM THOÁT
                    if (pumpOutPending) {
                        if (out == desiredPumpOutOn) {
                            handler.removeCallbacks(pumpOutTimeoutRunnable);
                            pumpOutPending = false;
                            pumpOutOn = out;
                            Toast.makeText(MainActivity.this,
                                    "✓ ESP32 đã xác nhận " + (out ? "BẬT" : "TẮT") + " Bơm thoát thành công!",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        pumpOutOn = out;
                    }

                    renderPumpStates();
                    updateMqttStatus();
                    updateGeneralStatusAndErrors();
                });

            } else if (topic.equals(config.getTopicTelemetry())) {
                double temp = json.optDouble("nhiet_do", Double.NaN);
                int water = json.optInt("muc_nuoc", -1);

                handler.post(() -> {
                    if (!activityVisible) return;
                    lastTelemetryTimestampMs = System.currentTimeMillis();
                    currentTemp = temp;
                    currentWaterLevel = water;

                    updateTemperature(temp);
                    updateWaterLevel(water);
                    updateTimestamp();
                    evaluateAlerts();
                    updateGeneralStatusAndErrors();
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "JSON MQTT không hợp lệ", e);
        }
    }

    // ==================== ĐIỀU KHIỂN MÁY BƠM ====================

    private void sendPumpCommand(boolean pumpIn, boolean turnOn) {
        if ("http".equalsIgnoreCase(config.getControlMode())) {
            sendHttpPumpCommand(pumpIn, turnOn);
            return;
        }

        if (mqttClient == null || !mqttClient.isConnected()) {
            Toast.makeText(this, "MQTT chưa kết nối Broker.", Toast.LENGTH_LONG).show();
            return;
        }

        if (pumpIn) {
            pumpInPending = true;
            btnPumpIn.setEnabled(false);
        } else {
            pumpOutPending = true;
            btnPumpOut.setEnabled(false);
        }

        String topic = pumpIn ? config.getTopicCmdPumpIn() : config.getTopicCmdPumpOut();

        try {
            mqttClient.publish(
                    topic,
                    (turnOn ? "1" : "0").getBytes(StandardCharsets.UTF_8),
                    1,
                    false,
                    null,
                    new IMqttActionListener() {
                        @Override
                        public void onSuccess(IMqttToken token) {
                            handler.post(() -> {
                                Log.i(TAG, "Đã gửi lệnh tới HiveMQ Broker, đang đợi ESP32 kích hoạt Relay...");
                                Toast.makeText(MainActivity.this, "Đã gửi lệnh, đang đợi ESP32 phản hồi...", Toast.LENGTH_SHORT).show();
                            });
                        }

                        @Override
                        public void onFailure(IMqttToken token, Throwable error) {
                            handler.post(() -> {
                                if (pumpIn) {
                                    handler.removeCallbacks(pumpInTimeoutRunnable);
                                    pumpInPending = false;
                                } else {
                                    handler.removeCallbacks(pumpOutTimeoutRunnable);
                                    pumpOutPending = false;
                                }
                                renderPumpStates();
                                Toast.makeText(MainActivity.this, "Gửi lệnh điều khiển thất bại.", Toast.LENGTH_LONG).show();
                            });
                            Log.e(TAG, "Publish MQTT lỗi", error);
                        }
                    }
            );

        } catch (MqttException e) {
            if (pumpIn) {
                handler.removeCallbacks(pumpInTimeoutRunnable);
                pumpInPending = false;
            } else {
                handler.removeCallbacks(pumpOutTimeoutRunnable);
                pumpOutPending = false;
            }
            renderPumpStates();
            Log.e(TAG, "Không gửi được lệnh MQTT", e);
        }
    }

    private void sendHttpPumpCommand(boolean pumpIn, boolean turnOn) {
        if (requestQueue == null) {
            return;
        }
        boolean desiredIn = pumpIn ? turnOn : pumpInOn;
        boolean desiredOut = pumpIn ? pumpOutOn : turnOn;
        if (config.isInterlockEnabled() && desiredIn && desiredOut) {
            Toast.makeText(this, "Khóa liên động đang BẬT: Không thể bật hai bơm cùng lúc.", Toast.LENGTH_LONG).show();
            return;
        }
        if (pumpIn) {
            pumpInPending = true;
            btnPumpIn.setEnabled(false);
        } else {
            pumpOutPending = true;
            btnPumpOut.setEnabled(false);
        }

        try {
            JSONObject command = new JSONObject();
            command.put("bom_vao", desiredIn ? 1 : 0);
            command.put("bom_ra", desiredOut ? 1 : 0);
            JSONObject body = new JSONObject();
            body.put("command", command);

            JsonObjectRequest request = new JsonObjectRequest(
                    Request.Method.POST,
                    config.getHttpApiUrl(),
                    body,
                    response -> {
                        finishPumpRequest(pumpIn);
                        Toast.makeText(this, "Đã gửi lệnh qua HTTP, chờ ESP32 xác nhận.", Toast.LENGTH_SHORT).show();
                    },
                    error -> {
                        finishPumpRequest(pumpIn);
                        Log.e(TAG, "Lệnh bơm qua HTTP thất bại", error);
                        Toast.makeText(this, "Không gửi được lệnh qua HTTP API.", Toast.LENGTH_LONG).show();
                    }
            );
            request.setShouldCache(false);
            request.setRetryPolicy(new DefaultRetryPolicy(4000, 0, 1.0f));
            request.setTag(TAG);
            requestQueue.add(request);
        } catch (Exception error) {
            finishPumpRequest(pumpIn);
            Log.e(TAG, "Không tạo được lệnh HTTP", error);
        }
    }

    private void finishPumpRequest(boolean pumpIn) {
        if (pumpIn) {
            pumpInPending = false;
            btnPumpIn.setEnabled(true);
        } else {
            pumpOutPending = false;
            btnPumpOut.setEnabled(true);
        }
    }

    private void closeMqtt() {
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
        if (!mqttConnected) {
            txtMqttBadge.setText("MQTT: Mất kết nối HiveMQ Broker");
            txtMqttBadge.setTextColor(Color.rgb(220, 38, 38));
        } else if (!espOnline) {
            txtMqttBadge.setText("MQTT: Đã kết nối Broker • Chờ ESP32 online");
            txtMqttBadge.setTextColor(Color.rgb(217, 119, 6));
        } else {
            txtMqttBadge.setText("MQTT: Đã kết nối • ESP32 đang Online");
            txtMqttBadge.setTextColor(Color.rgb(22, 163, 74));
        }
        updateGeneralStatusAndErrors();
    }

    // ==================== HTTP / XAMPP ====================

    private void getSensorData() {
        if (polling || requestQueue == null) {
            return;
        }

        String url = config.getHttpApiUrl();
        if (url == null || url.trim().isEmpty()) {
            txtConnection.setText("HTTP API: Chưa cấu hình URL");
            txtConnection.setTextColor(Color.rgb(148, 163, 184));
            lastHttpError = "Chưa cấu hình URL";
            updateGeneralStatusAndErrors();
            return;
        }

        polling = true;

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.GET,
                url,
                null,
                response -> {
                    polling = false;
                    lastHttpError = "";

                    double temp = response.optDouble("nhiet_do", Double.NaN);
                    int water = response.optInt("muc_nuoc", -1);

                    if ("http".equalsIgnoreCase(config.getControlMode()) || !mqttConnected) {
                        pumpInOn = response.optInt("bom_control", 0) == 1;
                        pumpOutOn = response.optInt("van_control", 0) == 1;
                        renderPumpStates();
                    }

                    currentTemp = temp;
                    currentWaterLevel = water;

                    updateTemperature(temp);
                    updateWaterLevel(water);
                    updateTimestamp();
                    evaluateAlerts();

                    txtConnection.setText("HTTP API: Đã kết nối • " + url);
                    txtConnection.setTextColor(Color.rgb(22, 163, 74));

                    updateGeneralStatusAndErrors();
                },
                error -> {
                    polling = false;
                    lastHttpError = "Lỗi kết nối";
                    Log.e(TAG, "Lỗi HTTP GET: " + error);
                    txtConnection.setText("HTTP API: Không thể kết nối (" + error.getMessage() + ")");
                    txtConnection.setTextColor(Color.rgb(220, 38, 38));

                    updateGeneralStatusAndErrors();
                }
        );

        request.setShouldCache(false);
        request.setRetryPolicy(new DefaultRetryPolicy(4000, 0, 1.0f));
        request.setTag(TAG);
        requestQueue.add(request);
    }

    // ==================== HIỂN THỊ CẢM BIẾN & CẢNH BÁO ====================

    private void updateWaterLevel(int water) {
        if (water == 1) {
            txtWater.setText("Bình thường");
            txtWater.setTextColor(Color.rgb(22, 163, 74));
            txtWaterStatus.setText("Nước trong khoảng an toàn");
        } else if (water == 0) {
            txtWater.setText("CẢNH BÁO: Cạn nước");
            txtWater.setTextColor(Color.rgb(220, 38, 38));
            txtWaterStatus.setText("Mực nước thấp hơn ngưỡng min");
        } else if (water == 2) {
            txtWater.setText("CẢNH BÁO: Tràn nước");
            txtWater.setTextColor(Color.rgb(220, 38, 38));
            txtWaterStatus.setText("Mực nước vượt ngưỡng max");
        }
    }

    private void updateTemperature(double temp) {
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

        if (!Double.isNaN(currentTemp)) {
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

        if (currentWaterLevel == 0) {
            tempAlert = true;
            alertMsg.append("⚠ Mực nước cạn nguy hiểm, cần cấp nước!");
        } else if (currentWaterLevel == 2) {
            tempAlert = true;
            alertMsg.append("⚠ Mực nước tràn hồ, cần xả bớt nước!");
        }

        if (tempAlert) {
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

    // ==================== HIỂN THỊ TRẠNG THÁI BƠM (DOUBLE CHECK) ====================

    private void handlePumpTimeout(boolean isPumpIn) {
        if (!activityVisible) return;
        if (isPumpIn && pumpInPending) {
            pumpInPending = false;
            renderPumpStates();
            Toast.makeText(this,
                    "⚠ Quá thời gian (7s): ESP32 chưa xác nhận trạng thái Bơm cấp! Vui lòng kiểm tra thiết bị.",
                    Toast.LENGTH_LONG).show();
            updateGeneralStatusAndErrors();
        } else if (!isPumpIn && pumpOutPending) {
            pumpOutPending = false;
            renderPumpStates();
            Toast.makeText(this,
                    "⚠ Quá thời gian (7s): ESP32 chưa xác nhận trạng thái Bơm thoát! Vui lòng kiểm tra thiết bị.",
                    Toast.LENGTH_LONG).show();
            updateGeneralStatusAndErrors();
        }
    }

    private void renderPumpStates() {
        if (btnPumpIn == null || btnPumpOut == null) {
            return;
        }

        // 1. Bơm cấp nước
        if (pumpInPending) {
            btnPumpIn.setEnabled(false);
            btnPumpIn.setText(desiredPumpInOn ? "⏳ ĐANG BẬT..." : "⏳ ĐANG TẮT...");
            btnPumpIn.setBackgroundResource(R.drawable.bg_button_secondary);
            txtPumpInStatus.setText("Trạng thái: Đang đợi ESP32 xác nhận (Double Check)...");
            txtPumpInStatus.setTextColor(Color.rgb(217, 119, 6));
        } else {
            btnPumpIn.setEnabled(true);
            btnPumpIn.setText(pumpInOn ? "TẮT BƠM" : "BẬT BƠM");
            btnPumpIn.setBackgroundResource(pumpInOn ? R.drawable.bg_button_danger : R.drawable.bg_button_primary);
            txtPumpInStatus.setText("Trạng thái: " + (pumpInOn ? "ĐANG BẬT" : "ĐANG TẮT"));
            txtPumpInStatus.setTextColor(pumpInOn ? Color.rgb(220, 38, 38) : Color.rgb(100, 116, 139));
        }

        // 2. Bơm thoát nước
        if (pumpOutPending) {
            btnPumpOut.setEnabled(false);
            btnPumpOut.setText(desiredPumpOutOn ? "⏳ ĐANG BẬT..." : "⏳ ĐANG TẮT...");
            btnPumpOut.setBackgroundResource(R.drawable.bg_button_secondary);
            txtPumpOutStatus.setText("Trạng thái: Đang đợi ESP32 xác nhận (Double Check)...");
            txtPumpOutStatus.setTextColor(Color.rgb(217, 119, 6));
        } else {
            btnPumpOut.setEnabled(true);
            btnPumpOut.setText(pumpOutOn ? "TẮT BƠM" : "BẬT BƠM");
            btnPumpOut.setBackgroundResource(pumpOutOn ? R.drawable.bg_button_danger : R.drawable.bg_button_primary);
            txtPumpOutStatus.setText("Trạng thái: " + (pumpOutOn ? "ĐANG BẬT" : "ĐANG TẮT"));
            txtPumpOutStatus.setTextColor(pumpOutOn ? Color.rgb(220, 38, 38) : Color.rgb(100, 116, 139));
        }
    }
}
