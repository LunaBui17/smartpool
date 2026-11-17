package com.buiducluan23011767.ck;

import android.content.Intent;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

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
    private RequestQueue queue;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserSession.restore(this);
        if (!UserSession.isAuthenticated()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_operation_log);
        ScreenInsets.apply(findViewById(R.id.operationLogRoot));
        status = findViewById(R.id.txtLogStatus);
        entries = findViewById(R.id.layoutOperationLog);
        queue = Volley.newRequestQueue(getApplicationContext());
        findViewById(R.id.btnLogBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefreshLog).setOnClickListener(v -> refresh());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (queue == null) return;
        if (!UserSession.isAuthenticated()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        refresh();
    }

    private void refresh() {
        if (loading) return;
        final Map<String, JSONObject> rows = new HashMap<>();
        final JSONObject body = new JSONObject();
        try {
            JSONArray local = OperationLog.get(this).readLocal();
            for (int i = 0; i < local.length(); i++) {
                JSONObject row = local.getJSONObject(i);
                rows.put(row.getString("event_id"), row);
            }
            body.put("action", "list_activity");
        } catch (SQLiteException | JSONException e) {
            Log.e(TAG, "Không đọc được nhật ký trên điện thoại", e);
            status.setText("Không đọc được nhật ký trên điện thoại.");
            return;
        }
        render(rows);
        String apiUrl = AppConfig.getInstance(this).getHttpApiUrl();
        if (apiUrl.trim().isEmpty()) {
            status.setText("Đang hiển thị nhật ký cục bộ; chưa cấu hình API.");
            return;
        }
        OperationLog.get(this).sync();
        loading = true;
        status.setText("Đang tải nhật ký từ máy chủ...");
        final String token = UserSession.getToken();
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, apiUrl, body,
                response -> {
                    loading = false;
                    try {
                        if (!"success".equals(response.getString("status"))) {
                            throw new JSONException("Máy chủ chưa xác nhận tải nhật ký");
                        }
                        JSONArray remote = response.getJSONArray("events");
                        for (int i = 0; i < remote.length(); i++) {
                            JSONObject row = remote.getJSONObject(i);
                            row.put("synced", true);
                            rows.put(row.getString("event_id"), row);
                        }
                        render(rows);
                        status.setText((UserSession.isAdmin() ? "Admin: nhật ký mọi tài khoản."
                                : "Nhật ký tài khoản của bạn.")
                                + "\nTối đa 100 sự kiện mới nhất. Mục chưa đồng bộ vẫn được giữ trên điện thoại.");
                    } catch (JSONException e) {
                        Log.e(TAG, "Phản hồi nhật ký không hợp lệ", e);
                        status.setText("Phản hồi máy chủ không hợp lệ; đang hiển thị bản cục bộ.");
                    }
                },
                error -> {
                    loading = false;
                    Log.w(TAG, "Không tải được nhật ký máy chủ", error);
                    status.setText("Không tải được nhật ký máy chủ. Đang hiển thị bản cục bộ;"
                            + " hãy kiểm tra kết nối, phiên đăng nhập và migration database.");
                }) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + token);
                return headers;
            }
        };
        request.setRetryPolicy(new DefaultRetryPolicy(8000, 0, 1.0f));
        request.setTag(this);
        queue.add(request);
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
            time.setText(format.format(new Date(row.optLong("occurred_at_ms"))));
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
            actor.setText(row.optString("username") + "\nThiết bị: " + row.optString("device_id"));
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
    protected void onDestroy() {
        if (queue != null) queue.cancelAll(this);
        super.onDestroy();
    }
}
