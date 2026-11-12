package com.buiducluan23011767.ck;

import android.content.Context;
import android.content.SharedPreferences;

final class UserSession {
    private static final String PREFERENCES = "smartpool_user_session";
    private static final String KEY_REMEMBER = "remember";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_ROLE = "role";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_EXPIRES_AT = "expires_at";

    private static String username;
    private static String role;
    private static String token;
    private static long expiresAtMillis;

    private UserSession() {
    }

    static synchronized boolean start(Context context, String username, String role, String token,
                                      long expiresInSeconds, boolean remember) {
        UserSession.username = username;
        UserSession.role = role;
        UserSession.token = token;
        expiresAtMillis = System.currentTimeMillis() + Math.max(0, expiresInSeconds) * 1000L;
        SharedPreferences preferences = preferences(context);
        SharedPreferences.Editor editor = preferences.edit().putBoolean(KEY_REMEMBER, remember);
        if (remember) {
            editor.putString(KEY_USERNAME, username)
                    .putString(KEY_ROLE, role)
                    .putString(KEY_TOKEN, token)
                    .putLong(KEY_EXPIRES_AT, expiresAtMillis);
        } else {
            editor.remove(KEY_USERNAME)
                    .remove(KEY_ROLE)
                    .remove(KEY_TOKEN)
                    .remove(KEY_EXPIRES_AT);
        }
        boolean saved = editor.commit();
        return !remember || saved;
    }

    static synchronized boolean restore(Context context) {
        SharedPreferences preferences = preferences(context);
        if (!preferences.getBoolean(KEY_REMEMBER, false)) return false;
        String savedUsername = preferences.getString(KEY_USERNAME, null);
        String savedRole = preferences.getString(KEY_ROLE, null);
        String savedToken = preferences.getString(KEY_TOKEN, null);
        long savedExpiry = preferences.getLong(KEY_EXPIRES_AT, 0);
        if (savedUsername == null || savedToken == null || savedToken.isEmpty()
                || !("admin".equals(savedRole) || "guest".equals(savedRole))
                || savedExpiry <= System.currentTimeMillis()) {
            clearSessionData(preferences);
            return false;
        }
        username = savedUsername;
        role = savedRole;
        token = savedToken;
        expiresAtMillis = savedExpiry;
        return true;
    }

    static synchronized boolean isRememberEnabled(Context context) {
        return preferences(context).getBoolean(KEY_REMEMBER, true);
    }

    static synchronized void clear(Context context) {
        username = null;
        role = null;
        token = null;
        expiresAtMillis = 0;
        clearSessionData(preferences(context));
    }

    static synchronized boolean isAuthenticated() {
        return role != null && token != null && !token.isEmpty()
                && System.currentTimeMillis() < expiresAtMillis;
    }

    static synchronized boolean isAdmin() {
        return "admin".equals(role) && isAuthenticated();
    }

    static synchronized String getToken() {
        return token;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static void clearSessionData(SharedPreferences preferences) {
        preferences.edit()
                .remove(KEY_USERNAME)
                .remove(KEY_ROLE)
                .remove(KEY_TOKEN)
                .remove(KEY_EXPIRES_AT)
                .apply();
    }
}
