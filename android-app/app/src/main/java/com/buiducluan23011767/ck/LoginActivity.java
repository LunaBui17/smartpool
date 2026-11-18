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
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;

import java.net.URI;
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
        requestQueue = Volley.newRequestQueue(getApplicationContext());
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
                        String url = normalizeApiUrl(input.getText().toString().trim());
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
            url = normalizeApiUrl(AppConfig.getInstance(this).getHttpApiUrl().trim());
        } catch (URISyntaxException e) {
            status.setText("URL API không hợp lệ. Nhấn Cấu hình API để cập nhật.");
            return;
        }
        AppConfig.getInstance(this).setHttpApiUrl(url);

        login.setEnabled(false);
        status.setText("Đang xác thực tài khoản...");
        JSONObject body = new JSONObject();
        try {
            body.put("action", "login");
            body.put("username", enteredUsername);
            body.put("password", enteredPassword);
            body.put("remember_me", rememberLogin.isChecked());
        } catch (org.json.JSONException e) {
            login.setEnabled(true);
            status.setText("Không thể tạo yêu cầu đăng nhập.");
            return;
        }

        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                url,
                body,
                response -> {
                    login.setEnabled(true);
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
                    boolean remembered = UserSession.start(this, accountName, returnedRole,
                            sessionToken, expiresIn, rememberLogin.isChecked());
                    if (!remembered) {
                        UserSession.clear(this);
                        status.setText("Không lưu được phiên đăng nhập trên thiết bị.");
                        return;
                    }
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                },
                error -> {
                    login.setEnabled(true);
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
        request.setRetryPolicy(new DefaultRetryPolicy(8000, 0, 1.0f));
        request.setTag(this);
        requestQueue.add(request);
    }

    private String normalizeApiUrl(String url) throws URISyntaxException {
        URI uri = new URI(url);
        if ((!"https".equalsIgnoreCase(uri.getScheme())
                && !"http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new URISyntaxException(url, "Invalid API URL");
        }
        if (uri.getRawPath() == null || uri.getRawPath().isEmpty()
                || "/".equals(uri.getRawPath())) {
            return new URI(uri.getScheme(), uri.getRawAuthority(),
                    "/pool_api/pool_api.php", null, null).toString();
        }
        return uri.toString();
    }

    @Override
    protected void onDestroy() {
        if (requestQueue != null) requestQueue.cancelAll(this);
        super.onDestroy();
    }
}
