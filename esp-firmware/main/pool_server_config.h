#pragma once
#include "cJSON.h"
#include "pump_auto.h"
#include "nvs.h"

// One credential-free NVS snapshot. Revision only advances with a committed snapshot.
typedef struct {
    uint32_t version;
    uint64_t revision;
    double temperature_min, temperature_max;
    int32_t water_adc_min, water_adc_max;
    double water_cm_min, water_cm_max;
    bool interlock;
    pump_auto_config_t automation[2];
} pool_server_config_t;

bool pool_server_config_parse(const cJSON *object, const char *device_id, pool_server_config_t *out);
bool pool_server_config_equal(const pool_server_config_t *a, const pool_server_config_t *b);
esp_err_t pool_server_config_load(nvs_handle_t nvs, pool_server_config_t *out);
esp_err_t pool_server_config_save(nvs_handle_t nvs, const pool_server_config_t *config);
