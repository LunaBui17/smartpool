package com.buiducluan23011767.ck;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

final class UserSession {
    private static final String PREFERENCES = "smartpool_user_session";
    private static Snapshot current;
    private static long generation;

    static final class Snapshot {
        final long userId;
        final String username;
        final String role;
        final String server;
        final String token;
        final long expiresAtMillis;
        final long generation;

        Snapshot(long userId, String username, String role, String server, String token,
                 long expiresAtMillis, long generation) {
            this.userId = userId;
            this.username = username;
            this.role = role;
            this.server = server;
            this.token = token;
            this.expiresAtMillis = expiresAtMillis;
            this.generation = generation;
        }
    }

    private UserSession() { }

    static synchronized boolean start(Context context, long userId, String username, String role,
                                      String server, String token, long expiresInSeconds,
                                      boolean remember) {
        clear(context);
        long now = System.currentTimeMillis();
        if (userId <= 0 || username.isEmpty() || !validRole(role) || server.isEmpty()
                || !token.matches("[a-fA-F0-9]{64}") || expiresInSeconds <= 0
                || expiresInSeconds > (Long.MAX_VALUE - now) / 1000L) {
            return false;
        }
        Snapshot next = new Snapshot(userId, username, role, server, token,
                now + expiresInSeconds * 1000L, ++generation);
        SharedPreferences.Editor edit = preferences(context).edit().clear()
                .putBoolean("remember", remember);
        if (remember) {
            edit.putLong("user_id", userId).putString("username", username)
                    .putString("role", role).putString("server", server)
                    .putString("token", token).putLong("expires_at", next.expiresAtMillis);
        }
        if (!edit.commit()) return false;
        current = next;
        return true;
    }

    static synchronized boolean restore(Context context) {
        String server = AppConfig.getInstance(context).getHttpApiUrl();
        if (isAuthenticated() && current.server.equals(server)) return true;
        if (current != null) clear(context);
        SharedPreferences prefs = preferences(context);
        if (!prefs.getBoolean("remember", false)) return false;
        long userId = prefs.getLong("user_id", 0);
        String username = prefs.getString("username", "");
        String role = prefs.getString("role", "");
        String savedServer = prefs.getString("server", "");
        String token = prefs.getString("token", "");
        long expiry = prefs.getLong("expires_at", 0);
        if (userId <= 0 || username.isEmpty() || !validRole(role) || server.isEmpty()
                || !server.equals(savedServer) || !token.matches("[a-fA-F0-9]{64}")
                || expiry <= System.currentTimeMillis()) {
            clear(context);
            return false;
        }
        current = new Snapshot(userId, username, role, server, token, expiry, ++generation);
        DeviceContext.clear();
        return true;
    }

    static synchronized boolean isRememberEnabled(Context context) {
        return preferences(context).getBoolean("remember", true);
    }

    static synchronized void clear(Context context) {
        current = null;
        generation++;
        DeviceContext.clear();
        if (!preferences(context).edit().clear().commit()) {
            android.util.Log.e("UserSession", "Không xóa được phiên đăng nhập đã lưu.");
            android.widget.Toast.makeText(context,
                    "Không xóa được phiên đã lưu. Không dùng chung điện thoại trước khi xử lý lỗi lưu trữ.",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        context.stopService(new Intent(context, PoolMonitorService.class));
    }

    static synchronized Snapshot snapshot() {
        return isAuthenticated() ? current : null;
    }

    static synchronized boolean matches(Snapshot snapshot) {
        return snapshot != null && current == snapshot && isAuthenticated();
    }

    static synchronized boolean isCurrent(Snapshot snapshot) {
        return snapshot != null && current == snapshot;
    }

    static synchronized long generation() {
        return generation;
    }

    static synchronized boolean isAuthenticated() {
        return current != null && current.expiresAtMillis > System.currentTimeMillis();
    }

    static synchronized boolean isAdmin() {
        return isAuthenticated() && "admin".equals(current.role);
    }

    static synchronized String getToken() {
        return isAuthenticated() ? current.token : null;
    }

    static synchronized String getUsername() {
        return isAuthenticated() ? current.username : null;
    }

    private static boolean validRole(String role) {
        return "admin".equals(role) || "user".equals(role);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
