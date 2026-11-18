package com.buiducluan23011767.ck;

import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class AdminActions {
    private final AppCompatActivity activity;
    private final ApiClient api;
    private final Runnable devicesChanged;
    private final Runnable usersChanged;
    private final Set<AlertDialog> dialogs = new HashSet<>();

    AdminActions(AppCompatActivity activity, ApiClient api, Runnable devicesChanged, Runnable usersChanged) {
        this.activity = activity;
        this.api = api;
        this.devicesChanged = devicesChanged;
        this.usersChanged = usersChanged;
    }

    void registerDevice() {
        LinearLayout form = column();
        EditText id = field(form, "Device ID (1-64 ký tự chữ, số, . _ -)");
        EditText name = field(form, "Tên thiết bị");
        EditText fullName = field(form, "Họ tên sinh viên");
        EditText student = field(form, "Mã sinh viên");
        form.addView(label("Đăng ký thủ công, chưa kích hoạt. Owner mặc định là admin đăng ký; "
                + "owner không thay thế quyền thành viên. Serial và BLE đang chờ API/firmware."));
        AlertDialog dialog = form("Đăng ký thiết bị", form, "Đăng ký");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String deviceId = id.getText().toString().trim();
            if (!deviceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                id.setError("ID không hợp lệ.");
                return;
            }
            if (!required(name, 128) || !required(fullName, 128) || !studentCode(student, true)) return;
            try {
                JSONObject body = new JSONObject().put("device_id", deviceId)
                        .put("name", name.getText().toString().trim())
                        .put("student_full_name", fullName.getText().toString().trim())
                        .put("student_code", student.getText().toString().trim())
                        .put("registration_source", "manual");
                mutate(dialog, "register_device", body, () -> {
                    feedback("Đã đăng ký " + deviceId + ". Thiết bị chưa được kích hoạt.");
                    devicesChanged.run();
                });
            } catch (JSONException error) {
                feedback("Không tạo được yêu cầu đăng ký.");
            }
        });
    }

    void manageDevice(JSONObject device) {
        String id = device.optString("device_id");
        boolean active = device.optBoolean("is_active");
        track(new AlertDialog.Builder(activity).setTitle(device.optString("name") + " · " + id)
                .setItems(new String[]{"Thành viên và phân quyền",
                        active ? "Vô hiệu hóa thiết bị" : "Kích hoạt thiết bị",
                        "Thông tin và chức năng đang chờ"}, (dialog, which) -> {
                    if (which == 0) new Members(id).show();
                    else if (which == 1) {
                        AlertDialog confirm = track(new AlertDialog.Builder(activity)
                                .setTitle(active ? "Vô hiệu hóa " + id + "?" : "Kích hoạt " + id + "?")
                                .setMessage(active
                                        ? "User sẽ không truy cập thiết bị; yêu cầu pending bị hủy trên server. "
                                        + "Đây KHÔNG phải lệnh tắt relay/bơm."
                                        : "Cho phép truy cập theo membership đã cấp. Không tự bật bơm.")
                                .setNegativeButton("Hủy", null).setPositiveButton("Xác nhận", null).create());
                        confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                            try {
                                mutate(confirm, "set_device_active", new JSONObject()
                                        .put("device_id", id).put("is_active", !active), () -> {
                                    feedback(active ? "Đã vô hiệu hóa thiết bị." : "Đã kích hoạt thiết bị.");
                                    devicesChanged.run();
                                });
                            } catch (JSONException error) {
                                feedback("Không tạo được yêu cầu trạng thái thiết bị.");
                            }
                        });
                    } else {
                        track(new AlertDialog.Builder(activity).setTitle("Thông tin " + id)
                                .setMessage("Tên: " + device.optString("name")
                                        + "\nOwner ID nội bộ: " + device.optString("owner_user_id", "Chưa có")
                                        + "\nSerial phần cứng: API chưa hỗ trợ."
                                        + "\nSửa metadata, lưu trữ/khôi phục và liên kết serial đang chờ API. "
                                        + "Không dùng vô hiệu hóa để giả lập xóa mềm.")
                                .setPositiveButton("Đóng", null).create());
                    }
                }).setNegativeButton("Đóng", null).create());
    }

    void changeUserActive(JSONObject user) {
        long id = user.optLong("id");
        boolean active = user.optBoolean("is_active");
        AlertDialog dialog = track(new AlertDialog.Builder(activity)
                .setTitle((active ? "Khóa " : "Mở khóa ") + user.optString("username") + "?")
                .setMessage(active
                        ? "Server thu hồi phiên và hủy yêu cầu pending của tài khoản. "
                        + "Không xóa tài khoản, lịch sử hay membership; không gửi lệnh tắt bơm."
                        : "Khôi phục đăng nhập theo quyền đang có. Đây không phải khôi phục từ lưu trữ.")
                .setNegativeButton("Hủy", null).setPositiveButton("Xác nhận", null).create());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                mutate(dialog, "set_user_active", new JSONObject().put("user_id", id)
                        .put("is_active", !active), () -> {
                    feedback(active ? "Đã khóa tài khoản." : "Đã mở khóa tài khoản.");
                    usersChanged.run();
                });
            } catch (JSONException error) {
                feedback("Không tạo được yêu cầu tài khoản.");
            }
        });
    }

    void resetPassword(JSONObject user) {
        LinearLayout content = column();
        EditText password = field(content, "Mật khẩu mới (7-72 byte UTF-8)");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(label("Server sẽ thu hồi các phiên của tài khoản. "
                + "Đổi mật khẩu chính bạn sẽ yêu cầu đăng nhập lại ở request tiếp theo."));
        AlertDialog dialog = form("Đặt lại mật khẩu · " + user.optString("username"), content, "Cập nhật");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!validPassword(password)) return;
            try {
                mutate(dialog, "set_user_password", new JSONObject().put("user_id", user.getLong("id"))
                        .put("password", password.getText().toString()), () -> {
                    password.setText("");
                    feedback("Server đã xác nhận đặt lại mật khẩu và thu hồi phiên.");
                    UserSession.Snapshot session = UserSession.snapshot();
                    if (session != null && session.userId == user.optLong("id")) ApiClient.expire(activity);
                });
            } catch (JSONException error) {
                feedback("Không tạo được yêu cầu mật khẩu.");
            }
        });
    }

    void assignDevice(JSONObject user) {
        feedback("Chọn một thiết bị để kiểm tra/gán quyền. API chưa có danh sách thiết bị theo user cho admin.");
        choose(false, device -> permissions(device.optString("device_id"), user.optLong("id"),
                user.optString("username"), devicesChanged));
    }

    private void permissions(String deviceId, long userId, String username, Runnable changed) {
        JSONObject body = new JSONObject();
        try {
            body.put("device_id", deviceId);
        } catch (JSONException error) {
            feedback("Không tạo được yêu cầu thành viên.");
            return;
        }
        AlertDialog progress = track(new AlertDialog.Builder(activity)
                .setTitle(username + " · " + deviceId).setMessage("Đang kiểm tra membership hiện tại...")
                .setNegativeButton("Hủy", null).create());
        api.post("list_device_members", body, null, response -> {
            if (!progress.isShowing()) return;
            progress.dismiss();
            try {
                if (!deviceId.equals(response.getString("device_id"))) throw new JSONException("Sai ID");
                JSONArray members = response.getJSONArray("members");
                JSONObject existing = null;
                for (int i = 0; i < members.length(); i++) {
                    JSONObject row = members.getJSONObject(i);
                    if (row.getLong("user_id") == userId) existing = row;
                }
                permissionForm(deviceId, userId, username, existing, changed);
            } catch (JSONException error) {
                feedback("Phản hồi thành viên không hợp lệ.");
            }
        }, (code, message) -> {
            if (!progress.isShowing()) return;
            progress.dismiss();
            feedback(message);
        });
    }

    private void permissionForm(String deviceId, long userId, String username,
                                JSONObject existing, Runnable changed) throws JSONException {
        LinearLayout content = column();
        content.addView(label(username + " · " + deviceId + "\n"
                + (existing == null ? "Chưa có membership." : "Đang sửa membership hiện có.")));
        CheckBox view = check(content, "Xem dữ liệu", true);
        view.setEnabled(false);
        CheckBox control = check(content, "Điều khiển", existing != null && existing.getBoolean("can_control"));
        CheckBox configure = check(content, "Cấu hình", existing != null && existing.getBoolean("can_configure"));
        content.addView(label("Membership luôn cần quyền xem. Cấp quyền cấu hình không đồng nghĩa "
                + "app/ESP đã hỗ trợ đầy đủ lưu và áp dụng cấu hình v2."));
        AlertDialog dialog = form("Quyền thiết bị", content, "Lưu quyền");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                JSONObject body = new JSONObject().put("device_id", deviceId).put("user_id", userId)
                        .put("can_view", true).put("can_control", control.isChecked())
                        .put("can_configure", configure.isChecked());
                mutate(dialog, "set_device_member", body, () -> {
                    feedback("Đã lưu quyền trên server.");
                    changed.run();
                });
            } catch (JSONException error) {
                feedback("Không tạo được yêu cầu phân quyền.");
            }
        });
        if (existing != null) {
            Button remove = button(content, "Gỡ quyền trên thiết bị này");
            remove.setOnClickListener(v -> {
                AlertDialog confirm = track(new AlertDialog.Builder(activity).setTitle("Gỡ quyền " + username + "?")
                        .setMessage("Chỉ gỡ khỏi " + deviceId + "; không xóa tài khoản hoặc quyền trên thiết bị khác. "
                                + "Yêu cầu pending của user trên thiết bị bị hủy; không tự tắt bơm.")
                        .setNegativeButton("Hủy", null).setPositiveButton("Gỡ quyền", null).create());
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                    try {
                        mutate(confirm, "remove_device_member", new JSONObject()
                                .put("device_id", deviceId).put("user_id", userId), () -> {
                            dialog.dismiss();
                            feedback("Đã gỡ membership trên server.");
                            changed.run();
                        });
                    } catch (JSONException error) {
                        feedback("Không tạo được yêu cầu gỡ quyền.");
                    }
                });
            });
        }
    }

    private final class Members {
        private final String deviceId;
        private AlertDialog dialog;
        private LinearLayout rows;
        private TextView status;
        private long generation;

        Members(String deviceId) { this.deviceId = deviceId; }

        void show() {
            LinearLayout content = column();
            status = label("Đang tải thành viên...");
            content.addView(status);
            button(content, "Thêm / cập nhật thành viên").setOnClickListener(v ->
                    choose(true, user -> permissions(deviceId, user.optLong("id"),
                            user.optString("username"), this::load)));
            button(content, "Tải lại thành viên").setOnClickListener(v -> load());
            rows = column();
            content.addView(rows);
            dialog = form("Thành viên · " + deviceId, content, "Đóng");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> dialog.dismiss());
            load();
        }

        void load() {
            if (!dialog.isShowing()) return;
            long request = ++generation;
            status.setText("Đang tải thành viên...");
            rows.removeAllViews();
            JSONObject body = new JSONObject();
            try {
                body.put("device_id", deviceId);
            } catch (JSONException error) {
                status.setText("Không tạo được yêu cầu thành viên.");
                return;
            }
            api.post("list_device_members", body, null, response -> {
                if (!dialog.isShowing() || request != generation) return;
                try {
                    if (!deviceId.equals(response.getString("device_id"))) throw new JSONException("Sai ID");
                    JSONArray members = response.getJSONArray("members");
                    for (int i = 0; i < members.length(); i++) {
                        JSONObject member = members.getJSONObject(i);
                        long id = member.getLong("user_id");
                        String username = member.getString("username");
                        rows.addView(label(username + " · " + member.getString("full_name")
                                + (member.getBoolean("account_active") ? "" : " · Tài khoản bị khóa")
                                + "\nXem: " + yesNo(member.getBoolean("can_view"))
                                + " · Điều khiển: " + yesNo(member.getBoolean("can_control"))
                                + " · Cấu hình: " + yesNo(member.getBoolean("can_configure"))));
                        button(rows, "Sửa / gỡ quyền · " + username).setOnClickListener(v ->
                                permissions(deviceId, id, username, this::load));
                    }
                    status.setText(members.length() == 0 ? "Chưa có user được gán. Admin có quyền toàn hệ thống."
                            : members.length() + " thành viên. Admin không cần membership riêng.");
                } catch (JSONException error) {
                    rows.removeAllViews();
                    status.setText("Phản hồi thành viên không hợp lệ. Hãy tải lại.");
                }
            }, (code, message) -> {
                if (dialog.isShowing() && request == generation) status.setText(message);
            });
        }
    }

    private void choose(boolean users, Consumer<JSONObject> selected) {
        new Picker(users, selected).load();
    }

    private final class Picker {
        private final boolean users;
        private final Consumer<JSONObject> selected;
        private final Map<String, JSONObject> choices = new LinkedHashMap<>();
        private final Set<String> seen = new HashSet<>();
        private final AlertDialog progress;
        private Object after;

        Picker(boolean users, Consumer<JSONObject> selected) {
            this.users = users;
            this.selected = selected;
            progress = track(new AlertDialog.Builder(activity).setTitle(users ? "Chọn user" : "Chọn thiết bị")
                    .setMessage("Đang tải các trang danh mục...").setNegativeButton("Hủy", null).create());
        }

        void load() {
            if (!progress.isShowing()) return;
            JSONObject body = new JSONObject();
            try {
                body.put("limit", 100);
                if (after != null) body.put(users ? "after_id" : "after_device_id", after);
            } catch (JSONException error) {
                progress.dismiss();
                feedback("Không tạo được yêu cầu danh mục.");
                return;
            }
            api.post(users ? "list_users" : "list_devices", body, null, response -> {
                if (!progress.isShowing()) return;
                try {
                    JSONArray page = response.getJSONArray(users ? "users" : "devices");
                    String cursorKey = users ? "next_after_id" : "next_after_device_id";
                    if (!response.has(cursorKey)) throw new JSONException("Thiếu cursor");
                    Object next = null;
                    if (!response.isNull(cursorKey)) {
                        if (users) next = response.getLong(cursorKey);
                        else next = response.getString(cursorKey);
                    }
                    String last = null;
                    for (int i = 0; i < page.length(); i++) {
                        JSONObject row = page.getJSONObject(i);
                        String id = users ? Long.toString(row.getLong("id")) : row.getString("device_id");
                        if (users) {
                            if (row.getLong("id") <= 0) throw new JSONException("ID user không hợp lệ");
                            row.getString("username");
                            row.getString("full_name");
                            String role = row.getString("role");
                            if (!"user".equals(role) && !"admin".equals(role)) throw new JSONException("Role sai");
                        } else {
                            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                                throw new JSONException("ID thiết bị không hợp lệ");
                            row.getString("name");
                        }
                        row.getBoolean("is_active");
                        if (!seen.add(id)) throw new JSONException("Trùng trang danh mục");
                        last = id;
                        if (!users || (row.getBoolean("is_active") && "user".equals(row.getString("role"))))
                            choices.put(id, row);
                    }
                    if (next != null && (!next.toString().equals(last) || next.equals(after)))
                        throw new JSONException("Cursor không tiến tới");
                    if (next != null) {
                        after = next;
                        load();
                    } else {
                        progress.dismiss();
                        showChoices();
                    }
                } catch (JSONException error) {
                    progress.dismiss();
                    feedback("Danh mục không hợp lệ; chưa thực hiện gán quyền. Hãy tải lại.");
                }
            }, (code, message) -> {
                if (!progress.isShowing()) return;
                progress.dismiss();
                feedback(message);
            });
        }

        private void showChoices() {
            if (choices.isEmpty()) {
                feedback(users ? "Chưa có user đang hoạt động để cấp quyền." : "Chưa có thiết bị để gán.");
                return;
            }
            List<JSONObject> records = new ArrayList<>(choices.values());
            String[] labels = new String[records.size()];
            for (int i = 0; i < records.size(); i++) {
                JSONObject row = records.get(i);
                labels[i] = users ? row.optString("username") + " · " + row.optString("full_name")
                        : row.optString("name") + " · " + row.optString("device_id")
                        + (row.optBoolean("is_active") ? "" : " · Vô hiệu hóa");
            }
            track(new AlertDialog.Builder(activity).setTitle(users ? "Chọn user" : "Chọn thiết bị")
                    .setItems(labels, (dialog, which) -> selected.accept(records.get(which)))
                    .setNegativeButton("Hủy", null).create());
        }
    }

    void mutate(AlertDialog dialog, String action, JSONObject body, Runnable success) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        api.post(action, body, null, response -> {
            dialog.dismiss();
            success.run();
        }, (code, message) -> {
            if (dialog.isShowing()) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            feedback(code == 0 ? "Chưa xác nhận kết quả trên server. " + message
                    + " Tải lại trước khi thao tác lại." : message);
        });
    }

    private AlertDialog track(AlertDialog dialog) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
        dialog.show();
        return dialog;
    }

    private AlertDialog form(String title, LinearLayout content, String positive) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        return track(new AlertDialog.Builder(activity).setTitle(title).setView(scroll)
                .setNegativeButton("Hủy", null).setPositiveButton(positive, null).create());
    }

    LinearLayout column() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(12 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        return content;
    }

    TextView label(String value) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextColor(activity.getColor(R.color.text_primary));
        text.setTextSize(14);
        text.setPadding(0, 8, 0, 8);
        return text;
    }

    Button button(LinearLayout parent, String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private EditText field(LinearLayout parent, String hint) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setSaveEnabled(false);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private CheckBox check(LinearLayout parent, String label, boolean checked) {
        CheckBox check = new CheckBox(activity);
        check.setText(label);
        check.setChecked(checked);
        parent.addView(check);
        return check;
    }

    static boolean required(EditText field, int maximum) {
        String text = field.getText().toString().trim();
        if (!text.isEmpty() && text.length() <= maximum) return true;
        field.setError("Nhập từ 1 đến " + maximum + " ký tự.");
        return false;
    }

    static boolean studentCode(EditText field, boolean required) {
        String value = field.getText().toString().trim();
        if ((!required && value.isEmpty()) || value.matches("[A-Za-z0-9._-]{1,32}")) return true;
        field.setError("Dùng 1-32 ký tự chữ, số, . _ -.");
        return false;
    }

    static boolean validPassword(EditText field) {
        String value = field.getText().toString();
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length >= 7 && length <= 72 && value.indexOf('\0') < 0) return true;
        field.setError("Mật khẩu cần 7-72 byte UTF-8 và không chứa ký tự NUL.");
        return false;
    }

    void feedback(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private static String yesNo(boolean value) { return value ? "Có" : "Không"; }

    void close() {
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
    }
}
