#pragma once
#include "esp_err.h"
#include <stdint.h>
typedef struct {
    uint32_t version;
    char job_id[37], token[65], api_url[257], device_id[65], factory_mac[18];
} pool_reset_job_t;
esp_err_t pool_reset_load(pool_reset_job_t *job);
esp_err_t pool_reset_save(const pool_reset_job_t *job);
esp_err_t pool_reset_clear_app(void);
esp_err_t pool_reset_forget(void);
