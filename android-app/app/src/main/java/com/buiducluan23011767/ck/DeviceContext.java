package com.buiducluan23011767.ck;

import org.json.JSONException;
import org.json.JSONObject;

final class DeviceContext {
    private static volatile Selection selected;

    static final class Selection {
        final UserSession.Snapshot session;
        final String id;
        final String name;
        final boolean active;
        final boolean canView;
        final boolean canControl;
        final boolean canConfigure;

        private Selection(UserSession.Snapshot session, JSONObject device) throws JSONException {
            this.session = session;
            id = device.getString("device_id");
            name = device.getString("name");
            active = device.getBoolean("is_active");
            canView = device.getBoolean("can_view");
            canControl = active && device.getBoolean("can_control");
            canConfigure = active && device.getBoolean("can_configure");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || name.isEmpty()
                    || !canView || (!active && !"admin".equals(session.role))) {
                throw new JSONException("Thiết bị không còn quyền xem hoặc đã vô hiệu hóa.");
            }
        }

        boolean isCurrent() {
            return selected == this && UserSession.matches(session);
        }
    }

    private DeviceContext() { }

    static Selection current() {
        Selection value = selected;
        return value != null && UserSession.matches(value.session) ? value : null;
    }

    static void select(UserSession.Snapshot session, JSONObject device) throws JSONException {
        if (!UserSession.matches(session)) throw new JSONException("Phiên đăng nhập đã thay đổi.");
        selected = new Selection(session, device);
    }

    static void clear() {
        selected = null;
    }
}
