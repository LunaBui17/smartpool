package com.buiducluan23011767.ck;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.HurlStack;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

final class ApiClient {
    interface Failure {
        void onError(int status, String message);
    }

    private final Context context;
    private final RequestQueue queue;
    private final Object tag = new Object();
    private final UserSession.Snapshot session;
    private boolean closed;

    ApiClient(Context context) {
        this.context = context.getApplicationContext();
        session = UserSession.snapshot();
        queue = newQueue(this.context);
    }

    static RequestQueue newQueue(Context context) {
        return Volley.newRequestQueue(context.getApplicationContext(), new HurlStack() {
            @Override
            protected HttpURLConnection createConnection(URL url) throws IOException {
                HttpURLConnection connection = super.createConnection(url);
                connection.setInstanceFollowRedirects(false);
                return connection;
            }
        });
    }

    static String normalizeUrl(String value) throws URISyntaxException {
        URI uri = new URI(value.trim()).normalize();
        if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getQuery() != null) {
            throw new URISyntaxException(value, "URL API không hợp lệ");
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/pool_api/pool_api.php";
        int port = uri.getPort();
        if (("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)) port = -1;
        URI origin = new URI(uri.getScheme().toLowerCase(java.util.Locale.ROOT), null,
                uri.getHost().toLowerCase(java.util.Locale.ROOT), port, null, null, null);
        return new URI(origin.toASCIIString() + path).toASCIIString();
    }

    void post(String action, JSONObject parameters, DeviceContext.Selection device,
              Response.Listener<JSONObject> success, Failure failure) {
        if (!valid(device)) return;
        JSONObject body;
        try {
            body = new JSONObject(parameters.toString());
            body.put("action", action);
            if (device != null) body.put("device_id", device.id);
        } catch (JSONException error) {
            failure.onError(0, "Không tạo được yêu cầu API.");
            return;
        }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, session.server, body,
                response -> {
                    if (!valid(device)) return;
                    if (!"success".equals(response.optString("status"))
                            || response.optInt("api_version") != 2) {
                        failure.onError(0, "Máy chủ không trả phản hồi API v2 hợp lệ.");
                        return;
                    }
                    success.onResponse(response);
                }, error -> {
                    if (!valid(device)) return;
                    int code = error.networkResponse == null ? 0 : error.networkResponse.statusCode;
                    if (code == 401) {
                        expire(context);
                        return;
                    }
                    failure.onError(code, errorMessage(error));
                }) {
            @Override public Map<String, String> getHeaders() {
                return Collections.singletonMap("Authorization", "Bearer " + session.token);
            }
        };
        request.setShouldCache(false);
        request.setRetryPolicy(new DefaultRetryPolicy(8000, 0, 1.0f));
        request.setTag(tag);
        queue.add(request);
    }

    private boolean valid(DeviceContext.Selection device) {
        if (closed) return false;
        if (session == null) {
            if (!UserSession.isAuthenticated()) expire(context);
            return false;
        }
        if (!UserSession.isCurrent(session)) return false;
        if (!UserSession.isAuthenticated()) {
            expire(context);
            return false;
        }
        return device == null || device.isCurrent();
    }

    static String errorMessage(VolleyError error) {
        int code = error.networkResponse == null ? 0 : error.networkResponse.statusCode;
        if (error.networkResponse != null && error.networkResponse.data != null) {
            try {
                JSONObject body = new JSONObject(new String(error.networkResponse.data, StandardCharsets.UTF_8));
                String message = body.optString("message", "");
                if (!message.isEmpty()) return message;
            } catch (JSONException ignored) {
                // Apache/proxy errors need not be JSON.
            }
        }
        if (code == 0) return "Không kết nối được API. Kiểm tra mạng và URL máy chủ.";
        if (code == 403) return "Không có quyền thực hiện hoặc thiết bị đã vô hiệu hóa.";
        if (code == 404) return "Không tìm thấy đối tượng. Hãy tải lại danh sách.";
        if (code == 409) return "Dữ liệu đã thay đổi hoặc đang có thao tác chờ. Hãy tải lại.";
        return "Yêu cầu thất bại (HTTP " + code + ").";
    }

    static void expire(Context context) {
        UserSession.clear(context);
        context.startActivity(new Intent(context, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("session_notice", "Phiên không còn hợp lệ. Vui lòng đăng nhập lại."));
    }

    static void logout(Context context) {
        Context app = context.getApplicationContext();
        UserSession.Snapshot previous = UserSession.snapshot();
        UserSession.clear(app);
        long logoutGeneration = UserSession.generation();
        app.startActivity(new Intent(app, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        if (previous == null) return;
        JSONObject body = new JSONObject();
        try {
            body.put("action", "logout");
        } catch (JSONException error) {
            Toast.makeText(app, "Đã xóa phiên trên điện thoại; chưa thu hồi được trên server.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        RequestQueue logoutQueue = newQueue(app);
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, previous.server, body,
                response -> {
                    if (!"success".equals(response.optString("status"))
                            && UserSession.generation() == logoutGeneration)
                        Toast.makeText(app, "Server chưa xác nhận thu hồi phiên.", Toast.LENGTH_LONG).show();
                    logoutQueue.stop();
                }, error -> {
                    if ((error.networkResponse == null || error.networkResponse.statusCode != 401)
                            && UserSession.generation() == logoutGeneration)
                        Toast.makeText(app, "Đã đăng xuất trên điện thoại; chưa xác nhận thu hồi phiên trên server.",
                                Toast.LENGTH_LONG).show();
                    logoutQueue.stop();
                }) {
            @Override public Map<String, String> getHeaders() {
                return Collections.singletonMap("Authorization", "Bearer " + previous.token);
            }
        };
        request.setShouldCache(false);
        request.setRetryPolicy(new DefaultRetryPolicy(5000, 0, 1.0f));
        logoutQueue.add(request);
    }

    void cancel() {
        queue.cancelAll(tag);
    }

    void close() {
        closed = true;
        cancel();
        queue.stop();
    }
}
