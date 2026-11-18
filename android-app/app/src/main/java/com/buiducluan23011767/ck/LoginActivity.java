package com.buiducluan23011767.ck;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;

import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.Locale;

public class LoginActivity extends AppCompatActivity {
    private EditText username;
    private EditText password;
    private TextView configureApiUrl;
    private Button login;
    private TextView status;
    private androidx.appcompat.widget.SwitchCompat rememberLogin;
    private RequestQueue requestQueue;
    private boolean visible;
    private long loginAttempt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        ScreenInsets.apply(findViewById(R.id.loginRoot));

        username = findViewById(R.id.edtLoginUsername);
        password = findViewById(R.id.edtLoginPassword);
        configureApiUrl = findViewById(R.id.txtConfigureApiUrl);
        login = findViewById(R.id.btnLogin);
        status = findViewById(R.id.txtLoginStatus);
        rememberLogin = findViewById(R.id.switchRememberLogin);
        rememberLogin.setChecked(UserSession.isRememberEnabled(this));
        requestQueue = ApiClient.newQueue(this);
        String notice = getIntent().getStringExtra("session_notice");
        if (notice != null) status.setText(notice);
        configureApiUrl.setOnClickListener(v -> showApiUrlDialog());
        login.setOnClickListener(v -> submitLogin());
        password.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            if (login.isEnabled()) submitLogin();
            return true;
        });
    }

    private void showApiUrlDialog() {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(AppConfig.getInstance(this).getHttpApiUrl());
        input.setSelection(input.length());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Cập nhật URL máy chủ API")
                .setView(input)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Cập nhật", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        String url = ApiClient.normalizeUrl(input.getText().toString());
                        AppConfig.getInstance(this).setHttpApiUrl(url);
                        status.setText("");
                        dialog.dismiss();
                    } catch (URISyntaxException e) {
                        input.setError("Nhập URL API HTTP hoặc HTTPS hợp lệ");
                    }
                }));
        dialog.show();
    }

    private void submitLogin() {
        String enteredUsername = username.getText().toString().trim();
        String enteredPassword = password.getText().toString();
        if (enteredUsername.isEmpty() || enteredPassword.isEmpty()) {
            status.setText("Nhập tên đăng nhập và mật khẩu.");
            return;
        }

        String url;
        try {
            url = ApiClient.normalizeUrl(AppConfig.getInstance(this).getHttpApiUrl());
        } catch (URISyntaxException e) {
            status.setText("URL API không hợp lệ. Nhấn Cấu hình API để cập nhật.");
            return;
        }
        AppConfig.getInstance(this).setHttpApiUrl(url);

        login.setEnabled(false);
        configureApiUrl.setEnabled(false);
        final long attempt = ++loginAttempt;
        final boolean remember = rememberLogin.isChecked();
        status.setText("Đang xác thực tài khoản...");
        JSONObject body = new JSONObject();
        try {
            body.put("action", "login");
            body.put("username", enteredUsername);
            body.put("password", enteredPassword);
            body.put("remember_me", remember);
        } catch (org.json.JSONException e) {
            login.setEnabled(true);
            configureApiUrl.setEnabled(true);
            status.setText("Không thể tạo yêu cầu đăng nhập.");
            return;
        }

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                url,
                body,
                response -> {
                    if (!visible || attempt != loginAttempt) return;
                    login.setEnabled(true);
                    configureApiUrl.setEnabled(true);
                    if (!url.equals(AppConfig.getInstance(this).getHttpApiUrl())) {
                        status.setText("Máy chủ đã thay đổi. Vui lòng đăng nhập lại.");
                        return;
                    }
                    JSONObject user = response.optJSONObject("user");
                    String returnedRole = user == null ? "" : user.optString("role", "");
                    String sessionToken = response.optString("token", "");
                    String accountName = user == null ? enteredUsername : user.optString("username", enteredUsername);
                    long expiresIn = response.optLong("expires_in", 0);
                    if (!"success".equals(response.optString("status"))
                            || !("admin".equals(returnedRole) || "user".equals(returnedRole))
                            || sessionToken.isEmpty() || expiresIn <= 0) {
                        status.setText("Máy chủ trả về thông tin tài khoản không hợp lệ.");
                        return;
                    }
                    if (user == null || user.optLong("id", 0) <= 0
                            || response.optInt("api_version") != 2
                            || response.optJSONArray("devices") == null
                            || !response.has("next_after_device_id")) {
                        status.setText("Phản hồi đăng nhập thiếu thông tin phiên hoặc danh sách thiết bị v2.");
                        return;
                    }
                    boolean remembered = UserSession.start(this, user.optLong("id"), accountName,
                            returnedRole, url, sessionToken, expiresIn, remember);
                    if (!remembered) {
                        UserSession.clear(this);
                        status.setText("Không lưu được phiên đăng nhập trên thiết bị.");
                        return;
                    }
                    password.setText("");
                    JSONObject firstPage = new JSONObject();
                    try {
                        firstPage.put("devices", response.getJSONArray("devices"));
                        firstPage.put("next_after_device_id", response.get("next_after_device_id"));
                    } catch (org.json.JSONException error) {
                        UserSession.clear(this);
                        status.setText("Danh sách thiết bị trong phản hồi đăng nhập không hợp lệ.");
                        return;
                    }
                    startActivity(new Intent(this, DeviceListActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra("login_devices", firstPage.toString()));
                    finish();
                },
                error -> {
                    if (!visible || attempt != loginAttempt) return;
                    login.setEnabled(true);
                    configureApiUrl.setEnabled(true);
                    int responseCode = error.networkResponse == null
                            ? 0 : error.networkResponse.statusCode;
                    if (responseCode == 401) {
                        status.setText("Tên đăng nhập hoặc mật khẩu không đúng.");
                    } else if (responseCode == 0) {
                        status.setText("Không thể kết nối máy chủ. Kiểm tra mạng và URL API ở trên; "
                                + "nếu Quick Tunnel đổi địa chỉ, nhập URL mới từ file smartpool-v2-endpoints.txt trên Pi.");
                    } else {
                        status.setText(String.format(Locale.getDefault(),
                                "Đăng nhập thất bại (HTTP %d).", responseCode));
                    }
                }
        );
        request.setShouldCache(false);
        request.setRetryPolicy(new DefaultRetryPolicy(8000, 0, 1.0f));
        request.setTag(this);
        requestQueue.add(request);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
    }

    @Override protected void onPause() {
        visible = false;
        loginAttempt++;
        if (requestQueue != null) requestQueue.cancelAll(this);
        if (login != null) login.setEnabled(true);
        if (configureApiUrl != null) configureApiUrl.setEnabled(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (requestQueue != null) {
            requestQueue.cancelAll(this);
            requestQueue.stop();
        }
        super.onDestroy();
    }
}
