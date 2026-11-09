#include "pool_network.h"

#include <stdio.h>
#include <string.h>
#include <stdatomic.h>
#include <inttypes.h>
#include <stdint.h>
#include <time.h>

#include "cJSON.h"
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
#include "mqtt_client.h"
#include "mqtt_credentials.h"
#include "nvs_flash.h"
#include "relay.h"
#include "sdkconfig.h"
#include "telemetry_queue.h"
#include "wifi_setup.h"

#define TOPIC_CMD_IN   "smartpool/cmd/bom_vao"
#define TOPIC_CMD_OUT  "smartpool/cmd/bom_ra"
#define TOPIC_STATUS   "smartpool/status"
#define TOPIC_TELEMETRY "smartpool/telemetry"

static const char *TAG = "pool_network";
static esp_mqtt_client_handle_t s_mqtt;
static _Atomic bool s_mqtt_started;
static _Atomic bool s_has_wifi_credentials;
static _Atomic unsigned int s_wifi_failures;
static _Atomic bool s_wifi_connected;
static _Atomic bool s_mqtt_connected;
static _Atomic bool s_pump_in;
static _Atomic bool s_pump_out;
static uint32_t s_boot_nonce;
static uint32_t s_sample_counter;
static SemaphoreHandle_t s_relay_lock;
#if CONFIG_POOL_CONTROL_HTTP
static uint32_t s_http_last_revision;
static _Atomic bool s_http_baselined;
static void http_command_task(void *arg);
#endif

static void publish_status(void)
{
    if (!s_mqtt_connected) {
        return;
    }
    char json[96];
    snprintf(json, sizeof(json),
             "{\"online\":1,\"bom_vao_control\":%d,\"bom_ra_control\":%d}",
             s_pump_in ? 1 : 0, s_pump_out ? 1 : 0);
    esp_mqtt_client_enqueue(s_mqtt, TOPIC_STATUS, json, 0, 1, 1, true);
}

#if CONFIG_POOL_CONTROL_MQTT
static bool is_topic(const esp_mqtt_event_handle_t event, const char *topic)
{
    size_t length = strlen(topic);
    return event->topic_len == length && memcmp(event->topic, topic, length) == 0;
}
#endif

static esp_err_t set_pumps(bool pump_in, bool pump_out)
{
    if (pump_in && pump_out) return ESP_ERR_INVALID_ARG;
    if (xSemaphoreTake(s_relay_lock, portMAX_DELAY) != pdTRUE) return ESP_FAIL;
    esp_err_t err = ESP_OK;
    if (!pump_in) err = relay_set(RELAY_1, false);
    if (err == ESP_OK && !pump_out) err = relay_set(RELAY_2, false);
    if (err == ESP_OK && pump_in) err = relay_set(RELAY_1, true);
    if (err == ESP_OK && pump_out) err = relay_set(RELAY_2, true);
    if (err == ESP_OK) {
        s_pump_in = pump_in;
        s_pump_out = pump_out;
    } else {
        relay_set(RELAY_1, false);
        relay_set(RELAY_2, false);
        s_pump_in = false;
        s_pump_out = false;
    }
    xSemaphoreGive(s_relay_lock);
    publish_status();
    return err;
}

static void mqtt_event_handler(void *arg, esp_event_base_t base, int32_t event_id, void *data)
{
    esp_mqtt_event_handle_t event = data;
    switch ((esp_mqtt_event_id_t)event_id) {
    case MQTT_EVENT_CONNECTED:
        s_mqtt_connected = true;
#if CONFIG_POOL_CONTROL_MQTT
        esp_mqtt_client_subscribe(s_mqtt, TOPIC_CMD_IN, 1);
        esp_mqtt_client_subscribe(s_mqtt, TOPIC_CMD_OUT, 1);
#endif
        publish_status();
        ESP_LOGI(TAG, "MQTT connected");
        break;
    case MQTT_EVENT_DISCONNECTED:
        s_mqtt_connected = false;
#if CONFIG_POOL_CONTROL_MQTT
        set_pumps(false, false);
        ESP_LOGW(TAG, "MQTT disconnected; pumps OFF");
#endif
        break;
    case MQTT_EVENT_ERROR:
        if (event->error_handle) {
            ESP_LOGE(TAG, "MQTT error: type=%d, TLS=%s, broker_code=%d",
                     event->error_handle->error_type,
                     esp_err_to_name(event->error_handle->esp_tls_last_esp_err),
                     event->error_handle->connect_return_code);
        }
        break;
    case MQTT_EVENT_DATA: {
#if CONFIG_POOL_CONTROL_MQTT
        if (event->total_data_len != event->data_len || event->data_len != 1 ||
            (event->data[0] != '0' && event->data[0] != '1')) {
            ESP_LOGW(TAG, "Ignored invalid pump command");
            break;
        }
        bool pump_in = is_topic(event, TOPIC_CMD_IN);
        bool pump_out = is_topic(event, TOPIC_CMD_OUT);
        if (!pump_in && !pump_out) {
            break;
        }
        bool on = event->data[0] == '1';
        if (on && !s_wifi_connected) break;
        bool desired_in = pump_in ? on : s_pump_in;
        bool desired_out = pump_out ? on : s_pump_out;
        if (desired_in && desired_out) {
            ESP_LOGW(TAG, "Cannot run both pumps together");
            publish_status();
            break;
        }
        esp_err_t err = set_pumps(desired_in, desired_out);
        if (err == ESP_OK) {
            ESP_LOGI(TAG, "%s %s", pump_in ? "Pump in" : "Pump out", on ? "ON" : "OFF");
        } else {
            ESP_LOGE(TAG, "Relay control failed: %s", esp_err_to_name(err));
        }
#endif
        break;
    }
    default:
        break;
    }
}

static void credentials_saved(void)
{
    s_has_wifi_credentials = true;
    s_wifi_failures = 0;
    esp_wifi_disconnect();
    esp_err_t err = esp_wifi_connect();
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Wi-Fi connection attempt: %s", esp_err_to_name(err));
    }
}

static void wifi_retry_task(void *arg)
{
    unsigned int seconds = 0;
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        seconds = (seconds + 1) % 30;
        if (seconds % 5 == 0 && s_wifi_connected && s_mqtt && !s_mqtt_started &&
            time(NULL) >= 1704067200) {
            esp_err_t err = esp_mqtt_client_start(s_mqtt);
            if (err == ESP_OK) {
                s_mqtt_started = true;
                ESP_LOGI(TAG, "MQTT TLS started after time synchronization");
            } else {
                ESP_LOGE(TAG, "Cannot start MQTT: %s", esp_err_to_name(err));
            }
        }
        if (seconds == 0 && s_has_wifi_credentials && !s_wifi_connected &&
            s_wifi_failures >= 3) {
            ESP_LOGI(TAG, "Retrying saved Wi-Fi while setup AP stays available");
            esp_wifi_connect();
        }
        if (seconds == 0 && s_wifi_connected && s_mqtt && !s_mqtt_started &&
            time(NULL) < 1704067200) {
            ESP_LOGW(TAG, "Waiting for SNTP time; MQTT TLS not started yet");
        }
    }
}

static void wifi_event_handler(void *arg, esp_event_base_t base, int32_t event_id, void *data)
{
    if (base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        if (s_has_wifi_credentials) esp_wifi_connect();
    } else if (base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        s_wifi_connected = false;
#if CONFIG_POOL_CONTROL_HTTP
        s_http_baselined = false;
#endif
        s_mqtt_connected = false;
        // Stop both pumps when network control is unavailable.
        set_pumps(false, false);
        if (s_has_wifi_credentials && s_wifi_failures < 3) s_wifi_failures++;
        if (s_has_wifi_credentials && s_wifi_failures < 3) {
            esp_wifi_connect();
        } else {
            esp_err_t err = wifi_setup_enable_ap();
            if (err != ESP_OK) ESP_LOGE(TAG, "Cannot enable setup AP: %s", esp_err_to_name(err));
        }
        ESP_LOGW(TAG, "Wi-Fi disconnected; pumps OFF");
    } else if (base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        s_wifi_connected = true;
        s_wifi_failures = 0;
        const ip_event_got_ip_t *ip_event = data;
        ESP_LOGI(TAG, "Wi-Fi got IP: " IPSTR, IP2STR(&ip_event->ip_info.ip));
        esp_err_t ap_err = wifi_setup_disable_ap();
        if (ap_err != ESP_OK) ESP_LOGW(TAG, "Cannot disable setup AP: %s", esp_err_to_name(ap_err));
        if (s_mqtt && !s_mqtt_started) ESP_LOGI(TAG, "Waiting for clock sync before MQTT TLS");
    }
}

esp_err_t pool_network_init(void)
{
    s_relay_lock = xSemaphoreCreateMutex();
    if (!s_relay_lock) return ESP_ERR_NO_MEM;
    esp_err_t err = nvs_flash_init();
    if (err != ESP_OK) return err;
    err = telemetry_queue_init();
    if (err != ESP_OK) ESP_LOGE(TAG, "Persistent telemetry queue unavailable: %s", esp_err_to_name(err));
    err = esp_netif_init();
    if (err != ESP_OK) return err;
    err = esp_event_loop_create_default();
    if (err != ESP_OK) return err;
    if (!esp_netif_create_default_wifi_sta()) return ESP_FAIL;
    if (!esp_netif_create_default_wifi_ap()) return ESP_FAIL;
    esp_sntp_config_t sntp_config = ESP_NETIF_SNTP_DEFAULT_CONFIG("pool.ntp.org");
    err = esp_netif_sntp_init(&sntp_config);
    if (err != ESP_OK) ESP_LOGW(TAG, "Cannot start SNTP: %s", esp_err_to_name(err));

    wifi_init_config_t wifi_init = WIFI_INIT_CONFIG_DEFAULT();
    err = esp_wifi_init(&wifi_init);
    if (err != ESP_OK) return err;
    s_boot_nonce = esp_random();
    err = esp_wifi_set_storage(WIFI_STORAGE_FLASH);
    if (err != ESP_OK) return err;
    err = esp_wifi_set_mode(WIFI_MODE_STA);
    if (err != ESP_OK) return err;

    wifi_config_t saved_wifi = {0};
    err = esp_wifi_get_config(WIFI_IF_STA, &saved_wifi);
    if (err != ESP_OK) return err;
    s_has_wifi_credentials = saved_wifi.sta.ssid[0] != '\0';
    if (!s_has_wifi_credentials) {
        err = wifi_setup_enable_ap();
        if (err != ESP_OK) return err;
    }

    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                                wifi_event_handler, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                                wifi_event_handler, NULL));

    const esp_mqtt_client_config_t mqtt_config = {
        .broker.address.uri = CONFIG_POOL_MQTT_URI,
        .broker.verification.crt_bundle_attach = esp_crt_bundle_attach,
        .credentials.username = SMARTPOOL_MQTT_USERNAME,
        .credentials.authentication.password = SMARTPOOL_MQTT_PASSWORD,
        .session.last_will.topic = TOPIC_STATUS,
        .session.last_will.msg = "{\"online\":0,\"bom_vao_control\":0,\"bom_ra_control\":0}",
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
    err = esp_wifi_start();
    if (err != ESP_OK) return err;
    err = wifi_setup_init(credentials_saved);
    if (err != ESP_OK) return err;
    ESP_LOGI(TAG, "HTTP API target: %s", CONFIG_POOL_API_URL);
    if (xTaskCreate(wifi_retry_task, "wifi_retry", 3072, NULL, 4, NULL) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
#if CONFIG_POOL_CONTROL_HTTP
    if (xTaskCreate(http_command_task, "http_commands", 4096, NULL, 4, NULL) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
#endif
#if CONFIG_POOL_CONTROL_HTTP
    ESP_LOGI(TAG, "Pump control mode: HTTP");
#else
    ESP_LOGI(TAG, "Pump control mode: MQTT");
#endif
    return ESP_OK;
}

#if CONFIG_POOL_CONTROL_HTTP
typedef struct {
    char text[128];
    size_t length;
} http_command_response_t;

static esp_err_t http_command_event(esp_http_client_event_t *event)
{
    if (event->event_id != HTTP_EVENT_ON_DATA) return ESP_OK;
    http_command_response_t *response = event->user_data;
    if (event->data_len < 0 ||
        (size_t)event->data_len >= sizeof(response->text) - response->length) {
        return ESP_FAIL;
    }
    memcpy(response->text + response->length, event->data, event->data_len);
    response->length += event->data_len;
    response->text[response->length] = '\0';
    return ESP_OK;
}

static void post_http_pump_status(void)
{
    char json[64];
    snprintf(json, sizeof(json), "{\"bom_control\":%d,\"van_control\":%d}",
             s_pump_in ? 1 : 0, s_pump_out ? 1 : 0);
    esp_http_client_config_t config = {
        .url = CONFIG_POOL_API_URL,
        .timeout_ms = 2000,
    };
    esp_http_client_handle_t client = esp_http_client_init(&config);
    if (!client) return;
    esp_http_client_set_method(client, HTTP_METHOD_POST);
    esp_http_client_set_header(client, "Content-Type", "application/json");
    esp_http_client_set_post_field(client, json, strlen(json));
    esp_err_t err = esp_http_client_perform(client);
    if (err != ESP_OK || esp_http_client_get_status_code(client) != 200) {
        ESP_LOGW(TAG, "Could not report pump status to XAMPP: %s", esp_err_to_name(err));
    }
    esp_http_client_cleanup(client);
}

static void process_http_command(const char *json)
{
    cJSON *root = cJSON_Parse(json);
    if (!root) return;
    const cJSON *revision_value = cJSON_GetObjectItemCaseSensitive(root, "revision");
    const cJSON *pump_in_value = cJSON_GetObjectItemCaseSensitive(root, "bom_vao");
    const cJSON *pump_out_value = cJSON_GetObjectItemCaseSensitive(root, "bom_ra");
    if (!cJSON_IsNumber(revision_value) || !cJSON_IsNumber(pump_in_value) ||
        !cJSON_IsNumber(pump_out_value) || revision_value->valuedouble < 0 ||
        revision_value->valuedouble > UINT32_MAX ||
        (double)(uint32_t)revision_value->valuedouble != revision_value->valuedouble ||
        (pump_in_value->valueint != 0 && pump_in_value->valueint != 1) ||
        (pump_out_value->valueint != 0 && pump_out_value->valueint != 1)) {
        ESP_LOGW(TAG, "Invalid HTTP pump command response");
        cJSON_Delete(root);
        return;
    }
    uint32_t revision = (uint32_t)revision_value->valuedouble;
    if (!s_http_baselined || revision < s_http_last_revision) {
        s_http_last_revision = revision;
        s_http_baselined = true;
        ESP_LOGI(TAG, "HTTP command baseline: %" PRIu32, revision);
    } else if (revision > s_http_last_revision && s_wifi_connected) {
        bool pump_in = pump_in_value->valueint == 1;
        bool pump_out = pump_out_value->valueint == 1;
        esp_err_t err = set_pumps(pump_in, pump_out);
        if (err == ESP_OK) {
            s_http_last_revision = revision;
            ESP_LOGI(TAG, "HTTP command %" PRIu32 ": pump in=%d, pump out=%d",
                     revision, pump_in, pump_out);
            post_http_pump_status();
        } else {
            ESP_LOGE(TAG, "Cannot apply HTTP command: %s", esp_err_to_name(err));
        }
    }
    cJSON_Delete(root);
}

static void http_command_task(void *arg)
{
    char url[256];
    unsigned int failed_polls = 0;
    int written = snprintf(url, sizeof(url), "%s?commands=1", CONFIG_POOL_API_URL);
    if (written < 0 || (size_t)written >= sizeof(url)) {
        ESP_LOGE(TAG, "HTTP command URL is too long");
        vTaskDelete(NULL);
        return;
    }
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(3000));
        if (!s_wifi_connected) continue;
        http_command_response_t response = {0};
        esp_http_client_config_t config = {
            .url = url,
            .timeout_ms = 2000,
            .event_handler = http_command_event,
            .user_data = &response,
        };
        esp_http_client_handle_t client = esp_http_client_init(&config);
        if (!client) continue;
        esp_err_t err = esp_http_client_perform(client);
        int status = esp_http_client_get_status_code(client);
        esp_http_client_cleanup(client);
        if (err == ESP_OK && status == 200 && response.length > 0) {
            failed_polls = 0;
            process_http_command(response.text);
        } else {
            if (++failed_polls >= 2) {
                if (s_pump_in || s_pump_out) {
                    set_pumps(false, false);
                    ESP_LOGW(TAG, "XAMPP command channel unavailable; pumps OFF");
                }
                s_http_baselined = false;
            }
            ESP_LOGW(TAG, "HTTP command poll failed: %s, status=%d",
                     esp_err_to_name(err), status);
        }
    }
}
#endif

static void sample_to_json(const telemetry_sample_t *sample, char *json, size_t capacity)
{
    if ((sample->flags & (TELEMETRY_HAS_TEMPERATURE | TELEMETRY_HAS_WATER)) ==
        (TELEMETRY_HAS_TEMPERATURE | TELEMETRY_HAS_WATER)) {
        snprintf(json, capacity,
                 "{\"sample_id\":\"%016" PRIX64 "\",\"nhiet_do\":%.2f,\"muc_nuoc\":%d,\"muc_nuoc_raw\":%d,\"bom_control\":%d,\"van_control\":%d}",
                 sample->id, sample->temperature,
                 (sample->flags & TELEMETRY_WATER_NORMAL) ? 1 : 0, sample->water_raw,
                 s_pump_in ? 1 : 0, s_pump_out ? 1 : 0);
    } else if (sample->flags & TELEMETRY_HAS_TEMPERATURE) {
        snprintf(json, capacity, "{\"sample_id\":\"%016" PRIX64 "\",\"nhiet_do\":%.2f,\"bom_control\":%d,\"van_control\":%d}",
                 sample->id, sample->temperature, s_pump_in ? 1 : 0, s_pump_out ? 1 : 0);
    } else {
        snprintf(json, capacity,
                 "{\"sample_id\":\"%016" PRIX64 "\",\"muc_nuoc\":%d,\"muc_nuoc_raw\":%d,\"bom_control\":%d,\"van_control\":%d}",
                 sample->id, (sample->flags & TELEMETRY_WATER_NORMAL) ? 1 : 0,
                 sample->water_raw, s_pump_in ? 1 : 0, s_pump_out ? 1 : 0);
    }
}

static bool post_sample(const telemetry_sample_t *sample)
{
    char json[160];
    sample_to_json(sample, json, sizeof(json));
    esp_http_client_config_t http_config = {
        .url = CONFIG_POOL_API_URL,
        .timeout_ms = 3000,
    };
    esp_http_client_handle_t http = esp_http_client_init(&http_config);
    if (!http) {
        ESP_LOGE(TAG, "Cannot create HTTP client");
        return false;
    }
    esp_http_client_set_method(http, HTTP_METHOD_POST);
    esp_http_client_set_header(http, "Content-Type", "application/json");
    esp_http_client_set_post_field(http, json, strlen(json));
    esp_err_t err = esp_http_client_perform(http);
    int status = esp_http_client_get_status_code(http);
    bool success = err == ESP_OK && status == 200;
    if (success) {
        ESP_LOGI(TAG, "HTTP POST OK: sample=%016" PRIX64 ", status=%d",
                 sample->id, status);
    } else {
        ESP_LOGW(TAG, "HTTP update failed: %s, status=%d", esp_err_to_name(err),
                 status);
    }
    esp_http_client_cleanup(http);
    return success;
}

static void queue_sample(const telemetry_sample_t *sample)
{
    bool dropped_oldest = false;
    esp_err_t err = telemetry_queue_push(sample, &dropped_oldest);
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
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "Cannot update telemetry queue: %s", esp_err_to_name(err));
        }
    }
}

void pool_network_send_telemetry(bool has_temperature, float temperature,
                                 bool has_water, bool water_normal, int water_raw)
{
    if (!has_temperature && !has_water) return;
    telemetry_sample_t sample = {
        .id = ((uint64_t)s_boot_nonce << 32) | ++s_sample_counter,
        .temperature = temperature,
        .water_raw = water_raw,
        .flags = (has_temperature ? TELEMETRY_HAS_TEMPERATURE : 0) |
                 (has_water ? TELEMETRY_HAS_WATER : 0) |
                 (has_water && water_normal ? TELEMETRY_WATER_NORMAL : 0),
    };
    char json[160];
    sample_to_json(&sample, json, sizeof(json));
    if (s_mqtt_connected) {
        esp_mqtt_client_enqueue(s_mqtt, TOPIC_TELEMETRY, json, 0, 1, 0, true);
    }

    if (telemetry_queue_count() == 0 && s_wifi_connected) {
        if (post_sample(&sample)) return;
        queue_sample(&sample);
        return;
    }
    queue_sample(&sample);
    if (s_wifi_connected) flush_queued_samples();
}
