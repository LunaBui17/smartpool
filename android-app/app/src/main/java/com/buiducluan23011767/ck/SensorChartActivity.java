package com.buiducluan23011767.ck;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;


import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;

public class SensorChartActivity extends AppCompatActivity {
    public static final String EXTRA_SENSOR = "sensor";
    public static final String SENSOR_TEMPERATURE = "temperature";
    public static final String SENSOR_ADC = "adc";

    private static final String TAG = "SensorChartActivity";
    private static final int HISTORY_LIMIT = 100;

    private final List<HistoryPoint> temperatures = new ArrayList<>();
    private final List<HistoryPoint> adcValues = new ArrayList<>();
    private long startMs;
    private long endMs;
    private int missingTimestamps;
    private TextView title;
    private TextView status;
    private Button temperatureTab;
    private Button adcTab;
    private FrameLayout chartContainer;
    private ApiClient api;
    private DeviceContext.Selection selectedDevice;
    private String selectedSensor = SENSOR_TEMPERATURE;
    private boolean historyLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserSession.restore(this);
        if (!UserSession.isAuthenticated()) {
            ApiClient.expire(this);
            finish();
            return;
        }
        selectedDevice = DeviceContext.current();
        if (selectedDevice == null) {
            openDeviceList();
            return;
        }
        setContentView(R.layout.activity_sensor_chart);
        ScreenInsets.apply(findViewById(R.id.chartRoot));

        title = findViewById(R.id.txtChartTitle);
        status = findViewById(R.id.txtChartStatus);
        temperatureTab = findViewById(R.id.btnTemperatureTab);
        adcTab = findViewById(R.id.btnAdcTab);
        chartContainer = findViewById(R.id.chartContainer);

        String requestedSensor = savedInstanceState != null
                ? savedInstanceState.getString(EXTRA_SENSOR)
                : getIntent().getStringExtra(EXTRA_SENSOR);
        if (requestedSensor == null) requestedSensor = AppConfig.getInstance(this).getChartSensor();
        selectedSensor = SENSOR_ADC.equals(requestedSensor) ? SENSOR_ADC : SENSOR_TEMPERATURE;

        findViewById(R.id.btnChartBack).setOnClickListener(v -> finish());
        temperatureTab.setOnClickListener(v -> selectSensor(SENSOR_TEMPERATURE));
        adcTab.setOnClickListener(v -> selectSensor(SENSOR_ADC));

        selectSensor(selectedSensor);
    }

    @Override protected void onResume() {
        super.onResume();
        if (chartContainer == null) return;
        if (!UserSession.isAuthenticated()) {
            ApiClient.expire(this);
            return;
        }
        if (!selectedDevice.isCurrent()) {
            openDeviceList();
            return;
        }
        api = new ApiClient(this);
        loadHistory();
    }

    private void openDeviceList() {
        startActivity(new android.content.Intent(this, DeviceListActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    private void selectSensor(String sensor) {
        selectedSensor = sensor;
        AppConfig.getInstance(this).setChartSensor(sensor);
        boolean temperatureSelected = SENSOR_TEMPERATURE.equals(sensor);
        boolean calibrated = AppConfig.getInstance(this).hasWaterLevelCalibration();
        title.setText(temperatureSelected ? "Biểu đồ nhiệt độ nước"
                : calibrated ? "Biểu đồ mực nước (cm)" : "Biểu đồ cảm biến nước (ADC)");
        adcTab.setText(calibrated ? "Mực nước (cm)" : "Cảm biến nước (ADC)");
        temperatureTab.setSelected(temperatureSelected);
        adcTab.setSelected(!temperatureSelected);
        temperatureTab.setBackgroundResource(temperatureSelected
                ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        adcTab.setBackgroundResource(temperatureSelected
                ? R.drawable.bg_button_secondary : R.drawable.bg_button_primary);
        temperatureTab.setTextColor(temperatureSelected ? Color.WHITE : Color.rgb(37, 99, 235));
        adcTab.setTextColor(temperatureSelected ? Color.rgb(37, 99, 235) : Color.WHITE);
        if (historyLoaded) updateHistoryStatus();
        showChart();
    }

    private void loadHistory() {
        String baseUrl = AppConfig.getInstance(this).getHttpApiUrl().trim();
        if (baseUrl.isEmpty()) {
            status.setText(UserSession.isAdmin()
                    ? "Chưa cấu hình URL HTTP API." : "Chưa tải được lịch sử cảm biến.");
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("action", "get_history");
            body.put("device_id", selectedDevice.id);
            body.put("time_basis", "received");
            body.put("limit", HISTORY_LIMIT);
            body.put("to_ms", System.currentTimeMillis() + 300000L);
        } catch (org.json.JSONException e) {
            status.setText("Không tạo được yêu cầu lịch sử.");
            return;
        }
        api.post("get_history", body, selectedDevice, response -> {
            try {
                if (!selectedDevice.id.equals(response.getString("device_id")))
                    throw new org.json.JSONException("Lịch sử thuộc thiết bị khác");
                onHistoryLoaded(response.getJSONArray("samples"));
            } catch (org.json.JSONException error) {
                status.setText("Phản hồi lịch sử không hợp lệ.");
            }
        }, (code, message) -> {
            status.setText(message);
            if (code == 403 || code == 404) {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
                openDeviceList();
            }
        });
    }

    private void onHistoryLoaded(JSONArray rows) {
        AppConfig config = AppConfig.getInstance(this);
        temperatures.clear();
        adcValues.clear();
        startMs = Long.MAX_VALUE;
        endMs = 0;
        missingTimestamps = 0;
        for (int i = 0; i < rows.length(); i++) {
            org.json.JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            long timeMs = row.optLong("received_at_ms", 0);
            if (timeMs <= 0) {
                missingTimestamps++;
                continue;
            }
            startMs = Math.min(startMs, timeMs);
            endMs = Math.max(endMs, timeMs);

            if (!row.isNull("temperature")) {
                double value = row.optDouble("temperature", Double.NaN);
                if (Double.isFinite(value)) temperatures.add(new HistoryPoint(timeMs, value));
            }
            if (!row.isNull("water_adc")) {
                double value = row.optDouble("water_adc", Double.NaN);
                if (Double.isFinite(value) && value >= 0 && value <= 4095) {
                    if (!config.hasWaterLevelCalibration()) adcValues.add(new HistoryPoint(timeMs, value));
                    else if (config.isWaterAdcWithinCalibration(value))
                        adcValues.add(new HistoryPoint(timeMs, config.waterAdcToCm(value)));
                }
            }
        }
        temperatures.sort(Comparator.comparingLong(point -> point.timeMs));
        adcValues.sort(Comparator.comparingLong(point -> point.timeMs));
        if (missingTimestamps > 0) Log.w(TAG, "Mẫu thiếu timestamp: " + missingTimestamps);

        historyLoaded = true;
        updateHistoryStatus();
        showChart();
    }

    private void updateHistoryStatus() {
        AppConfig config = AppConfig.getInstance(this);
        {
            int selectedCount = SENSOR_TEMPERATURE.equals(selectedSensor)
                    ? temperatures.size() : adcValues.size();
            status.setText(String.format(Locale.getDefault(),
                    "Đã tải %d mẫu. Trục X: thời điểm máy chủ nhận dữ liệu, giờ điện thoại (%s).",
                    selectedCount, java.util.TimeZone.getDefault().getID())
                    + (!SENSOR_TEMPERATURE.equals(selectedSensor) && !config.hasWaterLevelCalibration()
                    ? "\nChưa hiệu chuẩn, đang hiển thị số ADC thô." : "")
                    + (missingTimestamps == 0 ? "" : "\nBỏ qua " + missingTimestamps
                    + " mẫu thiếu thời gian. Cần cập nhật API hỗ trợ received_at_ms."));
        }
    }

    private void showChart() {
        List<HistoryPoint> values = SENSOR_TEMPERATURE.equals(selectedSensor) ? temperatures : adcValues;
        String unit = SENSOR_TEMPERATURE.equals(selectedSensor) ? "°C"
                : AppConfig.getInstance(this).hasWaterLevelCalibration() ? "cm" : "ADC";
        int color = SENSOR_TEMPERATURE.equals(selectedSensor)
                ? Color.rgb(14, 165, 233) : Color.rgb(16, 185, 129);
        chartContainer.removeAllViews();
        chartContainer.addView(new SensorLineChartView(this, values, unit, color, startMs, endMs),
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(EXTRA_SENSOR, selectedSensor);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        if (api != null) api.close();
        api = null;
        super.onPause();
    }

    private static final class SensorLineChartView extends View {
        private final List<HistoryPoint> values;
        private final long startMs;
        private final long endMs;
        private final String unit;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int lineColor;

        SensorLineChartView(android.content.Context context, List<HistoryPoint> values,
                            String unit, int lineColor, long startMs, long endMs) {
            super(context);
            this.values = values;
            this.unit = unit;
            this.lineColor = lineColor;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(52);
            float right = getWidth() - dp(14);
            float top = dp(20);
            float bottom = getHeight() - dp(80);

            if (right <= left || bottom <= top) return;
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(226, 232, 240));
            for (int i = 0; i <= 4; i++) {
                float y = top + (bottom - top) * i / 4f;
                canvas.drawLine(left, y, right, y, paint);
            }

            if (values.isEmpty()) {
                paint.setColor(Color.rgb(100, 116, 139));
                paint.setTextSize(dp(14));
                canvas.drawText("Chưa có dữ liệu lịch sử", left, (top + bottom) / 2f, paint);
                return;
            }

            double min = values.stream().mapToDouble(point -> point.value).min().getAsDouble();
            double max = values.stream().mapToDouble(point -> point.value).max().getAsDouble();
            double padding = max == min ? Math.max(Math.abs(max) * 0.05, 1) : (max - min) * 0.12;
            min -= padding;
            max += padding;

            paint.setTextSize(dp(10));
            paint.setColor(Color.rgb(100, 116, 139));
            paint.setStrokeWidth(dp(1));
            for (int i = 0; i <= 4; i++) {
                double value = max - (max - min) * i / 4.0;
                float y = top + (bottom - top) * i / 4f;
                canvas.drawText(String.format(Locale.getDefault(), "%.1f", value),
                        dp(2), y + dp(4), paint);
            }

            float width = right - left;
            float height = bottom - top;
            Path path = new Path();
            for (int i = 0; i < values.size(); i++) {
                HistoryPoint point = values.get(i);
                float x = left + width * (float) HistoryPoint.timeFraction(point.timeMs, startMs, endMs);
                float y = bottom - (float) ((point.value - min) / (max - min)) * height;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            paint.setColor(lineColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.5f));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            for (HistoryPoint point : values) {
                float x = left + width * (float) HistoryPoint.timeFraction(point.timeMs, startMs, endMs);
                float y = bottom - (float) ((point.value - min) / (max - min)) * height;
                canvas.drawCircle(x, y, dp(2.5f), paint);
            }

            double latest = values.get(values.size() - 1).value;
            paint.setColor(Color.rgb(15, 23, 42));
            paint.setTextSize(dp(13));
            canvas.drawText(String.format(Locale.getDefault(), "Mới nhất: %.2f %s", latest, unit),
                    left, getHeight() - dp(10), paint);
            paint.setColor(Color.rgb(100, 116, 139));
            paint.setTextSize(dp(10));
            SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            SimpleDateFormat date = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
            int tickCount = startMs == endMs ? 1 : 3;
            for (int i = 0; i < tickCount; i++) {
                double fraction = tickCount == 1 ? 0.5 : i / 2.0;
                long timestamp = startMs + Math.round((endMs - startMs) * fraction);
                float x = left + width * (float) fraction;
                paint.setTextAlign(tickCount == 1 || i == 1 ? Paint.Align.CENTER
                        : i == 0 ? Paint.Align.LEFT : Paint.Align.RIGHT);
                canvas.drawText(time.format(new Date(timestamp)), x, bottom + dp(18), paint);
                canvas.drawText(date.format(new Date(timestamp)), x, bottom + dp(32), paint);
            }
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
