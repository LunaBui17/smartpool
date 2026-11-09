#include "wifi_setup.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_http_server.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "sdkconfig.h"

static const char *TAG = "wifi_setup";
static httpd_handle_t s_server;
static void (*s_on_credentials_saved)(void);
static bool s_ap_enabled;

static const char *PAGE_START =
    "<!doctype html><html lang='vi'><head><meta charset='utf-8'>"
    "<meta name='viewport' content='width=device-width,initial-scale=1'>"
    "<title>Smart Pool Wi-Fi</title><style>body{font-family:sans-serif;"
    "max-width:360px;margin:40px auto;padding:16px}input,button{box-sizing:border-box;"
    "width:100%;padding:12px;margin:8px 0;font-size:16px}</style></head><body>"
    "<h2>Smart Pool Wi-Fi</h2><p>Chon mang Wi-Fi cho ESP32-S3.</p>"
    "<form method='post' action='/wifi'>"
    "<label>Mang Wi-Fi<select name='ssid'><option value=''>Chon mang</option>";

static const char *PAGE_END =
    "</select></label><label>Hoac nhap SSID an"
    "<input name='manual_ssid' maxlength='32'></label>"
    "<label>Mat khau Wi-Fi<input name='password' type='password' maxlength='63'></label>"
    "<button type='submit'>Luu va ket noi</button></form></body></html>";

static int hex_value(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static bool decode_form_value(const char *src, char *dst, size_t dst_size)
{
    size_t used = 0;
    while (*src) {
        unsigned char value;
        if (*src == '%') {
            int high = hex_value(src[1]);
            int low = src[1] && src[2] ? hex_value(src[2]) : -1;
            if (high < 0 || low < 0) return false;
            value = (unsigned char)((high << 4) | low);
            src += 3;
        } else {
            value = *src == '+' ? ' ' : (unsigned char)*src;
            src++;
        }
        if (value == 0 || used + 1 >= dst_size) return false;
        dst[used++] = value;
    }
    dst[used] = '\0';
    return true;
}

static void escape_html(const char *src, char *dst, size_t capacity)
{
    size_t used = 0;
    while (*src && used + 7 < capacity) {
        const char *replacement = NULL;
        switch (*src) {
        case '&': replacement = "&amp;"; break;
        case '<': replacement = "&lt;"; break;
        case '>': replacement = "&gt;"; break;
        case '"': replacement = "&quot;"; break;
        case '\'': replacement = "&#39;"; break;
        default: dst[used++] = *src++; continue;
        }
        size_t length = strlen(replacement);
        memcpy(dst + used, replacement, length);
        used += length;
        src++;
    }
    dst[used] = '\0';
}

static esp_err_t setup_get(httpd_req_t *req)
{
    httpd_resp_set_type(req, "text/html; charset=utf-8");
    httpd_resp_sendstr_chunk(req, PAGE_START);

    esp_err_t scan_err = esp_wifi_scan_start(NULL, true);
    if (scan_err == ESP_OK) {
        uint16_t count = 12;
        wifi_ap_record_t *networks = calloc(count, sizeof(*networks));
        if (networks && esp_wifi_scan_get_ap_records(&count, networks) == ESP_OK) {
            for (int i = 0; i < count; i++) {
                char ssid[33];
                memcpy(ssid, networks[i].ssid, 32);
                ssid[32] = '\0';
                if (ssid[0] == '\0') continue;
                bool duplicate = false;
                for (int previous = 0; previous < i; previous++) {
                    if (strncmp((const char *)networks[previous].ssid, ssid, 32) == 0) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;
                char escaped[256];
                char option[560];
                escape_html(ssid, escaped, sizeof(escaped));
                snprintf(option, sizeof(option), "<option value='%s'>%s (%d dBm)</option>",
                         escaped, escaped, networks[i].rssi);
                httpd_resp_sendstr_chunk(req, option);
            }
        } else {
            esp_wifi_clear_ap_list();
        }
        free(networks);
    } else {
        ESP_LOGW(TAG, "Wi-Fi scan failed: %s", esp_err_to_name(scan_err));
    }
    httpd_resp_sendstr_chunk(req, PAGE_END);
    return httpd_resp_sendstr_chunk(req, NULL);
}

static esp_err_t setup_post(httpd_req_t *req)
{
    char body[384];
    if (req->content_len <= 0 || req->content_len >= sizeof(body)) {
        return httpd_resp_send_err(req, HTTPD_413_CONTENT_TOO_LARGE, "Invalid form size");
    }
    size_t received = 0;
    while (received < req->content_len) {
        int count = httpd_req_recv(req, body + received, req->content_len - received);
        if (count <= 0) {
            return httpd_resp_send_err(req, HTTPD_408_REQ_TIMEOUT, "Form read failed");
        }
        received += count;
    }
    body[received] = '\0';

    char ssid[33] = {0};
    char manual_ssid[33] = {0};
    char password[64] = {0};
    bool valid = true;
    char *cursor = body;
    while (cursor && *cursor) {
        char *next = strchr(cursor, '&');
        if (next) *next++ = '\0';
        char *equals = strchr(cursor, '=');
        if (equals) {
            *equals++ = '\0';
            if (strcmp(cursor, "ssid") == 0) {
                valid = decode_form_value(equals, ssid, sizeof(ssid)) && valid;
            } else if (strcmp(cursor, "manual_ssid") == 0) {
                valid = decode_form_value(equals, manual_ssid, sizeof(manual_ssid)) && valid;
            } else if (strcmp(cursor, "password") == 0) {
                valid = decode_form_value(equals, password, sizeof(password)) && valid;
            }
        }
        cursor = next;
    }
    if (manual_ssid[0] != '\0') memcpy(ssid, manual_ssid, sizeof(ssid));
    size_t password_len = strlen(password);
    if (!valid || ssid[0] == '\0' || (password_len > 0 && password_len < 8)) {
        return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST,
                                   "SSID or password is invalid (password: 8-63 characters)");
    }

    wifi_config_t station = {0};
    memcpy(station.sta.ssid, ssid, strlen(ssid));
    memcpy(station.sta.password, password, password_len);
    esp_err_t err = esp_wifi_set_config(WIFI_IF_STA, &station);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Cannot save Wi-Fi settings: %s", esp_err_to_name(err));
        return httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Cannot save Wi-Fi");
    }

    httpd_resp_set_type(req, "text/html; charset=utf-8");
    esp_err_t response = httpd_resp_sendstr(req,
        "<html><meta name='viewport' content='width=device-width,initial-scale=1'>"
        "<body><h2>Da luu Wi-Fi</h2><p>ESP32-S3 dang ket noi."
        " Neu khong ket noi duoc, hay mo lai 192.168.4.1 de sua.</p></body></html>");
    ESP_LOGI(TAG, "Saved Wi-Fi SSID: %s", ssid);
    if (s_on_credentials_saved) s_on_credentials_saved();
    return response;
}

esp_err_t wifi_setup_init(void (*on_credentials_saved)(void))
{
    if (s_server) return ESP_OK;
    s_on_credentials_saved = on_credentials_saved;
    httpd_config_t server_config = HTTPD_DEFAULT_CONFIG();
    esp_err_t err = httpd_start(&s_server, &server_config);
    if (err != ESP_OK) {
        s_on_credentials_saved = NULL;
        return err;
    }
    const httpd_uri_t page = {.uri = "/", .method = HTTP_GET, .handler = setup_get};
    const httpd_uri_t submit = {.uri = "/wifi", .method = HTTP_POST, .handler = setup_post};
    err = httpd_register_uri_handler(s_server, &page);
    if (err == ESP_OK) err = httpd_register_uri_handler(s_server, &submit);
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

esp_err_t wifi_setup_disable_ap(void)
{
    if (!s_ap_enabled) return ESP_OK;
    esp_err_t err = esp_wifi_set_mode(WIFI_MODE_STA);
    if (err != ESP_OK) {
        return err;
    }
    s_ap_enabled = false;
    ESP_LOGI(TAG, "Setup AP stopped; configuration page remains on LAN IP");
    return ESP_OK;
}

