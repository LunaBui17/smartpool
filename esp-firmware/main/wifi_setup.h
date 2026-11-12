#pragma once

#include "esp_err.h"
#include <stdbool.h>

// Wi-Fi must be initialized and the default AP netif must exist first.
esp_err_t wifi_setup_init(esp_err_t (*on_credentials_saved)(void));
esp_err_t wifi_setup_enable_ap(void);
