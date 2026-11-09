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

    // Địa chỉ máy tính chạy XAMPP và MQTT Broker.
    // Sửa IP này nếu máy tính của bạn đã đổi địa chỉ.
    private static final String API_URL =
            "http://192.168.10.214/pool_api/pool_api.php";
    private static final String MQTT_BROKER =
            "tcp://192.168.10.214:1767";

    // Topic điều khiển hai máy bơm.
    private static final String TOPIC_CMD_PUMP_IN =
            "smartpool/cmd/bom_vao";
    private static final String TOPIC_CMD_PUMP_OUT =
            "smartpool/cmd/bom_ra";
    private static final String TOPIC_STATUS =
            "smartpool/status";
    private static final String TOPIC_TELEMETRY =
            "smartpool/telemetry";

    // Ngưỡng cảnh báo nhiệt độ tham khảo.
    private static final double TEMP_MIN = 20.0;
    private static final double TEMP_MAX = 35.0;

    private static final long POLL_INTERVAL_MS = 3000L;

    private View rootScroll;
    private View headerContainer;

    private TextView txtConnection;
    private TextView txtMqttStatus;
    private TextView txtAlert;
    private TextView txtTemp;
    private TextView txtWater;
    private TextView txtPumpInStatus;
    private TextView txtPumpOutStatus;
    private TextView txtUpdated;

    private Button btnPumpIn;
    private Button btnPumpOut;

    private RequestQueue requestQueue;
    private MqttAsyncClient mqttClient;

    private ActivityResultLauncher<String> notificationPermissionLauncher;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean activityVisible;
    private boolean mqttConnecting;
    private boolean mqttConnected;
    private boolean espOnline;
    private boolean polling;

    private boolean pumpInOn;
    private boolean pumpOutOn;

    private boolean pumpInPending;
    private boolean pumpOutPending;

    private long lastMqttAttemptMs;

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

            handler.postDelayed(this, POLL_INTERVAL_MS);
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

        rootScroll = findViewById(R.id.rootScroll);
        headerContainer = findViewById(R.id.headerContainer);

        txtConnection = findViewById(R.id.txtConnection);
        txtMqttStatus = findViewById(R.id.txtMqttStatus);
        txtAlert = findViewById(R.id.txtAlert);
        txtTemp = findViewById(R.id.txtTemp);
        txtWater = findViewById(R.id.txtWater);

        txtPumpInStatus = findViewById(R.id.txtPumpInStatus);
        txtPumpOutStatus = findViewById(R.id.txtPumpOutStatus);

        txtUpdated = findViewById(R.id.txtUpdated);

        btnPumpIn = findViewById(R.id.btnPumpIn);
        btnPumpOut = findViewById(R.id.btnPumpOut);

        applySystemBarInsets();

        requestQueue = Volley.newRequestQueue(getApplicationContext());

        // Nút điều khiển bơm cấp nước từ bể phụ vào bể chính.
        btnPumpIn.setOnClickListener(v -> {

            if (pumpInPending) {
                return;
            }

            // Không cho bật bơm cấp nếu bơm thoát đang chạy.
            if (!pumpInOn && pumpOutOn) {
                Toast.makeText(
                        this,
                        "Hãy tắt bơm thoát trước khi bật bơm cấp.",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            sendPumpCommand(true, !pumpInOn);
        });

        // Nút điều khiển bơm thoát nước từ bể chính ra ngoài.
        btnPumpOut.setOnClickListener(v -> {

            if (pumpOutPending) {
                return;
            }

            // Không cho bật bơm thoát nếu bơm cấp đang chạy.
            if (!pumpOutOn && pumpInOn) {
                Toast.makeText(
                        this,
                        "Hãy tắt bơm cấp trước khi bật bơm thoát.",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            sendPumpCommand(false, !pumpOutOn);
        });

        renderPumpStates();

        requestNotificationPermissionAndStartService();
    }

    private void requestNotificationPermissionAndStartService() {

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {

            notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
            );

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
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
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

        if (mqttClient != null && mqttClient.isConnected()) {
            mqttConnected = true;
            updateMqttStatus();
            return;
        }

        mqttConnecting = true;
        lastMqttAttemptMs = System.currentTimeMillis();

        txtMqttStatus.setText("MQTT: Đang kết nối Broker...");
        txtMqttStatus.setTextColor(Color.YELLOW);

        try {

            if (mqttClient == null) {

                String clientId = "SmartPoolUI-"
                        + UUID.randomUUID().toString().substring(0, 8);

                mqttClient = new MqttAsyncClient(
                        MQTT_BROKER,
                        clientId,
                        new MemoryPersistence()
                );

                mqttClient.setCallback(new MqttCallbackExtended() {

                    @Override
                    public void connectComplete(boolean reconnect, String uri) {

                        mqttConnected = true;
                        mqttConnecting = false;

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
                    public void messageArrived(
                            String topic,
                            org.eclipse.paho.client.mqttv3.MqttMessage message
                    ) {

                        String payload = new String(
                                message.getPayload(),
                                StandardCharsets.UTF_8
                        );

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
            options.setConnectionTimeout(5);
            options.setKeepAliveInterval(30);

            mqttClient.connect(options, null, new IMqttActionListener() {

                @Override
                public void onSuccess(IMqttToken token) {

                    mqttConnecting = false;
                    mqttConnected = true;

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

                    Log.e(TAG, "Kết nối MQTT thất bại", error);

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

            mqttClient.subscribe(TOPIC_STATUS, 1);
            mqttClient.subscribe(TOPIC_TELEMETRY, 1);

        } catch (MqttException e) {
            Log.e(TAG, "Lỗi đăng ký topic", e);
        }
    }

    private void handleMqttMessage(String topic, String payload) {

        try {

            JSONObject json = new JSONObject(payload);

            if (TOPIC_STATUS.equals(topic)) {

                boolean online = json.optInt("online", 0) == 1;

                boolean in = json.optInt("bom_vao_control", 0) == 1;

                boolean out = json.optInt("bom_ra_control", 0) == 1;

                handler.post(() -> {

                    if (!activityVisible) {
                        return;
                    }

                    espOnline = online;

                    pumpInOn = in;
                    pumpOutOn = out;

                    renderPumpStates();
                    updateMqttStatus();
                });

            } else if (TOPIC_TELEMETRY.equals(topic)) {

                double temp = json.optDouble("nhiet_do", Double.NaN);
                int water = json.optInt("muc_nuoc", -1);

                handler.post(() -> {

                    if (!activityVisible) {
                        return;
                    }

                    updateTemperature(temp);
                    updateWaterLevel(water);
                    updateTimestamp();
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "JSON MQTT không hợp lệ", e);
        }
    }

    // ==================== ĐIỀU KHIỂN MÁY BƠM ====================

    private void sendPumpCommand(boolean pumpIn, boolean turnOn) {

        if (mqttClient == null || !mqttClient.isConnected()) {

            Toast.makeText(
                    this,
                    "MQTT chưa kết nối Broker.",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        if (pumpIn) {
            pumpInPending = true;
            btnPumpIn.setEnabled(false);

        } else {
            pumpOutPending = true;
            btnPumpOut.setEnabled(false);
        }

        String topic = pumpIn
                ? TOPIC_CMD_PUMP_IN
                : TOPIC_CMD_PUMP_OUT;

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

                                finishPumpRequest(pumpIn);

                                Toast.makeText(
                                        MainActivity.this,
                                        "Đã gửi lệnh, chờ ESP32 xác nhận.",
                                        Toast.LENGTH_SHORT
                                ).show();
                            });
                        }

                        @Override
                        public void onFailure(
                                IMqttToken token,
                                Throwable error
                        ) {

                            handler.post(() -> {

                                finishPumpRequest(pumpIn);

                                Toast.makeText(
                                        MainActivity.this,
                                        "Gửi lệnh điều khiển thất bại.",
                                        Toast.LENGTH_LONG
                                ).show();
                            });

                            Log.e(TAG, "Publish MQTT lỗi", error);
                        }
                    }
            );

        } catch (MqttException e) {

            finishPumpRequest(pumpIn);

            Log.e(TAG, "Không gửi được lệnh MQTT", e);
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
        }
    }

    private void updateMqttStatus() {

        if (!mqttConnected) {

            txtMqttStatus.setText("MQTT: Chưa kết nối Broker");
            txtMqttStatus.setTextColor(Color.YELLOW);

        } else if (!espOnline) {

            txtMqttStatus.setText(
                    "MQTT: Broker đã kết nối, ESP32 chưa online"
            );

            txtMqttStatus.setTextColor(Color.YELLOW);

        } else {

            txtMqttStatus.setText(
                    "MQTT: Đã kết nối • ESP32 đang online"
            );

            txtMqttStatus.setTextColor(
                    Color.rgb(134, 239, 172)
            );
        }
    }

    // ==================== HTTP / XAMPP ====================

    private void getSensorData() {

        if (polling || requestQueue == null) {
            return;
        }

        polling = true;

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.GET,
                API_URL,
                null,

                response -> {

                    polling = false;

                    double temp = response.optDouble(
                            "nhiet_do",
                            Double.NaN
                    );
                    int water = response.optInt("muc_nuoc", -1);

                    updateTemperature(temp);
                    updateWaterLevel(water);
                    updateTimestamp();

                    txtConnection.setText(
                            "HTTP: Đã kết nối XAMPP"
                    );

                    txtConnection.setTextColor(
                            Color.rgb(134, 239, 172)
                    );
                },

                error -> {

                    polling = false;

                    Log.e(TAG, "Lỗi HTTP GET: " + error);

                    txtConnection.setText(
                            "HTTP: Không kết nối được XAMPP/API"
                    );

                    txtConnection.setTextColor(
                            Color.rgb(254, 202, 202)
                    );
                }
        );

        request.setShouldCache(false);

        request.setRetryPolicy(
                new DefaultRetryPolicy(
                        4000,
                        0,
                        1.0f
                )
        );

        request.setTag(TAG);

        requestQueue.add(request);
    }

    // ==================== HIỂN THỊ NHIỆT ĐỘ ====================

    private void updateWaterLevel(int water) {
        if (water == 1) {
            txtWater.setText("Mực nước: Bình thường");
            txtWater.setTextColor(Color.rgb(21, 128, 61));
        } else if (water == 0) {
            txtWater.setText("Mực nước: Thấp - kiểm tra bể");
            txtWater.setTextColor(Color.rgb(185, 28, 28));
        }
    }

    private void updateTemperature(double temp) {

        if (Double.isNaN(temp) || Double.isInfinite(temp)) {
            return;
        }

        txtTemp.setText(
                String.format(
                        Locale.getDefault(),
                        "%.1f °C",
                        temp
                )
        );

        if (temp > TEMP_MAX) {

            txtAlert.setVisibility(View.VISIBLE);

            txtAlert.setText(
                    String.format(
                            Locale.getDefault(),
                            "⚠ CẢNH BÁO: Nhiệt độ cao %.1f °C (tối đa %.1f °C)",
                            temp,
                            TEMP_MAX
                    )
            );

        } else if (temp < TEMP_MIN) {

            txtAlert.setVisibility(View.VISIBLE);

            txtAlert.setText(
                    String.format(
                            Locale.getDefault(),
                            "⚠ CẢNH BÁO: Nhiệt độ thấp %.1f °C (tối thiểu %.1f °C)",
                            temp,
                            TEMP_MIN
                    )
            );

        } else {

            txtAlert.setVisibility(View.GONE);
        }
    }

    private void updateTimestamp() {

        String time = new SimpleDateFormat(
                "HH:mm:ss dd/MM/yyyy",
                Locale.getDefault()
        ).format(new Date());

        txtUpdated.setText(
                "Cập nhật gần nhất: " + time
        );
    }

    // ==================== HIỂN THỊ TRẠNG THÁI BƠM ====================

    private void renderPumpStates() {

        if (btnPumpIn == null) {
            return;
        }

        btnPumpIn.setText(
                pumpInOn
                        ? "TẮT BƠM CẤP NƯỚC"
                        : "BẬT BƠM CẤP NƯỚC"
        );

        btnPumpIn.setBackgroundResource(
                pumpInOn
                        ? R.drawable.bg_button_danger
                        : R.drawable.bg_button_primary
        );

        txtPumpInStatus.setText(
                "Trạng thái: " + (pumpInOn ? "ĐANG BẬT" : "ĐANG TẮT")
        );

        txtPumpInStatus.setTextColor(
                pumpInOn
                        ? Color.rgb(198, 40, 40)
                        : Color.rgb(98, 125, 152)
        );

        btnPumpOut.setText(
                pumpOutOn
                        ? "TẮT BƠM THOÁT NƯỚC"
                        : "BẬT BƠM THOÁT NƯỚC"
        );

        btnPumpOut.setBackgroundResource(
                pumpOutOn
                        ? R.drawable.bg_button_danger
                        : R.drawable.bg_button_primary
        );

        txtPumpOutStatus.setText(
                "Trạng thái: " + (pumpOutOn ? "ĐANG BẬT" : "ĐANG TẮT")
        );

        txtPumpOutStatus.setTextColor(
                pumpOutOn
                        ? Color.rgb(198, 40, 40)
                        : Color.rgb(98, 125, 152)
        );
    }
}
