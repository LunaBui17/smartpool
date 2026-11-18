package com.buiducluan23011767.ck;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class AdminUsersPanel {
    private final Map<Long, JSONObject> users = new LinkedHashMap<>();
    private final ApiClient api;
    private final AdminActions actions;
    private final Runnable changed;
    private final EditText username;
    private final EditText password;
    private final EditText fullName;
    private final EditText student;
    private final EditText search;
    private final Spinner role;
    private final Button create;
    private final Button toggleCreate;
    private final View createForm;
    private final Button more;
    private final TextView status;
    private final LinearLayout list;
    private boolean loading;
    private boolean creating;
    private boolean loaded;
    private Long next;
    private long generation;

    AdminUsersPanel(AppCompatActivity activity, View root, ApiClient api, AdminActions actions, Runnable changed) {
        this.api = api;
        this.actions = actions;
        this.changed = changed;
        username = root.findViewById(R.id.edtUserUsername);
        password = root.findViewById(R.id.edtUserPassword);
        fullName = root.findViewById(R.id.edtUserFullName);
        student = root.findViewById(R.id.edtUserStudentCode);
        search = root.findViewById(R.id.edtAdminUserSearch);
        role = root.findViewById(R.id.spnUserRole);
        create = root.findViewById(R.id.btnSaveUser);
        toggleCreate = root.findViewById(R.id.btnToggleAdminUserForm);
        createForm = root.findViewById(R.id.adminUserCreateForm);
        more = root.findViewById(R.id.btnMoreAdminUsers);
        status = root.findViewById(R.id.txtAdminUsersStatus);
        list = root.findViewById(R.id.layoutUserList);
        root.findViewById(R.id.switchUserActive).setVisibility(View.GONE);
        root.findViewById(R.id.btnCancelUserEdit).setVisibility(View.GONE);
        role.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"User", "Admin"}));
        username.setText("");
        password.setText("");
        password.setHint("Mật khẩu tài khoản mới (7-72 byte UTF-8)");
        fullName.setText("");
        student.setText("");
        create.setText("Tạo tài khoản");
        create.setOnClickListener(v -> createUser());
        toggleCreate.setOnClickListener(v -> showCreateForm(createForm.getVisibility() != View.VISIBLE));
        showCreateForm(false);
        more.setOnClickListener(v -> load(false));
        search.setOnEditorActionListener((view, action, event) -> {
            render();
            return false;
        });
        render();
    }

    void refresh() { load(true); }

    private void load(boolean reset) {
        if (!reset && (loading || next == null)) return;
        JSONObject body = new JSONObject();
        try {
            body.put("limit", 50);
            if (!reset) body.put("after_id", next);
        } catch (JSONException error) {
            status.setText("Không tạo được yêu cầu danh sách tài khoản.");
            return;
        }
        if (reset) {
            users.clear();
            next = null;
            loaded = false;
            render();
        }
        long request = ++generation;
        Long after = reset ? null : next;
        loading = true;
        status.setText("Đang tải tài khoản...");
        updateActions();
        changed.run();
        api.post("list_users", body, null, response -> {
            if (request != generation) return;
            loading = false;
            try {
                JSONArray page = response.getJSONArray("users");
                if (!response.has("next_after_id")) throw new JSONException("Thiếu cursor");
                Map<Long, JSONObject> received = new LinkedHashMap<>();
                for (int i = 0; i < page.length(); i++) {
                    JSONObject user = page.getJSONObject(i);
                    long id = user.getLong("id");
                    if (id <= 0 || users.containsKey(id) || received.containsKey(id))
                        throw new JSONException("ID tài khoản không hợp lệ");
                    user.getString("username");
                    user.getString("full_name");
                    user.getBoolean("is_active");
                    if (!"admin".equals(user.getString("role")) && !"user".equals(user.getString("role")))
                        throw new JSONException("Role không hợp lệ");
                    received.put(id, user);
                }
                Long cursor = response.isNull("next_after_id") ? null : response.getLong("next_after_id");
                if (cursor != null && (page.length() == 0
                        || cursor.longValue() != page.getJSONObject(page.length() - 1).getLong("id")
                        || (after != null && cursor <= after))) throw new JSONException("Cursor không hợp lệ");
                users.putAll(received);
                next = cursor;
                loaded = true;
                render();
                status.setText("Đã tải " + users.size() + " tài khoản."
                        + (next == null ? "" : " Còn trang tiếp theo."));
            } catch (JSONException error) {
                status.setText("Danh sách tài khoản không hợp lệ. Nhấn Tải lại.");
            }
            updateActions();
            changed.run();
        }, (code, message) -> {
            if (request != generation) return;
            loading = false;
            status.setText(message);
            updateActions();
            changed.run();
        });
    }

    void render() {
        list.removeAllViews();
        String filter = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (JSONObject user : users.values()) {
            String text = user.optString("username") + " · " + user.optString("full_name")
                    + "\nID nội bộ: " + user.optLong("id") + " · " + user.optString("role")
                    + (user.optBoolean("is_active") ? " · Hoạt động" : " · Đã khóa")
                    + (user.isNull("student_code") ? "" : "\nMã sinh viên: " + user.optString("student_code"));
            if (!text.toLowerCase(Locale.ROOT).contains(filter)) continue;
            shown++;
            LinearLayout card = actions.column();
            card.setBackgroundResource(R.drawable.bg_card_modern);
            card.addView(actions.label(text));
            if ("user".equals(user.optString("role"))) {
                Button assign = actions.button(card, "Kiểm tra / gán quyền thiết bị");
                assign.setEnabled(user.optBoolean("is_active"));
                assign.setOnClickListener(v -> actions.assignDevice(user));
            } else {
                card.addView(actions.label("Admin có quyền trên mọi thiết bị, không cần cấp membership."));
            }
            Button active = actions.button(card, user.optBoolean("is_active") ? "Khóa tài khoản" : "Mở khóa");
            UserSession.Snapshot session = UserSession.snapshot();
            active.setEnabled(session != null && session.userId != user.optLong("id"));
            active.setOnClickListener(v -> actions.changeUserActive(user));
            actions.button(card, "Đặt lại mật khẩu").setOnClickListener(v -> actions.resetPassword(user));
            list.addView(card);
        }
        if (shown == 0 && !loading)
            list.addView(actions.label(users.isEmpty() ? "Chưa có tài khoản đã tải."
                    : "Không khớp bộ lọc trong các trang đã tải."));
    }

    private void createUser() {
        if (creating) return;
        String name = username.getText().toString().trim();
        if (!name.matches("[A-Za-z0-9._-]{3,64}")) {
            username.setError("Dùng 3-64 ký tự chữ, số, . _ -.");
            return;
        }
        if (!AdminActions.validPassword(password) || !AdminActions.required(fullName, 128)
                || !AdminActions.studentCode(student, false)) return;
        String createdRole = role.getSelectedItemPosition() == 0 ? "user" : "admin";
        JSONObject body = new JSONObject();
        try {
            body.put("username", name).put("password", password.getText().toString())
                    .put("full_name", fullName.getText().toString().trim())
                    .put("role", createdRole);
            if (!student.getText().toString().trim().isEmpty())
                body.put("student_code", student.getText().toString().trim());
        } catch (JSONException error) {
            status.setText("Không tạo được yêu cầu tài khoản.");
            return;
        }
        creating = true;
        updateActions();
        status.setText("Đang tạo tài khoản...");
        api.post("create_user", body, null, response -> {
            creating = false;
            if (response.optLong("user_id") <= 0) {
                status.setText("Server chưa trả ID hợp lệ. Tải lại trước khi tạo lại.");
                updateActions();
                return;
            }
            username.setText("");
            password.setText("");
            fullName.setText("");
            student.setText("");
            role.setSelection(0);
            showCreateForm(false);
            actions.feedback("Đã tạo " + name + ("admin".equals(createdRole)
                    ? ". Admin có quyền toàn hệ thống." : ". User mới chưa tự được gán thiết bị."));
            updateActions();
            refresh();
        }, (code, message) -> {
            creating = false;
            status.setText(code == 0 ? "Chưa xác nhận kết quả tạo tài khoản. " + message
                    + " Tải lại trước khi tạo lại." : message);
            updateActions();
        });
    }

    private void updateActions() {
        create.setEnabled(!creating);
        toggleCreate.setEnabled(!creating);
        for (View field : new View[]{username, password, fullName, student, role}) field.setEnabled(!creating);
        more.setEnabled(!loading && next != null);
    }

    private void showCreateForm(boolean visible) {
        createForm.setVisibility(visible ? View.VISIBLE : View.GONE);
        toggleCreate.setText(visible ? "Đóng biểu mẫu tạo tài khoản" : "Tạo tài khoản mới");
        if (!visible) password.setText("");
    }

    String summary() {
        if (!loaded) return "Tài khoản: chưa tải được danh mục.\n" + status.getText();
        int active = 0;
        for (JSONObject user : users.values()) if (user.optBoolean("is_active")) active++;
        return "Tài khoản đã tải: " + users.size() + (next == null ? " (hết danh sách)" : " (còn trang)")
                + "\nHoạt động: " + active + " · Đã khóa: " + (users.size() - active)
                + "\n" + status.getText();
    }

    void close() {
        generation++;
        password.setText("");
    }

    static void watch(EditText field, Runnable changed) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { changed.run(); }
            @Override public void afterTextChanged(Editable value) { }
        });
    }
}
