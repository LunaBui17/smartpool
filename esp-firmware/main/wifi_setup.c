#include "wifi_setup.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>

#include "cJSON.h"
#include "esp_http_server.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "esp_netif.h"
#include "pool_features.h"
#include "pool_network.h"

static const char *TAG = "wifi_setup";
static httpd_handle_t s_server;
static esp_err_t (*s_on_credentials_saved)(void);
static _Atomic bool s_ap_enabled;
extern const char dashboard_start[] asm("_binary_index_html_start");

static esp_err_t json_response(httpd_req_t *req, cJSON *root)
{
    char *text = root ? cJSON_PrintUnformatted(root) : NULL;
    cJSON_Delete(root);
    if (!text) return httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Not enough memory");
    httpd_resp_set_type(req, "application/json; charset=utf-8");
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");
    esp_err_t err = httpd_resp_sendstr(req, text);
    free(text);
    return err;
}

static esp_err_t action_response(httpd_req_t *req, esp_err_t err)
{
    cJSON *root = cJSON_CreateObject();
    if (!root) return httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Not enough memory");
    cJSON_AddBoolToObject(root, "ok", err == ESP_OK);
    if (err != ESP_OK) {
        httpd_resp_set_status(req, "409 Conflict");
        cJSON_AddStringToObject(root, "message", err == ESP_ERR_NOT_SUPPORTED
            ? "Relay, cam bien hoac chuc nang chua duoc bat."
            : err == ESP_ERR_INVALID_ARG ? "Lenh dieu khien khong hop le."
            : "Khong the ap dung thay doi. Vui long thu lai.");
    }
    return json_response(req, root);
}

static cJSON *read_json(httpd_req_t *req)
{
    char content_type[48];
    if (httpd_req_get_hdr_value_str(req, "Content-Type", content_type, sizeof(content_type)) != ESP_OK ||
        (strcmp(content_type, "application/json") != 0 &&
         strncmp(content_type, "application/json;", 17) != 0)) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Content-Type must be application/json");
        return NULL;
    }
    char body[384];
    if (req->content_len <= 0 || req->content_len >= sizeof(body)) {
        httpd_resp_send_err(req, HTTPD_413_CONTENT_TOO_LARGE, "Request is too large");
        return NULL;
    }
    size_t received = 0;
    while (received < req->content_len) {
        int count = httpd_req_recv(req, body + received, req->content_len - received);
        if (count <= 0) {
            httpd_resp_send_err(req, HTTPD_408_REQ_TIMEOUT, "Request timed out");
            return NULL;
        }
        received += count;
    }
    body[received] = '\0';
    cJSON *root = memchr(body, 0, received) ? NULL : cJSON_ParseWithOpts(body, NULL, true);
    if (!cJSON_IsObject(root)) {
        cJSON_Delete(root);
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Invalid JSON object");
        return NULL;
    }
    return root;
}

static esp_err_t setup_get(httpd_req_t *req)
{
    httpd_resp_set_type(req, "text/html; charset=utf-8");
    httpd_resp_set_hdr(req, "Cache-Control", "no-cache");
    return httpd_resp_sendstr(req, dashboard_start);
}

static esp_err_t status_get(httpd_req_t *req)
{
    pool_device_state_t state;
    pool_network_get_state(&state);
    cJSON *root = cJSON_CreateObject();
    if (!root) return json_response(req, root);
    cJSON_AddBoolToObject(root, "wifi_connected", state.wifi_connected);
    cJSON_AddBoolToObject(root, "station_enabled", state.station_enabled);
    cJSON_AddBoolToObject(root, "station_supported", POOL_WIFI_STA_ENABLED);
    cJSON_AddBoolToObject(root, "ap_enabled", s_ap_enabled);
    cJSON_AddStringToObject(root, "ap_ssid", CONFIG_POOL_SETUP_AP_SSID);
    cJSON_AddBoolToObject(root, "mqtt_supported", POOL_MQTT_ENABLED);
    cJSON_AddBoolToObject(root, "mqtt_connected", state.mqtt_connected);
    cJSON_AddBoolToObject(root, "clock_ready", state.clock_ready);
    cJSON_AddBoolToObject(root, "http_supported", POOL_HTTP_ENABLED);
    cJSON_AddStringToObject(root, "mqtt_state", state.mqtt_state);
    cJSON_AddStringToObject(root, "http_state", state.http_state);
    cJSON_AddNumberToObject(root, "http_status_code", state.http_status_code);
    cJSON_AddStringToObject(root, "http_error", state.http_error);
    cJSON_AddNumberToObject(root, "http_last_attempt_age_seconds", state.http_last_attempt_age_seconds);
    cJSON_AddNumberToObject(root, "http_last_success_age_seconds", state.http_last_success_age_seconds);
    cJSON_AddNumberToObject(root, "http_queued_samples", state.http_queued_samples);
    cJSON_AddBoolToObject(root, "relay1_supported", POOL_RELAY_1_ENABLED);
    cJSON_AddBoolToObject(root, "relay2_supported", POOL_RELAY_2_ENABLED);
    cJSON_AddBoolToObject(root, "relay1", state.relay_1_on);
    cJSON_AddBoolToObject(root, "relay2", state.relay_2_on);
    cJSON_AddBoolToObject(root, "temperature_supported", POOL_TEMPERATURE_ENABLED);
    cJSON_AddBoolToObject(root, "water_supported", POOL_WATER_ENABLED);
    cJSON_AddBoolToObject(root, "temperature_enabled", state.temperature_enabled);
    cJSON_AddBoolToObject(root, "water_enabled", state.water_enabled);
    if (state.has_temperature) cJSON_AddNumberToObject(root, "temperature", state.temperature);
    else cJSON_AddNullToObject(root, "temperature");
    if (state.has_water) {
        cJSON_AddNumberToObject(root, "water_raw", state.water_raw);
        cJSON_AddBoolToObject(root, "water_normal", state.water_normal);
    } else {
        cJSON_AddNullToObject(root, "water_raw");
        cJSON_AddNullToObject(root, "water_normal");
    }
    cJSON_AddNumberToObject(root, "sample_age_seconds", state.sample_age_seconds);
    cJSON *rules = cJSON_AddArrayToObject(root, "automation");
    if (!rules) { cJSON_Delete(root); return json_response(req, NULL); }
    for (int i = 0; i < 2; i++) {
        const pump_auto_view_t *view = &state.automation[i];
        cJSON *rule = cJSON_CreateObject();
        if (!rule) { cJSON_Delete(root); return json_response(req, NULL); }
        cJSON_AddItemToArray(rules, rule);
        cJSON_AddNumberToObject(rule, "channel", i + 1);
        cJSON_AddBoolToObject(rule, "enabled", view->config.enabled);
        cJSON_AddStringToObject(rule, "sensor", view->config.sensor == PUMP_SENSOR_WATER ? "water" : "temperature");
        cJSON_AddStringToObject(rule, "comparison", view->config.start_below ? "below" : "above");
        cJSON_AddNumberToObject(rule, "start_threshold", view->config.start_threshold);
        cJSON_AddStringToObject(rule, "stop_mode", view->config.stop_mode == PUMP_STOP_DURATION ? "duration" : "threshold");
        cJSON_AddNumberToObject(rule, "stop_threshold", view->config.stop_threshold);
        cJSON_AddNumberToObject(rule, "duration_seconds", view->config.duration_seconds);
        cJSON_AddBoolToObject(rule, "paused", view->paused);
        cJSON_AddBoolToObject(rule, "running", view->running);
        cJSON_AddStringToObject(rule, "reason", view->reason);
        cJSON_AddStringToObject(rule, "last_stop_reason", view->last_stop_reason);
        cJSON_AddNumberToObject(rule, "remaining_seconds", view->remaining_seconds);
    }
    wifi_config_t station;
    char ssid[33] = {0};
    if (esp_wifi_get_config(WIFI_IF_STA, &station) == ESP_OK) memcpy(ssid, station.sta.ssid, 32);
    cJSON_AddStringToObject(root, "ssid", ssid);
    esp_netif_ip_info_t ip;
    esp_netif_t *netif = esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
    char ip_text[16] = "";
    if (state.wifi_connected && netif && esp_netif_get_ip_info(netif, &ip) == ESP_OK) {
        snprintf(ip_text, sizeof(ip_text), IPSTR, IP2STR(&ip.ip));
    }
    cJSON_AddStringToObject(root, "ip", ip_text);
    return json_response(req, root);
}

static esp_err_t control_post(httpd_req_t *req)
{
    cJSON *root = read_json(req);
    if (!root) return ESP_OK;
    const cJSON *target = cJSON_GetObjectItemCaseSensitive(root, "target");
    const cJSON *enabled = cJSON_GetObjectItemCaseSensitive(root, "enabled");
    if (!cJSON_IsString(target) || !cJSON_IsBool(enabled)) {
        cJSON_Delete(root);
        return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Expected target and boolean enabled");
    }
    esp_err_t err = pool_network_local_control(target->valuestring, cJSON_IsTrue(enabled));
    cJSON_Delete(root);
    return action_response(req, err);
}

static esp_err_t invalid_pump_rule(httpd_req_t *req)
{
    httpd_resp_set_status(req, "400 Bad Request");
    cJSON *error = cJSON_CreateObject();
    if (error) {
        cJSON_AddBoolToObject(error, "ok", false);
        cJSON_AddStringToObject(error, "message", "Nguong, cam bien hoac thoi gian khong hop le.");
    }
    return json_response(req, error);
}

static esp_err_t automation_post(httpd_req_t *req)
{
    cJSON *root = read_json(req);
    if (!root) return ESP_OK;
    const cJSON *channel = cJSON_GetObjectItemCaseSensitive(root, "channel");
    const cJSON *enabled = cJSON_GetObjectItemCaseSensitive(root, "enabled");
    const cJSON *sensor = cJSON_GetObjectItemCaseSensitive(root, "sensor");
    const cJSON *comparison = cJSON_GetObjectItemCaseSensitive(root, "comparison");
    const cJSON *start = cJSON_GetObjectItemCaseSensitive(root, "start_threshold");
    const cJSON *mode = cJSON_GetObjectItemCaseSensitive(root, "stop_mode");
    const cJSON *stop = cJSON_GetObjectItemCaseSensitive(root, "stop_threshold");
    const cJSON *duration = cJSON_GetObjectItemCaseSensitive(root, "duration_seconds");
    bool valid = cJSON_IsNumber(channel) && (channel->valuedouble == 1 || channel->valuedouble == 2) &&
        cJSON_IsBool(enabled) && cJSON_IsString(sensor) &&
        (strcmp(sensor->valuestring, "water") == 0 || strcmp(sensor->valuestring, "temperature") == 0) &&
        cJSON_IsString(comparison) && (strcmp(comparison->valuestring, "below") == 0 || strcmp(comparison->valuestring, "above") == 0) &&
        cJSON_IsNumber(start) && cJSON_IsNumber(stop) && cJSON_IsString(mode) &&
        (strcmp(mode->valuestring, "duration") == 0 || strcmp(mode->valuestring, "threshold") == 0) &&
        cJSON_IsNumber(duration) && duration->valuedouble >= 1 && duration->valuedouble <= 86400 &&
        (double)(uint32_t)duration->valuedouble == duration->valuedouble;
    if (!valid) { cJSON_Delete(root); return invalid_pump_rule(req); }
    double minimum = strcmp(sensor->valuestring, "water") == 0 ? 0 : -55;
    double maximum = strcmp(sensor->valuestring, "water") == 0 ? 4095 : 125;
    if (!(start->valuedouble >= minimum && start->valuedouble <= maximum &&
          stop->valuedouble >= minimum && stop->valuedouble <= maximum)) {
        cJSON_Delete(root);
        return invalid_pump_rule(req);
    }
    pump_auto_config_t config = {
        .enabled = cJSON_IsTrue(enabled),
        .sensor = strcmp(sensor->valuestring, "water") == 0 ? PUMP_SENSOR_WATER : PUMP_SENSOR_TEMPERATURE,
        .start_below = strcmp(comparison->valuestring, "below") == 0,
        .start_threshold = start->valuedouble,
        .stop_mode = strcmp(mode->valuestring, "duration") == 0 ? PUMP_STOP_DURATION : PUMP_STOP_THRESHOLD,
        .stop_threshold = stop->valuedouble,
        .duration_seconds = (uint32_t)duration->valuedouble,
    };
    esp_err_t err = pool_network_configure_pump((int)channel->valuedouble - 1, &config);
    cJSON_Delete(root);
    return err == ESP_ERR_INVALID_ARG ? invalid_pump_rule(req) : action_response(req, err);
}

static esp_err_t time_post(httpd_req_t *req)
{
    cJSON *root = read_json(req);
    if (!root) return ESP_OK;
    const cJSON *value = cJSON_GetObjectItemCaseSensitive(root, "unix_time");
    if (!cJSON_IsNumber(value) || !(value->valuedouble >= 1704067200.0 &&
        value->valuedouble <= 4102444799.0) ||
        (double)(int64_t)value->valuedouble != value->valuedouble) {
        cJSON_Delete(root);
        return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Expected integer unix_time within 2024-2099");
    }
    esp_err_t err = pool_network_set_time((int64_t)value->valuedouble);
    cJSON_Delete(root);
    return action_response(req, err);
}

static esp_err_t network_post(httpd_req_t *req)
{
    cJSON *root = read_json(req);
    if (!root) return ESP_OK;
    const cJSON *enabled = cJSON_GetObjectItemCaseSensitive(root, "enabled");
    if (!cJSON_IsBool(enabled)) {
        cJSON_Delete(root);
        return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Expected boolean enabled");
    }
    esp_err_t err = pool_network_set_station_enabled(cJSON_IsTrue(enabled));
    cJSON_Delete(root);
    return action_response(req, err);
}

static esp_err_t setup_post(httpd_req_t *req)
{
    cJSON *root = read_json(req);
    if (!root) return ESP_OK;
    const cJSON *ssid = cJSON_GetObjectItemCaseSensitive(root, "ssid");
    const cJSON *password = cJSON_GetObjectItemCaseSensitive(root, "password");
    if (!cJSON_IsString(ssid) || !cJSON_IsString(password) ||
        strlen(ssid->valuestring) == 0 || strlen(ssid->valuestring) > 32 ||
        strlen(password->valuestring) > 63 ||
        (strlen(password->valuestring) > 0 && strlen(password->valuestring) < 8)) {
        cJSON_Delete(root);
        return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "SSID: 1-32 bytes; password: empty or 8-63 bytes");
    }
    if (!POOL_WIFI_STA_ENABLED) {
        cJSON_Delete(root);
        return action_response(req, ESP_ERR_NOT_SUPPORTED);
    }
    wifi_config_t station = {0};
    memcpy(station.sta.ssid, ssid->valuestring, strlen(ssid->valuestring));
    memcpy(station.sta.password, password->valuestring, strlen(password->valuestring));
    esp_err_t err = pool_network_set_station_enabled(false);
    if (err == ESP_OK) err = esp_wifi_set_config(WIFI_IF_STA, &station);
    cJSON_Delete(root);
    if (err == ESP_OK && s_on_credentials_saved) err = s_on_credentials_saved();
    return action_response(req, err);
}

static esp_err_t networks_get(httpd_req_t *req)
{
    esp_err_t err = esp_wifi_scan_start(NULL, true);
    if (err != ESP_OK) {
        httpd_resp_set_status(req, "503 Service Unavailable");
        return httpd_resp_sendstr(req, "Wi-Fi is connecting. Try scanning again shortly.");
    }
    uint16_t count = 12;
    wifi_ap_record_t *records = calloc(count, sizeof(*records));
    cJSON *root = cJSON_CreateArray();
    if (!records || !root) {
        free(records);
        cJSON_Delete(root);
        esp_wifi_clear_ap_list();
        return json_response(req, NULL);
    }
    err = esp_wifi_scan_get_ap_records(&count, records);
    if (err == ESP_OK) {
        for (int i = 0; i < count; i++) {
            char ssid[33];
            memcpy(ssid, records[i].ssid, 32);
            ssid[32] = '\0';
            if (!ssid[0]) continue;
            bool duplicate = false;
            for (int j = 0; j < i; j++) {
                if (memcmp(records[j].ssid, records[i].ssid, 32) == 0) duplicate = true;
            }
            if (duplicate) continue;
            cJSON *network = cJSON_CreateObject();
            if (!network) continue;
            cJSON_AddStringToObject(network, "ssid", ssid);
            cJSON_AddNumberToObject(network, "rssi", records[i].rssi);
            cJSON_AddBoolToObject(network, "secure", records[i].authmode != WIFI_AUTH_OPEN);
            cJSON_AddItemToArray(root, network);
        }
    } else {
        esp_wifi_clear_ap_list();
    }
    free(records);
    if (err != ESP_OK) {
        cJSON_Delete(root);
        return httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Cannot read scan results");
    }
    return json_response(req, root);
}

esp_err_t wifi_setup_init(esp_err_t (*on_credentials_saved)(void))
{
    if (s_server) return ESP_OK;
    s_on_credentials_saved = on_credentials_saved;
    httpd_config_t server_config = HTTPD_DEFAULT_CONFIG();
    server_config.stack_size = 8192;
    server_config.lru_purge_enable = true;
    esp_err_t err = httpd_start(&s_server, &server_config);
    if (err != ESP_OK) return err;
    const httpd_uri_t routes[] = {
        {.uri = "/", .method = HTTP_GET, .handler = setup_get},
        {.uri = "/api/status", .method = HTTP_GET, .handler = status_get},
        {.uri = "/api/control", .method = HTTP_POST, .handler = control_post},
        {.uri = "/api/automation", .method = HTTP_POST, .handler = automation_post},
        {.uri = "/api/time", .method = HTTP_POST, .handler = time_post},
        {.uri = "/api/network", .method = HTTP_POST, .handler = network_post},
        {.uri = "/api/networks", .method = HTTP_GET, .handler = networks_get},
        {.uri = "/wifi", .method = HTTP_POST, .handler = setup_post},
    };
    for (size_t i = 0; i < sizeof(routes) / sizeof(routes[0]); i++) {
        err = httpd_register_uri_handler(s_server, &routes[i]);
        if (err != ESP_OK) break;
    }
    if (err != ESP_OK) {
        httpd_stop(s_server);
        s_server = NULL;
        s_on_credentials_saved = NULL;
    }
    return err;
}

esp_err_t wifi_setup_enable_ap(void)
{
    if (s_ap_enabled) return ESP_OK;
    size_t ssid_len = strlen(CONFIG_POOL_SETUP_AP_SSID);
    size_t password_len = strlen(CONFIG_POOL_SETUP_AP_PASSWORD);
    if (ssid_len == 0 || ssid_len > 32 || password_len < 8 || password_len > 63) {
        return ESP_ERR_INVALID_ARG;
    }

    wifi_config_t access_point = {0};
    memcpy(access_point.ap.ssid, CONFIG_POOL_SETUP_AP_SSID, ssid_len);
    memcpy(access_point.ap.password, CONFIG_POOL_SETUP_AP_PASSWORD, password_len);
    access_point.ap.ssid_len = ssid_len;
    access_point.ap.max_connection = 4;
    access_point.ap.authmode = WIFI_AUTH_WPA2_PSK;

    esp_err_t err = esp_wifi_set_mode(WIFI_MODE_APSTA);
    if (err != ESP_OK) return err;
    err = esp_wifi_set_config(WIFI_IF_AP, &access_point);
    if (err != ESP_OK) return err;

    s_ap_enabled = true;
    ESP_LOGI(TAG, "Setup AP '%s' ready at http://192.168.4.1", CONFIG_POOL_SETUP_AP_SSID);
    return ESP_OK;
}

