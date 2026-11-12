package com.buiducluan23011767.ck;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    private View settingsRoot;
    private View toolbarContainer;

    private ImageButton btnBack;
    private ImageButton btnToolbarPreview;

    // Ngưỡng cảm biến
    private EditText edtTempMin;
    private EditText edtTempMax;
    private EditText edtWaterMin;
    private EditText edtWaterMax;
    private TextView txtWaterThresholdUnitInfo;
    private TextView txtWaterMinLabel;
    private TextView txtWaterMaxLabel;
    private EditText edtCalibrationCmStart;
    private EditText edtCalibrationCmEnd;
    private TextView txtWaterCalibrationInfo;
    private Button btnSaveWaterCalibration;
    private androidx.appcompat.widget.SwitchCompat switchSettingsInterlock;
    private Button btnSaveThresholds;
    private View settingsContent;
    private View automationContent;
    private View usersContent;
    private TextView tabSettings;
    private TextView tabAutomation;
    private TextView tabUsers;
    private TextView txtAutomationConnection;
    private TextView txtAutomationWaterUnitInfo;
    private Button btnRefreshAutomation;
    private LinearLayout layoutAutomationPumps;

    // WiFi
    private Button btnScanWifi;
    private Button btnHiddenWifi;
    private TextView txtWifiScanStatus;
    private LinearLayout layoutWifiSelect;
    private Spinner spnScannedWifi;
    private LinearLayout layoutWifiManual;
    private TextView txtWifiSsidLabel;
    private EditText edtWifiSsid;
    private EditText edtWifiPass;
    private Button btnSaveWifi;

    // Thiết bị
    private EditText edtDeviceId;
    private EditText edtDeviceName;
    private EditText edtDeviceLocation;
    private Button btnSaveDevice;

    // Cấu hình MQTT Broker & Tài khoản (người dùng có thể đổi)
    private EditText edtMqttBroker;
    private EditText edtMqttUser;
    private EditText edtMqttPass;
    private Button btnSaveMqttConfig;
    private TextView txtSettingsMqttStatus;

    // HTTP
    private EditText edtHttpUrl;
    private EditText edtHttpPoll;
    private Button btnSaveHttpConfig;

    // Các nút tổng
    private Button btnSaveAndPublishMqtt;
    private Button btnPreviewJson;
    private Button btnResetDefaults;
    private TextView txtSettingsFeedback;
    private EditText edtUserUsername;
    private EditText edtUserPassword;
    private androidx.appcompat.widget.SwitchCompat switchUserActive;
    private Button btnSaveUser;
    private Button btnCancelUserEdit;
    private LinearLayout layoutUserList;
    private int editingUserId;
    private TextView txtPreviewJsonInline;
    private boolean resetConfirmationPending;
    private final Runnable resetConfirmationExpiry = () -> {
        resetConfirmationPending = false;
        btnResetDefaults.setText("Khôi phục mặc định");
    };

    private AppConfig config;
    private RequestQueue accountRequestQueue;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MqttAsyncClient mqttClient;
    private boolean isMqttConnected = false;
    private boolean hasAutomationStatus;
    private JSONObject lastAutomationStatus;
    private int pendingAutomationChannel = -1;
    private final List<AutomationRuleViews> automationRuleViews = new ArrayList<>();
    private final List<String> scannedWifiList = new ArrayList<>();
    private ArrayAdapter<String> wifiSpinnerAdapter;

    // Mechanism 2: Double check cấu hình qua ACK & Request ID
    private String pendingAckReqId = null;
    private static final long ACK_TIMEOUT_MS = 10000L;
    private final Runnable ackTimeoutRunnable = this::handleAckTimeout;

    private static class AutomationConditionViews {
        androidx.appcompat.widget.SwitchCompat enabled;
        Spinner comparison;
        EditText threshold;
    }

    private static class AutomationRuleViews {
        int channel;
        androidx.appcompat.widget.SwitchCompat enabled;
        Spinner stopMode;
        EditText duration;
        TextView runtime;
        TextView dirty;
        Button save;
        AutomationConditionViews[] start = new AutomationConditionViews[2];
        AutomationConditionViews[] stop = new AutomationConditionViews[2];
        boolean loading;
        boolean dirtyValue;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserSession.restore(this);
        if (!UserSession.isAdmin()) {
            startActivity(new android.content.Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_settings);

        config = AppConfig.getInstance(this);

        initViews();
        applySystemBarInsets();
        setupWifiSpinner();
        loadValuesToViews();
        setupAutomationTab();
        setupListeners();

        refreshMqttActions();
        connectHiveMq();
    }

    private void initViews() {
        settingsRoot = findViewById(R.id.settingsRoot);
        toolbarContainer = findViewById(R.id.toolbarContainer);

        btnBack = findViewById(R.id.btnBack);
        btnToolbarPreview = findViewById(R.id.btnToolbarPreview);

        edtTempMin = findViewById(R.id.edtTempMin);
        edtTempMax = findViewById(R.id.edtTempMax);
        edtWaterMin = findViewById(R.id.edtWaterMin);
        edtWaterMax = findViewById(R.id.edtWaterMax);
        txtWaterThresholdUnitInfo = findViewById(R.id.txtWaterThresholdUnitInfo);
        txtWaterMinLabel = findViewById(R.id.txtWaterMinLabel);
        txtWaterMaxLabel = findViewById(R.id.txtWaterMaxLabel);
        edtCalibrationCmStart = findViewById(R.id.edtCalibrationCmStart);
        edtCalibrationCmEnd = findViewById(R.id.edtCalibrationCmEnd);
        txtWaterCalibrationInfo = findViewById(R.id.txtWaterCalibrationInfo);
        btnSaveWaterCalibration = findViewById(R.id.btnSaveWaterCalibration);
        switchSettingsInterlock = findViewById(R.id.switchSettingsInterlock);
        btnSaveThresholds = findViewById(R.id.btnSaveThresholds);

        btnScanWifi = findViewById(R.id.btnScanWifi);
        btnHiddenWifi = findViewById(R.id.btnHiddenWifi);
        txtWifiScanStatus = findViewById(R.id.txtWifiScanStatus);
        layoutWifiSelect = findViewById(R.id.layoutWifiSelect);
        spnScannedWifi = findViewById(R.id.spnScannedWifi);
        layoutWifiManual = findViewById(R.id.layoutWifiManual);
        txtWifiSsidLabel = findViewById(R.id.txtWifiSsidLabel);
        edtWifiSsid = findViewById(R.id.edtWifiSsid);
        edtWifiPass = findViewById(R.id.edtWifiPass);
        btnSaveWifi = findViewById(R.id.btnSaveWifi);

        edtDeviceId = findViewById(R.id.edtDeviceId);
        edtDeviceName = findViewById(R.id.edtDeviceName);
        edtDeviceLocation = findViewById(R.id.edtDeviceLocation);
        btnSaveDevice = findViewById(R.id.btnSaveDevice);

        edtMqttBroker = findViewById(R.id.edtMqttBroker);
        edtMqttUser = findViewById(R.id.edtMqttUser);
        edtMqttPass = findViewById(R.id.edtMqttPass);
        btnSaveMqttConfig = findViewById(R.id.btnSaveMqttConfig);
        txtSettingsMqttStatus = findViewById(R.id.txtSettingsMqttStatus);

        edtHttpUrl = findViewById(R.id.edtHttpUrl);
        edtHttpPoll = findViewById(R.id.edtHttpPoll);
        btnSaveHttpConfig = findViewById(R.id.btnSaveHttpConfig);

        btnSaveAndPublishMqtt = findViewById(R.id.btnSaveAndPublishMqtt);
        btnPreviewJson = findViewById(R.id.btnPreviewJson);
        btnResetDefaults = findViewById(R.id.btnResetDefaults);
        txtSettingsFeedback = findViewById(R.id.txtSettingsFeedback);
        edtUserUsername = findViewById(R.id.edtUserUsername);
        edtUserPassword = findViewById(R.id.edtUserPassword);
        switchUserActive = findViewById(R.id.switchUserActive);
        btnSaveUser = findViewById(R.id.btnSaveUser);
        btnCancelUserEdit = findViewById(R.id.btnCancelUserEdit);
        layoutUserList = findViewById(R.id.layoutUserList);
        txtPreviewJsonInline = findViewById(R.id.txtPreviewJsonInline);

        settingsContent = findViewById(R.id.settingsContent);
        automationContent = findViewById(R.id.automationContent);
        usersContent = findViewById(R.id.usersContent);
        tabSettings = findViewById(R.id.tabSettings);
        tabAutomation = findViewById(R.id.tabAutomation);
        tabUsers = findViewById(R.id.tabUsers);
        txtAutomationConnection = findViewById(R.id.txtAutomationConnection);
        txtAutomationWaterUnitInfo = findViewById(R.id.txtAutomationWaterUnitInfo);
        btnRefreshAutomation = findViewById(R.id.btnRefreshAutomation);
        layoutAutomationPumps = findViewById(R.id.layoutAutomationPumps);
    }

    private void setupAutomationTab() {
        tabSettings.setOnClickListener(v -> showSettingsTab(0));
        tabAutomation.setOnClickListener(v -> showSettingsTab(1));
        tabUsers.setVisibility(UserSession.isAdmin() ? View.VISIBLE : View.GONE);
        tabUsers.setOnClickListener(v -> showSettingsTab(2));
        btnRefreshAutomation.setOnClickListener(v -> requestAutomationStatus());
        layoutAutomationPumps.removeAllViews();
        automationRuleViews.clear();
        automationRuleViews.add(createAutomationRule(1, "Bơm cấp nước"));
        automationRuleViews.add(createAutomationRule(2, "Bơm xả nước"));
        for (AutomationRuleViews rule : automationRuleViews) {
            layoutAutomationPumps.addView(createAutomationRuleCard(rule));
        }
        updateAutomationWaterUnitUi();
        refreshAutomationControls();
    }

    private void showSettingsTab(int selected) {
        settingsContent.setVisibility(selected == 0 ? View.VISIBLE : View.GONE);
        automationContent.setVisibility(selected == 1 ? View.VISIBLE : View.GONE);
        usersContent.setVisibility(selected == 2 ? View.VISIBLE : View.GONE);
        View[] tabs = {tabSettings, tabAutomation, tabUsers};
        for (int i = 0; i < tabs.length; i++) {
            boolean active = i == selected;
            tabs[i].setBackgroundColor(getColor(active ? R.color.water_accent : R.color.white));
            ((TextView) tabs[i]).setTextColor(getColor(
                    active ? R.color.white : R.color.text_secondary));
        }
        if (selected == 1) requestAutomationStatus();
        if (selected == 2) requestUsers();
    }

    private AutomationRuleViews createAutomationRule(int channel, String title) {
        AutomationRuleViews rule = new AutomationRuleViews();
        rule.channel = channel;
        rule.enabled = new androidx.appcompat.widget.SwitchCompat(this);
        rule.enabled.setText("Bật tự động cho bơm này");
        rule.stopMode = createSpinner(new String[]{"Theo ngưỡng", "Theo thời gian"});
        rule.duration = createNumberInput("300", false);
        rule.runtime = new TextView(this);
        rule.runtime.setTextColor(getColor(R.color.text_secondary));
        rule.runtime.setTextSize(12);
        rule.dirty = new TextView(this);
        rule.dirty.setTextColor(getColor(R.color.warning));
        rule.dirty.setTextSize(11);
        rule.save = new Button(this);
        rule.save.setText("Lưu & áp dụng qua MQTT");
        rule.save.setAllCaps(false);
        rule.save.setBackgroundResource(R.drawable.bg_button_primary);
        rule.save.setTextColor(getColor(R.color.white));
        rule.save.setOnClickListener(v -> saveAutomationRule(rule));
        rule.enabled.setOnCheckedChangeListener((buttonView, checked) -> markAutomationDirty(rule));
        rule.stopMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!rule.loading) {
                    updateAutomationStopControls(rule);
                    markAutomationDirty(rule);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        addAutomationTextWatcher(rule.duration, rule);
        for (int sensor = 0; sensor < 2; sensor++) {
            rule.start[sensor] = createAutomationCondition(rule, sensor, true);
            rule.stop[sensor] = createAutomationCondition(rule, sensor, false);
        }
        return rule;
    }

    private AutomationConditionViews createAutomationCondition(
            AutomationRuleViews rule, int sensor, boolean start) {
        AutomationConditionViews condition = new AutomationConditionViews();
        condition.enabled = new androidx.appcompat.widget.SwitchCompat(this);
        condition.enabled.setText((start ? "Khi bật • " : "Khi tắt • ")
                + (sensor == 0 ? "Mực nước" : "Nhiệt độ (°C)"));
        condition.comparison = createSpinner(new String[]{"≤ ngưỡng", "≥ ngưỡng"});
        condition.threshold = createNumberInput(sensor == 0 ? "12.00" : "25", true);
        condition.enabled.setOnCheckedChangeListener((buttonView, checked) -> markAutomationDirty(rule));
        condition.comparison.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!rule.loading) markAutomationDirty(rule);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        addAutomationTextWatcher(condition.threshold, rule);
        return condition;
    }

    private View createAutomationRuleCard(AutomationRuleViews rule) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_modern);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);

        TextView title = automationLabel(rule.channel == 1 ? "BƠM CẤP NƯỚC" : "BƠM XẢ NƯỚC", 15, true);
        card.addView(title);
        rule.runtime.setPadding(0, dp(4), 0, dp(8));
        card.addView(rule.runtime);
        card.addView(rule.enabled);
        addAutomationConditionGroup(card, "ĐIỀU KIỆN BẬT (nước HOẶC nhiệt độ)", rule.start);
        addAutomationConditionGroup(card, "ĐIỀU KIỆN DỪNG (nước HOẶC nhiệt độ)", rule.stop);

        card.addView(automationLabel("Kiểu dừng", 12, false));
        card.addView(rule.stopMode, automationFieldLayout());
        card.addView(automationLabel("Thời gian tối đa (giây)", 12, false));
        card.addView(rule.duration, automationFieldLayout());
        rule.dirty.setPadding(0, dp(5), 0, 0);
        card.addView(rule.dirty);
        card.addView(rule.save, automationFieldLayout());
        updateAutomationStopControls(rule);
        return card;
    }

    private void addAutomationConditionGroup(
            LinearLayout parent, String title, AutomationConditionViews[] conditions) {
        TextView heading = automationLabel(title, 12, true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingParams.topMargin = dp(14);
        parent.addView(heading, headingParams);
        for (AutomationConditionViews condition : conditions) {
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundResource(R.drawable.bg_input_field);
            panel.setPadding(dp(10), dp(8), dp(10), dp(10));
            LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            panelParams.topMargin = dp(6);
            parent.addView(panel, panelParams);
            panel.addView(condition.enabled);
            panel.addView(automationLabel("So sánh", 11, false));
            panel.addView(condition.comparison, automationFieldLayout());
            panel.addView(automationLabel("Ngưỡng", 11, false));
            panel.addView(condition.threshold, automationFieldLayout());
        }
    }

    private Spinner createSpinner(String[] options) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private EditText createNumberInput(String hint, boolean decimal) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextSize(14);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        return input;
    }

    private TextView automationLabel(String text, int sizeSp, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(sizeSp);
        label.setTextColor(getColor(bold ? R.color.text_primary : R.color.text_secondary));
        if (bold) label.setTypeface(null, android.graphics.Typeface.BOLD);
        return label;
    }

    private LinearLayout.LayoutParams automationFieldLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        params.topMargin = dp(3);
        params.bottomMargin = dp(6);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void addAutomationTextWatcher(EditText input, AutomationRuleViews rule) {
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                markAutomationDirty(rule);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void markAutomationDirty(AutomationRuleViews rule) {
        if (rule.loading) return;
        rule.dirtyValue = true;
        rule.dirty.setText("Có thay đổi chưa lưu.");
        refreshAutomationControls();
    }

    private void updateAutomationStopControls(AutomationRuleViews rule) {
        boolean durationMode = rule.stopMode.getSelectedItemPosition() == 1;
        boolean canEdit = mqttClient != null && mqttClient.isConnected() && isMqttConnected
                && pendingAutomationChannel != rule.channel;
        for (int sensor = 0; sensor < rule.stop.length; sensor++) {
            AutomationConditionViews condition = rule.stop[sensor];
            boolean supported = hasAutomationStatus && lastAutomationStatus != null
                    && lastAutomationStatus.optBoolean(
                    sensor == 0 ? "water_supported" : "temperature_supported", false);
            condition.enabled.setEnabled(canEdit && supported && !durationMode);
            condition.comparison.setEnabled(canEdit && supported && !durationMode);
            condition.threshold.setEnabled(canEdit && supported && !durationMode);
        }
    }

    private void updateAutomationWaterUnitUi() {
        for (AutomationRuleViews rule : automationRuleViews) {
            for (int phase = 0; phase < 2; phase++) {
                AutomationConditionViews condition = phase == 0 ? rule.start[0] : rule.stop[0];
                condition.enabled.setText((phase == 0 ? "Khi bật • " : "Khi tắt • ")
                        + "Mực nước (cm)");
                condition.threshold.setInputType(InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_SIGNED
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            }
        }
        if (config.hasWaterLevelCalibration()) {
            txtAutomationWaterUnitInfo.setText(String.format(Locale.getDefault(),
                    "Khoảng đo đã hiệu chuẩn: %.2f–%.2f cm. App tự ánh xạ sang định dạng ESP32.",
                    Math.min(config.getWaterCalibrationCmStart(), config.getWaterCalibrationCmEnd()),
                    Math.max(config.getWaterCalibrationCmStart(), config.getWaterCalibrationCmEnd())));
        } else {
            txtAutomationWaterUnitInfo.setText("Cần hiệu chuẩn khoảng mực nước đo được trong tab Cài đặt trước.");
        }
    }

    private void requestAutomationStatus() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            txtAutomationConnection.setText("Chưa kết nối MQTT. Kết nối broker để tải hoặc lưu cấu hình tự động hóa.");
            return;
        }
        try {
            mqttClient.subscribe(config.getTopicStatus(), 1);
            txtAutomationConnection.setText("Đang yêu cầu cấu hình và trạng thái từ ESP32 qua MQTT…");
        } catch (MqttException e) {
            Log.e(TAG, "Không thể yêu cầu trạng thái tự động hóa qua MQTT", e);
            txtAutomationConnection.setText("Không thể tải trạng thái qua MQTT: " + e.getMessage());
        }
        refreshAutomationControls();
    }

    private void refreshAutomationControls() {
        boolean connected = mqttClient != null && mqttClient.isConnected() && isMqttConnected;
        boolean loaded = connected && hasAutomationStatus && lastAutomationStatus != null;
        btnRefreshAutomation.setEnabled(connected);
        for (AutomationRuleViews rule : automationRuleViews) {
            boolean supported = loaded && lastAutomationStatus.optBoolean(
                    "relay" + rule.channel + "_supported", false);
            boolean busy = pendingAutomationChannel == rule.channel;
            boolean editable = connected && !busy;
            rule.enabled.setEnabled(editable);
            rule.stopMode.setEnabled(editable);
            rule.duration.setEnabled(editable);
            rule.save.setEnabled(loaded && supported && !busy);
            for (int sensor = 0; sensor < 2; sensor++) {
                boolean sensorSupported = loaded && lastAutomationStatus.optBoolean(
                        sensor == 0 ? "water_supported" : "temperature_supported", false);
                for (AutomationConditionViews condition : new AutomationConditionViews[]{
                        rule.start[sensor], rule.stop[sensor]}) {
                    condition.enabled.setEnabled(editable && sensorSupported);
                    condition.comparison.setEnabled(editable && sensorSupported);
                    condition.threshold.setEnabled(editable && sensorSupported);
                }
            }
            updateAutomationStopControls(rule);
        }
    }

    private void renderAutomationStatus(String payload) {
        try {
            JSONObject status = new JSONObject(payload);
            JSONArray rules = status.optJSONArray("automation");
            if (rules == null || rules.length() < 2) return;
            lastAutomationStatus = status;
            hasAutomationStatus = true;
            JSONObject network = status.optJSONObject("network");
            String ip = network == null ? "" : network.optString("ip", "").trim();
            if (!ip.isEmpty()) config.setEspLocalIpAddress(ip);
            if (ip.isEmpty()) ip = config.getEspLocalIpAddress();
            txtAutomationConnection.setText("Đã nhận trạng thái ESP32 qua MQTT"
                    + (ip.isEmpty() ? "." : " • IP: " + ip)
                    + "\nQuy tắc được lưu trên ESP32; ngưỡng mực nước được nhập bằng cm.");
            for (int i = 0; i < 2; i++) {
                JSONObject ruleJson = rules.optJSONObject(i);
                if (ruleJson == null) continue;
                AutomationRuleViews rule = automationRuleViews.get(i);
                if (!rule.dirtyValue && pendingAutomationChannel != rule.channel) {
                    populateAutomationRule(rule, ruleJson);
                }
                String state = !status.optBoolean("relay" + rule.channel + "_supported", false)
                        ? "Relay chưa được kích hoạt trên ESP32"
                        : !ruleJson.optBoolean("enabled", false) ? "Tự động đang tắt"
                        : ruleJson.optBoolean("running", false) ? "Đang chạy tự động • còn "
                        + ruleJson.optInt("remaining_seconds", 0) + " giây"
                        : automationReason(ruleJson.optString("reason", "waiting_sensor"));
                rule.runtime.setText(state);
            }
        } catch (Exception e) {
            Log.e(TAG, "Không đọc được trạng thái tự động hóa MQTT", e);
            txtAutomationConnection.setText("Dữ liệu trạng thái tự động hóa từ ESP32 không hợp lệ.");
        }
        refreshAutomationControls();
    }

    private void populateAutomationRule(AutomationRuleViews rule, JSONObject source) {
        rule.loading = true;
        rule.enabled.setChecked(source.optBoolean("enabled", false));
        rule.stopMode.setSelection("duration".equals(source.optString("stop_mode")) ? 1 : 0);
        rule.duration.setText(String.valueOf(source.optInt("duration_seconds", 300)));
        JSONObject starts = source.optJSONObject("start_conditions");
        JSONObject stops = source.optJSONObject("stop_conditions");
        for (int sensor = 0; sensor < 2; sensor++) {
            String name = sensor == 0 ? "water" : "temperature";
            populateAutomationCondition(rule.start[sensor], starts == null ? null : starts.optJSONObject(name),
                    sensor == 0);
            populateAutomationCondition(rule.stop[sensor], stops == null ? null : stops.optJSONObject(name),
                    sensor == 0);
        }
        rule.loading = false;
        rule.dirtyValue = false;
        rule.dirty.setText("");
        updateAutomationStopControls(rule);
    }

    private void populateAutomationCondition(
            AutomationConditionViews target, JSONObject source, boolean convertWaterToCm) {
        if (source == null) return;
        target.enabled.setChecked(source.optBoolean("enabled", false));
        target.comparison.setSelection("above".equals(source.optString("comparison")) ? 1 : 0);
        double threshold = source.optDouble("threshold", Double.NaN);
        if (convertWaterToCm) {
            target.threshold.setText(config.isWaterAdcWithinCalibration(threshold)
                    ? String.format(Locale.getDefault(), "%.2f", config.waterAdcToCm(threshold))
                    : "");
        } else {
            target.threshold.setText(Double.isFinite(threshold)
                    ? source.optString("threshold", "0") : "");
        }
    }

    private String automationReason(String reason) {
        switch (reason) {
            case "waiting_sensor": return "Đang chờ số đo cảm biến hợp lệ";
            case "ready": return "Đang chờ điều kiện bật";
            case "waiting_reset": return "Chờ điều kiện bật được gỡ trước khi chạy lại";
            case "manual": return "Đã tạm dừng tự động do điều khiển tay";
            case "sensor_error": return "Đã dừng an toàn do lỗi cảm biến";
            case "interlock": return "Đang chờ bơm còn lại dừng (interlock)";
            case "target_reached": return "Đã đạt ngưỡng dừng";
            case "duration_done": return "Đã đủ thời gian bơm";
            case "timeout": return "Đã hết thời gian tối đa";
            case "relay_error": return "Lỗi relay; cần kiểm tra thiết bị";
            default: return "Tự động đang theo dõi";
        }
    }

    private void saveAutomationRule(AutomationRuleViews rule) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            showSettingsFeedback("Chưa kết nối MQTT.", true);
            return;
        }
        try {
            boolean enabled = rule.enabled.isChecked();
            String stopMode = rule.stopMode.getSelectedItemPosition() == 1 ? "duration" : "threshold";
            int duration = Integer.parseInt(rule.duration.getText().toString().trim());
            if (duration < 1 || duration > 86400) {
                throw new IllegalArgumentException("Thời gian tối đa phải từ 1 đến 86400 giây.");
            }
            JSONObject starts = new JSONObject();
            JSONObject stops = new JSONObject();
            int startCount = 0;
            int stopCount = 0;
            for (int sensor = 0; sensor < 2; sensor++) {
                String name = sensor == 0 ? "water" : "temperature";
                JSONObject start = automationConditionJson(rule.start[sensor], sensor);
                JSONObject stop = automationConditionJson(rule.stop[sensor], sensor);
                starts.put(name, start);
                stops.put(name, stop);
                if (start.optBoolean("enabled")) startCount++;
                if (stop.optBoolean("enabled")) stopCount++;
                if ("threshold".equals(stopMode) && start.optBoolean("enabled")
                        && stop.optBoolean("enabled")) {
                    boolean startBelow = "below".equals(start.optString("comparison"));
                    boolean stopBelow = "below".equals(stop.optString("comparison"));
                    double startValue = start.optDouble("threshold");
                    double stopValue = stop.optDouble("threshold");
                    if (startBelow == stopBelow
                            || (startBelow ? stopValue <= startValue : stopValue >= startValue)) {
                        throw new IllegalArgumentException("Với cùng cảm biến, ngưỡng dừng phải ngược chiều và cách ngưỡng bật.");
                    }
                }
                if (enabled && (start.optBoolean("enabled")
                        || ("threshold".equals(stopMode) && stop.optBoolean("enabled")))) {
                    String supportKey = sensor == 0 ? "water_enabled" : "temperature_enabled";
                    if (lastAutomationStatus == null || !isAutomationSensorEnabled(supportKey)) {
                        throw new IllegalArgumentException("Hãy bật cảm biến "
                                + (sensor == 0 ? "mực nước" : "nhiệt độ") + " trên ESP32 trước.");
                    }
                }
            }
            if (startCount == 0 || ("threshold".equals(stopMode) && stopCount == 0)) {
                throw new IllegalArgumentException("Chọn ít nhất một điều kiện bật và một điều kiện dừng.");
            }
            String reqId = "req_auto_" + rule.channel + "_" + UUID.randomUUID().toString().replace("-", "");
            JSONObject payload = new JSONObject();
            payload.put("action", "UPDATE_AUTOMATION");
            payload.put("req_id", reqId);
            payload.put("timestamp", System.currentTimeMillis() / 1000L);
            payload.put("channel", rule.channel);
            payload.put("enabled", enabled);
            payload.put("stop_mode", stopMode);
            payload.put("duration_seconds", duration);
            payload.put("start_conditions", starts);
            payload.put("stop_conditions", stops);
            pendingAutomationChannel = rule.channel;
            refreshAutomationControls();
            publishWithAckProgress("Lưu tự động hóa bơm " + rule.channel,
                    config.getTopicConfigAutomation(), payload.toString(), reqId,
                    "Đã lưu tự động hóa", false);
        } catch (NumberFormatException e) {
            showSettingsFeedback("Nhập thời gian tối đa hợp lệ.", true);
        } catch (IllegalArgumentException e) {
            showSettingsFeedback(e.getMessage(), true);
        } catch (Exception e) {
            Log.e(TAG, "Không thể tạo cấu hình tự động hóa", e);
            showSettingsFeedback("Không thể tạo cấu hình tự động hóa.", true);
        }
    }

    private JSONObject automationConditionJson(AutomationConditionViews condition, int sensor)
            throws Exception {
        boolean enabled = condition.enabled.isChecked();
        String text = condition.threshold.getText().toString().trim();
        if (text.isEmpty() && !enabled) {
            JSONObject disabled = new JSONObject();
            disabled.put("enabled", false);
            disabled.put("comparison",
                    condition.comparison.getSelectedItemPosition() == 0 ? "below" : "above");
            disabled.put("threshold", 0);
            return disabled;
        }
        if (text.isEmpty()) throw new IllegalArgumentException("Nhập đầy đủ các ngưỡng đang bật.");
        final double inputValue;
        try {
            inputValue = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ngưỡng phải là số hợp lệ.");
        }
        if (!Double.isFinite(inputValue)) {
            throw new IllegalArgumentException("Ngưỡng phải là số hữu hạn.");
        }
        double threshold = inputValue;
        if (sensor == 0) {
            if (!config.hasWaterLevelCalibration()) {
                throw new IllegalArgumentException("Cần lưu khoảng mực nước đo được trước khi đặt ngưỡng cm.");
            }
            double cmStart = Math.min(config.getWaterCalibrationCmStart(),
                    config.getWaterCalibrationCmEnd());
            double cmEnd = Math.max(config.getWaterCalibrationCmStart(),
                    config.getWaterCalibrationCmEnd());
            if (inputValue < cmStart || inputValue > cmEnd) {
                throw new IllegalArgumentException(String.format(Locale.getDefault(),
                        "Ngưỡng mực nước phải trong khoảng %.2f–%.2f cm.", cmStart, cmEnd));
            }
            threshold = Math.round(config.waterCmToAdc(inputValue));
        } else if (inputValue < -55 || inputValue > 125) {
            throw new IllegalArgumentException("Ngưỡng nhiệt độ phải từ -55 đến 125 °C.");
        }
        JSONObject result = new JSONObject();
        result.put("enabled", enabled);
        result.put("comparison", condition.comparison.getSelectedItemPosition() == 0 ? "below" : "above");
        result.put("threshold", threshold);
        return result;
    }

    private boolean isAutomationSensorEnabled(String key) {
        if (lastAutomationStatus == null) return false;
        return lastAutomationStatus.optBoolean(key, false)
                || lastAutomationStatus.optInt(key, 0) == 1;
    }

    private void applySystemBarInsets() {
        if (settingsRoot == null || toolbarContainer == null) return;
        final int headerTop = toolbarContainer.getPaddingTop();

        ViewCompat.setOnApplyWindowInsetsListener(settingsRoot, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );

            toolbarContainer.setPadding(
                    toolbarContainer.getPaddingLeft(),
                    headerTop + bars.top,
                    toolbarContainer.getPaddingRight(),
                    toolbarContainer.getPaddingBottom()
            );

            return insets;
        });

        ViewCompat.requestApplyInsets(settingsRoot);
    }

    private void setupWifiSpinner() {
        scannedWifiList.clear();
        scannedWifiList.add("-- Chọn mạng WiFi quét được --");
        wifiSpinnerAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                scannedWifiList
        );
        spnScannedWifi.setAdapter(wifiSpinnerAdapter);

        spnScannedWifi.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0 && position < scannedWifiList.size()) {
                    String selected = scannedWifiList.get(position);
                    // Lọc bỏ phần RSSI nếu có ví dụ: "MyWiFi (-65 dBm)"
                    int idx = selected.indexOf(" (");
                    String ssid = idx > 0 ? selected.substring(0, idx) : selected;
                    edtWifiSsid.setText(ssid);
                    txtWifiSsidLabel.setText("Tên mạng WiFi (Đã chọn từ danh sách):");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void loadValuesToViews() {
        edtDeviceId.setText(config.getDeviceId());
        edtDeviceName.setText(config.getDeviceName());
        edtDeviceLocation.setText(config.getDeviceLocation());

        edtMqttBroker.setText(config.getMqttBroker());
        edtMqttUser.setText(config.getMqttUsername());
        edtMqttPass.setText(config.getMqttPassword());

        edtTempMin.setText(String.valueOf(config.getTempMin()));
        edtTempMax.setText(String.valueOf(config.getTempMax()));
        edtWaterMin.setText(config.hasWaterLevelCalibration()
                ? formatThreshold(config.getWaterThresholdDisplayMin()) : "");
        edtWaterMax.setText(config.hasWaterLevelCalibration()
                ? formatThreshold(config.getWaterThresholdDisplayMax()) : "");
        updateWaterThresholdUnitLabels();
        if (config.hasWaterLevelCalibration()) {
            edtCalibrationCmStart.setText(String.valueOf(config.getWaterCalibrationCmStart()));
            edtCalibrationCmEnd.setText(String.valueOf(config.getWaterCalibrationCmEnd()));
            txtWaterCalibrationInfo.setText(String.format(Locale.getDefault(),
                    "Khoảng đo: %.2f–%.2f cm. Ánh xạ cảm biến được dùng nội bộ.",
                    Math.min(config.getWaterCalibrationCmStart(), config.getWaterCalibrationCmEnd()),
                    Math.max(config.getWaterCalibrationCmStart(), config.getWaterCalibrationCmEnd())));
        } else {
            edtCalibrationCmStart.setText("");
            edtCalibrationCmEnd.setText("");
            txtWaterCalibrationInfo.setText("Chưa hiệu chuẩn. Nhập mực nước thấp nhất và cao nhất có thể đo.");
        }
        switchSettingsInterlock.setChecked(config.isInterlockEnabled());

        edtWifiSsid.setText(config.getWifiSsid());
        edtWifiPass.setText(config.getWifiPassword());

        edtHttpUrl.setText(config.getHttpApiUrl());
        edtHttpPoll.setText(String.valueOf(config.getHttpPollIntervalSec()));
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnToolbarPreview.setOnClickListener(v -> togglePreviewJson());
        btnPreviewJson.setOnClickListener(v -> togglePreviewJson());
        btnSaveUser.setOnClickListener(v -> saveUser());
        btnCancelUserEdit.setOnClickListener(v -> resetUserForm());

        switchSettingsInterlock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setInterlockEnabled(isChecked);
            showSettingsFeedback(isChecked ? "Đã bật khóa liên động."
                    : "Đã tắt khóa liên động.", false);
        });

        btnSaveWaterCalibration.setOnClickListener(v -> saveWaterCalibration());

        // 1. Nút Lưu riêng cho Ngưỡng cảm biến
        btnSaveThresholds.setOnClickListener(v -> saveAndPublishThresholds());

        // 2. Nút Quét mạng WiFi (Gửi lệnh MQTT xuống ESP32)
        btnScanWifi.setOnClickListener(v -> triggerWifiScan());

        // 3. Nút Nhập mạng ẩn (SSID ẩn)
        btnHiddenWifi.setOnClickListener(v -> {
            layoutWifiManual.setVisibility(View.VISIBLE);
            txtWifiSsidLabel.setText("Tên mạng WiFi ẩn (SSID nhập thủ công):");
            edtWifiSsid.requestFocus();
            showSettingsFeedback("Đã chuyển sang chế độ nhập mạng WiFi ẩn thủ công.", false);
        });

        // 4. Nút Lưu riêng cho WiFi
        btnSaveWifi.setOnClickListener(v -> saveAndPublishWifi());

        // 5. Nút Lưu riêng cho Thông tin thiết bị
        btnSaveDevice.setOnClickListener(v -> saveAndPublishDevice());

        // 6. Nút Lưu riêng cho MQTT Broker & Tài khoản
        btnSaveMqttConfig.setOnClickListener(v -> saveMqttBrokerAndCredentials());

        btnSaveHttpConfig.setOnClickListener(v -> saveAndPublishHttp());

        // 7. Nút Gửi toàn bộ cấu hình đồng bộ (ALL)
        btnSaveAndPublishMqtt.setOnClickListener(v -> saveAndPublishAll());

        // 8. Khôi phục mặc định
        btnResetDefaults.setOnClickListener(v -> confirmResetDefaultsInline());
    }

    private void showSettingsFeedback(String message, boolean error) {
        txtSettingsFeedback.setText(message);
        txtSettingsFeedback.setTextColor(getColor(error ? R.color.danger : R.color.text_primary));
        txtSettingsFeedback.setVisibility(View.VISIBLE);
    }

    private void confirmResetDefaultsInline() {
        if (!resetConfirmationPending) {
            resetConfirmationPending = true;
            btnResetDefaults.setText("Chạm lần nữa để xác nhận");
            showSettingsFeedback("Chạm lại nút trong 5 giây để khôi phục mặc định.", true);
            handler.removeCallbacks(resetConfirmationExpiry);
            handler.postDelayed(resetConfirmationExpiry, 5000L);
            return;
        }
        handler.removeCallbacks(resetConfirmationExpiry);
        resetConfirmationPending = false;
        btnResetDefaults.setText("Khôi phục mặc định");
        config.resetToDefaults();
        loadValuesToViews();
        showSettingsFeedback("Đã khôi phục cài đặt mặc định.", false);
    }

    private void togglePreviewJson() {
        if (txtPreviewJsonInline.getVisibility() == View.VISIBLE) {
            txtPreviewJsonInline.setVisibility(View.GONE);
            return;
        }
        commitGeneralValues();
        StringBuilder preview = new StringBuilder();
        preview.append("=== 1. TOPIC: ").append(config.getTopicConfigThresholds()).append(" ===\n")
                .append(config.buildThresholdsJson()).append("\n\n")
                .append("=== 2. TOPIC: ").append(config.getTopicConfigWifi()).append(" ===\n")
                .append(config.buildWifiJson()).append("\n\n")
                .append("=== 3. TOPIC: ").append(config.getTopicConfigDevice()).append(" ===\n")
                .append(config.buildDeviceJson()).append("\n\n")
                .append("=== 4. TOPIC TỔNG HỢP: ").append(config.getTopicConfig()).append(" ===\n")
                .append(config.toJsonString(true));
        txtPreviewJsonInline.setText(preview.toString());
        txtPreviewJsonInline.setVisibility(View.VISIBLE);
        showSettingsFeedback("Đang hiển thị JSON cấu hình bên trong trang.", false);
    }

    private void saveUser() {
        if (!UserSession.isAdmin()) {
            showSettingsFeedback("Phiên admin đã hết hạn. Hãy đăng nhập lại.", true);
            UserSession.clear(this);
            finish();
            return;
        }
        String username = edtUserUsername.getText().toString().trim();
        String password = edtUserPassword.getText().toString();
        if (!username.matches("[A-Za-z0-9._-]{3,64}")) {
            edtUserUsername.setError("Dùng 3-64 ký tự chữ, số, . _ -.");
            return;
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if ((editingUserId == 0 && passwordBytes < 8)
                || (!password.isEmpty() && (passwordBytes < 8 || passwordBytes > 72))) {
            edtUserPassword.setError(editingUserId == 0
                    ? "Mật khẩu cần dài 8-72 byte." : "Mật khẩu mới cần dài 8-72 byte.");
            return;
        }
        boolean editing = editingUserId != 0;
        JSONObject body = new JSONObject();
        try {
            body.put("action", "save_user");
            if (editingUserId != 0) body.put("user_id", editingUserId);
            body.put("username", username);
            if (!password.isEmpty()) body.put("password", password);
            body.put("role", "guest");
            body.put("is_active", switchUserActive.isChecked());
        } catch (org.json.JSONException e) {
            showSettingsFeedback("Không thể tạo yêu cầu lưu tài khoản.", true);
            return;
        }
        if (accountRequestQueue == null) {
            accountRequestQueue = Volley.newRequestQueue(getApplicationContext());
        }
        String apiUrl = config.getHttpApiUrl().trim();
        if (apiUrl.isEmpty()) {
            showSettingsFeedback("Hãy cấu hình HTTP API trước khi lưu tài khoản.", true);
            return;
        }
        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST,
                apiUrl,
                body,
                response -> {
                    resetUserForm();
                    showSettingsFeedback(editing
                            ? "Đã cập nhật tài khoản: " + username : "Đã tạo tài khoản: " + username, false);
                    requestUsers();
                },
                error -> {
                    showAccountApiError(error, "Không lưu được tài khoản.");
                }
        ) {
            @Override
            public java.util.Map<String, String> getHeaders() {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Authorization", "Bearer " + UserSession.getToken());
                return headers;
            }
        };
        request.setRetryPolicy(new DefaultRetryPolicy(8000, 0, 1.0f));
        request.setTag(this);
        accountRequestQueue.add(request);
    }

    private void requestUsers() {
        if (!UserSession.isAdmin()) {
            showSettingsFeedback("Chỉ admin được quản lý tài khoản.", true);
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("action", "list_users");
        } catch (org.json.JSONException e) {
            showSettingsFeedback("Không thể tạo yêu cầu tải danh sách.", true);
            return;
        }
        if (accountRequestQueue == null) {
            accountRequestQueue = Volley.newRequestQueue(getApplicationContext());
        }
        String apiUrl = config.getHttpApiUrl().trim();
        if (apiUrl.isEmpty()) {
            showSettingsFeedback("Hãy cấu hình HTTP API trước khi quản lý tài khoản.", true);
            return;
        }
        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST, apiUrl, body,
                response -> renderUsers(response.optJSONArray("users")),
                error -> showAccountApiError(error, "Không tải được danh sách tài khoản.")
        ) {
            @Override
            public java.util.Map<String, String> getHeaders() {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Authorization", "Bearer " + UserSession.getToken());
                return headers;
            }
        };
        request.setRetryPolicy(new DefaultRetryPolicy(8000, 0, 1.0f));
        request.setTag(this);
        accountRequestQueue.add(request);
    }

    private void renderUsers(JSONArray users) {
        layoutUserList.removeAllViews();
        if (users == null) {
            showSettingsFeedback("API trả về danh sách tài khoản không hợp lệ.", true);
            return;
        }
        if (users.length() == 0) {
            layoutUserList.addView(automationLabel("Chưa có tài khoản.", 12, false));
            return;
        }
        for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.optJSONObject(i);
            if (user == null) continue;
            int userId = user.optInt("id", -1);
            String username = user.optString("username", "");
            boolean active = user.optInt("is_active", 0) == 1;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackgroundResource(R.drawable.bg_card_modern);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(8);
            layoutUserList.addView(card, cardParams);

            TextView detail = automationLabel(username + " • GUEST"
                    + (active ? " • Hoạt động" : " • Đã khóa"), 13, true);
            card.addView(detail);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            card.addView(actions);
            Button edit = new Button(this);
            edit.setText("Sửa");
            edit.setAllCaps(false);
            edit.setOnClickListener(v -> editUser(user));
            actions.addView(edit, new LinearLayout.LayoutParams(0, dp(42), 1));
            Button delete = new Button(this);
            delete.setText("Xóa");
            delete.setAllCaps(false);
            delete.setTextColor(getColor(R.color.danger));
            delete.setOnClickListener(v -> deleteUser(userId, username));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(42), 1);
            deleteParams.leftMargin = dp(8);
            actions.addView(delete, deleteParams);
        }
    }

    private void editUser(JSONObject user) {
        editingUserId = user.optInt("id", 0);
        edtUserUsername.setText(user.optString("username", ""));
        edtUserPassword.setText("");
        switchUserActive.setChecked(user.optInt("is_active", 0) == 1);
        btnSaveUser.setText("LƯU THAY ĐỔI");
        btnCancelUserEdit.setVisibility(View.VISIBLE);
        edtUserUsername.requestFocus();
    }

    private void resetUserForm() {
        editingUserId = 0;
        edtUserUsername.setText("");
        edtUserPassword.setText("");
        switchUserActive.setChecked(true);
        btnSaveUser.setText("TẠO TÀI KHOẢN");
        btnCancelUserEdit.setVisibility(View.GONE);
    }

    private void deleteUser(int userId, String username) {
        if (accountRequestQueue == null) {
            accountRequestQueue = Volley.newRequestQueue(getApplicationContext());
        }
        String apiUrl = config.getHttpApiUrl().trim();
        if (apiUrl.isEmpty()) {
            showSettingsFeedback("Hãy cấu hình HTTP API trước khi xóa tài khoản.", true);
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("action", "delete_user");
            body.put("user_id", userId);
        } catch (org.json.JSONException e) {
            showSettingsFeedback("Không thể tạo yêu cầu xóa tài khoản.", true);
            return;
        }
        JsonObjectRequest request = new JsonObjectRequest(
                Request.Method.POST, apiUrl, body,
                response -> {
                    showSettingsFeedback("Đã xóa tài khoản: " + username, false);
                    if (editingUserId == userId) resetUserForm();
                    requestUsers();
                },
                error -> showAccountApiError(error, "Không xóa được tài khoản.")
        ) {
            @Override
            public java.util.Map<String, String> getHeaders() {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Authorization", "Bearer " + UserSession.getToken());
                return headers;
            }
        };
        request.setRetryPolicy(new DefaultRetryPolicy(8000, 0, 1.0f));
        request.setTag(this);
        accountRequestQueue.add(request);
    }

    private void showAccountApiError(com.android.volley.VolleyError error, String fallback) {
        String message = fallback;
        if (error.networkResponse != null && error.networkResponse.data != null) {
            try {
                JSONObject response = new JSONObject(new String(
                        error.networkResponse.data, StandardCharsets.UTF_8));
                message = response.optString("message", message);
            } catch (org.json.JSONException parseError) {
                Log.w(TAG, "Không đọc được lỗi tài khoản từ API", parseError);
            }
        }
        showSettingsFeedback(message, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!UserSession.isAdmin()) {
            UserSession.clear(this);
            startActivity(new android.content.Intent(this, LoginActivity.class));
            finish();
        }
    }

    // ==================== KẾT NỐI HIVEMQ CLOUD ====================

    private void refreshMqttActions() {
        boolean ready = mqttClient != null && mqttClient.isConnected();
        for (android.view.View action : new android.view.View[]{btnSaveThresholds, btnScanWifi,
                btnSaveWifi, btnSaveDevice, btnSaveHttpConfig, btnSaveAndPublishMqtt}) {
            if (action != null) action.setEnabled(ready);
        }
        refreshAutomationControls();
    }

    private void connectHiveMq() {
        final String broker = config.getMqttBroker();
        final String username = config.getMqttUsername();
        final String password = config.getMqttPassword();

        try {
            if (mqttClient == null) {
                String clientId = "SettingsMgr-" + UUID.randomUUID().toString().substring(0, 8);
                mqttClient = new MqttAsyncClient(broker, clientId, new MemoryPersistence());

                mqttClient.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String uri) {
                        isMqttConnected = true;
                        Log.i(TAG, "HiveMQ connected");
                        subscribeScanResultTopic();
                        handler.post(() -> {
                            refreshMqttActions();
                            if (txtSettingsMqttStatus != null) {
                                txtSettingsMqttStatus.setText("✓ Trạng thái: Đã kết nối HiveMQ Cloud • Sẵn sàng");
                                txtSettingsMqttStatus.setTextColor(android.graphics.Color.rgb(22, 163, 74));
                            }
                        });
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        isMqttConnected = false;
                        Log.w(TAG, "HiveMQ connection lost", cause);
                        handler.post(() -> {
                            refreshMqttActions();
                            if (txtSettingsMqttStatus != null) {
                                txtSettingsMqttStatus.setText("⚠ Trạng thái: Mất kết nối HiveMQ • Đang thử lại...");
                                txtSettingsMqttStatus.setTextColor(android.graphics.Color.rgb(220, 38, 38));
                            }
                            txtAutomationConnection.setText(
                                    "Mất kết nối MQTT; không thể tải hoặc lưu cấu hình tự động hóa.");
                            txtAutomationConnection.setText("Mất kết nối MQTT; không thể tải hoặc lưu cấu hình tự động hóa.");
                        });
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                        if (topic.equals(config.getTopicWifiScanResult())) {
                            handleWifiScanResult(payload);
                        } else if (topic.equals(config.getTopicAck())) {
                            handleAckMessage(payload);
                        } else if (topic.equals(config.getTopicStatus())) {
                            handler.post(() -> renderAutomationStatus(payload));
                        }
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                    }
                });
            }

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(12);
            options.setKeepAliveInterval(30);

            if (username != null && !username.trim().isEmpty()) {
                options.setUserName(username.trim());
            }
            if (password != null && !password.isEmpty()) {
                options.setPassword(password.toCharArray());
            }

            mqttClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    isMqttConnected = true;
                    subscribeScanResultTopic();
                    handler.post(() -> {
                        refreshMqttActions();
                        if (txtSettingsMqttStatus != null) {
                            txtSettingsMqttStatus.setText("✓ Trạng thái: Đã kết nối HiveMQ Cloud • Sẵn sàng");
                            txtSettingsMqttStatus.setTextColor(android.graphics.Color.rgb(22, 163, 74));
                        }
                    });
                }

                @Override
                public void onFailure(IMqttToken token, Throwable exception) {
                    isMqttConnected = false;
                    Log.e(TAG, "Lỗi kết nối HiveMQ", exception);
                    handler.post(() -> {
                        refreshMqttActions();
                        if (txtSettingsMqttStatus != null) {
                            txtSettingsMqttStatus.setText("⚠ Trạng thái: Chưa kết nối được Broker (" + (exception != null ? exception.getMessage() : "") + ")");
                            txtSettingsMqttStatus.setTextColor(android.graphics.Color.rgb(220, 38, 38));
                        }
                    });
                }
            });

        } catch (MqttException e) {
            Log.e(TAG, "Lỗi khởi tạo MQTT client", e);
        }
    }

    private void subscribeScanResultTopic() {
        if (mqttClient == null || !mqttClient.isConnected()) return;
        try {
            mqttClient.subscribe(config.getTopicWifiScanResult(), 1);
            mqttClient.subscribe(config.getTopicAck(), 1);
            mqttClient.subscribe(config.getTopicStatus(), 1);
            Log.i(TAG, "Đã subscribe topics: " + config.getTopicWifiScanResult()
                    + ", " + config.getTopicAck() + " & " + config.getTopicStatus());
        } catch (MqttException e) {
            Log.e(TAG, "Lỗi subscribe settings topics", e);
        }
    }

    // ==================== XỬ LÝ LỆNH QUÉT WIFI ====================

    private void triggerWifiScan() {
        txtWifiScanStatus.setText("⏳ Đang gửi lệnh quét WiFi tới ESP32 qua topic: " + config.getTopicCmdWifiScan() + "...");

        String payload = config.buildWifiScanCommandJson().toString();
        publishToMqtt(
                config.getTopicCmdWifiScan(),
                payload,
                false, // Không retain lệnh quét
                () -> {
                    txtWifiScanStatus.setText("📡 Đã gửi lệnh quét. ESP32 đang quét WiFi xung quanh...\nĐang lắng nghe topic: " + config.getTopicWifiScanResult());
                    showSettingsFeedback("Đã gửi yêu cầu quét WiFi tới ESP32.", false);

                    // Timeout gợi ý sau 8s nếu chưa có ESP32 thật
                    handler.postDelayed(() -> {
                        if (scannedWifiList.size() <= 1 && !isFinishing()) {
                            txtWifiScanStatus.setText("💡 Nếu chưa có ESP32 thật để quét, bạn có thể bấm 'Mạng ẩn (Nhập tay)' hoặc gửi JSON mẫu lên topic '" + config.getTopicWifiScanResult() + "': [\"WiFi_Nha\",\"WiFi_SanVuon\"]");
                        }
                    }, 8000L);
                },
                error -> txtWifiScanStatus.setText("❌ Không gửi được lệnh quét: " + error)
        );
    }

    /**
     * Nhận kết quả danh sách WiFi từ ESP32 qua topic smartpool/wifi/scan_result
     */
    private void handleWifiScanResult(String payload) {
        handler.post(() -> {
            try {
                List<String> foundSsids = new ArrayList<>();

                // Hỗ trợ cả định dạng mảng JSON ["SSID1", "SSID2"] hoặc đối tượng {"networks": [...]}
                if (payload.trim().startsWith("[")) {
                    JSONArray arr = new JSONArray(payload);
                    for (int i = 0; i < arr.length(); i++) {
                        Object item = arr.get(i);
                        if (item instanceof JSONObject) {
                            JSONObject obj = (JSONObject) item;
                            String ssid = obj.optString("ssid", "");
                            int rssi = obj.optInt("rssi", 0);
                            if (!ssid.isEmpty()) {
                                foundSsids.add(ssid + (rssi != 0 ? " (" + rssi + " dBm)" : ""));
                            }
                        } else {
                            foundSsids.add(item.toString());
                        }
                    }
                } else if (payload.trim().startsWith("{")) {
                    JSONObject obj = new JSONObject(payload);
                    JSONArray arr = obj.optJSONArray("networks");
                    if (arr == null) arr = obj.optJSONArray("ssids");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            Object item = arr.get(i);
                            if (item instanceof JSONObject) {
                                JSONObject net = (JSONObject) item;
                                String ssid = net.optString("ssid", "");
                                int rssi = net.optInt("rssi", 0);
                                if (!ssid.isEmpty()) {
                                    foundSsids.add(ssid + (rssi != 0 ? " (" + rssi + " dBm)" : ""));
                                }
                            } else {
                                foundSsids.add(item.toString());
                            }
                        }
                    }
                }

                if (!foundSsids.isEmpty()) {
                    scannedWifiList.clear();
                    scannedWifiList.add("-- Đã tìm thấy " + foundSsids.size() + " mạng (Bấm để chọn) --");
                    scannedWifiList.addAll(foundSsids);
                    wifiSpinnerAdapter.notifyDataSetChanged();

                    layoutWifiSelect.setVisibility(View.VISIBLE);
                    layoutWifiManual.setVisibility(View.VISIBLE);
                    txtWifiScanStatus.setText("✓ Đã nhận " + foundSsids.size() + " mạng WiFi từ ESP32. Chọn từ danh sách bên dưới:");
                    showSettingsFeedback("Đã cập nhật danh sách WiFi từ ESP32.", false);
                } else {
                    txtWifiScanStatus.setText("ESP32 không tìm thấy mạng WiFi nào xung quanh.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Lỗi parse kết quả WiFi scan", e);
                txtWifiScanStatus.setText("Nhận được phản hồi nhưng không đọc được danh sách WiFi: " + payload);
            }
        });
    }

    // ==================== LƯU & PUBLISH TỪNG TOPIC ====================

    /**
     * 1. Lưu và gửi Ngưỡng cảnh báo lên topic: smartpool/config/thresholds
     */
    private void saveAndPublishThresholds() {
        if (!commitThresholdValues()) return;

        String reqId = "req_thresh_" + (System.currentTimeMillis() / 1000L);
        String topic = config.getTopicConfigThresholds();
        String payload = config.buildThresholdsJson(reqId).toString();

        publishWithAckProgress(
                "Đang lưu ngưỡng cảnh báo",
                topic,
                payload,
                reqId,
                "Cấu hình ngưỡng an toàn"
        );
    }

    private boolean commitThresholdValues() {
        String tempMinStr = edtTempMin.getText().toString().trim();
        String tempMaxStr = edtTempMax.getText().toString().trim();
        String waterMinStr = edtWaterMin.getText().toString().trim();
        String waterMaxStr = edtWaterMax.getText().toString().trim();

        float tempMin = AppConfig.DEFAULT_TEMP_MIN;
        float tempMax = AppConfig.DEFAULT_TEMP_MAX;
        try {
            if (!tempMinStr.isEmpty()) tempMin = Float.parseFloat(tempMinStr);
            if (!tempMaxStr.isEmpty()) tempMax = Float.parseFloat(tempMaxStr);
        } catch (NumberFormatException e) {
            showSettingsFeedback("Ngưỡng nhiệt độ phải là số hợp lệ.", true);
            return false;
        }

        if (tempMin >= tempMax) {
            edtTempMin.setError("Nhiệt độ tối thiểu phải nhỏ hơn tối đa");
            return false;
        }

        if (!config.hasWaterLevelCalibration()) {
            showSettingsFeedback("Hãy lưu khoảng mực nước đo được trước khi đặt ngưỡng.", true);
            return false;
        }
        float waterMin;
        float waterMax;
        try {
            waterMin = Float.parseFloat(waterMinStr);
            waterMax = Float.parseFloat(waterMaxStr);
        } catch (NumberFormatException e) {
            showSettingsFeedback("Nhập đủ hai ngưỡng mực nước theo cm.", true);
            return false;
        }

        float cmStart = Math.min(config.getWaterCalibrationCmStart(),
                config.getWaterCalibrationCmEnd());
        float cmEnd = Math.max(config.getWaterCalibrationCmStart(),
                config.getWaterCalibrationCmEnd());
        if (!Float.isFinite(waterMin) || !Float.isFinite(waterMax)
                || waterMin < cmStart || waterMax > cmEnd || waterMin >= waterMax) {
            edtWaterMin.setError("Ngưỡng thấp phải nhỏ hơn ngưỡng cao.");
            showSettingsFeedback(String.format(Locale.getDefault(),
                    "Hai ngưỡng phải nằm trong khoảng đo %.2f–%.2f cm và Min phải nhỏ hơn Max.",
                    cmStart, cmEnd), true);
            return false;
        }

        int rawMin = Math.round(config.waterCmToAdc(waterMin));
        int rawMax = Math.round(config.waterCmToAdc(waterMax));
        if (rawMin >= rawMax) {
            edtWaterMin.setError("Hai ngưỡng phải quy đổi thành hai giá trị ADC khác nhau.");
            return false;
        }

        // Lưu vào SharedPreferences
        config.setTempMin(tempMin);
        config.setTempMax(tempMax);
        config.setWaterThresholdsFromCm(waterMin, waterMax);
        config.setInterlockEnabled(switchSettingsInterlock.isChecked());
        return true;
    }

    private void saveWaterCalibration() {
        int adcStart = config.hasWaterLevelCalibration()
                ? config.getWaterCalibrationAdcStart() : 0;
        int adcEnd = config.hasWaterLevelCalibration()
                ? config.getWaterCalibrationAdcEnd() : 4095;
        final float cmStart;
        final float cmEnd;
        try {
            cmStart = Float.parseFloat(edtCalibrationCmStart.getText().toString().trim());
            cmEnd = Float.parseFloat(edtCalibrationCmEnd.getText().toString().trim());
        } catch (NumberFormatException e) {
            showSettingsFeedback("Nhập mực nước thấp nhất và cao nhất theo cm.", true);
            return;
        }

        if (!Float.isFinite(cmStart) || !Float.isFinite(cmEnd) || cmEnd <= cmStart) {
            edtCalibrationCmEnd.setError("Mực nước cao nhất phải lớn hơn thấp nhất.");
            return;
        }

        config.setWaterLevelCalibration(adcStart, cmStart, adcEnd, cmEnd);
        config.setWaterThresholdsFromCm(Math.min(cmStart, cmEnd), Math.max(cmStart, cmEnd));
        txtWaterCalibrationInfo.setText(String.format(Locale.getDefault(),
                "Khoảng đo: %.2f–%.2f cm. Ánh xạ cảm biến được dùng nội bộ.",
                Math.min(cmStart, cmEnd), Math.max(cmStart, cmEnd)));
        edtWaterMin.setText(formatThreshold(Math.min(cmStart, cmEnd)));
        edtWaterMax.setText(formatThreshold(Math.max(cmStart, cmEnd)));
        updateWaterThresholdUnitLabels();
        updateAutomationWaterUnitUi();
        showSettingsFeedback("Đã lưu hiệu chuẩn.", false);
    }

    private void updateWaterThresholdUnitLabels() {
        txtWaterMinLabel.setText("Ngưỡng thấp (cm)");
        txtWaterMaxLabel.setText("Ngưỡng cao (cm)");
        if (config.hasWaterLevelCalibration()) {
            txtWaterThresholdUnitInfo.setText(String.format(Locale.getDefault(),
                    "Khoảng đo được: %.2f–%.2f cm.",
                    Math.min(config.getWaterCalibrationCmStart(), config.getWaterCalibrationCmEnd()),
                    Math.max(config.getWaterCalibrationCmStart(), config.getWaterCalibrationCmEnd())));
        } else {
            txtWaterThresholdUnitInfo.setText("Cần lưu hiệu chuẩn khoảng đo trước khi đặt ngưỡng.");
        }
    }

    private static String formatThreshold(float value) {
        return Float.isFinite(value) ? String.format(Locale.getDefault(), "%.2f", value) : "";
    }

    /**
     * 2. Lưu và gửi Cấu hình WiFi lên topic: smartpool/config/wifi (Mechanism 2)
     */
    private void saveAndPublishWifi() {
        String ssid = edtWifiSsid.getText().toString().trim();
        String pass = edtWifiPass.getText().toString();

        if (ssid.isEmpty()) {
            edtWifiSsid.setError("Vui lòng nhập hoặc chọn tên mạng WiFi");
            return;
        }

        // Lưu vào SharedPreferences
        config.setWifiSsid(ssid);
        config.setWifiPassword(pass);

        // Gửi lên topic riêng của WiFi kèm req_id (Mechanism 2)
        String reqId = "req_wifi_" + (System.currentTimeMillis() / 1000L);
        String topic = config.getTopicConfigWifi();
        String payload = config.buildWifiJson(reqId).toString();

        publishWithAckProgress(
                "Đang gửi cấu hình WiFi",
                topic,
                payload,
                reqId,
                "Cấu hình WiFi cho ESP32"
        );
    }

    /**
     * 3. Lưu và gửi Thông tin thiết bị lên topic: smartpool/config/device
     */
    private void saveAndPublishDevice() {
        String id = edtDeviceId.getText().toString().trim();
        String name = edtDeviceName.getText().toString().trim();
        String location = edtDeviceLocation.getText().toString().trim();

        if (id.isEmpty()) {
            edtDeviceId.setError("Vui lòng nhập Device ID");
            return;
        }

        config.setDeviceId(id);
        config.setDeviceName(name);
        config.setDeviceLocation(location);

        String reqId = "req_dev_" + (System.currentTimeMillis() / 1000L);
        String topic = config.getTopicConfigDevice();
        String payload = config.buildDeviceJson(reqId).toString();

        publishWithAckProgress(
                "Đang gửi thông tin thiết bị",
                topic,
                payload,
                reqId,
                "Thông tin thiết bị"
        );
    }

    /**
     * 4. Lưu Cấu hình MQTT Broker & Tài khoản (người dùng tự tùy chỉnh)
     */
    private void saveMqttBrokerAndCredentials() {
        String broker = edtMqttBroker.getText().toString().trim();
        String user = edtMqttUser.getText().toString().trim();
        String pass = edtMqttPass.getText().toString();

        if (broker.isEmpty()) {
            edtMqttBroker.setError("Vui lòng nhập Broker URI (ssl://... hoặc tcp://...)");
            return;
        }

        config.setMqttBroker(broker);
        config.setMqttUsername(user);
        config.setMqttPassword(pass);

        showSettingsFeedback("Đã lưu Broker và tài khoản MQTT. Đang kết nối lại...", false);

        if (mqttClient != null) {
            try {
                if (mqttClient.isConnected()) mqttClient.disconnect();
                mqttClient.close();
            } catch (Exception ignored) {
            }
            mqttClient = null;
        }

        txtSettingsMqttStatus.setText("Trạng thái: Đang kết nối lại Broker...");
        txtSettingsMqttStatus.setTextColor(android.graphics.Color.rgb(217, 119, 6));

        refreshMqttActions();
        connectHiveMq();
    }

    private void saveAndPublishHttp() {
        String httpUrl = edtHttpUrl.getText().toString().trim();
        try {
            URI uri = new URI(httpUrl);
            if ((!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                edtHttpUrl.setError("Nhập URL HTTP hoặc HTTPS hợp lệ");
                return;
            }
        } catch (URISyntaxException error) {
            edtHttpUrl.setError("URL HTTP không hợp lệ");
            return;
        }

        int interval;
        try {
            interval = Integer.parseInt(edtHttpPoll.getText().toString().trim());
            if (interval <= 0) {
                edtHttpPoll.setError("Chu kỳ phải lớn hơn 0 giây");
                return;
            }
        } catch (NumberFormatException error) {
            edtHttpPoll.setError("Nhập chu kỳ bằng số nguyên dương");
            return;
        }

        config.setHttpApiUrl(httpUrl);
        config.setHttpPollIntervalSec(interval);
        config.setControlMode("mqtt");

        if (mqttClient == null || !mqttClient.isConnected()) {
            showSettingsFeedback("Đã lưu HTTP trong app. Kết nối MQTT để đồng bộ URL với ESP32.", false);
            return;
        }

        String reqId = "req_http_" + UUID.randomUUID();
        publishWithAckProgress(
                "Đang gửi cấu hình HTTP",
                config.getTopicConfigHttp(),
                config.buildHttpJson(reqId).toString(),
                reqId,
                "Cấu hình HTTP"
        );
    }

    /**
     * 5. Gửi đồng bộ toàn bộ cấu hình (ALL) lên topic tổng: smartpool/config
     */
    private void saveAndPublishAll() {
        if (!commitThresholdValues()) return;
        commitGeneralValues();

        // Đồng thời lưu broker, username, password nếu có thay đổi
        String broker = edtMqttBroker.getText().toString().trim();
        if (!broker.isEmpty()) {
            config.setMqttBroker(broker);
            config.setMqttUsername(edtMqttUser.getText().toString().trim());
            config.setMqttPassword(edtMqttPass.getText().toString());
        }

        String reqId = "req_all_" + (System.currentTimeMillis() / 1000L);
        String topic = config.getTopicConfig();
        String payload = config.toJsonString(true, reqId);

        publishWithAckProgress(
                "Đang gửi toàn bộ cấu hình",
                topic,
                payload,
                reqId,
                "Toàn bộ cấu hình hệ thống"
        );
    }

    private void commitGeneralValues() {
        // Cập nhật giá trị HTTP
        String httpUrl = edtHttpUrl.getText().toString().trim();
        if (!httpUrl.isEmpty()) config.setHttpApiUrl(httpUrl);

        try {
            int interval = Integer.parseInt(edtHttpPoll.getText().toString().trim());
            if (interval > 0) config.setHttpPollIntervalSec(interval);
        } catch (Exception ignored) {
        }

        config.setControlMode("mqtt");
    }

    // ==================== DOUBLE CHECK MECHANISM 2: ACK & REQUEST ID ====================

    private void handleAckMessage(String payload) {
        handler.post(() -> {
            try {
                JSONObject json = new JSONObject(payload);
                String reqId = json.optString("req_id", "");
                String action = json.optString("action", "");
                String status = json.optString("status", "SUCCESS");
                String message = json.optString("message", "Đã lưu vào bộ nhớ Flash ESP32");

                if (pendingAckReqId != null && pendingAckReqId.equals(reqId)) {
                    int completedAutomationChannel = pendingAutomationChannel;
                    if ("UPDATE_AUTOMATION".equals(action)) {
                        pendingAutomationChannel = -1;
                        if ("SUCCESS".equalsIgnoreCase(status) || "OK".equalsIgnoreCase(status)) {
                            for (AutomationRuleViews rule : automationRuleViews) {
                                if (rule.channel == completedAutomationChannel) {
                                    rule.dirtyValue = false;
                                    rule.dirty.setText("");
                                }
                            }
                            if (lastAutomationStatus != null) {
                                renderAutomationStatus(lastAutomationStatus.toString());
                            }
                        }
                        refreshAutomationControls();
                    }
                    handler.removeCallbacks(ackTimeoutRunnable);
                    pendingAckReqId = null;

                    if ("SUCCESS".equalsIgnoreCase(status) || "OK".equalsIgnoreCase(status)) {
                        showSettingsFeedback("ESP32 đã lưu cấu hình. " + message, false);
                    } else {
                        showSettingsFeedback("ESP32 từ chối cấu hình: " + message, true);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi parse ACK message", e);
            }
        });
    }

    private void handleAckTimeout() {
        pendingAckReqId = null;
        if (pendingAutomationChannel > 0) {
            pendingAutomationChannel = -1;
            refreshAutomationControls();
        }

        showSettingsFeedback("Quá thời gian chờ xác nhận từ ESP32. Hãy kiểm tra thiết bị trước khi gửi lại.", true);
    }

    private void publishWithAckProgress(String title, String topic, String payload, String reqId, String successTitle) {
        publishWithAckProgress(title, topic, payload, reqId, successTitle, true);
    }

    private void publishWithAckProgress(String title, String topic, String payload, String reqId,
                                        String successTitle, boolean retained) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            showSettingsFeedback("Chưa kết nối MQTT. Hãy kết nối rồi gửi lại.", true);
            return;
        }
        pendingAckReqId = reqId;
        showSettingsFeedback(title + ": đang gửi và chờ ESP32 xác nhận...", false);

        handler.removeCallbacks(ackTimeoutRunnable);
        handler.postDelayed(ackTimeoutRunnable, ACK_TIMEOUT_MS);

        publishToMqtt(
                topic,
                payload,
                retained,
                () -> {
                    Log.i(TAG, "Đã gửi lên MQTT Cloud với req_id=" + reqId + ", đang chờ ACK...");
                },
                error -> {
                    handler.removeCallbacks(ackTimeoutRunnable);
                    pendingAckReqId = null;
                    if (pendingAutomationChannel > 0) {
                        pendingAutomationChannel = -1;
                        refreshAutomationControls();
                    }
                    showSettingsFeedback("Gửi cấu hình thất bại: " + error, true);
                }
        );
    }

    private void publishToMqtt(String topic, String payload, boolean retained, Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
        new Thread(() -> {
            try {
                MqttAsyncClient connectedClient = mqttClient;
                if (connectedClient == null || !connectedClient.isConnected()) {
                    handler.post(() -> onFailure.accept("MQTT chưa kết nối. Hãy kết nối rồi gửi lại."));
                    return;
                }

                MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                msg.setQos(1);
                msg.setRetained(retained);

                connectedClient.publish(topic, msg).waitForCompletion(5000L);

                handler.post(onSuccess);

            } catch (Exception e) {
                Log.e(TAG, "Lỗi publish MQTT", e);
                handler.post(() -> onFailure.accept(e.getMessage()));
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(resetConfirmationExpiry);
        if (accountRequestQueue != null) accountRequestQueue.cancelAll(this);
        if (mqttClient != null) {
            try {
                if (mqttClient.isConnected()) mqttClient.disconnect();
                mqttClient.close();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }
}
