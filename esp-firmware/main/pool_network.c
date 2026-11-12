#include "pool_network.h"
#include "pool_mqtt_protocol.h"
#include "cJSON.h"
#include <stdlib.h>

#include <stdio.h>
#include <string.h>
#include <stdatomic.h>
#include <inttypes.h>
#include <stdint.h>
#include <time.h>
#include <sys/time.h>

#include "esp_crt_bundle.h"
#include "esp_event.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_netif_sntp.h"
#include "esp_random.h"
#include "esp_wifi.h"
#include "esp_wifi_default.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "freertos/queue.h"
#include "mqtt_client.h"
#include "pool_features.h"
#if POOL_MQTT_ENABLED
#include "mqtt_credentials.h"
#else
#define SMARTPOOL_MQTT_USERNAME ""
#define SMARTPOOL_MQTT_PASSWORD ""
#endif
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_timer.h"
#include "relay.h"
#include "sdkconfig.h"
#include "telemetry_queue.h"
#include "wifi_setup.h"

#define TOPIC_CMD_IN   "smartpool/cmd/bom_vao"
#define TOPIC_CMD_OUT  "smartpool/cmd/bom_ra"
#define TOPIC_CMD_TEMPERATURE "smartpool/cmd/temperature_enabled"
#define TOPIC_CMD_WATER "smartpool/cmd/water_level_enabled"
#define TOPIC_STATUS   "smartpool/status"
#define TOPIC_TELEMETRY "smartpool/telemetry"

static const char *TAG = "pool_network";
static esp_mqtt_client_handle_t s_mqtt;
static _Atomic bool s_mqtt_started;
static bool s_sntp_ready;
static _Atomic bool s_sntp_start_requested;
typedef enum {
    MQTT_PHASE_CONNECTING,
    MQTT_PHASE_CONNECTED,
    MQTT_PHASE_DISCONNECTED,
    MQTT_PHASE_ERROR,
} mqtt_phase_t;
static _Atomic mqtt_phase_t s_mqtt_phase;
static _Atomic unsigned int s_http_queued_count;
// Protected by s_relay_lock; no HTTP operations run while holding this mutex.
static struct {
    bool sending;
    bool attempted;
    int status_code;
    esp_err_t error;
    int64_t last_attempt_us;
    int64_t last_success_us;
} s_http_status;
static _Atomic bool s_has_wifi_credentials;
static _Atomic unsigned int s_wifi_failures;
static _Atomic bool s_wifi_connected;
static _Atomic bool s_station_enabled;
static _Atomic bool s_connect_requested;
static bool s_local_pump_in;
static bool s_local_pump_out;
static nvs_handle_t s_network_nvs;
static pool_device_state_t s_readings;
static int64_t s_last_sample_us;
static _Atomic bool s_mqtt_connected;
static _Atomic bool s_pump_in = POOL_RELAY_INITIAL_1_ON;
static _Atomic bool s_pump_out = POOL_RELAY_INITIAL_2_ON;
static _Atomic bool s_temperature_enabled = POOL_TEMPERATURE_ENABLED;
static _Atomic bool s_water_enabled = POOL_WATER_ENABLED;
typedef struct { telemetry_sample_t sample; bool measurement; } network_sample_t;
static QueueHandle_t s_pending_samples;
static QueueHandle_t s_mqtt_commands, s_mqtt_published;
static pool_mqtt_rx_t s_mqtt_rx;
static pool_mqtt_settings_t s_mqtt_settings = {
    .temp_min = 20, .temp_max = 35, .water_min = CONFIG_WATER_LOW_THRESHOLD, .water_max = 4095,
    .id = "SmartPool", .name = "SmartPool", .api_url = CONFIG_POOL_API_URL,
};
typedef struct { uint32_t version; bool wifi_pending; pool_mqtt_settings_t settings; } stored_mqtt_settings_t;
_Static_assert(sizeof(pool_mqtt_settings_t) == 744 && offsetof(pool_mqtt_settings_t, timestamps) == 712,
               "Preserve v1 settings blob layout for migration");
static bool s_mqtt_wifi_pending;
static void mqtt_command_task(void *arg);
static esp_err_t persist_mqtt_settings(const pool_mqtt_settings_t *settings, bool wifi_pending);
static void mqtt_receive(esp_mqtt_event_handle_t event);
static void send_sample(bool has_temperature, float temperature, bool has_water,
                         bool water_normal, int water_raw, bool measurement);
static void telemetry_task(void *arg);
static void automation_task(void *arg);
static _Atomic bool s_sample_requested;
static pump_auto_config_t s_auto_config[2] = {
    {.sensor = PUMP_SENSOR_WATER, .start_below = true, .start_threshold = 1500,
     .stop_mode = PUMP_STOP_THRESHOLD, .stop_threshold = 2500, .duration_seconds = 300},
    {.sensor = PUMP_SENSOR_WATER, .start_below = false, .start_threshold = 3000,
     .stop_mode = PUMP_STOP_THRESHOLD, .stop_threshold = 2000, .duration_seconds = 300},
};
static pump_auto_runtime_t s_auto_runtime[2];
static int64_t s_auto_wait_after_us[2];
typedef struct {
    uint32_t version;
    pump_auto_config_t rules[2];
} stored_auto_rules_t;

static bool automation_available(int channel, const pump_auto_config_t *config)
{
    return (channel == 0 ? POOL_RELAY_1_ENABLED : POOL_RELAY_2_ENABLED) &&
        (config->sensor == PUMP_SENSOR_WATER ? s_water_enabled : s_temperature_enabled);
}

static uint32_t s_boot_nonce;
static uint32_t s_sample_counter;
static SemaphoreHandle_t s_relay_lock;

static void publish_status(void)
{
    if (!s_mqtt_connected) {
        return;
    }
    cJSON *status = cJSON_CreateObject();
    if (!status) return;
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    cJSON_AddNumberToObject(status, "online", 1);
    cJSON_AddNumberToObject(status, "bom_vao_control", s_pump_in ? 1 : 0);
    cJSON_AddNumberToObject(status, "bom_ra_control", s_pump_out ? 1 : 0);
    cJSON_AddNumberToObject(status, "temperature_enabled", s_temperature_enabled ? 1 : 0);
    cJSON_AddNumberToObject(status, "water_level_enabled", s_water_enabled ? 1 : 0);
    cJSON_AddStringToObject(status, "water_unit", "adc");
    cJSON_AddNumberToObject(status, "interlock", s_mqtt_settings.interlock ? 1 : 0);
    cJSON *device = cJSON_AddObjectToObject(status, "device");
    if (device) {
        cJSON_AddStringToObject(device, "id", s_mqtt_settings.id);
        cJSON_AddStringToObject(device, "name", s_mqtt_settings.name);
        cJSON_AddStringToObject(device, "location", s_mqtt_settings.location);
    }
    cJSON *thresholds = cJSON_AddObjectToObject(status, "thresholds");
    if (thresholds) {
        cJSON_AddNumberToObject(thresholds, "temp_min", s_mqtt_settings.temp_min);
        cJSON_AddNumberToObject(thresholds, "temp_max", s_mqtt_settings.temp_max);
        cJSON_AddNumberToObject(thresholds, "water_min", s_mqtt_settings.water_min);
        cJSON_AddNumberToObject(thresholds, "water_max", s_mqtt_settings.water_max);
    }
    xSemaphoreGive(s_relay_lock);
    char *json = cJSON_PrintUnformatted(status);
    if (json) esp_mqtt_client_enqueue(s_mqtt, TOPIC_STATUS, json, 0, 1, 1, true);
    free(json);
    cJSON_Delete(status);
}

static esp_err_t set_pumps(bool pump_in, bool pump_out, bool local, bool remote_stop_only, int channel)
{
    if (xSemaphoreTake(s_relay_lock, portMAX_DELAY) != pdTRUE) return ESP_FAIL;
    if (remote_stop_only) {
        pump_in = s_local_pump_in && s_pump_in;
        pump_out = s_local_pump_out && s_pump_out;
    } else {
        if (channel == 1) pump_out = s_pump_out;
        if (channel == 2) pump_in = s_pump_in;
    }
    if (s_mqtt_settings.interlock && pump_in && pump_out) {
        xSemaphoreGive(s_relay_lock);
        return ESP_ERR_INVALID_STATE;
    }
    if ((pump_in && !POOL_RELAY_1_ENABLED) || (pump_out && !POOL_RELAY_2_ENABLED)) {
        xSemaphoreGive(s_relay_lock);
        return ESP_ERR_NOT_SUPPORTED;
    }
    if (!local && !remote_stop_only && (pump_in || pump_out) && (!s_wifi_connected || !s_mqtt_connected || !s_station_enabled)) {
        xSemaphoreGive(s_relay_lock);
        return ESP_ERR_INVALID_STATE;
    }
    esp_err_t err = ESP_OK;
    if (!pump_in) err = relay_set(RELAY_1, false);
    if (err == ESP_OK && !pump_out) err = relay_set(RELAY_2, false);
    if (err == ESP_OK && pump_in) err = relay_set(RELAY_1, true);
    if (err == ESP_OK && pump_out) err = relay_set(RELAY_2, true);
    if (err == ESP_OK) {
        if (!remote_stop_only) {
            if (channel == 0 || channel == 1) pump_auto_pause(&s_auto_runtime[0]);
            if (channel == 0 || channel == 2) pump_auto_pause(&s_auto_runtime[1]);
        }
        if (!pump_in) s_local_pump_in = false;
        else if (!remote_stop_only && (channel == 0 || channel == 1)) s_local_pump_in = local;
        if (!pump_out) s_local_pump_out = false;
        else if (!remote_stop_only && (channel == 0 || channel == 2)) s_local_pump_out = local;
        s_pump_in = pump_in;
        s_pump_out = pump_out;
    } else {
        relay_set(RELAY_1, false);
        relay_set(RELAY_2, false);
        s_pump_in = false;
        s_pump_out = false;
        s_local_pump_in = false;
        s_local_pump_out = false;
        for (int i = 0; i < 2; i++) {
            pump_auto_pause(&s_auto_runtime[i]);
            s_auto_runtime[i].reason = PUMP_AUTO_RELAY_ERROR;
            s_auto_runtime[i].last_stop = PUMP_AUTO_RELAY_ERROR;
        }
    }
    xSemaphoreGive(s_relay_lock);
    publish_status();
    pool_network_send_telemetry(false, 0, false, false, 0);
    return err;
}

static void mqtt_event_handler(void *arg, esp_event_base_t base, int32_t event_id, void *data)
{
    esp_mqtt_event_handle_t event = data;
    switch ((esp_mqtt_event_id_t)event_id) {
    case MQTT_EVENT_BEFORE_CONNECT:
        s_mqtt_phase = MQTT_PHASE_CONNECTING;
        break;
    case MQTT_EVENT_CONNECTED:
        s_mqtt_phase = MQTT_PHASE_CONNECTED;
        s_mqtt_connected = true;
        if (POOL_RELAY_1_ENABLED) esp_mqtt_client_subscribe(s_mqtt, TOPIC_CMD_IN, 1);
        if (POOL_RELAY_2_ENABLED) esp_mqtt_client_subscribe(s_mqtt, TOPIC_CMD_OUT, 1);
        if (POOL_TEMPERATURE_ENABLED) esp_mqtt_client_subscribe(s_mqtt, TOPIC_CMD_TEMPERATURE, 1);
        if (POOL_WATER_ENABLED) esp_mqtt_client_subscribe(s_mqtt, TOPIC_CMD_WATER, 1);
        const char *configs[] = {"smartpool/config", "smartpool/config/thresholds", "smartpool/config/wifi",
            "smartpool/config/device", "smartpool/config/http", "smartpool/cmd/wifi_scan"};
        for (size_t i = 0; i < sizeof(configs) / sizeof(configs[0]); i++) esp_mqtt_client_subscribe(s_mqtt, configs[i], 1);
        s_sample_requested = true;
        publish_status();
        pool_network_send_telemetry(false, 0, false, false, 0);
        ESP_LOGI(TAG, "MQTT connected");
        break;
    case MQTT_EVENT_DISCONNECTED:
        s_mqtt_connected = false;
        memset(&s_mqtt_rx, 0, sizeof(s_mqtt_rx));
        if (s_mqtt_phase != MQTT_PHASE_ERROR) s_mqtt_phase = MQTT_PHASE_DISCONNECTED;
        set_pumps(false, false, false, true, 0);
        ESP_LOGW(TAG, "MQTT disconnected; remotely controlled pumps OFF");
        break;
    case MQTT_EVENT_ERROR:
        s_mqtt_phase = MQTT_PHASE_ERROR;
        if (event->error_handle) {
            ESP_LOGE(TAG, "MQTT error: type=%d, TLS=%s, broker_code=%d",
                     event->error_handle->error_type,
                     esp_err_to_name(event->error_handle->esp_tls_last_esp_err),
                     event->error_handle->connect_return_code);
        }
        break;
    case MQTT_EVENT_DATA:
        mqtt_receive(event);
        break;
    case MQTT_EVENT_PUBLISHED:
        if (s_mqtt_published) xQueueSend(s_mqtt_published, &event->msg_id, 0);
        break;
    default:
        break;
    }
}

static esp_err_t credentials_saved(void)
{
    s_has_wifi_credentials = true;
    return pool_network_set_station_enabled(true);
}

esp_err_t pool_network_set_station_enabled(bool enabled)
{
    if (enabled && !POOL_WIFI_STA_ENABLED) return ESP_ERR_NOT_SUPPORTED;
    esp_err_t err = wifi_setup_enable_ap();
    if (err != ESP_OK) return err;
    err = nvs_set_u8(s_network_nvs, "sta_enabled", enabled);
    if (err == ESP_OK) err = nvs_commit(s_network_nvs);
    if (err != ESP_OK) return err;
    s_station_enabled = enabled;
    s_connect_requested = enabled && s_has_wifi_credentials;
    s_wifi_connected = false;
    s_mqtt_connected = false;
    s_wifi_failures = 0;
    set_pumps(false, false, true, false, 0);
    err = esp_wifi_disconnect();
    if (err != ESP_OK && err != ESP_ERR_WIFI_NOT_CONNECT) return err;
    // The retry task starts the connection after pending disconnect events.
    return ESP_OK;
}

static bool clock_ready(void)
{
    time_t now = time(NULL);
    return now >= 1704067200LL && now <= 4102444799LL;
}

esp_err_t pool_network_set_time(int64_t unix_time)
{
    if (unix_time < 1704067200LL || unix_time > 4102444799LL) return ESP_ERR_INVALID_ARG;
    struct timeval tv = {.tv_sec = (time_t)unix_time, .tv_usec = 0};
    if (settimeofday(&tv, NULL) != 0) return ESP_FAIL;
    ESP_LOGI(TAG, "Clock set from local browser; MQTT TLS verification remains enabled");
    return ESP_OK;
}

static void time_synchronized(struct timeval *tv)
{
    ESP_LOGI(TAG, "SNTP synchronized: unix=%lld", (long long)tv->tv_sec);
}

static void wifi_retry_task(void *arg)
{
    unsigned int seconds = 0;
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        seconds = (seconds + 1) % 30;
        if (atomic_exchange(&s_sntp_start_requested, false) && s_sntp_ready &&
            s_station_enabled && s_wifi_connected) {
            esp_err_t err = esp_netif_sntp_start();
            if (err != ESP_OK) ESP_LOGW(TAG, "Cannot start SNTP after IP: %s", esp_err_to_name(err));
            else ESP_LOGI(TAG, "SNTP started after IP; primary=%s, backup=%s",
                          CONFIG_POOL_NTP_SERVER, CONFIG_POOL_NTP_BACKUP_SERVER);
        }
        if (atomic_exchange(&s_connect_requested, false) && s_station_enabled &&
            s_has_wifi_credentials && !s_wifi_connected) {
            esp_err_t err = esp_wifi_connect();
            if (err != ESP_OK && err != ESP_ERR_WIFI_CONN) {
                ESP_LOGW(TAG, "Wi-Fi connection attempt: %s", esp_err_to_name(err));
            }
        }
        if (seconds % 5 == 0 && s_station_enabled && s_wifi_connected && s_mqtt && !s_mqtt_started &&
            clock_ready()) {
            esp_err_t err = esp_mqtt_client_start(s_mqtt);
            if (err == ESP_OK) {
                s_mqtt_started = true;
                ESP_LOGI(TAG, "MQTT TLS started after time synchronization");
            } else {
                s_mqtt_phase = MQTT_PHASE_ERROR;
                ESP_LOGE(TAG, "Cannot start MQTT: %s", esp_err_to_name(err));
            }
        }
        if (seconds == 0 && s_station_enabled && s_has_wifi_credentials && !s_wifi_connected &&
            s_wifi_failures >= 3) {
            ESP_LOGI(TAG, "Retrying saved Wi-Fi while setup AP stays available");
            s_connect_requested = true;
        }
        if (seconds == 0 && s_wifi_connected && s_mqtt && !s_mqtt_started &&
            !clock_ready()) {
            ESP_LOGW(TAG, "Clock not synchronized; check DNS/UDP 123 or use the AP web clock-sync button; MQTT TLS is waiting");
        }
    }
}

static void wifi_event_handler(void *arg, esp_event_base_t base, int32_t event_id, void *data)
{
    if (base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        if (s_station_enabled && s_has_wifi_credentials) s_connect_requested = true;
    } else if (base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        s_wifi_connected = false;
        s_mqtt_connected = false;
        // Stop remote-owned pumps; AP commands continue to work during retries.
        set_pumps(false, false, false, true, 0);
        esp_err_t ap_err = wifi_setup_enable_ap();
        if (ap_err != ESP_OK) ESP_LOGE(TAG, "Cannot enable local AP: %s", esp_err_to_name(ap_err));
        if (s_station_enabled && s_has_wifi_credentials && s_wifi_failures < 3) {
            s_wifi_failures++;
            if (s_wifi_failures < 3) s_connect_requested = true;
        }
        ESP_LOGW(TAG, "Router Wi-Fi disconnected; local dashboard available on AP");
    } else if (base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        if (!s_station_enabled) {
            esp_wifi_disconnect();
            return;
        }
        s_wifi_connected = true;
        s_wifi_failures = 0;
        s_sntp_start_requested = true;
        const ip_event_got_ip_t *ip_event = data;
        ESP_LOGI(TAG, "Wi-Fi got IP: " IPSTR, IP2STR(&ip_event->ip_info.ip));
        // Keep the local AP available while the router connection is online.
        if (s_mqtt && !s_mqtt_started) ESP_LOGI(TAG, "Waiting for clock sync before MQTT TLS");
    }
}

static esp_err_t load_auto_rules(void)
{
    stored_auto_rules_t stored;
    size_t size = sizeof(stored);
    esp_err_t err = nvs_get_blob(s_network_nvs, "auto_rules", &stored, &size);
    if (err == ESP_OK && size == sizeof(stored) && stored.version == 1 &&
        pump_auto_config_valid(&stored.rules[0]) && pump_auto_config_valid(&stored.rules[1])) {
        memcpy(s_auto_config, stored.rules, sizeof(s_auto_config));
    } else if (err != ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGW(TAG, "Invalid automation settings; defaults remain disabled");
    }
    for (int i = 0; i < 2; i++) {
        pump_auto_reset(&s_auto_runtime[i]);
        if (!automation_available(i, &s_auto_config[i])) s_auto_config[i].enabled = false;
        bool initial_on = !s_auto_config[i].enabled &&
            (i == 0 ? POOL_RELAY_INITIAL_1_ON : POOL_RELAY_INITIAL_2_ON);
        // A saved interlock takes precedence over the BOTH_ON boot setting.
        if (s_mqtt_settings.interlock && POOL_RELAY_INITIAL_1_ON && POOL_RELAY_INITIAL_2_ON) initial_on = false;
        err = relay_set(i == 0 ? RELAY_1 : RELAY_2, initial_on);
        if (err != ESP_OK) return err;
        if (i == 0) s_pump_in = initial_on;
        else s_pump_out = initial_on;
    }
    return ESP_OK;
}

esp_err_t pool_network_init(void)
{
    s_relay_lock = xSemaphoreCreateMutex();
    if (!s_relay_lock) return ESP_ERR_NO_MEM;
    s_pending_samples = xQueueCreate(16, sizeof(network_sample_t));
    if (!s_pending_samples) return ESP_ERR_NO_MEM;
    esp_err_t err = nvs_flash_init();
    if (err != ESP_OK) return err;
    err = nvs_open("poolnet", NVS_READWRITE, &s_network_nvs);
    if (err != ESP_OK) return err;
    uint8_t station_enabled = 1;
    err = nvs_get_u8(s_network_nvs, "sta_enabled", &station_enabled);
    if (err != ESP_OK && err != ESP_ERR_NVS_NOT_FOUND) return err;
    s_station_enabled = POOL_WIFI_STA_ENABLED && station_enabled;
    stored_mqtt_settings_t saved_mqtt;
    size_t mqtt_size = sizeof(saved_mqtt);
    if (nvs_get_blob(s_network_nvs, "mqtt_settings", &saved_mqtt, &mqtt_size) == ESP_OK &&
        mqtt_size == sizeof(saved_mqtt) && (saved_mqtt.version == 1 || saved_mqtt.version == 2)) {
        // v1 ignored interlock; its old padding must never be read as a boolean.
        if (saved_mqtt.version == 1) {
            saved_mqtt.settings.interlock = false;
            // Reaccept the retained threshold group once: v1 never applied its interlock field.
            saved_mqtt.settings.timestamps[0] = 0;
        }
        s_mqtt_settings = saved_mqtt.settings;
        s_mqtt_wifi_pending = saved_mqtt.wifi_pending;
    }
    err = load_auto_rules();
    if (err != ESP_OK) return err;
    if (POOL_HTTP_ENABLED) {
        err = telemetry_queue_init();
        if (err != ESP_OK) ESP_LOGE(TAG, "Persistent telemetry queue unavailable: %s", esp_err_to_name(err));
        s_http_queued_count = telemetry_queue_count();
    }
    err = esp_netif_init();
    if (err != ESP_OK) return err;
    err = esp_event_loop_create_default();
    if (err != ESP_OK) return err;
    if (!esp_netif_create_default_wifi_sta()) return ESP_FAIL;
    if (!esp_netif_create_default_wifi_ap()) return ESP_FAIL;
    if (POOL_MQTT_ENABLED) {
        esp_sntp_config_t sntp_config = ESP_NETIF_SNTP_DEFAULT_CONFIG(CONFIG_POOL_NTP_SERVER);
        sntp_config.start = false;
        sntp_config.sync_cb = time_synchronized;
#if CONFIG_LWIP_SNTP_MAX_SERVERS >= 2
        sntp_config.num_of_servers = 2;
        sntp_config.servers[1] = CONFIG_POOL_NTP_BACKUP_SERVER;
#endif
#if CONFIG_LWIP_DHCP_GET_NTP_SRV
        sntp_config.server_from_dhcp = true;
        sntp_config.renew_servers_after_new_IP = true;
        sntp_config.index_of_first_server = CONFIG_LWIP_SNTP_MAX_SERVERS >= 3 ? 1 : 0;
#endif
        err = esp_netif_sntp_init(&sntp_config);
        s_sntp_ready = err == ESP_OK;
        if (err != ESP_OK) ESP_LOGW(TAG, "Cannot initialize SNTP: %s; browser time sync remains available", esp_err_to_name(err));
    }

    wifi_init_config_t wifi_init = WIFI_INIT_CONFIG_DEFAULT();
    err = esp_wifi_init(&wifi_init);
    if (err != ESP_OK) return err;
    s_boot_nonce = esp_random();
    err = esp_wifi_set_storage(WIFI_STORAGE_FLASH);
    if (err != ESP_OK) return err;
    err = esp_wifi_set_mode(WIFI_MODE_STA);
    if (err != ESP_OK) return err;

    if (s_mqtt_wifi_pending && POOL_WIFI_STA_ENABLED && s_mqtt_settings.ssid[0]) {
        wifi_config_t pending_wifi = {0};
        memcpy(pending_wifi.sta.ssid, s_mqtt_settings.ssid, strlen(s_mqtt_settings.ssid));
        memcpy(pending_wifi.sta.password, s_mqtt_settings.password, strlen(s_mqtt_settings.password));
        err = esp_wifi_set_config(WIFI_IF_STA, &pending_wifi);
        if (err != ESP_OK) return err;
        err = nvs_set_u8(s_network_nvs, "sta_enabled", 1);
        if (err == ESP_OK) err = nvs_commit(s_network_nvs);
        if (err != ESP_OK) return err;
        s_station_enabled = true;
        s_mqtt_wifi_pending = false;
        err = persist_mqtt_settings(&s_mqtt_settings, false);
        if (err != ESP_OK) return err;
    }
    wifi_config_t saved_wifi = {0};
    err = esp_wifi_get_config(WIFI_IF_STA, &saved_wifi);
    if (err != ESP_OK) return err;
    s_has_wifi_credentials = saved_wifi.sta.ssid[0] != '\0';
    err = wifi_setup_enable_ap();
    if (err != ESP_OK) return err;

    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                                wifi_event_handler, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                                wifi_event_handler, NULL));

    if (POOL_MQTT_ENABLED) {
        s_mqtt_commands = xQueueCreate(6, sizeof(pool_mqtt_rx_t *));
        s_mqtt_published = xQueueCreate(16, sizeof(int));
        if (!s_mqtt_commands || !s_mqtt_published) return ESP_ERR_NO_MEM;
        const esp_mqtt_client_config_t mqtt_config = {
            .broker.address.uri = CONFIG_POOL_MQTT_URI,
            .broker.verification.crt_bundle_attach = esp_crt_bundle_attach,
            .credentials.username = SMARTPOOL_MQTT_USERNAME,
            .credentials.authentication.password = SMARTPOOL_MQTT_PASSWORD,
            .session.last_will.topic = TOPIC_STATUS,
            .session.last_will.msg = "{\"online\":0}",
            .session.last_will.qos = 1,
            .session.last_will.retain = 1,
        };
        if (SMARTPOOL_MQTT_USERNAME[0] == '\0' || SMARTPOOL_MQTT_PASSWORD[0] == '\0') {
            ESP_LOGW(TAG, "HiveMQ credentials missing in mqtt_credentials.h; MQTT disabled");
        } else {
            s_mqtt = esp_mqtt_client_init(&mqtt_config);
            if (!s_mqtt) return ESP_FAIL;
            err = esp_mqtt_client_register_event(s_mqtt, ESP_EVENT_ANY_ID, mqtt_event_handler, NULL);
            if (err != ESP_OK) return err;
        }
    }
    if ((POOL_HTTP_ENABLED || POOL_MQTT_ENABLED) &&
        xTaskCreate(telemetry_task, "telemetry_http", 6144, NULL, 4, NULL) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    if (POOL_MQTT_ENABLED && xTaskCreate(mqtt_command_task, "mqtt_commands", 8192, NULL, 4, NULL) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    if (xTaskCreate(automation_task, "pump_auto", 4096, NULL, 5, NULL) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    err = esp_wifi_start();
    if (err != ESP_OK) return err;
    err = wifi_setup_init(credentials_saved);
    if (err != ESP_OK) return err;
    ESP_LOGI(TAG, "MQTT %s; HTTP storage %s", POOL_MQTT_ENABLED ? "enabled" : "disabled",
             POOL_HTTP_ENABLED ? CONFIG_POOL_API_URL : "disabled");
    if (xTaskCreate(wifi_retry_task, "wifi_retry", 3072, NULL, 4, NULL) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}


static void sample_to_json(const telemetry_sample_t *sample, char *json, size_t capacity,
                           bool include_missing_readings)
{
    char temperature_field[64] = "";
    char water_field[80] = "";
    if (sample->flags & TELEMETRY_HAS_TEMPERATURE) {
        snprintf(temperature_field, sizeof(temperature_field),
                 ",\"nhiet_do\":%.2f", sample->temperature);
    } else if (include_missing_readings) {
        snprintf(temperature_field, sizeof(temperature_field), ",\"nhiet_do\":null");
    }
    if (sample->flags & TELEMETRY_HAS_WATER) {
        snprintf(water_field, sizeof(water_field), ",\"muc_nuoc\":%d,\"muc_nuoc_raw\":%d",
                 !!(sample->flags & TELEMETRY_WATER_NORMAL), sample->water_raw);
    } else if (include_missing_readings) {
        snprintf(water_field, sizeof(water_field), ",\"muc_nuoc\":null,\"muc_nuoc_raw\":null");
    }
    if (!sample->status_valid) {
        // Samples queued by firmware v1 have no historical device state.
        snprintf(json, capacity, "{\"sample_id\":\"%016" PRIX64 "\"%s%s}",
                 sample->id, temperature_field, water_field);
        return;
    }
    snprintf(json, capacity,
             "{\"sample_id\":\"%016" PRIX64 "\"%s%s,\"bom_control\":%d,\"van_control\":%d,"
             "\"temperature_enabled\":%d,\"water_level_enabled\":%d,\"online\":%d}",
             sample->id, temperature_field, water_field,
             !!(sample->flags & TELEMETRY_PUMP_IN), !!(sample->flags & TELEMETRY_PUMP_OUT),
             !!(sample->flags & TELEMETRY_TEMPERATURE_ENABLED),
             !!(sample->flags & TELEMETRY_WATER_ENABLED), !!(sample->flags & TELEMETRY_ONLINE));
}

static void record_http_result(esp_err_t error, int status_code)
{
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    s_http_status.sending = false;
    s_http_status.attempted = true;
    s_http_status.error = error;
    s_http_status.status_code = status_code;
    s_http_status.last_attempt_us = esp_timer_get_time();
    if (error == ESP_OK && status_code == 200) {
        s_http_status.last_success_us = s_http_status.last_attempt_us;
    }
    xSemaphoreGive(s_relay_lock);
}

static bool post_sample(const telemetry_sample_t *sample)
{
    char json[320];
    // The deployed PHP API validates numeric fields and rejects JSON null.
    // Omit unavailable readings; never turn them into a false zero sample.
    sample_to_json(sample, json, sizeof(json), false);
    char api_url[257];
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    memcpy(api_url, s_mqtt_settings.api_url, sizeof(api_url));
    xSemaphoreGive(s_relay_lock);
    esp_http_client_config_t http_config = {
        .url = api_url,
        .timeout_ms = 3000,
        .crt_bundle_attach = esp_crt_bundle_attach,
    };
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    s_http_status.sending = true;
    xSemaphoreGive(s_relay_lock);
    esp_http_client_handle_t http = esp_http_client_init(&http_config);
    if (!http) {
        record_http_result(ESP_ERR_NO_MEM, 0);
        ESP_LOGE(TAG, "Cannot create HTTP client");
        return false;
    }
    esp_http_client_set_method(http, HTTP_METHOD_POST);
    esp_http_client_set_header(http, "Content-Type", "application/json");
    esp_http_client_set_post_field(http, json, strlen(json));
    esp_err_t err = esp_http_client_perform(http);
    int status = esp_http_client_get_status_code(http);
    bool success = err == ESP_OK && status == 200;
    record_http_result(err, status);
    if (success) {
        ESP_LOGI(TAG, "HTTP POST OK: sample=%016" PRIX64 ", status=%d",
                 sample->id, status);
    } else {
        ESP_LOGW(TAG, "HTTP update failed: %s, status=%d, url=%s", esp_err_to_name(err),
                 status, api_url);
    }
    esp_http_client_cleanup(http);
    return success;
}

static void queue_sample(const telemetry_sample_t *sample)
{
    bool dropped_oldest = false;
    esp_err_t err = telemetry_queue_push(sample, &dropped_oldest);
    s_http_queued_count = telemetry_queue_count();
    if (err != ESP_OK) ESP_LOGE(TAG, "Cannot persist telemetry: %s", esp_err_to_name(err));
    if (dropped_oldest) ESP_LOGW(TAG, "Telemetry queue full; oldest sample removed");
}

static void flush_queued_samples(void)
{
    unsigned int delivered = 0;
    for (; delivered < 4 && s_wifi_connected; delivered++) {
        telemetry_sample_t queued;
        if (!telemetry_queue_peek_at(delivered, &queued)) break;
        if (!post_sample(&queued)) break;
    }
    if (delivered > 0) {
        esp_err_t err = telemetry_queue_pop_many(delivered);
        s_http_queued_count = telemetry_queue_count();
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "Cannot update telemetry queue: %s", esp_err_to_name(err));
        }
    }
}

bool pool_network_temperature_enabled(void)
{
    return s_temperature_enabled;
}

bool pool_network_water_enabled(void)
{
    return s_water_enabled;
}

void pool_network_update_readings(bool has_temperature, float temperature,
                                  bool has_water, bool water_normal, int water_raw)
{
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    s_readings.has_temperature = has_temperature && s_temperature_enabled;
    s_readings.temperature = temperature;
    s_readings.has_water = has_water && s_water_enabled;
    s_readings.water_normal = water_normal;
    s_readings.water_raw = water_raw;
    s_last_sample_us = esp_timer_get_time();
    xSemaphoreGive(s_relay_lock);
    send_sample(has_temperature, temperature, has_water, water_normal, water_raw, true);
}

void pool_network_get_state(pool_device_state_t *state)
{
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    *state = s_readings;
    uint64_t auto_now_ms = esp_timer_get_time() / 1000;
    for (int i = 0; i < 2; i++) {
        state->automation[i].config = s_auto_config[i];
        state->automation[i].paused = s_auto_runtime[i].paused;
        state->automation[i].running = s_auto_runtime[i].running;
        state->automation[i].reason = s_auto_config[i].enabled
            ? pump_auto_reason_name(s_auto_runtime[i].reason) : "disabled";
        state->automation[i].last_stop_reason = pump_auto_reason_name(s_auto_runtime[i].last_stop);
        uint64_t elapsed = s_auto_runtime[i].running ? auto_now_ms - s_auto_runtime[i].started_ms : 0;
        uint64_t limit = (uint64_t)s_auto_config[i].duration_seconds * 1000;
        state->automation[i].remaining_seconds = s_auto_runtime[i].running && elapsed < limit
            ? (uint32_t)((limit - elapsed + 999) / 1000) : 0;
    }
    state->wifi_connected = s_wifi_connected;
    state->station_enabled = s_station_enabled;
    state->mqtt_connected = s_mqtt_connected;
    state->clock_ready = clock_ready();
    if (!POOL_MQTT_ENABLED) state->mqtt_state = "disabled";
    else if (!s_mqtt) state->mqtt_state = "missing_credentials";
    else if (!state->station_enabled || !state->wifi_connected) state->mqtt_state = "waiting_wifi";
    else if (state->mqtt_connected) state->mqtt_state = "connected";
    else if (!s_mqtt_started && !state->clock_ready) state->mqtt_state = "waiting_time";
    else if (s_mqtt_phase == MQTT_PHASE_ERROR) state->mqtt_state = "error";
    else if (s_mqtt_phase == MQTT_PHASE_DISCONNECTED) state->mqtt_state = "disconnected";
    else state->mqtt_state = "connecting";

    if (!POOL_HTTP_ENABLED) state->http_state = "disabled";
    else if (!state->station_enabled || !state->wifi_connected) state->http_state = "waiting_wifi";
    else if (s_http_status.sending) state->http_state = "sending";
    else if (!s_http_status.attempted) state->http_state = "pending";
    else if (s_http_status.error == ESP_OK && s_http_status.status_code == 200) state->http_state = "success";
    else state->http_state = "error";
    state->http_status_code = s_http_status.status_code;
    state->http_error = s_http_status.error == ESP_OK ? "" : esp_err_to_name(s_http_status.error);
    int64_t now_us = esp_timer_get_time();
    state->http_last_attempt_age_seconds = s_http_status.attempted
        ? (int)((now_us - s_http_status.last_attempt_us) / 1000000) : -1;
    state->http_last_success_age_seconds = s_http_status.last_success_us
        ? (int)((now_us - s_http_status.last_success_us) / 1000000) : -1;
    state->http_queued_samples = POOL_HTTP_ENABLED ? s_http_queued_count +
        (s_pending_samples ? uxQueueMessagesWaiting(s_pending_samples) : 0) : 0;
    state->interlock = s_mqtt_settings.interlock;
    state->relay_1_on = s_pump_in;
    state->relay_2_on = s_pump_out;
    state->temperature_enabled = s_temperature_enabled;
    state->water_enabled = s_water_enabled;
    state->sample_age_seconds = s_last_sample_us
        ? (int)((esp_timer_get_time() - s_last_sample_us) / 1000000) : -1;
    xSemaphoreGive(s_relay_lock);
}

esp_err_t pool_network_local_control(const char *target, bool on)
{
    if (strcmp(target, "stop") == 0) {
        return on ? ESP_ERR_INVALID_ARG : set_pumps(false, false, true, false, 0);
    }
    if (strcmp(target, "relay1") == 0 || strcmp(target, "relay2") == 0) {
        // Resolve the other channel under the relay mutex to keep controls independent.
        return set_pumps(strcmp(target, "relay1") == 0 ? on : s_pump_in,
                         strcmp(target, "relay2") == 0 ? on : s_pump_out,
                         true, false, strcmp(target, "relay1") == 0 ? 1 : 2);
    }
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    esp_err_t err = ESP_OK;
    if (strcmp(target, "temperature") == 0 && POOL_TEMPERATURE_ENABLED) {
        s_temperature_enabled = on;
        s_readings.has_temperature = false;
    } else if (strcmp(target, "water") == 0 && POOL_WATER_ENABLED) {
        s_water_enabled = on;
        s_readings.has_water = false;
    } else {
        err = ESP_ERR_NOT_SUPPORTED;
    }
    xSemaphoreGive(s_relay_lock);
    if (err == ESP_OK) {
        s_sample_requested = true;
        publish_status();
        pool_network_send_telemetry(false, 0, false, false, 0);
    }
    return err;
}

void pool_network_send_telemetry(bool has_temperature, float temperature,
                                 bool has_water, bool water_normal, int water_raw)
{
    send_sample(has_temperature, temperature, has_water, water_normal, water_raw, false);
}

static void send_sample(bool has_temperature, float temperature, bool has_water,
                         bool water_normal, int water_raw, bool measurement)
{
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    has_temperature = has_temperature && s_temperature_enabled;
    has_water = has_water && s_water_enabled;
    // State-only notifications do not overwrite the last measured readings.
    // The measurement loop updates availability separately, including failures.
    telemetry_sample_t sample = {
        .status_valid = 1,
        .temperature = temperature,
        .water_raw = water_raw,
        .flags = (has_temperature ? TELEMETRY_HAS_TEMPERATURE : 0) |
                 (has_water ? TELEMETRY_HAS_WATER : 0) |
                 (has_water && water_normal ? TELEMETRY_WATER_NORMAL : 0) |
                 (s_pump_in ? TELEMETRY_PUMP_IN : 0) |
                 (s_pump_out ? TELEMETRY_PUMP_OUT : 0) |
                 (s_temperature_enabled ? TELEMETRY_TEMPERATURE_ENABLED : 0) |
                 (s_water_enabled ? TELEMETRY_WATER_ENABLED : 0) |
                 (s_wifi_connected ? TELEMETRY_ONLINE : 0),
    };
    xSemaphoreGive(s_relay_lock);
    if (!POOL_HTTP_ENABLED && !POOL_MQTT_ENABLED) return;
    network_sample_t work = {.sample = sample, .measurement = measurement};
    if (xQueueSend(s_pending_samples, &work, 0) != pdTRUE) {
        ESP_LOGW(TAG, "HTTP worker busy; sample dropped before persistence");
    }
}

bool pool_network_take_sample_request(void)
{
    return atomic_exchange(&s_sample_requested, false);
}

esp_err_t pool_network_configure_pump(int channel, const pump_auto_config_t *config)
{
    if (channel < 0 || channel > 1 || !pump_auto_config_valid(config)) return ESP_ERR_INVALID_ARG;
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    if (config->enabled && !automation_available(channel, config)) {
        xSemaphoreGive(s_relay_lock);
        return ESP_ERR_NOT_SUPPORTED;
    }
    stored_auto_rules_t stored = {.version = 1, .rules = {s_auto_config[0], s_auto_config[1]}};
    stored.rules[channel] = *config;
    esp_err_t err = nvs_set_blob(s_network_nvs, "auto_rules", &stored, sizeof(stored));
    if (err == ESP_OK) err = nvs_commit(s_network_nvs);
    if (err == ESP_OK) {
        bool stop = config->enabled || s_auto_runtime[channel].running;
        s_auto_config[channel] = *config;
        pump_auto_reset(&s_auto_runtime[channel]);
        s_auto_wait_after_us[channel] = s_last_sample_us;
        if (stop) {
            err = relay_set(channel == 0 ? RELAY_1 : RELAY_2, false);
            if (err == ESP_OK) {
                if (channel == 0) { s_pump_in = false; s_local_pump_in = false; }
                else { s_pump_out = false; s_local_pump_out = false; }
            } else {
                pump_auto_pause(&s_auto_runtime[channel]);
                s_auto_runtime[channel].reason = PUMP_AUTO_RELAY_ERROR;
                s_auto_runtime[channel].last_stop = PUMP_AUTO_RELAY_ERROR;
            }
        }
    }
    xSemaphoreGive(s_relay_lock);
    if (err == ESP_OK) {
        s_sample_requested = true;
        publish_status();
        pool_network_send_telemetry(false, 0, false, false, 0);
    }
    return err;
}

static void automation_task(void *arg)
{
    TickType_t wake = xTaskGetTickCount();
    while (1) {
        xTaskDelayUntil(&wake, pdMS_TO_TICKS(250));
        bool changed = false;
        xSemaphoreTake(s_relay_lock, portMAX_DELAY);
        int64_t now_us = esp_timer_get_time();
        uint32_t interval_ms = s_pump_in || s_pump_out
            ? CONFIG_POOL_SENSOR_PUMP_INTERVAL_MS : CONFIG_POOL_SENSOR_IDLE_INTERVAL_MS;
        bool fresh = s_last_sample_us > 0 && now_us - s_last_sample_us <=
            ((int64_t)interval_ms * 2 + 2000) * 1000;
        for (int i = 0; i < 2; i++) {
            pump_auto_config_t *config = &s_auto_config[i];
            pump_auto_runtime_t *runtime = &s_auto_runtime[i];
            if (!config->enabled || runtime->paused) continue;
            bool valid = fresh && s_last_sample_us > s_auto_wait_after_us[i] &&
                automation_available(i, config) && (config->sensor == PUMP_SENSOR_WATER
                    ? s_readings.has_water : s_readings.has_temperature);
            float value = config->sensor == PUMP_SENSOR_WATER ? s_readings.water_raw : s_readings.temperature;
            bool was_running = runtime->running;
            bool start_allowed = !s_mqtt_settings.interlock || !(i == 0 ? s_pump_out : s_pump_in);
            pump_auto_step_allowed(config, runtime, valid, value, now_us / 1000, start_allowed);
            if (runtime->running == was_running) continue;
            esp_err_t err = relay_set(i == 0 ? RELAY_1 : RELAY_2, runtime->running);
            if (err != ESP_OK) {
                relay_set(i == 0 ? RELAY_1 : RELAY_2, false);
                pump_auto_pause(runtime);
                runtime->reason = PUMP_AUTO_RELAY_ERROR;
                runtime->last_stop = PUMP_AUTO_RELAY_ERROR;
            }
            if (i == 0) { s_pump_in = runtime->running; s_local_pump_in = runtime->running; }
            else { s_pump_out = runtime->running; s_local_pump_out = runtime->running; }
            changed = true;
            ESP_LOGI(TAG, "Automatic pump %d %s: %s", i + 1,
                     runtime->running ? "ON" : "OFF", pump_auto_reason_name(runtime->reason));
        }
        xSemaphoreGive(s_relay_lock);
        if (changed) {
            publish_status();
            pool_network_send_telemetry(false, 0, false, false, 0);
        }
    }
}

static esp_err_t persist_mqtt_settings(const pool_mqtt_settings_t *settings, bool wifi_pending)
{
    stored_mqtt_settings_t stored = {.version = 2, .wifi_pending = wifi_pending, .settings = *settings};
    esp_err_t err = nvs_set_blob(s_network_nvs, "mqtt_settings", &stored, sizeof(stored));
    return err == ESP_OK ? nvs_commit(s_network_nvs) : err;
}

static void mqtt_receive(esp_mqtt_event_handle_t event)
{
    int result = pool_mqtt_rx_feed(&s_mqtt_rx, event->topic, event->topic_len, event->data,
        event->data_len, event->total_data_len, event->current_data_offset, event->retain);
    if (result < 0) { ESP_LOGW(TAG, "Rejected invalid/oversized MQTT frame"); return; }
    if (result == 0) return;
    // Relay and sensor commands stay responsive while a config worker scans Wi-Fi.
    if (strncmp(s_mqtt_rx.topic, "smartpool/cmd/", 14) == 0 &&
        strcmp(s_mqtt_rx.topic, "smartpool/cmd/wifi_scan") != 0) {
        pool_mqtt_command_t command;
        if (!pool_mqtt_decode(s_mqtt_rx.topic, s_mqtt_rx.payload, s_mqtt_rx.received,
                              s_mqtt_rx.retained, &s_mqtt_settings, &command)) {
            publish_status();
            return;
        }
        esp_err_t err = ESP_ERR_INVALID_ARG;
        if (command.kind == POOL_MQTT_PUMP_IN || command.kind == POOL_MQTT_PUMP_OUT) {
            bool inlet = command.kind == POOL_MQTT_PUMP_IN;
            err = set_pumps(inlet && command.on, !inlet && command.on, false, false, inlet ? 1 : 2);
        } else if (command.kind == POOL_MQTT_TEMPERATURE || command.kind == POOL_MQTT_WATER) {
            err = pool_network_local_control(command.kind == POOL_MQTT_TEMPERATURE ? "temperature" : "water", command.on);
        }
        if (err != ESP_OK) { ESP_LOGW(TAG, "MQTT control rejected: %s", esp_err_to_name(err)); publish_status(); }
        return;
    }
    pool_mqtt_rx_t *message = malloc(sizeof(*message));
    if (!message) return;
    *message = s_mqtt_rx;
    if (xQueueSend(s_mqtt_commands, &message, 0) != pdTRUE) {
        free(message);
        ESP_LOGW(TAG, "MQTT config queue full; request not applied");
    }
}

static int mqtt_ack(const pool_mqtt_command_t *command, bool success, const char *message)
{
    cJSON *ack = cJSON_CreateObject();
    if (!ack) return -1;
    cJSON_AddStringToObject(ack, "req_id", command->req_id);
    cJSON_AddStringToObject(ack, "action", command->action);
    cJSON_AddStringToObject(ack, "status", success ? "SUCCESS" : "ERROR");
    cJSON_AddStringToObject(ack, "message", message);
    cJSON_AddStringToObject(ack, "water_unit", "adc");
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    cJSON_AddNumberToObject(ack, "interlock", s_mqtt_settings.interlock ? 1 : 0);
    xSemaphoreGive(s_relay_lock);
    char *payload = cJSON_PrintUnformatted(ack);
    int id = payload && s_mqtt_connected ? esp_mqtt_client_enqueue(s_mqtt, "smartpool/ack", payload, 0, 1, 0, true) : -1;
    free(payload);
    cJSON_Delete(ack);
    return id;
}

static void mqtt_scan(void)
{
    esp_err_t err = esp_wifi_scan_start(NULL, true);
    if (err != ESP_OK) { ESP_LOGW(TAG, "MQTT Wi-Fi scan unavailable: %s", esp_err_to_name(err)); return; }
    wifi_ap_record_t records[12];
    uint16_t count = 12;
    err = esp_wifi_scan_get_ap_records(&count, records);
    if (err != ESP_OK) { esp_wifi_clear_ap_list(); return; }
    cJSON *networks = cJSON_CreateArray();
    for (int i = 0; networks && i < count; i++) {
        char ssid[33];
        memcpy(ssid, records[i].ssid, 32); ssid[32] = 0;
        if (!ssid[0]) continue;
        bool duplicate = false;
        for (int j = 0; j < i; j++) if (memcmp(records[j].ssid, records[i].ssid, 32) == 0) duplicate = true;
        if (duplicate) continue;
        cJSON *entry = cJSON_CreateObject();
        if (!entry) continue;
        cJSON_AddStringToObject(entry, "ssid", ssid);
        cJSON_AddNumberToObject(entry, "rssi", records[i].rssi);
        cJSON_AddItemToArray(networks, entry);
    }
    char *payload = networks ? cJSON_PrintUnformatted(networks) : NULL;
    if (payload && s_mqtt_connected) esp_mqtt_client_enqueue(s_mqtt, "smartpool/wifi/scan_result", payload, 0, 1, 0, true);
    free(payload);
    cJSON_Delete(networks);
}

static void mqtt_apply_wifi(void)
{
    // Called after the configuration ACK has been sent, outside the MQTT callback.
    esp_err_t err = pool_network_set_station_enabled(false);
    wifi_config_t station = {0};
    memcpy(station.sta.ssid, s_mqtt_settings.ssid, strlen(s_mqtt_settings.ssid));
    memcpy(station.sta.password, s_mqtt_settings.password, strlen(s_mqtt_settings.password));
    if (err == ESP_OK) err = esp_wifi_set_config(WIFI_IF_STA, &station);
    if (err == ESP_OK) err = credentials_saved();
    if (err == ESP_OK) {
        s_mqtt_wifi_pending = false;
        err = persist_mqtt_settings(&s_mqtt_settings, false);
    }
    if (err != ESP_OK) ESP_LOGE(TAG, "Saved MQTT Wi-Fi config pending: %s; local AP remains available", esp_err_to_name(err));
}

static void mqtt_command_task(void *arg)
{
    while (1) {
        pool_mqtt_rx_t *message;
        if (xQueueReceive(s_mqtt_commands, &message, portMAX_DELAY) != pdTRUE) continue;
        pool_mqtt_command_t command;
        bool valid = pool_mqtt_decode(message->topic, message->payload, message->received,
                                       message->retained, &s_mqtt_settings, &command);
        free(message);
        if (!valid) { mqtt_ack(&command, false, command.error_message ? command.error_message : "Invalid topic, action or configuration values; nothing applied"); continue; }
        if (command.kind == POOL_MQTT_SCAN) { mqtt_scan(); continue; }
        if (command.kind != POOL_MQTT_CONFIG) continue;
        if (pool_mqtt_config_is_stale(&command, &s_mqtt_settings)) { mqtt_ack(&command, false, "Stale configuration; send a newer timestamp; nothing applied"); continue; }
        if ((command.fields & POOL_CFG_WIFI) && !POOL_WIFI_STA_ENABLED) {
            mqtt_ack(&command, false, "Router Wi-Fi disabled in menuconfig; nothing applied"); continue;
        }
        bool wifi_changed = false;
        if ((command.fields & POOL_CFG_WIFI) && (command.settings.timestamps[1] > s_mqtt_settings.timestamps[1] ||
            strcmp(command.settings.ssid, s_mqtt_settings.ssid) || strcmp(command.settings.password, s_mqtt_settings.password))) {
            wifi_config_t actual = {0}, requested = {0};
            memcpy(requested.sta.ssid, command.settings.ssid, strlen(command.settings.ssid));
            memcpy(requested.sta.password, command.settings.password, strlen(command.settings.password));
            esp_err_t wifi_error = esp_wifi_get_config(WIFI_IF_STA, &actual);
            if (wifi_error != ESP_OK) { mqtt_ack(&command, false, "Cannot read Wi-Fi settings; nothing applied"); continue; }
            wifi_changed = memcmp(actual.sta.ssid, requested.sta.ssid, sizeof(actual.sta.ssid)) ||
                memcmp(actual.sta.password, requested.sta.password, sizeof(actual.sta.password));
        }
        bool pending = s_mqtt_wifi_pending || wifi_changed;
        bool changed = memcmp(&command.settings, &s_mqtt_settings, sizeof(s_mqtt_settings)) != 0;
        esp_err_t err = changed ? persist_mqtt_settings(&command.settings, pending) : ESP_OK;
        if (err != ESP_OK) { mqtt_ack(&command, false, "NVS save failed; configuration not activated"); continue; }
        xSemaphoreTake(s_relay_lock, portMAX_DELAY);
        s_mqtt_settings = command.settings;
        s_mqtt_wifi_pending = pending;
        bool stopped_conflict = s_mqtt_settings.interlock && s_pump_in && s_pump_out;
        if (stopped_conflict) {
            // Serialize the mode change with every manual/automatic GPIO update.
            esp_err_t stop_in = relay_set(RELAY_1, false);
            esp_err_t stop_out = relay_set(RELAY_2, false);
            err = stop_in != ESP_OK ? stop_in : stop_out;
            s_pump_in = false; s_pump_out = false;
            s_local_pump_in = false; s_local_pump_out = false;
            for (int i = 0; i < 2; i++) {
                pump_auto_pause(&s_auto_runtime[i]);
                if (err != ESP_OK) {
                    s_auto_runtime[i].reason = PUMP_AUTO_RELAY_ERROR;
                    s_auto_runtime[i].last_stop = PUMP_AUTO_RELAY_ERROR;
                }
            }
            ESP_LOGW(TAG, "Interlock enabled while both pumps ON; stopped both and paused automation");
        }
        xSemaphoreGive(s_relay_lock);
        // Drain old PUBACK notifications before publishing this configuration ACK.
        int published;
        while (xQueueReceive(s_mqtt_published, &published, 0) == pdTRUE) {}
        int id = mqtt_ack(&command, err == ESP_OK, err != ESP_OK
            ? "Settings saved; relay stop failed. Check hardware before restarting pumps."
            : stopped_conflict ? "Saved to NVS. Interlock enabled; both pumps stopped and automation paused."
            : command.wifi_skipped ? "Saved to NVS. Empty Wi-Fi in Sync All skipped; current Wi-Fi unchanged. Interlock applied. Water thresholds use raw ADC 0-4095."
            : "Saved to NVS. Interlock applied. Water thresholds use raw ADC 0-4095; app polling/broker settings do not alter firmware sampling or MQTT connection.");
        if (stopped_conflict) pool_network_send_telemetry(false, 0, false, false, 0);
        s_sample_requested = true;
        publish_status();
        if (pending) {
            // Give QoS 1 ACK up to 3 s to reach the broker before changing Wi-Fi.
            TickType_t begin = xTaskGetTickCount();
            while (id >= 0 && (TickType_t)(xTaskGetTickCount() - begin) < pdMS_TO_TICKS(3000)) {
                if (xQueueReceive(s_mqtt_published, &published, pdMS_TO_TICKS(100)) == pdTRUE && published == id) break;
            }
            mqtt_apply_wifi();
        }
    }
}

static void telemetry_task(void *arg)
{
    while (1) {
        network_sample_t work;
        if (xQueueReceive(s_pending_samples, &work, pdMS_TO_TICKS(1000)) == pdTRUE) {
            telemetry_sample_t sample = work.sample;
            sample.id = ((uint64_t)s_boot_nonce << 32) | ++s_sample_counter;
            char json[320];
            sample_to_json(&sample, json, sizeof(json), true);
            if (s_mqtt_connected && work.measurement) {
                // Android replaces both readings on every telemetry frame. State-only events use status.
                cJSON *telemetry = cJSON_Parse(json);
                if (telemetry) {
                    cJSON_AddStringToObject(telemetry, "water_unit", "adc");
                    if (sample.flags & TELEMETRY_HAS_TEMPERATURE) {
                        xSemaphoreTake(s_relay_lock, portMAX_DELAY);
                        int temperature_state = sample.temperature < s_mqtt_settings.temp_min ? 0
                            : sample.temperature > s_mqtt_settings.temp_max ? 2 : 1;
                        xSemaphoreGive(s_relay_lock);
                        cJSON_AddNumberToObject(telemetry, "temperature_state", temperature_state);
                    }
                }
                if (telemetry && (sample.flags & TELEMETRY_HAS_WATER)) {
                    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
                    int water_state = pool_mqtt_water_state(sample.water_raw, &s_mqtt_settings);
                    xSemaphoreGive(s_relay_lock);
                    cJSON_ReplaceItemInObjectCaseSensitive(telemetry, "muc_nuoc", cJSON_CreateNumber(water_state));
                }
                char *payload = telemetry ? cJSON_PrintUnformatted(telemetry) : NULL;
                if (payload) esp_mqtt_client_enqueue(s_mqtt, TOPIC_TELEMETRY, payload, 0, 1, 0, true);
                free(payload);
                cJSON_Delete(telemetry);
            }
            if (!POOL_HTTP_ENABLED) continue;
            if (telemetry_queue_count() == 0 && s_wifi_connected && post_sample(&sample)) {
                continue;
            }
            queue_sample(&sample);
        }
        if (POOL_HTTP_ENABLED && s_wifi_connected) flush_queued_samples();
    }
}
