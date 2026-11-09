#include "pool_network.h"

#include <stdio.h>
#include <string.h>
#include <stdatomic.h>
#include <inttypes.h>

#include "esp_event.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_random.h"
#include "esp_wifi.h"
#include "esp_wifi_default.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "mqtt_client.h"
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
static bool s_mqtt_started;
static _Atomic bool s_has_wifi_credentials;
static _Atomic unsigned int s_wifi_failures;
static _Atomic bool s_wifi_connected;
static _Atomic bool s_mqtt_connected;
static _Atomic bool s_pump_in;
static _Atomic bool s_pump_out;
static uint32_t s_boot_nonce;
static uint32_t s_sample_counter;

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

static bool is_topic(const esp_mqtt_event_handle_t event, const char *topic)
{
    size_t length = strlen(topic);
    return event->topic_len == length && memcmp(event->topic, topic, length) == 0;
}

static void mqtt_event_handler(void *arg, esp_event_base_t base, int32_t event_id, void *data)
{
    esp_mqtt_event_handle_t event = data;
    switch ((esp_mqtt_event_id_t)event_id) {
    case MQTT_EVENT_CONNECTED:
        s_mqtt_connected = true;
        esp_mqtt_client_subscribe(s_mqtt, TOPIC_CMD_IN, 1);
        esp_mqtt_client_subscribe(s_mqtt, TOPIC_CMD_OUT, 1);
        publish_status();
        ESP_LOGI(TAG, "MQTT connected");
        break;
    case MQTT_EVENT_DISCONNECTED:
        s_mqtt_connected = false;
        relay_set(RELAY_1, false);
        relay_set(RELAY_2, false);
        s_pump_in = false;
        s_pump_out = false;
        ESP_LOGW(TAG, "MQTT disconnected; pumps OFF");
        break;
    case MQTT_EVENT_DATA: {
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
        if (on && ((pump_in && s_pump_out) || (pump_out && s_pump_in))) {
            ESP_LOGW(TAG, "Cannot run both pumps together");
            publish_status();
            break;
        }
        relay_channel_t channel = pump_in ? RELAY_1 : RELAY_2;
        esp_err_t err = relay_set(channel, on);
        if (err == ESP_OK) {
            if (pump_in) {
                s_pump_in = on;
            } else {
                s_pump_out = on;
            }
            publish_status();
            ESP_LOGI(TAG, "%s %s", pump_in ? "Pump in" : "Pump out", on ? "ON" : "OFF");
        } else {
            ESP_LOGE(TAG, "Relay control failed: %s", esp_err_to_name(err));
        }
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
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(30000));
        if (s_has_wifi_credentials && !s_wifi_connected && s_wifi_failures >= 3) {
            ESP_LOGI(TAG, "Retrying saved Wi-Fi while setup AP stays available");
            esp_wifi_connect();
        }
    }
}

static void wifi_event_handler(void *arg, esp_event_base_t base, int32_t event_id, void *data)
{
    if (base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        if (s_has_wifi_credentials) esp_wifi_connect();
    } else if (base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        s_wifi_connected = false;
        s_mqtt_connected = false;
        // Stop both pumps when network control is unavailable.
        relay_set(RELAY_1, false);
        relay_set(RELAY_2, false);
        s_pump_in = false;
        s_pump_out = false;
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
        if (!s_mqtt_started) {
            esp_err_t err = esp_mqtt_client_start(s_mqtt);
            if (err == ESP_OK) {
                s_mqtt_started = true;
            } else {
                ESP_LOGE(TAG, "Cannot start MQTT: %s", esp_err_to_name(err));
            }
        }
    }
}

esp_err_t pool_network_init(void)
{
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
        .session.last_will.topic = TOPIC_STATUS,
        .session.last_will.msg = "{\"online\":0,\"bom_vao_control\":0,\"bom_ra_control\":0}",
        .session.last_will.qos = 1,
        .session.last_will.retain = 1,
    };
    s_mqtt = esp_mqtt_client_init(&mqtt_config);
    if (!s_mqtt) return ESP_FAIL;
    err = esp_mqtt_client_register_event(s_mqtt, ESP_EVENT_ANY_ID, mqtt_event_handler, NULL);
    if (err != ESP_OK) return err;
    err = esp_wifi_start();
    if (err != ESP_OK) return err;
    err = wifi_setup_init(credentials_saved);
    if (err != ESP_OK) return err;
    if (xTaskCreate(wifi_retry_task, "wifi_retry", 3072, NULL, 4, NULL) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}

static void sample_to_json(const telemetry_sample_t *sample, char *json, size_t capacity)
{
    if ((sample->flags & (TELEMETRY_HAS_TEMPERATURE | TELEMETRY_HAS_WATER)) ==
        (TELEMETRY_HAS_TEMPERATURE | TELEMETRY_HAS_WATER)) {
        snprintf(json, capacity,
                 "{\"sample_id\":\"%016" PRIX64 "\",\"nhiet_do\":%.2f,\"muc_nuoc\":%d,\"muc_nuoc_raw\":%d}",
                 sample->id, sample->temperature,
                 (sample->flags & TELEMETRY_WATER_NORMAL) ? 1 : 0, sample->water_raw);
    } else if (sample->flags & TELEMETRY_HAS_TEMPERATURE) {
        snprintf(json, capacity, "{\"sample_id\":\"%016" PRIX64 "\",\"nhiet_do\":%.2f}",
                 sample->id, sample->temperature);
    } else {
        snprintf(json, capacity,
                 "{\"sample_id\":\"%016" PRIX64 "\",\"muc_nuoc\":%d,\"muc_nuoc_raw\":%d}",
                 sample->id, (sample->flags & TELEMETRY_WATER_NORMAL) ? 1 : 0,
                 sample->water_raw);
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
    if (!success) {
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
