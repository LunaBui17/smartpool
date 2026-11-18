#include "pool_identity.h"
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "esp_mac.h"
#include "nvs.h"

typedef struct {
    uint32_t version;
    uint8_t factory_mac[6];
    char device_id[65];
    char device_key[65];
} stored_identity_t;
_Static_assert(sizeof(stored_identity_t) == 140, "Persisted identity v1 layout");
static stored_identity_t s_active;
static nvs_handle_t s_nvs;
static uint8_t s_factory_mac[6];
static char s_serial[24];
static char s_mac_text[18];
static char s_saved_id[65];

bool pool_identity_id_valid(const char *id)
{
    if (!id) return false;
    size_t length = strlen(id);
    if (!length || length > 64) return false;
    for (size_t i = 0; i < length; i++) {
        char c = id[i];
        bool alnum = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
        if (!alnum && (!i || (c != '.' && c != '_' && c != '-'))) return false;
    }
    return true;
}

static bool key_valid(const char *key)
{
    if (!key || strlen(key) != 64) return false;
    for (int i = 0; i < 64; i++) {
        if (!((key[i] >= '0' && key[i] <= '9') || (key[i] >= 'a' && key[i] <= 'f'))) return false;
    }
    return true;
}

esp_err_t pool_identity_init(void)
{
    esp_err_t error = esp_efuse_mac_get_default(s_factory_mac);
    if (error != ESP_OK) return error;
    snprintf(s_mac_text, sizeof(s_mac_text), "%02X:%02X:%02X:%02X:%02X:%02X",
             s_factory_mac[0], s_factory_mac[1], s_factory_mac[2],
             s_factory_mac[3], s_factory_mac[4], s_factory_mac[5]);
    snprintf(s_serial, sizeof(s_serial), "SP-%02X%02X%02X%02X%02X%02X",
             s_factory_mac[0], s_factory_mac[1], s_factory_mac[2],
             s_factory_mac[3], s_factory_mac[4], s_factory_mac[5]);
    error = nvs_open("poolid", NVS_READWRITE, &s_nvs);
    if (error != ESP_OK) return error;
    size_t size = sizeof(s_active);
    error = nvs_get_blob(s_nvs, "identity", &s_active, &size);
    if (error == ESP_ERR_NVS_NOT_FOUND) {
        memset(&s_active, 0, sizeof(s_active));
        return ESP_OK; // Fresh boards stay local until provisioned.
    }
    if (error != ESP_OK) return error;
    if (size != sizeof(s_active) || s_active.version != 1 ||
        memcmp(s_active.factory_mac, s_factory_mac, sizeof(s_factory_mac)) ||
        !memchr(s_active.device_id, 0, sizeof(s_active.device_id)) ||
        !memchr(s_active.device_key, 0, sizeof(s_active.device_key)) ||
        !pool_identity_id_valid(s_active.device_id) || !key_valid(s_active.device_key)) {
        memset(&s_active, 0, sizeof(s_active));
        return ESP_ERR_INVALID_STATE; // Preserve corrupt/foreign NVS; never erase automatically.
    }
    memcpy(s_saved_id, s_active.device_id, sizeof(s_saved_id));
    return ESP_OK;
}

bool pool_identity_ready(void) { return s_active.device_id[0] && s_active.device_key[0]; }
const char *pool_identity_id(void) { return s_active.device_id; }
const char *pool_identity_key(void) { return s_active.device_key; }
const char *pool_identity_serial(void) { return s_serial; }
const char *pool_identity_mac(void) { return s_mac_text; }

// Caller serializes writers. This helper is transport-independent for future BLE.
esp_err_t pool_identity_save(const char *device_id, const char *device_key)
{
    if (!pool_identity_id_valid(device_id) || !device_key) return ESP_ERR_INVALID_ARG;
    if (s_saved_id[0] && strcmp(s_saved_id, device_id)) return ESP_ERR_INVALID_STATE;
    const char *key = device_key[0] ? device_key : s_active.device_key;
    if (!key_valid(key)) return ESP_ERR_INVALID_ARG;
    stored_identity_t next = {.version = 1};
    memcpy(next.factory_mac, s_factory_mac, sizeof(s_factory_mac));
    snprintf(next.device_id, sizeof(next.device_id), "%s", device_id);
    snprintf(next.device_key, sizeof(next.device_key), "%s", key);
    esp_err_t error = nvs_set_blob(s_nvs, "identity", &next, sizeof(next));
    if (error == ESP_OK) error = nvs_commit(s_nvs);
    if (error == ESP_OK) memcpy(s_saved_id, next.device_id, sizeof(s_saved_id));
    memset(&next, 0, sizeof(next));
    return error;
}
