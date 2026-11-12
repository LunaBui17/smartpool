package com.buiducluan23011767.ck;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SensorChartActivity extends AppCompatActivity {
    public static final String EXTRA_SENSOR = "sensor";
    public static final String SENSOR_TEMPERATURE = "temperature";
    public static final String SENSOR_ADC = "adc";

    private static final String TAG = "SensorChartActivity";
    private static final int HISTORY_LIMIT = 100;

    private final List<Double> temperatures = new ArrayList<>();
    private final List<Double> adcValues = new ArrayList<>();
    private TextView title;
    private TextView status;
    private Button temperatureTab;
    private Button adcTab;
    private FrameLayout chartContainer;
    private RequestQueue requestQueue;
    private String selectedSensor = SENSOR_TEMPERATURE;
    private boolean historyLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserSession.restore(this);
        if (!UserSession.isAuthenticated()) {
            UserSession.clear(this);
            startActivity(new android.content.Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_sensor_chart);

        title = findViewById(R.id.txtChartTitle);
        status = findViewById(R.id.txtChartStatus);
        temperatureTab = findViewById(R.id.btnTemperatureTab);
        adcTab = findViewById(R.id.btnAdcTab);
        chartContainer = findViewById(R.id.chartContainer);

        String requestedSensor = getIntent().getStringExtra(EXTRA_SENSOR);
        if (SENSOR_ADC.equals(requestedSensor)) selectedSensor = SENSOR_ADC;

        findViewById(R.id.btnChartBack).setOnClickListener(v -> finish());
        temperatureTab.setOnClickListener(v -> selectSensor(SENSOR_TEMPERATURE));
        adcTab.setOnClickListener(v -> selectSensor(SENSOR_ADC));

        requestQueue = Volley.newRequestQueue(getApplicationContext());
        selectSensor(selectedSensor);
        loadHistory();
    }

    private void selectSensor(String sensor) {
        selectedSensor = sensor;
        boolean temperatureSelected = SENSOR_TEMPERATURE.equals(sensor);
        title.setText(temperatureSelected ? "Biểu đồ nhiệt độ nước"
                : "Biểu đồ mực nước (cm)");
        adcTab.setText("Mực nước (cm)");
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
        String historyUrl = Uri.parse(baseUrl).buildUpon()
                .appendQueryParameter("history", "1")
                .appendQueryParameter("limit", String.valueOf(HISTORY_LIMIT))
                .build().toString();

        JsonArrayRequest request = new JsonArrayRequest(
                Request.Method.GET,
                historyUrl,
                null,
                this::onHistoryLoaded,
                error -> {
                    Log.e(TAG, "Không tải được lịch sử cảm biến qua HTTP", error);
                    status.setText(UserSession.isAdmin()
                            ? "Không tải được lịch sử HTTP: "
                            + (error.getMessage() == null ? "lỗi kết nối" : error.getMessage())
                            : "Chưa tải được lịch sử cảm biến. Vui lòng thử lại sau.");
                }
        );
        request.setShouldCache(false);
        request.setRetryPolicy(new DefaultRetryPolicy(6000, 1, 1.0f));
        request.setTag(this);
        requestQueue.add(request);
    }

    private void onHistoryLoaded(JSONArray rows) {
        AppConfig config = AppConfig.getInstance(this);
        temperatures.clear();
        adcValues.clear();
        for (int i = 0; i < rows.length(); i++) {
            org.json.JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;

            if (!row.isNull("nhiet_do")) {
                double value = row.optDouble("nhiet_do", Double.NaN);
                if (Double.isFinite(value)) temperatures.add(value);
            }
            if (!row.isNull("muc_nuoc_raw")) {
                double value = row.optDouble("muc_nuoc_raw", Double.NaN);
                if (config.isWaterAdcWithinCalibration(value)) {
                    adcValues.add((double) config.waterAdcToCm(value));
                }
            }
        }

        historyLoaded = true;
        updateHistoryStatus();
        showChart();
    }

    private void updateHistoryStatus() {
        AppConfig config = AppConfig.getInstance(this);
        if (!SENSOR_TEMPERATURE.equals(selectedSensor) && !config.hasWaterLevelCalibration()) {
            status.setText(UserSession.isAdmin()
                    ? "Chưa hiệu chuẩn. Vào Cài đặt để cấu hình khoảng mực nước đo được."
                    : "Chưa hiệu chuẩn mực nước theo cm; không thể vẽ biểu đồ chiều cao.");
        } else {
            int selectedCount = SENSOR_TEMPERATURE.equals(selectedSensor)
                    ? temperatures.size() : adcValues.size();
            status.setText(UserSession.isAdmin()
                    ? String.format(Locale.getDefault(),
                    "Đã tải %d mẫu từ HTTP (tối đa %d).", selectedCount, HISTORY_LIMIT)
                    : String.format(Locale.getDefault(),
                    "Đã tải %d mẫu lịch sử.", selectedCount));
        }
    }

    private void showChart() {
        List<Double> values = SENSOR_TEMPERATURE.equals(selectedSensor) ? temperatures : adcValues;
        String unit = SENSOR_TEMPERATURE.equals(selectedSensor) ? "°C"
                : "cm";
        int color = SENSOR_TEMPERATURE.equals(selectedSensor)
                ? Color.rgb(14, 165, 233) : Color.rgb(16, 185, 129);
        chartContainer.removeAllViews();
        chartContainer.addView(new SensorLineChartView(this, values, unit, color),
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onDestroy() {
        if (requestQueue != null) requestQueue.cancelAll(this);
        super.onDestroy();
    }

    private static final class SensorLineChartView extends View {
        private final List<Double> values;
        private final String unit;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int lineColor;

        SensorLineChartView(android.content.Context context, List<Double> values,
                            String unit, int lineColor) {
            super(context);
            this.values = values;
            this.unit = unit;
            this.lineColor = lineColor;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(52);
            float right = getWidth() - dp(14);
            float top = dp(20);
            float bottom = getHeight() - dp(46);

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

            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
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
                float x = values.size() == 1 ? (left + right) / 2f
                        : left + width * i / (values.size() - 1f);
                float y = bottom - (float) ((values.get(i) - min) / (max - min)) * height;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            paint.setColor(lineColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.5f));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);

            double latest = values.get(values.size() - 1);
            paint.setColor(Color.rgb(15, 23, 42));
            paint.setTextSize(dp(13));
            canvas.drawText(String.format(Locale.getDefault(), "Mới nhất: %.2f %s", latest, unit),
                    left, getHeight() - dp(22), paint);
            paint.setColor(Color.rgb(100, 116, 139));
            paint.setTextSize(dp(10));
            canvas.drawText("Cũ nhất", left, getHeight() - dp(6), paint);
            String newest = "Mới nhất";
            canvas.drawText(newest, right - paint.measureText(newest), getHeight() - dp(6), paint);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
