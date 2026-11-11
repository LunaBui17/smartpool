#pragma once

#include <stdbool.h>
#include "esp_err.h"

typedef enum {
    RELAY_1 = 0,
    RELAY_2,
} relay_channel_t;

// Call once at startup. Both relays are initialized in the OFF state.
esp_err_t relay_init(void);

// Set one relay ON or OFF using the active level selected in Kconfig.
esp_err_t relay_set(relay_channel_t channel, bool on);
