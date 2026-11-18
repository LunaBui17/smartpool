#pragma once
#include <stdbool.h>
#include "esp_err.h"

// Active identity is immutable until reboot. Never expose key() through a local GET.
esp_err_t pool_identity_init(void);
bool pool_identity_ready(void);
bool pool_identity_id_valid(const char *id);
const char *pool_identity_id(void);
const char *pool_identity_key(void);
const char *pool_identity_serial(void);
// Factory eFuse MAC in uppercase colon notation; independent of Wi-Fi interfaces.
const char *pool_identity_mac(void);
esp_err_t pool_identity_save(const char *device_id, const char *device_key);
