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

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PoolMonitorService extends Service {

    private static final String TAG = "PoolMonitorService";

    private static final String SERVICE_CHANNEL_ID = "smartpool_monitor_service";
    private static final String ALERT_CHANNEL_ID = "smartpool_safety_alerts";

    private static final int SERVICE_NOTIFICATION_ID = 1001;
    private static final int ALERT_NOTIFICATION_ID = 1002;
    private static final int RECOVERY_NOTIFICATION_ID = 1003;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private NotificationManager notificationManager;
    private final ExecutorService apiWorker = Executors.newSingleThreadExecutor();
    private boolean temperatureEnabled = true;
    private String lastAlertKey = "";

    private AppConfig config;
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            apiWorker.execute(() -> pollApi());
            handler.postDelayed(this, 30000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        UserSession.restore(this);

        config = AppConfig.getInstance(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        createNotificationChannels();

        Notification notification = buildServiceNotification("Đang khởi tạo giám sát bể bơi thông minh");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            );
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(pollRunnable);
        Log.i(TAG, "Giám sát nền đang tạm khóa trong giai đoạn tích hợp context v2.");
        stopForeground(true);
        stopSelf();
        return START_NOT_STICKY;
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

        NotificationChannel serviceChannel = new NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Smart Pool - Giám sát nền",
                NotificationManager.IMPORTANCE_LOW
        );
        serviceChannel.setDescription("Thông báo dịch vụ giám sát Smart Pool đang chạy");
        serviceChannel.setShowBadge(false);

        NotificationChannel alertChannel = new NotificationChannel(
                ALERT_CHANNEL_ID,
                "Cảnh báo nhiệt độ Smart Pool",
                NotificationManager.IMPORTANCE_HIGH
        );
        alertChannel.setDescription("Cảnh báo khi nhiệt độ bể chính vượt ngưỡng");
        alertChannel.enableVibration(true);
        alertChannel.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 700});

        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, SERVICE_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("SMART POOL")
                .setContentText(status)
                .setStyle(new Notification.BigTextStyle().bigText(status))
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

    private void pollApi() {
        String token = UserSession.getToken();
        if (!UserSession.isAuthenticated() || token == null) {
            handler.post(() -> updateServiceNotification("Sign in to monitor SmartPool"));
            return;
        }
        HttpURLConnection connection = null;
        try {
            JSONObject body = new JSONObject();
            body.put("action", "get_status");
            body.put("device_id", config.getDeviceId());
            connection = (HttpURLConnection) new URL(config.getHttpApiUrl()).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200) throw new IllegalStateException("API HTTP " + connection.getResponseCode());
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            JSONObject response = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            JSONObject state = response.optJSONObject("state");
            JSONObject sample = response.optJSONObject("last_received_sample");
            if (state == null || !state.optBoolean("is_recent_report", false)) {
                handler.post(() -> updateServiceNotification("SmartPool device offline"));
                return;
            }
            boolean enabled = state.optBoolean("temperature_enabled", false);
            double temperature = sample == null ? Double.NaN : sample.optDouble("temperature", Double.NaN);
            long receivedAt = 0;
            if (sample != null) {
                String rawTime = sample.optString("received_at", "");
                if (rawTime.length() >= 19) {
                    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                    format.setTimeZone(TimeZone.getTimeZone("UTC"));
                    Date parsed = format.parse(rawTime.substring(0, 19));
                    if (parsed != null) receivedAt = parsed.getTime();
                }
            }
            boolean fresh = receivedAt > 0 && System.currentTimeMillis() - receivedAt <= 120000L;
            handler.post(() -> {
                temperatureEnabled = enabled;
                updateServiceNotification("SmartPool online via API v2");
                if (!enabled) {
                    lastAlertKey = "";
                    notificationManager.cancel(ALERT_NOTIFICATION_ID);
                }
                if (enabled && fresh && Double.isFinite(temperature)) checkTemperature(temperature);
            });
        } catch (Exception error) {
            Log.w(TAG, "API v2 monitor request failed", error);
            handler.post(() -> updateServiceNotification("SmartPool API unavailable"));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void checkTemperature(double temp) {
        float tempMin = config.getTempMin();
        float tempMax = config.getTempMax();

        String key = "";
        String message = "";

        if (temp > tempMax) {
            key = "TEMP_HIGH";
            message = String.format(
                    Locale.getDefault(),
                    "Nhiệt độ bể chính cao: %.1f °C (ngưỡng tối đa %.1f °C)",
                    temp,
                    tempMax
            );
        } else if (temp < tempMin) {
            key = "TEMP_LOW";
            message = String.format(
                    Locale.getDefault(),
                    "Nhiệt độ bể chính thấp: %.1f °C (ngưỡng tối thiểu %.1f °C)",
                    temp,
                    tempMin
            );
        }

        // Nếu nhiệt độ trở về mức bình thường
        if (key.isEmpty()) {
            if (!lastAlertKey.isEmpty()) {
                lastAlertKey = "";
                notificationManager.cancel(ALERT_NOTIFICATION_ID);
                showRecoveryNotification();
            }
            return;
        }

        // Tránh gửi thông báo lặp liên tục cùng một cảnh báo
        if (key.equals(lastAlertKey)) {
            return;
        }

        lastAlertKey = key;
        showSafetyNotification(message);
    }

    // ==================== GỬI THÔNG BÁO CẢNH BÁO ====================

    private void showSafetyNotification(String message) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pending = PendingIntent.getActivity(
                this,
                2002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, ALERT_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL);
        }

        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("CẢNH BÁO SMART POOL")
                .setContentText("Nhiệt độ bể bơi vượt ngưỡng an toàn")
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setCategory(Notification.CATEGORY_ALARM)
                .setColor(Color.rgb(198, 40, 40))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();

        notificationManager.notify(ALERT_NOTIFICATION_ID, notification);
    }

    // ==================== THÔNG BÁO KHI NHIỆT ĐỘ ỔN ĐỊNH ====================

    private void showRecoveryNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                2003,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, ALERT_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_DEFAULT);
        }

        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("SMART POOL")
                .setContentText("Nhiệt độ bể bơi đã trở về khoảng an toàn")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();

        notificationManager.notify(RECOVERY_NOTIFICATION_ID, notification);
    }

    // ==================== ĐÓNG DỊCH VỤ ====================

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        apiWorker.shutdownNow();
        notificationManager.cancel(SERVICE_NOTIFICATION_ID);
        notificationManager.cancel(ALERT_NOTIFICATION_ID);
        notificationManager.cancel(RECOVERY_NOTIFICATION_ID);
        super.onDestroy();
    }
}
