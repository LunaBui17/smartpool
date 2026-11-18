package com.buiducluan23011767.ck;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OperationLogActivity extends AppCompatActivity {
    private static final String TAG = "OperationLogActivity";
    private TextView status;
    private LinearLayout entries;
    private ApiClient api;
    private DeviceContext.Selection selectedDevice;
    private boolean loading;

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
        setContentView(R.layout.activity_operation_log);
        ScreenInsets.apply(findViewById(R.id.operationLogRoot));
        status = findViewById(R.id.txtLogStatus);
        entries = findViewById(R.id.layoutOperationLog);
        findViewById(R.id.btnLogBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefreshLog).setOnClickListener(v -> refresh());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (entries == null) return;
        if (!UserSession.isAuthenticated()) {
            ApiClient.expire(this);
            finish();
            return;
        }
        if (!selectedDevice.isCurrent()) {
            openDeviceList();
            return;
        }
        api = new ApiClient(this);
        refresh();
    }

    private void openDeviceList() {
        startActivity(new Intent(this, DeviceListActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    private void refresh() {
        if (loading || api == null) return;
        final Map<String, JSONObject> rows = new HashMap<>();
        final JSONObject body = new JSONObject();
        try {
            body.put("action", "list_activity");
            body.put("device_id", selectedDevice.id);
            body.put("limit", 100);
        } catch (JSONException e) {
            Log.e(TAG, "Không tạo được yêu cầu nhật ký", e);
            status.setText("Không tạo được yêu cầu nhật ký.");
            return;
        }
        render(rows);
        loading = true;
        status.setText("Đang tải nhật ký từ máy chủ...");
        api.post("list_activity", body, selectedDevice,
                response -> {
                    loading = false;
                    try {
                        if (!"success".equals(response.getString("status"))) {
                            throw new JSONException("Máy chủ chưa xác nhận tải nhật ký");
                        }
                        JSONArray remote = response.getJSONArray("events");
                        for (int i = 0; i < remote.length(); i++) {
                            JSONObject row = remote.getJSONObject(i);
                            if (!selectedDevice.id.equals(row.getString("device_id")))
                                throw new JSONException("Nhật ký thuộc thiết bị khác");
                            row.put("synced", true);
                            rows.put(row.getString("event_id"), row);
                        }
                        render(rows);
                        status.setText("Nhật ký thiết bị " + AppConfig.getInstance(this).getDeviceId() + "."
                                + "\nTối đa 100 sự kiện mới nhất từ server. Không trộn nhật ký SQLite cũ.");
                    } catch (JSONException e) {
                        Log.e(TAG, "Phản hồi nhật ký không hợp lệ", e);
                        status.setText("Phản hồi nhật ký máy chủ không hợp lệ.");
                    }
                },
                (code, message) -> {
                    loading = false;
                    status.setText(message);
                    if (code == 403 || code == 404) {
                        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
                        openDeviceList();
                    }
                });
    }

    private void render(Map<String, JSONObject> rows) {
        entries.removeAllViews();
        List<JSONObject> sorted = new ArrayList<>(rows.values());
        sorted.sort(Comparator.comparingLong((JSONObject row) -> row.optLong("occurred_at_ms"))
                .reversed());
        SimpleDateFormat format = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
        for (int i = 0; i < Math.min(100, sorted.size()); i++) {
            JSONObject row = sorted.get(i);
            View item = getLayoutInflater().inflate(R.layout.item_operation_log, entries, false);
            TextView time = item.findViewById(R.id.txtEventTime);
            long eventTime = row.optLong("occurred_at_ms", 0);
            if (eventTime <= 0) {
                String received = row.optString("received_at", "");
                if (received.length() >= 19) {
                    try {
                        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                        utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        Date parsed = utc.parse(received.substring(0, 19));
                        if (parsed != null) eventTime = parsed.getTime();
                    } catch (java.text.ParseException ignored) { }
                }
            }
            time.setText(eventTime > 0 ? format.format(new Date(eventTime)) : "--");
            TextView action = item.findViewById(R.id.txtEventAction);
            action.setText(row.optString("action"));
            TextView result = item.findViewById(R.id.txtEventResult);
            String resultCode = row.optString("result");
            result.setText(OperationLog.resultLabel(resultCode));
            int color = R.color.text_secondary;
            switch (resultCode) {
                case "confirmed":
                case "saved": color = R.color.success; break;
                case "failed":
                case "timeout": color = R.color.danger; break;
                case "requested":
                case "published":
                case "interrupted": color = R.color.warning; break;
            }
            result.setTextColor(getColor(color));
            TextView actor = item.findViewById(R.id.txtEventActor);
            actor.setText(row.optString("actor_username", row.optString("username"))
                    + "\nThiết bị: " + row.optString("device_id"));
            TextView detail = item.findViewById(R.id.txtEventDetail);
            detail.setText(row.optString("detail"));
            detail.setVisibility(row.optString("detail").isEmpty() ? View.GONE : View.VISIBLE);
            item.findViewById(R.id.txtEventSync).setVisibility(
                    row.optBoolean("synced", false) ? View.GONE : View.VISIBLE);
            entries.addView(item);
        }
        if (sorted.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Chưa có nhật ký thao tác.");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(14);
            entries.addView(empty);
        }
    }

    @Override
    protected void onPause() {
        if (api != null) api.close();
        api = null;
        loading = false;
        super.onPause();
    }
}
