package com.buiducluan23011767.ck;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;

import java.util.Locale;

public class LoginActivity extends AppCompatActivity {
    private EditText username;
    private EditText password;
    private Button login;
    private TextView status;
    private androidx.appcompat.widget.SwitchCompat rememberLogin;
    private RequestQueue requestQueue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        username = findViewById(R.id.edtLoginUsername);
        password = findViewById(R.id.edtLoginPassword);
        login = findViewById(R.id.btnLogin);
        status = findViewById(R.id.txtLoginStatus);
        rememberLogin = findViewById(R.id.switchRememberLogin);
        rememberLogin.setChecked(UserSession.isRememberEnabled(this));
        requestQueue = Volley.newRequestQueue(getApplicationContext());
        login.setOnClickListener(v -> submitLogin());
    }

    private void submitLogin() {
        String enteredUsername = username.getText().toString().trim();
        String enteredPassword = password.getText().toString();
        if (enteredUsername.isEmpty() || enteredPassword.isEmpty()) {
            status.setText("Nhập tên đăng nhập và mật khẩu.");
            return;
        }

        String url = AppConfig.getInstance(this).getHttpApiUrl().trim();
        if (url.isEmpty()) {
            status.setText("Máy chủ đăng nhập chưa được cấu hình.");
            return;
        }

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
                    String returnedRole = response.optString("role", "");
                    String sessionToken = response.optString("token", "");
                    String accountName = response.optString("username", enteredUsername);
                    long expiresIn = response.optLong("expires_in", 0);
                    if (!("admin".equals(returnedRole) || "guest".equals(returnedRole))
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
                        status.setText("Không thể kết nối máy chủ đăng nhập. Kiểm tra mạng và URL API.");
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

    @Override
    protected void onDestroy() {
        if (requestQueue != null) requestQueue.cancelAll(this);
        super.onDestroy();
    }
}
