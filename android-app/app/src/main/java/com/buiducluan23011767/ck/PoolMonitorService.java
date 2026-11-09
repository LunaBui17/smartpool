package com.buiducluan23011767.ck;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

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
import java.util.Locale;
import java.util.UUID;

public class PoolMonitorService extends Service {

    private static final String TAG = "PoolMonitorService";

    // HiveMQ Cloud qua TLS, cùng broker với MainActivity.
    private static final String MQTT_BROKER =
            "ssl://24660077a88741378be5ffe7f979f2f3.s1.eu.hivemq.cloud:8883";

    private static final String TOPIC_TELEMETRY =
            "smartpool/telemetry";

    private static final String SERVICE_CHANNEL_ID =
            "smartpool_monitor_service";

    private static final String ALERT_CHANNEL_ID =
            "smartpool_safety_alerts";

    // Ngưỡng cảnh báo nhiệt độ.
    private static final double TEMP_MIN = 20.0;
    private static final double TEMP_MAX = 35.0;

    private static final int SERVICE_NOTIFICATION_ID = 1001;
    private static final int ALERT_NOTIFICATION_ID = 1002;
    private static final int RECOVERY_NOTIFICATION_ID = 1003;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private NotificationManager notificationManager;

    private MqttAsyncClient mqttClient;

    private boolean mqttConnecting;

    private String lastAlertKey = "";

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {

            if (mqttClient == null || !mqttClient.isConnected()) {
                connectMqtt();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        notificationManager = (NotificationManager)
                getSystemService(NOTIFICATION_SERVICE);

        createNotificationChannels();

        Notification notification = buildServiceNotification(
                "Đang khởi tạo giám sát nhiệt độ"
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            startForeground(
                    SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            );

        } else {

            startForeground(
                    SERVICE_NOTIFICATION_ID,
                    notification
            );
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        connectMqtt();

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== TẠO KÊNH THÔNG BÁO ====================

    private void createNotificationChannels() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel serviceChannel =
                new NotificationChannel(
                        SERVICE_CHANNEL_ID,
                        "Smart Pool - Giám sát nền",
                        NotificationManager.IMPORTANCE_LOW
                );

        serviceChannel.setDescription(
                "Thông báo dịch vụ giám sát MQTT đang chạy"
        );

        serviceChannel.setShowBadge(false);

        NotificationChannel alertChannel =
                new NotificationChannel(
                        ALERT_CHANNEL_ID,
                        "Cảnh báo nhiệt độ Smart Pool",
                        NotificationManager.IMPORTANCE_HIGH
                );

        alertChannel.setDescription(
                "Cảnh báo khi nhiệt độ bể chính vượt ngưỡng"
        );

        alertChannel.enableVibration(true);

        alertChannel.setVibrationPattern(
                new long[]{0, 500, 250, 500, 250, 700}
        );

        Uri sound = RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_NOTIFICATION
        );

        AudioAttributes attrs =
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(
                                AudioAttributes.CONTENT_TYPE_SONIFICATION
                        )
                        .build();

        alertChannel.setSound(sound, attrs);

        notificationManager.createNotificationChannel(serviceChannel);
        notificationManager.createNotificationChannel(alertChannel);
    }

    // ==================== THÔNG BÁO DỊCH VỤ NỀN ====================

    private Notification buildServiceNotification(String status) {

        Intent openIntent = new Intent(this, MainActivity.class);

        openIntent.setFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NEW_TASK
        );

        PendingIntent pending = PendingIntent.getActivity(
                this,
                1001,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            builder = new Notification.Builder(
                    this,
                    SERVICE_CHANNEL_ID
            );

        } else {

            builder = new Notification.Builder(this);
        }

        return builder
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("SMART POOL")
                .setContentText(status)
                .setStyle(
                        new Notification.BigTextStyle().bigText(status)
                )
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateServiceNotification(String message) {

        if (notificationManager != null) {

            notificationManager.notify(
                    SERVICE_NOTIFICATION_ID,
                    buildServiceNotification(message)
            );
        }
    }

    // ==================== KẾT NỐI MQTT ====================

    private synchronized void connectMqtt() {

        if (mqttConnecting
                || (mqttClient != null && mqttClient.isConnected())) {
            return;
        }

        if (BuildConfig.HIVEMQ_USERNAME.isEmpty() || BuildConfig.HIVEMQ_PASSWORD.isEmpty()) {
            updateServiceNotification("Chưa cấu hình tài khoản HiveMQ");
            return;
        }

        mqttConnecting = true;

        try {

            if (mqttClient == null) {

                String clientId = "SmartPoolMonitor-"
                        + UUID.randomUUID().toString().substring(0, 8);

                mqttClient = new MqttAsyncClient(
                        MQTT_BROKER,
                        clientId,
                        new MemoryPersistence()
                );

                mqttClient.setCallback(new MqttCallbackExtended() {

                    @Override
                    public void connectComplete(
                            boolean reconnect,
                            String uri
                    ) {

                        mqttConnecting = false;

                        subscribeTopics();

                        updateServiceNotification(
                                "Đang theo dõi nhiệt độ bể chính qua MQTT"
                        );
                    }

                    @Override
                    public void connectionLost(Throwable cause) {

                        mqttConnecting = false;

                        updateServiceNotification(
                                "Mất MQTT - đang thử kết nối lại"
                        );

                        scheduleReconnect();
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
                    public void deliveryComplete(
                            IMqttDeliveryToken token
                    ) {
                    }
                });
            }

            MqttConnectOptions options = new MqttConnectOptions();

            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);
            options.setUserName(BuildConfig.HIVEMQ_USERNAME);
            options.setPassword(BuildConfig.HIVEMQ_PASSWORD.toCharArray());

            mqttClient.connect(
                    options,
                    null,
                    new IMqttActionListener() {

                        @Override
                        public void onSuccess(IMqttToken token) {

                            mqttConnecting = false;

                            subscribeTopics();
                        }

                        @Override
                        public void onFailure(
                                IMqttToken token,
                                Throwable error
                        ) {

                            mqttConnecting = false;

                            Log.e(
                                    TAG,
                                    "Kết nối MQTT thất bại",
                                    error
                            );

                            updateServiceNotification(
                                    "Chưa kết nối Broker - đang thử lại"
                            );

                            scheduleReconnect();
                        }
                    }
            );

        } catch (MqttException e) {

            mqttConnecting = false;

            Log.e(TAG, "Lỗi khởi tạo MQTT", e);

            scheduleReconnect();
        }
    }

    private void subscribeTopics() {

        if (mqttClient == null || !mqttClient.isConnected()) {
            return;
        }

        try {

            mqttClient.subscribe(
                    TOPIC_TELEMETRY,
                    1
            );

        } catch (MqttException e) {

            Log.e(
                    TAG,
                    "Không subscribe được telemetry",
                    e
            );

            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {

        handler.removeCallbacks(reconnectRunnable);

        handler.postDelayed(
                reconnectRunnable,
                5000L
        );
    }

    // ==================== ĐỌC DỮ LIỆU CẢM BIẾN ====================

    private void handleMqttMessage(
            String topic,
            String payload
    ) {

        if (!TOPIC_TELEMETRY.equals(topic)) {
            return;
        }

        try {

            JSONObject json = new JSONObject(payload);

            double temp = json.optDouble(
                    "nhiet_do",
                    Double.NaN
            );

            if (Double.isNaN(temp) || Double.isInfinite(temp)) {
                return;
            }

            checkTemperature(temp);

        } catch (Exception e) {

            Log.e(TAG, "Không đọc được telemetry", e);
        }
    }

    // ==================== KIỂM TRA NHIỆT ĐỘ ====================

    private void checkTemperature(double temp) {

        String key = "";
        String message = "";

        if (temp > TEMP_MAX) {

            key = "TEMP_HIGH";

            message = String.format(
                    Locale.getDefault(),
                    "Nhiệt độ bể chính cao: %.1f °C (ngưỡng tối đa %.1f °C)",
                    temp,
                    TEMP_MAX
            );

        } else if (temp < TEMP_MIN) {

            key = "TEMP_LOW";

            message = String.format(
                    Locale.getDefault(),
                    "Nhiệt độ bể chính thấp: %.1f °C (ngưỡng tối thiểu %.1f °C)",
                    temp,
                    TEMP_MIN
            );
        }

        // Nếu nhiệt độ trở về mức bình thường.
        if (key.isEmpty()) {

            if (!lastAlertKey.isEmpty()) {

                lastAlertKey = "";

                notificationManager.cancel(
                        ALERT_NOTIFICATION_ID
                );

                showRecoveryNotification();
            }

            return;
        }

        // Tránh gửi thông báo lặp liên tục cùng một cảnh báo.
        if (key.equals(lastAlertKey)) {
            return;
        }

        lastAlertKey = key;

        showSafetyNotification(message);
    }

    // ==================== GỬI THÔNG BÁO CẢNH BÁO ====================

    private void showSafetyNotification(String message) {

        Intent intent = new Intent(this, MainActivity.class);

        intent.setFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NEW_TASK
        );

        PendingIntent pending = PendingIntent.getActivity(
                this,
                2002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            builder = new Notification.Builder(
                    this,
                    ALERT_CHANNEL_ID
            );

        } else {

            builder = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL);
        }

        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("CẢNH BÁO SMART POOL")
                .setContentText(
                        "Nhiệt độ bể chính vượt ngưỡng"
                )
                .setStyle(
                        new Notification.BigTextStyle().bigText(message)
                )
                .setCategory(Notification.CATEGORY_ALARM)
                .setColor(Color.rgb(198, 40, 40))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();

        notificationManager.notify(
                ALERT_NOTIFICATION_ID,
                notification
        );
    }

    // ==================== THÔNG BÁO KHI NHIỆT ĐỘ ỔN ĐỊNH ====================

    private void showRecoveryNotification() {

        Intent intent = new Intent(this, MainActivity.class);

        PendingIntent pending = PendingIntent.getActivity(
                this,
                2003,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            builder = new Notification.Builder(
                    this,
                    ALERT_CHANNEL_ID
            );

        } else {

            builder = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_DEFAULT);
        }

        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("SMART POOL")
                .setContentText(
                        "Nhiệt độ bể chính đã trở về ngưỡng cài đặt"
                )
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();

        notificationManager.notify(
                RECOVERY_NOTIFICATION_ID,
                notification
        );
    }

    // ==================== ĐÓNG DỊCH VỤ ====================

    @Override
    public void onDestroy() {

        handler.removeCallbacksAndMessages(null);

        if (mqttClient != null) {

            try {

                if (mqttClient.isConnected()) {
                    mqttClient.disconnect();
                }

                mqttClient.close();

            } catch (Exception e) {

                Log.e(
                        TAG,
                        "Lỗi đóng MQTT Service",
                        e
                );
            }
        }

        super.onDestroy();
    }
}
