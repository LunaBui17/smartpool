#pragma once

#include <stdbool.h>
#include "esp_err.h"

typedef enum {
    RELAY_1 = 0,
    RELAY_2,
} relay_channel_t;

// Configure enabled outputs OFF. Network initialization applies boot states
// after loading automation settings, before Wi-Fi starts.
esp_err_t relay_init(void);

// Set one relay ON or OFF. Input polarity is selected per channel in menuconfig. Disabled channels reject ON.
esp_err_t relay_set(relay_channel_t channel, bool on);
