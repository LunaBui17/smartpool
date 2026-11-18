package com.buiducluan23011767.ck;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import android.widget.Toast;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class OperationLog extends SQLiteOpenHelper {
    private static final String TAG = "OperationLog";
    private static OperationLog instance;
    private final Context context;
    private boolean syncing;

    static synchronized OperationLog get(Context context) {
        if (instance == null) instance = new OperationLog(context.getApplicationContext());
        return instance;
    }

    private OperationLog(Context context) {
        super(context, "operation_log.db", null, 1);
        this.context = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (event_id TEXT PRIMARY KEY, request_id TEXT NOT NULL, "
                + "username TEXT NOT NULL, api_url TEXT NOT NULL, device_id TEXT NOT NULL, "
                + "action TEXT NOT NULL, result TEXT NOT NULL, detail TEXT NOT NULL, "
                + "occurred_at_ms INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX event_account ON events(api_url, username, occurred_at_ms)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException("Unsupported operation log database upgrade");
    }

    String begin(String action) {
        String requestId = UUID.randomUUID().toString();
        return append(requestId, UserSession.getUsername(), AppConfig.getInstance(context).getHttpApiUrl(),
                AppConfig.getInstance(context).getDeviceId(), action, "requested", "")
                ? requestId : null;
    }

    void record(String action, String result, String detail) {
        append(UUID.randomUUID().toString(), UserSession.getUsername(),
                AppConfig.getInstance(context).getHttpApiUrl(),
                AppConfig.getInstance(context).getDeviceId(), action, result, detail);
    }

    void finish(String requestId, String result, String detail) {
        if (requestId == null) return;
        try (Cursor cursor = getReadableDatabase().query("events",
                new String[]{"username", "api_url", "device_id", "action"},
                "request_id = ?", new String[]{requestId}, null, null, "occurred_at_ms ASC", "1")) {
            if (!cursor.moveToFirst()) {
                throw new SQLiteException("Missing operation start: " + requestId);
            }
            append(requestId, cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), result, detail);
        } catch (SQLiteException e) {
            reportStorageError(e);
        }
    }

    private boolean append(String requestId, String username, String apiUrl, String deviceId,
                           String action, String result, String detail) {
        if (username == null) {
            Log.e(TAG, "Không ghi nhật ký khi chưa có tài khoản đăng nhập");
            Toast.makeText(context, "Không ghi được nhật ký: chưa đăng nhập.", Toast.LENGTH_LONG).show();
            return false;
        }
        ContentValues row = new ContentValues();
        row.put("event_id", UUID.randomUUID().toString());
        row.put("request_id", requestId);
        row.put("username", username);
        row.put("api_url", apiUrl);
        row.put("device_id", deviceId);
        row.put("action", action);
        row.put("result", result);
        String safeDetail = detail.replace('\n', ' ').replace('\r', ' ');
        int detailLength = safeDetail.codePointCount(0, safeDetail.length());
        row.put("detail", safeDetail.substring(0,
                safeDetail.offsetByCodePoints(0, Math.min(256, detailLength))));
        row.put("occurred_at_ms", System.currentTimeMillis());
        try {
            getWritableDatabase().insertOrThrow("events", null, row);
        } catch (SQLiteException e) {
            reportStorageError(e);
            return false;
        }
        sync();
        return true;
    }

    private void reportStorageError(SQLiteException error) {
        Log.e(TAG, "Không lưu được nhật ký thao tác", error);
        Toast.makeText(context, "Không lưu được nhật ký trên thiết bị.", Toast.LENGTH_LONG).show();
    }

    JSONArray readLocal() throws JSONException {
        String where = "api_url = ?";
        List<String> args = new ArrayList<>();
        args.add(AppConfig.getInstance(context).getHttpApiUrl());
        if (!UserSession.isAdmin()) {
            where += " AND username = ?";
            args.add(UserSession.getUsername());
        }
        try (Cursor cursor = getReadableDatabase().query("events", null, where,
                args.toArray(new String[0]), null, null, "occurred_at_ms DESC, rowid DESC", "100")) {
            return readEvents(cursor);
        }
    }

    private JSONArray readEvents(Cursor cursor) throws JSONException {
        JSONArray events = new JSONArray();
        while (cursor.moveToNext()) {
            JSONObject row = new JSONObject();
            for (String column : new String[]{"event_id", "request_id", "username", "device_id",
                    "action", "result", "detail"}) {
                row.put(column, cursor.getString(cursor.getColumnIndexOrThrow(column)));
            }
            row.put("occurred_at_ms", cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at_ms")));
            row.put("synced", cursor.getInt(cursor.getColumnIndexOrThrow("synced")) == 1);
            events.put(row);
        }
        return events;
    }

    void sync() {
        // Legacy local descriptions cannot be mapped to the typed v2 event contract.
    }

    static String resultLabel(String result) {
        switch (result) {
            case "requested": return "Đã yêu cầu, chưa xác nhận";
            case "confirmed": return "Đã xác nhận";
            case "failed": return "Thất bại";
            case "timeout": return "Hết thời gian chờ";
            case "interrupted": return "Ngừng chờ xác nhận";
            case "saved": return "Đã lưu trên app";
            case "published": return "Broker đã nhận, chưa xác nhận thiết bị";
            default: return result;
        }
    }
}
