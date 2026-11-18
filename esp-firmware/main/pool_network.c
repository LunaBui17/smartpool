#include "pool_network.h"
#include "pool_mqtt_protocol.h"
#include "pool_server_config.h"
#include "pool_identity.h"
#include "pool_reset.h"
#include "cJSON.h"
#include <stdlib.h>

#include <stdio.h>
#include <string.h>
#include <stdatomic.h>
#include <inttypes.h>
#include <stdint.h>
#include <math.h>
#include <time.h>
#include <sys/time.h>

#include "esp_crt_bundle.h"
#include "esp_event.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_system.h"
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
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_timer.h"
#include "relay.h"
#include "sdkconfig.h"
#ifndef CONFIG_POOL_DEVICE_ID
#define CONFIG_POOL_DEVICE_ID "SmartPool"
#endif
// Only for attributing pre-provisioning legacy data, never a runtime identity.
static char s_legacy_device_id[65] = CONFIG_POOL_DEVICE_ID;
static _Atomic bool s_provisioning_pending;
static _Atomic bool s_resetting;
static pool_reset_job_t s_reset_job;
static bool s_reset_storage_cleared;
static bool post_reset_receipt(const char *result, const char *error_code);
static void handle_remote_reset(const cJSON *command, const char *api_url);
static void finish_remote_reset(void);
#include "telemetry_queue.h"
#include "wifi_setup.h"

#define TOPIC_CMD_IN   "smartpool/cmd/bom_vao"
#define TOPIC_CMD_OUT  "smartpool/cmd/bom_ra"
#define TOPIC_CMD_TEMPERATURE "smartpool/cmd/temperature_enabled"
#define TOPIC_CMD_WATER "smartpool/cmd/water_level_enabled"
static struct { char status[112], telemetry[112], command[112], ack[112]; } s_topics;
static char s_mqtt_will[192], s_mqtt_client_id[48];
#define TOPIC_STATUS s_topics.status
#define TOPIC_TELEMETRY s_topics.telemetry
#define TOPIC_CMD_PUMP s_topics.command
#define TOPIC_ACK_COMMAND s_topics.ack

static const char *TAG = "pool_network";
// WSS upgrades use HTTP/1.1; do not advertise HTTP ALPN to raw MQTTS brokers.
static const char *s_http_alpn[] = {"http/1.1", NULL};
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
static _Atomic bool s_status_pending;
static char s_boot_id[33];
static uint32_t s_state_sequence;
static uint32_t s_presence_sequence;
static pool_server_config_t s_server_config;
static bool s_server_config_valid;
// Only control_http_task changes the apply report after initialization.
static uint32_t s_config_attempt;
static uint64_t s_attempt_revision;
static const char *s_config_error;
static bool s_config_report_pending;
static _Atomic bool s_config_poll_requested;
static void control_http_task(void *arg);
static const char *apply_server_config(const pool_server_config_t *next, bool persist);
typedef struct { char *data; size_t used; bool overflow; } config_response_t;
#define CONFIG_RESPONSE_LIMIT 8192
static esp_err_t config_http_event(esp_http_client_event_t *event);
static cJSON *api_response_success(const config_response_t *response);
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
    .id = "", .name = "SmartPool", .api_url = CONFIG_POOL_API_URL,
};
typedef struct {
    uint32_t version;
    char broker[257];
    char username[129];
    char password[129];
} stored_connection_t;
static stored_connection_t s_connection;
typedef struct { uint32_t version; bool wifi_pending; pool_mqtt_settings_t settings; } stored_mqtt_settings_t;
_Static_assert(sizeof(pool_mqtt_settings_t) == 744 && offsetof(pool_mqtt_settings_t, timestamps) == 712,
               "Preserve v1 settings blob layout for migration");
static bool s_mqtt_wifi_pending;
static void mqtt_command_task(void *arg);
static esp_err_t persist_mqtt_settings(const pool_mqtt_settings_t *settings, bool wifi_pending);
static void load_connection_config(void);
static void mqtt_receive(esp_mqtt_event_handle_t event);
static void send_sample(bool has_temperature, float temperature, bool has_water,
                         bool water_normal, int water_raw, bool measurement);
static void telemetry_task(void *arg);
static void automation_task(void *arg);
static _Atomic bool s_sample_requested;
static pump_auto_config_t s_auto_config[2] = {
    {.start = {[PUMP_SENSOR_WATER] = {.enabled = true, .below = true, .threshold = 1500}},
     .stop = {[PUMP_SENSOR_WATER] = {.enabled = true, .below = false, .threshold = 2500}},
     .stop_mode = PUMP_STOP_THRESHOLD, .duration_seconds = 300},
    {.start = {[PUMP_SENSOR_WATER] = {.enabled = true, .below = false, .threshold = 3000}},
     .stop = {[PUMP_SENSOR_WATER] = {.enabled = true, .below = true, .threshold = 2000}},
     .stop_mode = PUMP_STOP_THRESHOLD, .duration_seconds = 300},
};
static pump_auto_runtime_t s_auto_runtime[2];
static char s_last_automation_req_ids[2][97];
static int64_t s_auto_wait_after_us[2];
typedef struct {
    uint32_t version;
    pump_auto_config_t rules[2];
} stored_auto_rules_t;

typedef struct {
    bool enabled;
    pump_sensor_t sensor;
    bool start_below;
    double start_threshold;
    pump_stop_mode_t stop_mode;
    double stop_threshold;
    uint32_t duration_seconds;
} old_auto_rule_t;
typedef struct { uint32_t version; old_auto_rule_t rules[2]; } old_auto_rules_t;
_Static_assert(sizeof(old_auto_rule_t) == 48, "Preserve v1 automation NVS layout");

static bool automation_available(int channel, const pump_auto_config_t *config)
{
    if (!(channel == 0 ? POOL_RELAY_1_ENABLED : POOL_RELAY_2_ENABLED)) return false;
    for (int sensor = 0; sensor < PUMP_SENSOR_COUNT; sensor++) {
        if ((config->start[sensor].enabled ||
             (config->stop_mode == PUMP_STOP_THRESHOLD && config->stop[sensor].enabled)) &&
            !(sensor == PUMP_SENSOR_WATER ? s_water_enabled : s_temperature_enabled)) return false;
    }
    return true;
}

static uint32_t s_boot_nonce;
static uint32_t s_sample_counter;
static SemaphoreHandle_t s_relay_lock;

static void publish_status_frame(bool retained)
{
    if (!pool_identity_ready() || s_provisioning_pending || !s_mqtt_connected) {
        return;
    }
    pool_device_state_t device_state;
    pool_network_get_state(&device_state);
    cJSON *status = cJSON_CreateObject();
    if (!status) return;
    char ip_text[16] = "";
    if (s_wifi_connected) {
        esp_netif_ip_info_t ip;
        esp_netif_t *netif = esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
        if (netif && esp_netif_get_ip_info(netif, &ip) == ESP_OK) {
            snprintf(ip_text, sizeof(ip_text), IPSTR, IP2STR(&ip.ip));
        }
    }
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    cJSON_AddBoolToObject(status, "online", true);
    cJSON_AddStringToObject(status, "device_id", pool_identity_id());
    cJSON_AddStringToObject(status, "factory_mac", pool_identity_mac());
    cJSON_AddStringToObject(status, "boot_id", s_boot_id);
    cJSON_AddNumberToObject(status, "presence_sequence", ++s_presence_sequence);
    if (s_server_config_valid) cJSON_AddNumberToObject(status, "reported_config_revision", s_server_config.revision);
    cJSON_AddBoolToObject(status, "pump_in", s_pump_in);
    cJSON_AddBoolToObject(status, "pump_out", s_pump_out);
    cJSON_AddNumberToObject(status, "bom_vao_control", s_pump_in ? 1 : 0);
    cJSON_AddNumberToObject(status, "bom_ra_control", s_pump_out ? 1 : 0);
    cJSON_AddBoolToObject(status, "relay1_supported", POOL_RELAY_1_ENABLED);
    cJSON_AddBoolToObject(status, "relay2_supported", POOL_RELAY_2_ENABLED);
    cJSON_AddBoolToObject(status, "temperature_supported", POOL_TEMPERATURE_ENABLED);
    cJSON_AddBoolToObject(status, "water_supported", POOL_WATER_ENABLED);
    cJSON_AddBoolToObject(status, "sensor_simulation", POOL_SENSOR_SIMULATION);
    cJSON_AddNumberToObject(status, "temperature_enabled", s_temperature_enabled ? 1 : 0);
    cJSON_AddNumberToObject(status, "water_level_enabled", s_water_enabled ? 1 : 0);
    cJSON_AddBoolToObject(status, "water_enabled", s_water_enabled);
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
    cJSON *automation = cJSON_AddArrayToObject(status, "automation");
    for (int i = 0; automation && i < 2; i++) {
        const pump_auto_view_t *view = &device_state.automation[i];
        cJSON *rule = cJSON_CreateObject();
        if (!rule) continue;
        cJSON_AddItemToArray(automation, rule);
        cJSON_AddNumberToObject(rule, "channel", i + 1);
        cJSON_AddBoolToObject(rule, "enabled", view->config.enabled);
        cJSON_AddStringToObject(rule, "stop_mode", view->config.stop_mode == PUMP_STOP_DURATION
            ? "duration" : "threshold");
        cJSON_AddNumberToObject(rule, "duration_seconds", view->config.duration_seconds);
        cJSON *start_conditions = cJSON_AddObjectToObject(rule, "start_conditions");
        cJSON *stop_conditions = cJSON_AddObjectToObject(rule, "stop_conditions");
        for (int sensor = 0; start_conditions && stop_conditions && sensor < PUMP_SENSOR_COUNT; sensor++) {
            const char *name = sensor == PUMP_SENSOR_WATER ? "water" : "temperature";
            cJSON *start = cJSON_AddObjectToObject(start_conditions, name);
            cJSON *stop = cJSON_AddObjectToObject(stop_conditions, name);
            if (!start || !stop) continue;
            cJSON_AddBoolToObject(start, "enabled", view->config.start[sensor].enabled);
            cJSON_AddStringToObject(start, "comparison", view->config.start[sensor].below ? "below" : "above");
            cJSON_AddNumberToObject(start, "threshold", view->config.start[sensor].threshold);
            cJSON_AddBoolToObject(stop, "enabled", view->config.stop[sensor].enabled);
            cJSON_AddStringToObject(stop, "comparison", view->config.stop[sensor].below ? "below" : "above");
            cJSON_AddNumberToObject(stop, "threshold", view->config.stop[sensor].threshold);
        }
        cJSON_AddBoolToObject(rule, "paused", view->paused);
        cJSON_AddBoolToObject(rule, "running", view->running);
        cJSON_AddStringToObject(rule, "reason", view->reason);
        cJSON_AddStringToObject(rule, "last_stop_reason", view->last_stop_reason);
        cJSON_AddNumberToObject(rule, "remaining_seconds", view->remaining_seconds);
    }
    cJSON *network = cJSON_AddObjectToObject(status, "network");
    if (network) {
        cJSON_AddStringToObject(network, "ip", ip_text);
        cJSON_AddStringToObject(network, "http_api_url", s_mqtt_settings.api_url);
    }
    xSemaphoreGive(s_relay_lock);
    char *json = cJSON_PrintUnformatted(status);
    if (json) esp_mqtt_client_enqueue(s_mqtt, TOPIC_STATUS, json, 0, 1, retained, true);
    free(json);
    cJSON_Delete(status);
}

static void publish_status(void)
{
    s_status_pending = true;
    publish_status_frame(true);
}

// Only MQTT-originated commands require this gate; AP/local control remains independent.
static bool mqtt_control_ready(void)
{
    return pool_identity_ready() && !s_provisioning_pending && s_mqtt_connected && s_wifi_connected && s_station_enabled;
}

static esp_err_t set_pumps(bool pump_in, bool pump_out, bool local, bool remote_stop_only, int channel)
{
    if (xSemaphoreTake(s_relay_lock, portMAX_DELAY) != pdTRUE) return ESP_FAIL;
    if(s_resetting&&(pump_in||pump_out)) { xSemaphoreGive(s_relay_lock);return ESP_ERR_INVALID_STATE; }
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
    if (!local && !remote_stop_only && !mqtt_control_ready()) {
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
        esp_mqtt_client_subscribe(s_mqtt, TOPIC_CMD_PUMP, 1);
        s_config_poll_requested = true;
        s_sample_requested = true;
        publish_status();
        publish_status_frame(false);
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
            ESP_LOGE(TAG, "MQTT error: type=%d, TLS=%s, mbedtls=%d, verify=0x%x, socket=%d, broker_code=%d, free_heap=%" PRIu32,
                     event->error_handle->error_type,
                     esp_err_to_name(event->error_handle->esp_tls_last_esp_err),
                     event->error_handle->esp_tls_stack_err,
                     (unsigned)event->error_handle->esp_tls_cert_verify_flags,
                     event->error_handle->esp_transport_sock_errno,
                     event->error_handle->connect_return_code, esp_get_free_heap_size());
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
    if(s_resetting) {
        s_station_enabled=enabled;s_connect_requested=enabled&&s_has_wifi_credentials;
        if(!enabled)esp_wifi_disconnect();
        return ESP_OK;
    }
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
        if(s_resetting)continue;
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
        bool mqtt_tls = s_mqtt && (strncmp(s_connection.broker, "wss://", 6) == 0 ||
                                   strncmp(s_connection.broker, "mqtts://", 8) == 0);
        if (seconds % 5 == 0 && s_station_enabled && s_wifi_connected && s_mqtt && !s_mqtt_started &&
            (!mqtt_tls || clock_ready())) {
            esp_err_t err = esp_mqtt_client_start(s_mqtt);
            if (err == ESP_OK) {
                s_mqtt_started = true;
                ESP_LOGI(TAG, "MQTT client started");
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
            mqtt_tls && !clock_ready()) {
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
        s_config_poll_requested = true;
        const ip_event_got_ip_t *ip_event = data;
        ESP_LOGI(TAG, "Wi-Fi got IP: " IPSTR, IP2STR(&ip_event->ip_info.ip));
        // Keep the local AP available while the router connection is online.
        if (s_mqtt && !s_mqtt_started) ESP_LOGI(TAG, "Waiting for clock sync before MQTT TLS");
    }
}

static esp_err_t load_auto_rules(void)
{
    size_t managed_size = 0;
    bool managed = nvs_get_blob(s_network_nvs, "server_cfg", NULL, &managed_size) == ESP_OK;
    union { stored_auto_rules_t current; old_auto_rules_t old; } stored = {0};
    size_t size = sizeof(stored);
    esp_err_t err = nvs_get_blob(s_network_nvs, "auto_rules", &stored, &size);
    if (err == ESP_OK && size == sizeof(stored.current) && stored.current.version == 2 &&
        pump_auto_config_valid(&stored.current.rules[0]) && pump_auto_config_valid(&stored.current.rules[1])) {
        memcpy(s_auto_config, stored.current.rules, sizeof(s_auto_config));
    } else if (err == ESP_OK && size == sizeof(stored.old) && stored.old.version == 1) {
        for (int i = 0; i < 2; i++) {
            old_auto_rule_t *previous = &stored.old.rules[i];
            if (previous->sensor != PUMP_SENSOR_WATER && previous->sensor != PUMP_SENSOR_TEMPERATURE) continue;
            pump_auto_config_t migrated = {.enabled = previous->enabled,
                .stop_mode = previous->stop_mode, .duration_seconds = previous->duration_seconds};
            migrated.start[previous->sensor] = (pump_auto_condition_t){true, previous->start_below, previous->start_threshold};
            migrated.stop[previous->sensor] = (pump_auto_condition_t){true, !previous->start_below, previous->stop_threshold};
            if (pump_auto_config_valid(&migrated)) s_auto_config[i] = migrated;
        }
    } else if (err != ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGW(TAG, "Invalid automation settings; defaults remain disabled");
    }
    for (int i = 0; i < 2; i++) {
        pump_auto_reset(&s_auto_runtime[i]);
        if (managed) s_auto_config[i].enabled = false;
        if (!automation_available(i, &s_auto_config[i])) s_auto_config[i].enabled = false;
        bool initial_on = !s_resetting && !managed && !s_auto_config[i].enabled &&
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

static bool connection_url_valid(const char *url, bool mqtt)
{
    if (!url || !url[0] || strlen(url) > 256) return false;
    const char *host = NULL;
    if (mqtt && strncmp(url, "mqtts://", 8) == 0) host = url + 8;
    if (mqtt && strncmp(url, "mqtt://", 7) == 0) host = url + 7;
    if (mqtt && strncmp(url, "wss://", 6) == 0) host = url + 6;
    if (!mqtt && strncmp(url, "http://", 7) == 0) host = url + 7;
    if (!mqtt && strncmp(url, "https://", 8) == 0) host = url + 8;
    if (!host || !host[0] || *host == '/' || *host == ':') return false;
    for (const char *p = host; *p; p++) {
        if ((unsigned char)*p <= 32 || *p == '@' || *p == '#') return false;
    }
    return true;
}

static void load_connection_config(void)
{
    memset(&s_connection, 0, sizeof(s_connection));
    s_connection.version = 2;
    snprintf(s_connection.broker, sizeof(s_connection.broker), "%s", CONFIG_POOL_MQTT_URI);
    snprintf(s_connection.username, sizeof(s_connection.username), "%s", pool_identity_id());
    stored_connection_t saved;
    size_t size = sizeof(saved);
    if (nvs_get_blob(s_network_nvs, "mqtt_conn", &saved, &size) == ESP_OK &&
        size == sizeof(saved) && saved.version == 2 &&
        memchr(saved.broker, 0, sizeof(saved.broker)) &&
        memchr(saved.username, 0, sizeof(saved.username)) &&
        memchr(saved.password, 0, sizeof(saved.password)) &&
        connection_url_valid(saved.broker, true)) {
        if (pool_identity_id_valid(saved.username))
            snprintf(s_legacy_device_id, sizeof(s_legacy_device_id), "%s", saved.username);
        if (pool_identity_ready() && strcmp(saved.username, pool_identity_id()) == 0) s_connection = saved;
    }
}

void pool_network_get_connection_config(pool_connection_config_t *config)
{
    if (!config) return;
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    snprintf(config->broker, sizeof(config->broker), "%s", s_connection.broker);
    snprintf(config->username, sizeof(config->username), "%s", s_connection.username);
    config->password_set = s_connection.password[0] != '\0';
    snprintf(config->api_url, sizeof(config->api_url), "%s", s_mqtt_settings.api_url);
    snprintf(config->device_id, sizeof(config->device_id), "%s", pool_identity_id());
    snprintf(config->factory_serial, sizeof(config->factory_serial), "%s", pool_identity_serial());
    config->provisioned = pool_identity_ready();
    config->device_key_set = pool_identity_key()[0] != 0;
    config->provisioning_pending = s_provisioning_pending;
    xSemaphoreGive(s_relay_lock);
}

esp_err_t pool_network_provision_device(const char *device_id, const char *device_key)
{
    if (!pool_identity_id_valid(device_id) || !device_key) return ESP_ERR_INVALID_ARG;
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    esp_err_t error = s_provisioning_pending ? ESP_ERR_INVALID_STATE : ESP_OK;
    if (error == ESP_OK) error = telemetry_queue_check_device(device_id, s_legacy_device_id);
    if (error == ESP_OK && !pool_identity_ready() && strcmp(device_id, s_legacy_device_id)) {
        const char *keys[] = {"server_cfg", "cmd_log"};
        for (int i = 0; i < 2; i++) {
            size_t size = 0;
            esp_err_t found = nvs_get_blob(s_network_nvs, keys[i], NULL, &size);
            if (found != ESP_ERR_NVS_NOT_FOUND) {
                error = found == ESP_OK ? ESP_ERR_INVALID_STATE : found;
                break;
            }
        }
    }
    if (error == ESP_OK) error = pool_identity_save(device_id, device_key);
    if (error == ESP_OK) s_provisioning_pending = true;
    xSemaphoreGive(s_relay_lock);
    if (error == ESP_OK) set_pumps(false, false, false, true, 0);
    return error;
}

esp_err_t pool_network_save_http_url(const char *url)
{
    if (!POOL_HTTP_ENABLED) return ESP_ERR_NOT_SUPPORTED;
    if (!connection_url_valid(url, false)) return ESP_ERR_INVALID_ARG;
    if(s_resetting) {
        xSemaphoreTake(s_relay_lock,portMAX_DELAY);
        snprintf(s_reset_job.api_url,sizeof(s_reset_job.api_url),"%s",url);
        esp_err_t saved=pool_reset_save(&s_reset_job);
        xSemaphoreGive(s_relay_lock);return saved;
    }
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    pool_mqtt_settings_t next = s_mqtt_settings;
    snprintf(next.api_url, sizeof(next.api_url), "%s", url);
    time_t current_time = time(NULL);
    uint64_t now = current_time >= 1704067200 ? (uint64_t)current_time : 0;
    next.timestamps[3] = now > next.timestamps[3] ? now : next.timestamps[3] + 1;
    esp_err_t err = persist_mqtt_settings(&next, s_mqtt_wifi_pending);
    if (err == ESP_OK) s_mqtt_settings = next;
    xSemaphoreGive(s_relay_lock);
    if (err == ESP_OK) publish_status();
    return err;
}

esp_err_t pool_network_save_mqtt_connection(const char *broker, const char *username,
                                             const char *password)
{
    if (!POOL_MQTT_ENABLED) return ESP_ERR_NOT_SUPPORTED;
    if (!pool_identity_ready() || s_provisioning_pending) return ESP_ERR_INVALID_STATE;
    if (!connection_url_valid(broker, true) || !username ||
        strcmp(username, pool_identity_id()) != 0 ||
        strlen(username) >= sizeof(s_connection.username) || !password ||
        strlen(password) >= sizeof(s_connection.password)) return ESP_ERR_INVALID_ARG;
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    stored_connection_t next = s_connection;
    snprintf(next.broker, sizeof(next.broker), "%s", broker);
    snprintf(next.username, sizeof(next.username), "%s", username);
    if (password[0]) snprintf(next.password, sizeof(next.password), "%s", password);
    esp_err_t err = next.password[0] ? nvs_set_blob(s_network_nvs, "mqtt_conn", &next, sizeof(next))
                                    : ESP_ERR_INVALID_ARG;
    if (err == ESP_OK) err = nvs_commit(s_network_nvs);
    if (err == ESP_OK) s_connection = next;
    xSemaphoreGive(s_relay_lock);
    return err;
}

esp_err_t pool_network_init(void)
{
    s_relay_lock = xSemaphoreCreateMutex();
    if (!s_relay_lock) return ESP_ERR_NO_MEM;
    s_pending_samples = xQueueCreate(16, sizeof(network_sample_t));
    if (!s_pending_samples) return ESP_ERR_NO_MEM;
    esp_err_t err = nvs_flash_init();
    if (err != ESP_OK) return err;
    err=pool_reset_load(&s_reset_job);
    if(err==ESP_OK) {
        s_resetting=true;s_provisioning_pending=true;
        err=pool_reset_clear_app();if(err!=ESP_OK)return err;
    } else if(err!=ESP_ERR_NVS_NOT_FOUND)return err;
    err = nvs_open("poolnet", NVS_READWRITE, &s_network_nvs);
    if (err != ESP_OK) return err;
    err = pool_identity_init();
    if (err != ESP_OK) return err;
    ESP_LOGI(TAG, "Hardware serial=%s, factory MAC=%s", pool_identity_serial(), pool_identity_mac());
    if (pool_identity_ready()) {
        snprintf(s_topics.status, sizeof(s_topics.status), "smartpool/devices/%s/status", pool_identity_id());
        snprintf(s_topics.telemetry, sizeof(s_topics.telemetry), "smartpool/devices/%s/telemetry", pool_identity_id());
        snprintf(s_topics.command, sizeof(s_topics.command), "smartpool/devices/%s/cmd/pump", pool_identity_id());
        snprintf(s_topics.ack, sizeof(s_topics.ack), "smartpool/devices/%s/ack/command", pool_identity_id());
        snprintf(s_mqtt_will, sizeof(s_mqtt_will), "{\"device_id\":\"%s\",\"factory_mac\":\"%s\",\"online\":false}", pool_identity_id(), pool_identity_mac());
        snprintf(s_mqtt_client_id, sizeof(s_mqtt_client_id), "smartpool-%s", pool_identity_serial());
    }
    load_connection_config();
    uint8_t station_enabled = 1;
    err = nvs_get_u8(s_network_nvs, "sta_enabled", &station_enabled);
    if (err != ESP_OK && err != ESP_ERR_NVS_NOT_FOUND) return err;
    s_station_enabled = POOL_WIFI_STA_ENABLED && station_enabled;
    if(s_resetting)s_station_enabled=false;
    stored_mqtt_settings_t saved_mqtt;
    size_t mqtt_size = sizeof(saved_mqtt);
    if (nvs_get_blob(s_network_nvs, "mqtt_settings", &saved_mqtt, &mqtt_size) == ESP_OK &&
        mqtt_size == sizeof(saved_mqtt) &&
        (saved_mqtt.version == 1 || saved_mqtt.version == 2 || saved_mqtt.version == 3)) {
        // v1 ignored interlock; its old padding must never be read as a boolean.
        if (saved_mqtt.version == 1) {
            saved_mqtt.settings.interlock = false;
            // Reaccept the retained threshold group once: v1 never applied its interlock field.
            saved_mqtt.settings.timestamps[0] = 0;
        }
        s_mqtt_settings = saved_mqtt.settings;
        if (memchr(saved_mqtt.settings.id, 0, sizeof(saved_mqtt.settings.id)) &&
            pool_identity_id_valid(saved_mqtt.settings.id))
            snprintf(s_legacy_device_id, sizeof(s_legacy_device_id), "%s", saved_mqtt.settings.id);
        // Older NVS settings belong to the pre-v2 API and global MQTT topics.
        if (saved_mqtt.version < 3)
            snprintf(s_mqtt_settings.api_url, sizeof(s_mqtt_settings.api_url), "%s", CONFIG_POOL_API_URL);
        s_mqtt_wifi_pending = saved_mqtt.wifi_pending;
    }
    if (pool_identity_ready())
        snprintf(s_mqtt_settings.id, sizeof(s_mqtt_settings.id), "%s", pool_identity_id());
    err = load_auto_rules();
    if (err != ESP_OK) return err;
    pool_server_config_t stored_server;
    err = pool_server_config_load(s_network_nvs, &stored_server);
    if (err == ESP_OK && pool_identity_ready()) {
        s_config_error = apply_server_config(&stored_server, false);
        s_attempt_revision = stored_server.revision;
        s_config_attempt = 1;
        s_config_report_pending = true;
    } else if (err != ESP_OK && err != ESP_ERR_NVS_NOT_FOUND) {
        // Do not claim a revision when its complete snapshot cannot be restored.
        ESP_LOGW(TAG, "Server config snapshot unavailable: %s", esp_err_to_name(err));
    }
    if (POOL_HTTP_ENABLED && pool_identity_ready()) {
        err = telemetry_queue_init(pool_identity_id(), s_legacy_device_id);
        if (err != ESP_OK) ESP_LOGE(TAG, "Persistent telemetry queue unavailable: %s", esp_err_to_name(err));
        s_http_queued_count = telemetry_queue_count();
    }
    err = esp_netif_init();
    if (err != ESP_OK) return err;
    err = esp_event_loop_create_default();
    if (err != ESP_OK) return err;
    if (!esp_netif_create_default_wifi_sta()) return ESP_FAIL;
    if (!esp_netif_create_default_wifi_ap()) return ESP_FAIL;
    if (POOL_MQTT_ENABLED || POOL_HTTP_ENABLED) {
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
    if(s_resetting) {
        err=esp_wifi_restore();if(err!=ESP_OK)return err;
        s_reset_storage_cleared=true;
    }
    s_boot_nonce = esp_random();
    for (int i = 0; i < 4; i++) {
        uint32_t part = esp_random();
        snprintf(s_boot_id + i * 8, sizeof(s_boot_id) - i * 8, "%08" PRIx32, part);
    }
    err = esp_wifi_set_storage(s_resetting?WIFI_STORAGE_RAM:WIFI_STORAGE_FLASH);
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
            .broker.address.uri = s_connection.broker,
            .broker.verification.crt_bundle_attach = esp_crt_bundle_attach,
            .broker.verification.alpn_protos = strncmp(s_connection.broker, "wss://", 6) == 0
                ? s_http_alpn : NULL,
            .credentials.username = s_connection.username,
            .credentials.authentication.password = s_connection.password,
            .session.last_will.topic = TOPIC_STATUS,
            .session.last_will.msg = s_mqtt_will,
            .credentials.client_id = s_mqtt_client_id,
            .session.last_will.qos = 1,
            .session.last_will.retain = 1,
        };
        if (!pool_identity_ready() || s_connection.password[0] == '\0') {
            ESP_LOGW(TAG, "MQTT credentials missing; configure them on the local dashboard");
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
    if ((POOL_HTTP_ENABLED || POOL_MQTT_ENABLED) &&
        xTaskCreate(control_http_task, "control_http", 8192, NULL, 4, NULL) != pdPASS) return ESP_ERR_NO_MEM;
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
                           bool include_missing_readings, bool backfill)
{
    char temperature_field[64] = "";
    char water_field[80] = "";
    char measured[32] = "null";
    if (sample->measured_at_ms) snprintf(measured, sizeof(measured), "%" PRId64, sample->measured_at_ms);
    if (sample->flags & TELEMETRY_HAS_TEMPERATURE) {
        snprintf(temperature_field, sizeof(temperature_field),
                 ",\"temperature\":%.2f", sample->temperature);
    } else if (include_missing_readings) {
        snprintf(temperature_field, sizeof(temperature_field), ",\"temperature\":null");
    }
    if (sample->flags & TELEMETRY_HAS_WATER) {
        snprintf(water_field, sizeof(water_field), ",\"water_adc\":%d", sample->water_raw);
    } else if (include_missing_readings) {
        snprintf(water_field, sizeof(water_field), ",\"water_adc\":null");
    }
    snprintf(json, capacity,
             "{\"action\":\"upload_sample\",\"device_id\":\"%s\",\"factory_mac\":\"%s\",\"sample_id\":\"%016" PRIX64
             "\",\"measured_at_ms\":%s,\"time_quality\":\"%s\",\"is_backfill\":%s%s%s}",
             pool_identity_id(), pool_identity_mac(), sample->id, measured, sample->measured_at_ms ? "synced" : "unknown",
             backfill ? "true" : "false", temperature_field, water_field);
}

static void record_http_result(esp_err_t error, int status_code)
{
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    s_http_status.sending = false;
    s_http_status.attempted = true;
    s_http_status.error = error;
    s_http_status.status_code = status_code;
    s_http_status.last_attempt_us = esp_timer_get_time();
    if (error == ESP_OK && (status_code == 200 || status_code == 201)) {
        s_http_status.last_success_us = s_http_status.last_attempt_us;
    }
    xSemaphoreGive(s_relay_lock);
}

static bool post_sample(const telemetry_sample_t *sample, bool backfill)
{
    char json[320];
    if (!pool_identity_ready() || s_provisioning_pending) {
        ESP_LOGW(TAG, "Device not provisioned; configure ID/key on the local AP page");
        return false;
    }
    sample_to_json(sample, json, sizeof(json), false, backfill);
    char api_url[257];
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    memcpy(api_url, s_mqtt_settings.api_url, sizeof(api_url));
    xSemaphoreGive(s_relay_lock);
    config_response_t response = {.data = calloc(1, CONFIG_RESPONSE_LIMIT + 1)};
    if (!response.data) { record_http_result(ESP_ERR_NO_MEM, 0); return false; }
    esp_http_client_config_t http_config = {
        .url = api_url,
        .timeout_ms = 10000,
        .crt_bundle_attach = esp_crt_bundle_attach,
        .alpn_protos = s_http_alpn, .disable_auto_redirect = true,
        .event_handler = config_http_event, .user_data = &response,
    };
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    s_http_status.sending = true;
    xSemaphoreGive(s_relay_lock);
    esp_http_client_handle_t http = esp_http_client_init(&http_config);
    if (!http) {
        record_http_result(ESP_ERR_NO_MEM, 0);
        ESP_LOGE(TAG, "Cannot create HTTP client");
        free(response.data);
        return false;
    }
    esp_http_client_set_method(http, HTTP_METHOD_POST);
    esp_http_client_set_header(http, "Content-Type", "application/json");
    esp_http_client_set_header(http, "X-Pool-Device-Key", pool_identity_key());
    esp_http_client_set_post_field(http, json, strlen(json));
    esp_err_t err = esp_http_client_perform(http);
    int status = esp_http_client_get_status_code(http);
    cJSON *reply = err == ESP_OK && (status == 200 || status == 201) ? api_response_success(&response) : NULL;
    const cJSON *received = cJSON_GetObjectItemCaseSensitive(reply, "sample");
    const cJSON *received_id = cJSON_GetObjectItemCaseSensitive(received, "sample_id");
    const cJSON *received_device = cJSON_GetObjectItemCaseSensitive(received, "device_id");
    char sample_id[17];
    snprintf(sample_id, sizeof(sample_id), "%016" PRIX64, sample->id);
    bool success = cJSON_IsString(received_id) && strcmp(received_id->valuestring, sample_id) == 0 &&
        cJSON_IsString(received_device) && strcmp(received_device->valuestring, pool_identity_id()) == 0;
    cJSON_Delete(reply);
    free(response.data);
    if (!success && err == ESP_OK) err = ESP_ERR_INVALID_RESPONSE;
    record_http_result(err, status);
    if (success) {
        ESP_LOGI(TAG, "HTTP POST OK: sample=%016" PRIX64 ", status=%d",
                 sample->id, status);
    } else {
        ESP_LOGW(TAG, "HTTP update failed: %s, status=%d, url=%s", esp_err_to_name(err),
                 status, api_url);
        ESP_LOGW(TAG, "HTTP transport: socket=%d, free_heap=%" PRIu32,
                 esp_http_client_get_errno(http), esp_get_free_heap_size());
    }
    esp_http_client_cleanup(http);
    return success;
}

static void queue_sample(const telemetry_sample_t *sample)
{
    xSemaphoreTake(s_relay_lock,portMAX_DELAY);
    if(s_resetting){xSemaphoreGive(s_relay_lock);return;}
    bool dropped_oldest = false;
    esp_err_t err = telemetry_queue_push(sample, &dropped_oldest);
    s_http_queued_count = telemetry_queue_count();
    if (err != ESP_OK) ESP_LOGE(TAG, "Cannot persist telemetry: %s", esp_err_to_name(err));
    if (dropped_oldest) ESP_LOGW(TAG, "Telemetry queue full; oldest sample removed");
    xSemaphoreGive(s_relay_lock);
}

static void flush_queued_samples(void)
{
    unsigned int delivered = 0;
    for (; delivered < 4 && s_wifi_connected; delivered++) {
        telemetry_sample_t queued;
        if (!telemetry_queue_peek_at(delivered, &queued)) break;
        if (!(queued.flags & (TELEMETRY_HAS_TEMPERATURE | TELEMETRY_HAS_WATER))) continue;
        if (!post_sample(&queued, true)) break;
    }
    if (delivered > 0) {
        xSemaphoreTake(s_relay_lock,portMAX_DELAY);
        if(s_resetting){xSemaphoreGive(s_relay_lock);return;}
        esp_err_t err = telemetry_queue_pop_many(delivered);
        s_http_queued_count = telemetry_queue_count();
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "Cannot update telemetry queue: %s", esp_err_to_name(err));
        }
        xSemaphoreGive(s_relay_lock);
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
    if (s_readings.has_temperature) {
        s_readings.temperature = temperature;
        s_readings.has_last_temperature = true;
    }
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
    else if (!pool_identity_ready()) state->mqtt_state = "unprovisioned";
    else if (s_provisioning_pending) state->mqtt_state = "restarting";
    else if (!s_mqtt) state->mqtt_state = "missing_credentials";
    else if (!state->station_enabled || !state->wifi_connected) state->mqtt_state = "waiting_wifi";
    else if (state->mqtt_connected) state->mqtt_state = "connected";
    else if (!s_mqtt_started && !state->clock_ready) state->mqtt_state = "waiting_time";
    else if (s_mqtt_phase == MQTT_PHASE_ERROR) state->mqtt_state = "error";
    else if (s_mqtt_phase == MQTT_PHASE_DISCONNECTED) state->mqtt_state = "disconnected";
    else state->mqtt_state = "connecting";

    if (!POOL_HTTP_ENABLED) state->http_state = "disabled";
    else if (!pool_identity_ready()) state->http_state = "unprovisioned";
    else if (s_provisioning_pending) state->http_state = "restarting";
    else if (!state->station_enabled || !state->wifi_connected) state->http_state = "waiting_wifi";
    else if (s_http_status.sending) state->http_state = "sending";
    else if (!s_http_status.attempted) state->http_state = "pending";
    else if (s_http_status.error == ESP_OK &&
             (s_http_status.status_code == 200 || s_http_status.status_code == 201)) state->http_state = "success";
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

static esp_err_t set_sensor_enabled(const char *target, bool on, bool remote)
{
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    if (remote && !mqtt_control_ready()) {
        xSemaphoreGive(s_relay_lock);
        return ESP_ERR_INVALID_STATE;
    }
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
    return set_sensor_enabled(target, on, false);
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
    // Capture before enqueue; clock synchronization later must never rewrite this time.
    if (measurement && clock_ready()) {
        struct timeval now;
        gettimeofday(&now, NULL);
        sample.measured_at_ms = (int64_t)now.tv_sec * 1000 + now.tv_usec / 1000;
    }
    xSemaphoreGive(s_relay_lock);
    if ((!POOL_HTTP_ENABLED && !POOL_MQTT_ENABLED) || !pool_identity_ready() || s_provisioning_pending) return;
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
    if (s_server_config_valid) {
        xSemaphoreGive(s_relay_lock);
        return ESP_ERR_INVALID_STATE; // Edit managed automation through update_config.
    }
    if (config->enabled && !automation_available(channel, config)) {
        xSemaphoreGive(s_relay_lock);
        return ESP_ERR_NOT_SUPPORTED;
    }
    stored_auto_rules_t stored = {.version = 2, .rules = {s_auto_config[0], s_auto_config[1]}};
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
        if(s_resetting)continue;
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
            bool sample_ready = fresh && s_last_sample_us > s_auto_wait_after_us[i] &&
                automation_available(i, config);
            bool valid[PUMP_SENSOR_COUNT] = {sample_ready && s_readings.has_water,
                                            sample_ready && s_readings.has_temperature};
            float value[PUMP_SENSOR_COUNT] = {s_readings.water_raw, s_readings.temperature};
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
    stored_mqtt_settings_t stored = {.version = 3, .wifi_pending = wifi_pending, .settings = *settings};
    esp_err_t err = nvs_set_blob(s_network_nvs, "mqtt_settings", &stored, sizeof(stored));
    return err == ESP_OK ? nvs_commit(s_network_nvs) : err;
}

static void mqtt_v2_pump_ack(const char *command_id, const char *result)
{
    cJSON *ack = cJSON_CreateObject();
    if (!ack) return;
    cJSON_AddStringToObject(ack, "device_id", pool_identity_id());
    cJSON_AddStringToObject(ack, "factory_mac", pool_identity_mac());
    cJSON_AddStringToObject(ack, "command_id", command_id);
    cJSON_AddStringToObject(ack, "result", result);
    char *payload = cJSON_PrintUnformatted(ack);
    if (payload && s_mqtt_connected)
        esp_mqtt_client_enqueue(s_mqtt, TOPIC_ACK_COMMAND, payload, 0, 1, 0, true);
    free(payload);
    cJSON_Delete(ack);
}

#define COMMAND_LEDGER_SIZE 32
typedef struct {
    int64_t expires_at_ms;
    char id[37];
    uint8_t result; // 0 reserved/uncertain, 1 applied, 2 rejected
    bool inlet, enabled;
} command_record_t;
typedef struct { uint32_t version; command_record_t records[COMMAND_LEDGER_SIZE]; } command_ledger_t;

static void mqtt_v2_pump_command(const pool_mqtt_rx_t *rx)
{
    if (rx->retained) return;
    cJSON *root = cJSON_ParseWithLength(rx->payload, rx->received);
    if (!cJSON_IsObject(root)) { cJSON_Delete(root); return; }
    const cJSON *device = cJSON_GetObjectItemCaseSensitive(root, "device_id");
    const cJSON *mac = cJSON_GetObjectItemCaseSensitive(root, "factory_mac");
    const cJSON *id = cJSON_GetObjectItemCaseSensitive(root, "command_id");
    const cJSON *target = cJSON_GetObjectItemCaseSensitive(root, "target");
    const cJSON *enabled = cJSON_GetObjectItemCaseSensitive(root, "enabled");
    const cJSON *expiry = cJSON_GetObjectItemCaseSensitive(root, "expires_at_ms");
    if (!cJSON_IsString(device) || strcmp(device->valuestring, pool_identity_id()) != 0 ||
        !cJSON_IsString(mac) || strcmp(mac->valuestring, pool_identity_mac()) != 0 ||
        !cJSON_IsString(id) || strlen(id->valuestring) != 36 ||
        !cJSON_IsString(target) ||
        (strcmp(target->valuestring, "pump_in") != 0 && strcmp(target->valuestring, "pump_out") != 0) ||
        !cJSON_IsBool(enabled) || !cJSON_IsNumber(expiry)) {
        cJSON_Delete(root);
        return;
    }
    char command_id[37];
    memcpy(command_id, id->valuestring, sizeof(command_id));
    for (int i = 0; i < 36; i++) {
        bool dash = i == 8 || i == 13 || i == 18 || i == 23;
        char ch = command_id[i];
        if (dash ? ch != '-' : !((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
            cJSON_Delete(root);
            return;
        }
    }
    bool inlet = strcmp(target->valuestring, "pump_in") == 0;
    struct timeval now;
    gettimeofday(&now, NULL);
    int64_t now_ms = (int64_t)now.tv_sec * 1000 + now.tv_usec / 1000;
    if (!isfinite(expiry->valuedouble) || floor(expiry->valuedouble) != expiry->valuedouble ||
        !clock_ready() || expiry->valuedouble <= (double)now_ms ||
        expiry->valuedouble > (double)now_ms + 60000) {
        mqtt_v2_pump_ack(command_id, "rejected");
        cJSON_Delete(root);
        return;
    }
    bool on = cJSON_IsTrue(enabled);
    // Reserve durably before actuation. After a crash, an uncertain reservation
    // replays as rejected, never as another relay transition.
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    command_ledger_t ledger = {0};
    size_t size = sizeof(ledger);
    esp_err_t err = nvs_get_blob(s_network_nvs, "cmd_log", &ledger, &size);
    if (err == ESP_ERR_NVS_NOT_FOUND) { ledger.version = 1; err = ESP_OK; }
    if (err != ESP_OK || ledger.version != 1 || size != sizeof(ledger)) {
        xSemaphoreGive(s_relay_lock);
        mqtt_v2_pump_ack(command_id, "rejected");
        cJSON_Delete(root);
        return;
    }
    int slot = -1;
    for (int i = 0; i < COMMAND_LEDGER_SIZE; i++) {
        command_record_t *record = &ledger.records[i];
        if (memcmp(record->id, command_id, sizeof(record->id)) == 0) {
            bool same = record->inlet == inlet && record->enabled == on &&
                record->expires_at_ms == (int64_t)expiry->valuedouble;
            const char *result = same && record->result == 1 ? "applied" : "rejected";
            xSemaphoreGive(s_relay_lock);
            mqtt_v2_pump_ack(command_id, result);
            cJSON_Delete(root);
            return;
        }
        if (slot < 0 && (!record->id[0] || record->expires_at_ms <= now_ms)) slot = i;
    }
    if (slot < 0) {
        xSemaphoreGive(s_relay_lock);
        mqtt_v2_pump_ack(command_id, "rejected"); // Never evict an unexpired command.
        cJSON_Delete(root);
        return;
    }
    command_record_t *record = &ledger.records[slot];
    memset(record, 0, sizeof(*record));
    memcpy(record->id, command_id, sizeof(record->id));
    record->inlet = inlet;
    record->enabled = on;
    record->expires_at_ms = (int64_t)expiry->valuedouble;
    err = nvs_set_blob(s_network_nvs, "cmd_log", &ledger, sizeof(ledger));
    if (err == ESP_OK) err = nvs_commit(s_network_nvs);
    xSemaphoreGive(s_relay_lock);
    if (err == ESP_OK) {
        err = set_pumps(inlet && on, !inlet && on, false, false, inlet ? 1 : 2);
        record->result = err == ESP_OK ? 1 : 2;
        xSemaphoreTake(s_relay_lock, portMAX_DELAY);
        esp_err_t saved = nvs_set_blob(s_network_nvs, "cmd_log", &ledger, sizeof(ledger));
        if (saved == ESP_OK) saved = nvs_commit(s_network_nvs);
        xSemaphoreGive(s_relay_lock);
        if (saved != ESP_OK) {
            err = saved;
            if (on) set_pumps(false, false, false, true, 0);
        }
    }
    mqtt_v2_pump_ack(command_id, err == ESP_OK ? "applied" : "rejected");
    cJSON_Delete(root);
}

static void mqtt_receive(esp_mqtt_event_handle_t event)
{
    if (!mqtt_control_ready()) {
        memset(&s_mqtt_rx, 0, sizeof(s_mqtt_rx));
        ESP_LOGW(TAG, "MQTT command blocked: no active broker connection");
        return;
    }
    int result = pool_mqtt_rx_feed(&s_mqtt_rx, event->topic, event->topic_len, event->data,
        event->data_len, event->total_data_len, event->current_data_offset, event->retain);
    if (result < 0) { ESP_LOGW(TAG, "Rejected invalid/oversized MQTT frame"); return; }
    if (result == 0) return;
    // API v2 is the only configuration authority. Never execute global legacy topics.
    if (strcmp(s_mqtt_rx.topic, TOPIC_CMD_PUMP) != 0) return;
    if (strcmp(s_mqtt_rx.topic, TOPIC_CMD_PUMP) == 0) {
        mqtt_v2_pump_command(&s_mqtt_rx);
        return;
    }
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
            err = set_sensor_enabled(command.kind == POOL_MQTT_TEMPERATURE ? "temperature" : "water", command.on, true);
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
        if (!mqtt_control_ready()) { free(message); continue; }
        pool_mqtt_command_t command;
        bool valid = pool_mqtt_decode(message->topic, message->payload, message->received,
                                       message->retained, &s_mqtt_settings, &command);
        free(message);
        if (!valid) { mqtt_ack(&command, false, command.error_message ? command.error_message : "Invalid topic, action or configuration values; nothing applied"); continue; }
        if (command.kind == POOL_MQTT_SCAN) { mqtt_scan(); continue; }
        if (command.kind == POOL_MQTT_AUTOMATION) {
            int channel = command.automation_channel - 1;
            if (command.req_id[0] && strcmp(command.req_id, s_last_automation_req_ids[channel]) == 0) {
                mqtt_ack(&command, true, "Duplicate request already applied.");
                continue;
            }
            esp_err_t err = pool_network_configure_pump(channel, &command.automation);
            if (err == ESP_OK && command.req_id[0]) {
                memcpy(s_last_automation_req_ids[channel], command.req_id,
                       sizeof(s_last_automation_req_ids[channel]));
            }
            mqtt_ack(&command, err == ESP_OK, err == ESP_OK
                ? "Automation saved to NVS and applied. Water thresholds use raw ADC 0-4095."
                : err == ESP_ERR_NOT_SUPPORTED
                ? "Relay or sensor unavailable; automation was not applied."
                : "Automation could not be saved or applied; nothing was activated.");
            continue;
        }
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

static bool post_live_status(void)
{
    if (!POOL_HTTP_ENABLED || !pool_identity_ready() || s_provisioning_pending) return false;
    char api_url[257], ssid[33] = "", ip_text[16] = "";
    bool pump_in, pump_out, temperature_enabled, water_enabled;
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    snprintf(api_url, sizeof(api_url), "%s", s_mqtt_settings.api_url);
    pump_in = s_pump_in;
    pump_out = s_pump_out;
    temperature_enabled = s_temperature_enabled;
    water_enabled = s_water_enabled;
    xSemaphoreGive(s_relay_lock);
    wifi_config_t wifi = {0};
    if (esp_wifi_get_config(WIFI_IF_STA, &wifi) == ESP_OK) {
        memcpy(ssid, wifi.sta.ssid, sizeof(wifi.sta.ssid));
        ssid[32] = 0;
    }
    esp_netif_t *netif = esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
    esp_netif_ip_info_t ip;
    if (netif && esp_netif_get_ip_info(netif, &ip) == ESP_OK)
        snprintf(ip_text, sizeof(ip_text), IPSTR, IP2STR(&ip.ip));
    cJSON *body = cJSON_CreateObject();
    if (!body) return false;
    cJSON_AddStringToObject(body, "action", "report_status");
    cJSON_AddStringToObject(body, "device_id", pool_identity_id());
    cJSON_AddStringToObject(body, "factory_mac", pool_identity_mac());
    cJSON_AddStringToObject(body, "boot_id", s_boot_id);
    cJSON_AddNumberToObject(body, "state_sequence", ++s_state_sequence);
    cJSON_AddBoolToObject(body, "pump_in", pump_in);
    cJSON_AddBoolToObject(body, "pump_out", pump_out);
    cJSON_AddBoolToObject(body, "temperature_enabled", temperature_enabled);
    cJSON_AddBoolToObject(body, "water_enabled", water_enabled);
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    if (s_server_config_valid) cJSON_AddNumberToObject(body, "reported_config_revision", s_server_config.revision);
    xSemaphoreGive(s_relay_lock);
    if (clock_ready()) {
        struct timeval now;
        gettimeofday(&now, NULL);
        cJSON_AddNumberToObject(body, "reported_at_ms", (double)now.tv_sec * 1000 + now.tv_usec / 1000);
    }
    if (s_config_report_pending) {
        cJSON *apply = cJSON_AddObjectToObject(body, "config_apply");
        if (!apply) { cJSON_Delete(body); return false; }
        cJSON_AddNumberToObject(apply, "attempt_sequence", s_config_attempt);
        cJSON_AddNumberToObject(apply, "revision", s_attempt_revision);
        cJSON_AddStringToObject(apply, "result", s_config_error ? "error" : "applied");
        if (s_config_error) cJSON_AddStringToObject(apply, "error_code", s_config_error);
    }
    if (ssid[0]) cJSON_AddStringToObject(body, "wifi_ssid", ssid);
    if (ip_text[0]) cJSON_AddStringToObject(body, "lan_ip", ip_text);
    char *json = cJSON_PrintUnformatted(body);
    cJSON_Delete(body);
    if (!json) return false;
    config_response_t response = {.data = calloc(1, CONFIG_RESPONSE_LIMIT + 1)};
    if (!response.data) { free(json); return false; }
    esp_http_client_config_t config = {
        .url = api_url,
        .timeout_ms = 10000,
        .crt_bundle_attach = esp_crt_bundle_attach,
        .alpn_protos = s_http_alpn, .disable_auto_redirect = true,
        .event_handler = config_http_event, .user_data = &response,
    };
    esp_http_client_handle_t http = esp_http_client_init(&config);
    if (!http) { free(json); free(response.data); return false; }
    esp_http_client_set_method(http, HTTP_METHOD_POST);
    esp_http_client_set_header(http, "Content-Type", "application/json");
    esp_http_client_set_header(http, "X-Pool-Device-Key", pool_identity_key());
    esp_http_client_set_post_field(http, json, strlen(json));
    esp_err_t err = esp_http_client_perform(http);
    int status = esp_http_client_get_status_code(http);
    esp_http_client_cleanup(http);
    free(json);
    cJSON *reply = err == ESP_OK && status == 200 ? api_response_success(&response) : NULL;
    const cJSON *state = cJSON_GetObjectItemCaseSensitive(reply, "state");
    const cJSON *device = cJSON_GetObjectItemCaseSensitive(state, "device_id");
    const cJSON *boot = cJSON_GetObjectItemCaseSensitive(state, "boot_id");
    const cJSON *sequence = cJSON_GetObjectItemCaseSensitive(state, "state_sequence");
    bool accepted = cJSON_IsString(device) && !strcmp(device->valuestring, pool_identity_id()) &&
        cJSON_IsString(boot) && !strcmp(boot->valuestring, s_boot_id) &&
        cJSON_IsNumber(sequence) && sequence->valuedouble == s_state_sequence;
    cJSON_Delete(reply);
    free(response.data);
    if (!accepted) {
        ESP_LOGW(TAG, "HTTP status report failed: %s, status=%d", esp_err_to_name(err), status);
        return false;
    }
    return true;
}

static void telemetry_task(void *arg)
{
    while (1) {
        network_sample_t work;
        if (xQueueReceive(s_pending_samples, &work, pdMS_TO_TICKS(1000)) == pdTRUE) {
            telemetry_sample_t sample = work.sample;
            sample.id = ((uint64_t)s_boot_nonce << 32) | ++s_sample_counter;
            char json[320];
            sample_to_json(&sample, json, sizeof(json), true, false);
            if (!s_provisioning_pending && s_mqtt_connected && work.measurement) {
                // MQTT telemetry is device scoped; state-only events use status.
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
                    cJSON_AddNumberToObject(telemetry, "water_state", water_state);
                }
                char *payload = telemetry ? cJSON_PrintUnformatted(telemetry) : NULL;
                if (payload) esp_mqtt_client_enqueue(s_mqtt, TOPIC_TELEMETRY, payload, 0, 1, 0, true);
                free(payload);
                cJSON_Delete(telemetry);
            }
            if (POOL_HTTP_ENABLED && work.measurement &&
                (sample.flags & (TELEMETRY_HAS_TEMPERATURE | TELEMETRY_HAS_WATER))) {
                bool sent = telemetry_queue_count() == 0 && s_wifi_connected && post_sample(&sample, false);
                if (!sent) queue_sample(&sample);
            }
        }
        if (POOL_HTTP_ENABLED && pool_identity_ready() && !s_provisioning_pending && s_wifi_connected) flush_queued_samples();
    }
}

// Stop and persist as one managed configuration transition. No HTTP under the relay lock.
static const char *apply_server_config(const pool_server_config_t *next, bool persist)
{
    const char *error = NULL;
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    if (s_server_config_valid && next->revision < s_server_config.revision) error = "REVISION_CONFLICT";
    if (!error && s_server_config_valid && next->revision == s_server_config.revision) {
        bool same = pool_server_config_equal(next, &s_server_config);
        xSemaphoreGive(s_relay_lock);
        return same ? NULL : "REVISION_CONFLICT";
    }
    for (int i = 0; !error && i < 2; i++) {
        if (next->automation[i].enabled && !automation_available(i, &next->automation[i]))
            error = "FEATURE_UNAVAILABLE";
    }
    if (!error) {
        esp_err_t first = relay_set(RELAY_1, false);
        esp_err_t second = relay_set(RELAY_2, false);
        if (first == ESP_OK) { s_pump_in = false; s_local_pump_in = false; }
        if (second == ESP_OK) { s_pump_out = false; s_local_pump_out = false; }
        for (int i = 0; i < 2; i++) pump_auto_pause(&s_auto_runtime[i]);
        if (first != ESP_OK || second != ESP_OK) error = "RELAY_STOP_FAILED";
    }
    if (!error && persist && pool_server_config_save(s_network_nvs, next) != ESP_OK) error = "NVS_WRITE_FAILED";
    if (!error) {
        s_server_config = *next;
        s_server_config_valid = true;
        s_mqtt_settings.temp_min = next->temperature_min;
        s_mqtt_settings.temp_max = next->temperature_max;
        s_mqtt_settings.water_min = next->water_adc_min;
        s_mqtt_settings.water_max = next->water_adc_max;
        s_mqtt_settings.interlock = next->interlock;
        memcpy(s_auto_config, next->automation, sizeof(s_auto_config));
        for (int i = 0; i < 2; i++) {
            pump_auto_reset(&s_auto_runtime[i]);
            s_auto_wait_after_us[i] = s_last_sample_us; // Wait for a fresh measurement.
        }
    }
    xSemaphoreGive(s_relay_lock);
    s_sample_requested = true;
    s_status_pending = true;
    return error;
}

static esp_err_t config_http_event(esp_http_client_event_t *event)
{
    config_response_t *response = event->user_data;
    if (event->event_id != HTTP_EVENT_ON_DATA || !response || event->data_len <= 0) return ESP_OK;
    if (response->used + (size_t)event->data_len > CONFIG_RESPONSE_LIMIT) {
        response->overflow = true;
        return ESP_FAIL;
    }
    memcpy(response->data + response->used, event->data, event->data_len);
    response->used += event->data_len;
    response->data[response->used] = 0;
    return ESP_OK;
}

static cJSON *api_response_success(const config_response_t *response)
{
    cJSON *root = !response->overflow ? cJSON_ParseWithLength(response->data, response->used) : NULL;
    const cJSON *status = cJSON_GetObjectItemCaseSensitive(root, "status");
    const cJSON *version = cJSON_GetObjectItemCaseSensitive(root, "api_version");
    if (cJSON_IsString(status) && !strcmp(status->valuestring, "success") &&
        cJSON_IsNumber(version) && version->valuedouble == 2) return root;
    cJSON_Delete(root);
    return NULL;
}

static void poll_server_config(void)
{
    if (!pool_identity_ready() || s_provisioning_pending) return;
    char api_url[257];
    xSemaphoreTake(s_relay_lock, portMAX_DELAY);
    snprintf(api_url, sizeof(api_url), "%s", s_mqtt_settings.api_url);
    xSemaphoreGive(s_relay_lock);
    config_response_t response = {.data = calloc(1, CONFIG_RESPONSE_LIMIT + 1)};
    if (!response.data) return;
    char request[224];
    snprintf(request, sizeof(request), "{\"action\":\"get_device_config\",\"device_id\":\"%s\",\"factory_mac\":\"%s\"}", pool_identity_id(), pool_identity_mac());
    esp_http_client_config_t config = {
        .url = api_url, .timeout_ms = 10000, .crt_bundle_attach = esp_crt_bundle_attach,
        .alpn_protos = s_http_alpn, .disable_auto_redirect = true,
        .event_handler = config_http_event, .user_data = &response,
    };
    esp_http_client_handle_t http = esp_http_client_init(&config);
    if (!http) { free(response.data); return; }
    esp_http_client_set_method(http, HTTP_METHOD_POST);
    esp_http_client_set_header(http, "Content-Type", "application/json");
    esp_http_client_set_header(http, "X-Pool-Device-Key", pool_identity_key());
    esp_http_client_set_post_field(http, request, strlen(request));
    esp_err_t result = esp_http_client_perform(http);
    int status = esp_http_client_get_status_code(http);
    esp_http_client_cleanup(http);
    cJSON *root = result == ESP_OK && status == 200 ? api_response_success(&response) : NULL;
    const cJSON *success = cJSON_GetObjectItemCaseSensitive(root, "status");
    const cJSON *object = cJSON_GetObjectItemCaseSensitive(root, "config");
    const cJSON *reset=cJSON_GetObjectItemCaseSensitive(root,"reset_command");
    if(cJSON_IsObject(reset)) {
        handle_remote_reset(reset,api_url);
        cJSON_Delete(root);free(response.data);return;
    }
    if(s_config_report_pending){cJSON_Delete(root);free(response.data);return;}
    pool_server_config_t next;
    if (cJSON_IsString(success) && strcmp(success->valuestring, "success") == 0 && cJSON_IsObject(object)) {
        bool valid = pool_server_config_parse(object, pool_identity_id(), &next);
        const cJSON *revision = cJSON_GetObjectItemCaseSensitive(object, "revision");
        const cJSON *id = cJSON_GetObjectItemCaseSensitive(object, "device_id");
        if (cJSON_IsString(id) && strcmp(id->valuestring, pool_identity_id()) == 0 &&
            cJSON_IsNumber(revision) && isfinite(revision->valuedouble) &&
            revision->valuedouble >= 1 && revision->valuedouble <= 9007199254740991.0 &&
            floor(revision->valuedouble) == revision->valuedouble) {
            xSemaphoreTake(s_relay_lock, portMAX_DELAY);
            bool already = valid && s_server_config_valid && pool_server_config_equal(&next, &s_server_config);
            xSemaphoreGive(s_relay_lock);
            if (!already) {
                s_config_error = valid ? apply_server_config(&next, true) : "INVALID_CONFIG";
                s_attempt_revision = (uint64_t)revision->valuedouble;
                ++s_config_attempt;
                s_config_report_pending = true;
                s_status_pending = true;
                publish_status();
            }
        }
    } else {
        // Never log response bodies: credentials or proxy diagnostics may be included.
        ESP_LOGW(TAG, "Config fetch unavailable: %s, status=%d", esp_err_to_name(result), status);
    }
    cJSON_Delete(root);
    free(response.data);
}

static void control_http_task(void *arg)
{
    int64_t last_status = 0, last_config = 0, last_presence = 0;
    bool had_wifi = false;
    while (1) {
        int64_t now = esp_timer_get_time();
        if(s_resetting) {
            if(s_wifi_connected&&s_sntp_ready&&atomic_exchange(&s_sntp_start_requested,false))esp_netif_sntp_start();
            if(s_reset_storage_cleared&&s_wifi_connected) {
                if(post_reset_receipt("cleared",NULL))finish_remote_reset();
            } else if(s_has_wifi_credentials&&(atomic_exchange(&s_connect_requested,false)||now-last_config>=5000000)) {
                last_config=now;esp_wifi_connect();
            }
            vTaskDelay(pdMS_TO_TICKS(2000));continue;
        }
        if (pool_identity_ready() && !s_provisioning_pending && s_mqtt_connected && (!last_presence || now - last_presence >= 30000000)) {
            publish_status_frame(false);
            last_presence = now;
        }
        if (!s_mqtt_connected) last_presence = 0;
        if (POOL_HTTP_ENABLED && pool_identity_ready() && !s_provisioning_pending && s_wifi_connected) {
            if (!had_wifi) { s_config_poll_requested = true; s_status_pending = true; }
            // Keep retrying the same apply attempt until the API commits its report.
            if (atomic_exchange(&s_config_poll_requested, false) ||
                !last_config || now - last_config >= 30000000) {
                last_config = now;
                poll_server_config();
            }
            now = esp_timer_get_time();
            if ((!last_status || now - last_status >= 30000000 || s_status_pending) &&
                (!last_status || now - last_status >= 5000000)) {
                atomic_exchange(&s_status_pending, false);
                last_status = now;
                if (post_live_status()) s_config_report_pending = false;
                else s_status_pending = true;
            }
        }
        had_wifi = s_wifi_connected;
        vTaskDelay(pdMS_TO_TICKS(500));
    }
}

bool pool_network_reset_pending(void){return s_resetting;}

static bool post_reset_receipt(const char *result,const char *error_code)
{
    cJSON *body=cJSON_CreateObject();
    if(!body)return false;
    cJSON_AddStringToObject(body,"action","acknowledge_device_reset");
    cJSON_AddStringToObject(body,"job_id",s_reset_job.job_id);
    cJSON_AddStringToObject(body,"factory_mac",s_reset_job.factory_mac);
    cJSON_AddStringToObject(body,"reset_token",s_reset_job.token);
    cJSON_AddStringToObject(body,"result",result);
    if(error_code)cJSON_AddStringToObject(body,"error_code",error_code);
    char *json=cJSON_PrintUnformatted(body);cJSON_Delete(body);if(!json)return false;
    config_response_t response={.data=calloc(1,CONFIG_RESPONSE_LIMIT+1)};
    if(!response.data){free(json);return false;}
    char url[257];
    xSemaphoreTake(s_relay_lock,portMAX_DELAY);
    snprintf(url,sizeof(url),"%s",s_reset_job.api_url);
    xSemaphoreGive(s_relay_lock);
    esp_http_client_config_t cfg={.url=url,.timeout_ms=10000,.crt_bundle_attach=esp_crt_bundle_attach,
        .alpn_protos=s_http_alpn,.disable_auto_redirect=true,.event_handler=config_http_event,.user_data=&response};
    esp_http_client_handle_t http=esp_http_client_init(&cfg);
    if(!http){free(json);free(response.data);return false;}
    esp_http_client_set_method(http,HTTP_METHOD_POST);
    esp_http_client_set_header(http,"Content-Type","application/json");
    esp_http_client_set_post_field(http,json,strlen(json));
    esp_err_t error=esp_http_client_perform(http);
    int status=esp_http_client_get_status_code(http);
    esp_http_client_cleanup(http);free(json);
    cJSON *reply=error==ESP_OK&&status==200?api_response_success(&response):NULL;
    const cJSON *job=cJSON_GetObjectItemCaseSensitive(reply,"job_id");
    bool accepted=cJSON_IsString(job)&&!strcmp(job->valuestring,s_reset_job.job_id)&&
        (strcmp(result,"cleared")||cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(reply,"reset_recorded")));
    cJSON_Delete(reply);free(response.data);return accepted;
}

static void finish_remote_reset(void)
{
    if(pool_reset_forget()!=ESP_OK)return; // Keep recovery marker until receipt AND durable cleanup.
    esp_wifi_disconnect();
    esp_wifi_stop();
    esp_restart();
}

static void handle_remote_reset(const cJSON *command,const char *api_url)
{
    const cJSON *id=cJSON_GetObjectItemCaseSensitive(command,"device_id");
    const cJSON *mac=cJSON_GetObjectItemCaseSensitive(command,"factory_mac");
    const cJSON *job=cJSON_GetObjectItemCaseSensitive(command,"job_id");
    const cJSON *token=cJSON_GetObjectItemCaseSensitive(command,"reset_token");
    const cJSON *expiry=cJSON_GetObjectItemCaseSensitive(command,"expires_at_ms");
    struct timeval tv;gettimeofday(&tv,NULL);
    double now=(double)tv.tv_sec*1000+tv.tv_usec/1000;
    if(!cJSON_IsString(id)||strcmp(id->valuestring,pool_identity_id())||
       !cJSON_IsString(mac)||strcmp(mac->valuestring,pool_identity_mac())||
       !cJSON_IsString(job)||strlen(job->valuestring)!=36||
       !cJSON_IsString(token)||strlen(token->valuestring)!=64||
       !cJSON_IsNumber(expiry)||!isfinite(expiry->valuedouble)||!clock_ready()||
       expiry->valuedouble<=now||expiry->valuedouble>now+65000)return;
    memset(&s_reset_job,0,sizeof(s_reset_job));s_reset_job.version=1;
    snprintf(s_reset_job.job_id,sizeof(s_reset_job.job_id),"%s",job->valuestring);
    snprintf(s_reset_job.token,sizeof(s_reset_job.token),"%s",token->valuestring);
    snprintf(s_reset_job.api_url,sizeof(s_reset_job.api_url),"%s",api_url);
    snprintf(s_reset_job.device_id,sizeof(s_reset_job.device_id),"%s",id->valuestring);
    snprintf(s_reset_job.factory_mac,sizeof(s_reset_job.factory_mac),"%s",mac->valuestring);
    if(pool_reset_save(&s_reset_job)!=ESP_OK){post_reset_receipt("error","NVS_CLEAR_FAILED");return;}
    s_resetting=true;s_provisioning_pending=true;
    if(set_pumps(false,false,true,false,0)!=ESP_OK){post_reset_receipt("error","RELAY_STOP_FAILED");esp_restart();return;}
    wifi_config_t old_station={0};
    esp_wifi_get_config(WIFI_IF_STA,&old_station);
    if(s_mqtt&&s_mqtt_started)esp_mqtt_client_stop(s_mqtt);
    s_mqtt_started=false;s_mqtt_connected=false;
    if(wifi_setup_suspend_http()!=ESP_OK){post_reset_receipt("error","NVS_CLEAR_FAILED");esp_restart();return;}
    xSemaphoreTake(s_relay_lock,portMAX_DELAY);
    esp_err_t error=pool_reset_clear_app();
    xSemaphoreGive(s_relay_lock);
    if(error!=ESP_OK){post_reset_receipt("error","NVS_CLEAR_FAILED");esp_restart();return;}
    s_station_enabled=false;
    esp_wifi_disconnect();
    error=esp_wifi_restore(); // Erase Wi-Fi driver configuration stored on flash.
    if(error!=ESP_OK){post_reset_receipt("error","WIFI_RESET_FAILED");esp_restart();return;}
    error=esp_wifi_set_storage(WIFI_STORAGE_RAM);
    if(error==ESP_OK)error=wifi_setup_reconfigure_ap();
    if(error==ESP_OK)error=wifi_setup_init(credentials_saved);
    if(error!=ESP_OK){esp_restart();return;}
    s_reset_storage_cleared=true;
    // Temporary RAM-only association delivers the post-erase receipt. No old SSID/PSK returns to NVS.
    if(old_station.sta.ssid[0]&&esp_wifi_set_config(WIFI_IF_STA,&old_station)==ESP_OK){
        s_has_wifi_credentials=true;s_station_enabled=true;s_connect_requested=true;
    }else{s_has_wifi_credentials=false;}
    memset(&old_station,0,sizeof(old_station));
}
