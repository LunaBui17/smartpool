package com.buiducluan23011767.ck;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

public class DeviceListActivity extends AppCompatActivity {
    static final String EXTRA_ADMIN_TAB = "admin_tab";
    static final int TAB_OVERVIEW = 0;
    static final int TAB_DEVICES = 1;
    static final int TAB_USERS = 2;
    private final Map<String, JSONObject> devices = new LinkedHashMap<>();
    private ApiClient api;
    private TextView status;
    private LinearLayout list;
    private Button refresh;
    private Button more;
    private String nextAfter;
    private boolean loading;
    private boolean selecting;
    private String firstPage;
    private boolean admin;
    private boolean devicesLoaded;
    private int adminTab;
    private EditText search;
    private Button register;
    private AdminActions adminActions;
    private AdminUsersPanel adminUsers;
    private UserSession.Snapshot screenSession;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!UserSession.restore(this)) {
            ApiClient.expire(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_device_list);
        screenSession = UserSession.snapshot();
        admin = UserSession.isAdmin();
        adminTab = state == null ? getIntent().getIntExtra(EXTRA_ADMIN_TAB, TAB_OVERVIEW)
                : state.getInt(EXTRA_ADMIN_TAB, TAB_OVERVIEW);
        if (adminTab < TAB_OVERVIEW || adminTab > TAB_USERS) adminTab = TAB_OVERVIEW;
        ScreenInsets.apply(findViewById(R.id.deviceListRoot));
        list = findViewById(R.id.deviceListItems);
        status = findViewById(R.id.deviceListStatus);
        refresh = findViewById(R.id.btnRefreshDevices);
        more = findViewById(R.id.btnMoreDevices);
        search = findViewById(R.id.deviceListSearch);
        register = findViewById(R.id.btnRegisterDevice);
        TextView account = findViewById(R.id.deviceListAccount);
        account.setText(UserSession.getUsername() + (UserSession.isAdmin() ? " · Admin" : " · User")
                + "\n" + AppConfig.getInstance(this).getHttpApiUrl());
        refresh.setOnClickListener(v -> {
            if (!admin || adminTab != TAB_USERS) loadPage(true);
            if (adminUsers != null && adminTab != TAB_DEVICES) adminUsers.refresh();
        });
        more.setOnClickListener(v -> loadPage(false));
        findViewById(R.id.btnDeviceListLogout).setOnClickListener(v -> ApiClient.logout(this));
        AdminUsersPanel.watch(search, this::render);
        if (admin) {
            register.setVisibility(View.VISIBLE);
            register.setOnClickListener(v -> {
                if (!loading && !selecting && adminActions != null) adminActions.registerDevice();
            });
            AdminUsersPanel.watch(findViewById(R.id.edtAdminUserSearch), () -> {
                if (adminUsers != null) adminUsers.render();
            });
            BottomNavigationView navigation = findViewById(R.id.adminNavigation);
            navigation.setVisibility(View.VISIBLE);
            navigation.setOnItemSelectedListener(item -> {
                adminTab = item.getItemId() == R.id.navAdminDevices ? TAB_DEVICES
                        : item.getItemId() == R.id.navAdminUsers ? TAB_USERS : TAB_OVERVIEW;
                showTab();
                return true;
            });
            findViewById(R.id.btnOverviewDevices).setOnClickListener(v ->
                    navigation.setSelectedItemId(R.id.navAdminDevices));
            findViewById(R.id.btnOverviewUsers).setOnClickListener(v ->
                    navigation.setSelectedItemId(R.id.navAdminUsers));
            navigation.setSelectedItemId(adminTab == TAB_DEVICES ? R.id.navAdminDevices
                    : adminTab == TAB_USERS ? R.id.navAdminUsers : R.id.navAdminOverview);
        }
        showTab();
        if (state == null) firstPage = getIntent().getStringExtra("login_devices");
    }

    @Override protected void onResume() {
        super.onResume();
        if (list == null) return;
        if (!UserSession.isAuthenticated()) {
            ApiClient.expire(this);
            finish();
            return;
        }
        if (!UserSession.matches(screenSession)) {
            startActivity(new Intent(this, DeviceListActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        DeviceContext.clear();
        api = new ApiClient(this);
        if (admin) {
            adminActions = new AdminActions(this, api, () -> loadPage(true), () -> {
                if (adminUsers != null) adminUsers.refresh();
            });
            adminUsers = new AdminUsersPanel(this, findViewById(R.id.adminUsersContent), api,
                    adminActions, this::renderOverview);
            adminUsers.refresh();
        }
        if (firstPage != null) {
            try {
                applyPage(new JSONObject(firstPage), true);
            } catch (JSONException error) {
                showError("Danh sách từ lần đăng nhập không hợp lệ. Nhấn Tải lại.");
            }
            firstPage = null;
        } else {
            loadPage(true);
        }
        renderOverview();
    }

    private void loadPage(boolean reset) {
        if (loading || selecting || api == null || (!reset && nextAfter == null)) return;
        JSONObject parameters = new JSONObject();
        try {
            parameters.put("limit", 50);
            if (!reset) parameters.put("after_device_id", nextAfter);
        } catch (JSONException error) {
            showError("Không tạo được yêu cầu danh sách thiết bị.");
            return;
        }
        if (reset) {
            devices.clear();
            devicesLoaded = false;
            nextAfter = null;
            render();
        }
        loading = true;
        updateActions();
        status.setText("Đang tải danh sách thiết bị...");
        renderOverview();
        final String cursor = reset ? null : nextAfter;
        api.post("list_devices", parameters, null, response -> {
            loading = false;
            try {
                applyPage(response, reset);
                if (nextAfter != null && nextAfter.equals(cursor)) {
                    nextAfter = null;
                    showError("Cursor không tiến tới trang mới. Nhấn Tải lại.");
                }
            } catch (JSONException error) {
                showError("Phản hồi danh sách thiết bị không hợp lệ. Nhấn Tải lại.");
            }
            updateActions();
        }, (code, message) -> {
            loading = false;
            showError(message);
        });
    }

    private void applyPage(JSONObject response, boolean reset) throws JSONException {
        if (!response.has("next_after_device_id")) throw new JSONException("Thiếu cursor danh sách");
        JSONArray page = response.getJSONArray("devices");
        Map<String, JSONObject> received = new LinkedHashMap<>();
        for (int i = 0; i < page.length(); i++) {
            JSONObject device = page.getJSONObject(i);
            String id = device.getString("device_id");
            device.getString("name");
            device.getBoolean("is_active");
            device.getBoolean("can_view");
            device.getBoolean("can_control");
            device.getBoolean("can_configure");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                throw new JSONException("ID không hợp lệ");
            received.put(id, device);
        }
        String cursor = response.isNull("next_after_device_id")
                ? null : response.getString("next_after_device_id");
        if (cursor != null && (page.length() == 0
                || !cursor.equals(page.getJSONObject(page.length() - 1).getString("device_id"))))
            throw new JSONException("Cursor không khớp trang");
        if (reset) devices.clear();
        devices.putAll(received);
        devicesLoaded = true;
        nextAfter = cursor;
        render();
        status.setText(devices.isEmpty()
                ? UserSession.isAdmin() ? "Chưa đăng ký thiết bị nào trên server."
                : "Bạn chưa được cấp quyền xem thiết bị đang hoạt động."
                : "Đã tải " + devices.size() + " thiết bị"
                + (nextAfter == null ? "." : ". Còn trang tiếp theo.")
                + "\nChọn thiết bị để mở Tổng quan / Điều khiển / Hệ thống.");
        updateActions();
        renderOverview();
    }

    private void render() {
        list.removeAllViews();
        String filter = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (JSONObject device : devices.values()) {
            if (!(device.optString("name") + " " + device.optString("device_id"))
                    .toLowerCase(Locale.ROOT).contains(filter)) continue;
            shown++;
            Button card = new Button(this);
            String label = device.optString("name") + "\nID: " + device.optString("device_id")
                    + (device.optBoolean("is_active") ? "" : "\nĐã vô hiệu hóa · chỉ admin xem")
                    + "\nXem" + (device.optBoolean("can_control") ? " · Điều khiển" : "")
                    + (device.optBoolean("can_configure") ? " · Cấu hình" : "");
            card.setText(label);
            card.setAllCaps(false);
            card.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            card.setTextColor(getColor(R.color.text_primary));
            card.setBackgroundResource(R.drawable.bg_card);
            card.setPadding(dp(16), dp(16), dp(16), dp(16));
            card.setMinHeight(dp(88));
            card.setEnabled(!loading && !selecting);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(12);
            if (admin) {
                LinearLayout container = adminActions == null ? new LinearLayout(this) : adminActions.column();
                container.setOrientation(LinearLayout.VERTICAL);
                container.setBackgroundResource(R.drawable.bg_card_modern);
                container.addView(card, new LinearLayout.LayoutParams(-1, -2));
                Button manage = new Button(this);
                manage.setText("Quản lý thiết bị / thành viên");
                manage.setAllCaps(false);
                manage.setOnClickListener(v -> {
                    if (!loading && !selecting && adminActions != null) adminActions.manageDevice(device);
                });
                container.addView(manage, new LinearLayout.LayoutParams(-1, -2));
                list.addView(container, params);
            } else {
                list.addView(card, params);
            }
            card.setOnClickListener(v -> select(device.optString("device_id")));
        }
        if (shown == 0 && !devices.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Không khớp bộ lọc trong các trang đã tải.");
            list.addView(empty);
        }
        updateActions();
    }

    private void select(String id) {
        if (loading || selecting || api == null) return;
        JSONObject parameters = new JSONObject();
        try {
            parameters.put("device_id", id);
        } catch (JSONException error) {
            showError("Không tạo được yêu cầu kiểm tra thiết bị.");
            return;
        }
        selecting = true;
        updateActions();
        status.setText("Đang xác minh quyền truy cập " + id + "...");
        UserSession.Snapshot session = UserSession.snapshot();
        api.post("get_device", parameters, null, response -> {
            selecting = false;
            try {
                JSONObject device = response.getJSONObject("device");
                if (!id.equals(device.getString("device_id")))
                    throw new JSONException("API trả nhầm thiết bị");
                DeviceContext.select(session, device);
                startActivity(new Intent(this, MainActivity.class));
            } catch (JSONException error) {
                showError("Thiết bị hoặc quyền truy cập không hợp lệ. Nhấn Tải lại.");
            }
            updateActions();
        }, (code, message) -> {
            selecting = false;
            if (code == 403 || code == 404) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                loadPage(true);
            } else {
                showError(message);
            }
        });
    }

    private void showError(String message) {
        status.setText(message);
        updateActions();
        renderOverview();
    }

    private void updateActions() {
        refresh.setEnabled(!loading && !selecting);
        register.setEnabled(!loading && !selecting);
        more.setEnabled(!loading && !selecting && nextAfter != null);
        for (int i = 0; i < list.getChildCount(); i++)
            setActionsEnabled(list.getChildAt(i), !loading && !selecting);
    }

    private void setActionsEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setActionsEnabled(group.getChildAt(i), enabled);
        }
    }

    private void showTab() {
        findViewById(R.id.adminOverviewContent).setVisibility(admin && adminTab == TAB_OVERVIEW ? View.VISIBLE : View.GONE);
        findViewById(R.id.deviceListContent).setVisibility(!admin || adminTab == TAB_DEVICES ? View.VISIBLE : View.GONE);
        findViewById(R.id.adminUsersContent).setVisibility(admin && adminTab == TAB_USERS ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.deviceListTitle)).setText(!admin ? "Thiết bị của bạn"
                : adminTab == TAB_DEVICES ? "Quản lý thiết bị"
                : adminTab == TAB_USERS ? "Quản lý người dùng" : "Quản trị hệ thống");
    }

    private void renderOverview() {
        if (!admin) return;
        int active = 0;
        for (JSONObject device : devices.values()) if (device.optBoolean("is_active")) active++;
        String deviceSummary = devicesLoaded ? "Thiết bị đã tải: " + devices.size()
                + (nextAfter == null ? " (hết danh sách)" : " (còn trang)")
                + "\nĐược kích hoạt: " + active + " · Vô hiệu hóa: " + (devices.size() - active)
                : "Thiết bị: chưa tải được danh mục.";
        ((TextView) findViewById(R.id.adminOverviewSummary)).setText(deviceSummary
                + "\n" + status.getText() + "\n\n"
                + (adminUsers == null ? "Tài khoản: đang tải..." : adminUsers.summary()));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt(EXTRA_ADMIN_TAB, adminTab);
        super.onSaveInstanceState(state);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onPause() {
        if (adminUsers != null) adminUsers.close();
        if (adminActions != null) adminActions.close();
        adminUsers = null;
        adminActions = null;
        if (api != null) api.close();
        api = null;
        loading = false;
        selecting = false;
        super.onPause();
    }
}
