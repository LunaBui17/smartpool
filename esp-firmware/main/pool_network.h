#pragma once

#include <stdbool.h>
#include "esp_err.h"

esp_err_t pool_network_init(void);
void pool_network_send_telemetry(bool has_temperature, float temperature,
                                 bool has_water, bool water_normal, int water_raw);
