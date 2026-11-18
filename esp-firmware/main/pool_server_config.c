#include "pool_server_config.h"
#include <math.h>
#include <string.h>

static bool number(const cJSON *object, const char *name, double low, double high, double *out)
{
    const cJSON *value = cJSON_GetObjectItemCaseSensitive(object, name);
    if (!cJSON_IsNumber(value) || !isfinite(value->valuedouble) ||
        value->valuedouble < low || value->valuedouble > high) return false;
    *out = value->valuedouble;
    return true;
}

static bool boolean(const cJSON *object, const char *name, bool *out)
{
    const cJSON *value = cJSON_GetObjectItemCaseSensitive(object, name);
    if (!cJSON_IsBool(value)) return false;
    *out = cJSON_IsTrue(value);
    return true;
}

static bool conditions(const cJSON *object, pump_auto_condition_t out[2])
{
    if (!cJSON_IsObject(object)) return false;
    for (int i = 0; i < 2; i++) {
        const cJSON *condition = cJSON_GetObjectItemCaseSensitive(object, i == 0 ? "water" : "temperature");
        if (!cJSON_IsObject(condition) || !boolean(condition, "enabled", &out[i].enabled)) return false;
        if (!out[i].enabled) continue; // API normalizes disabled conditions to {enabled:false}.
        const cJSON *comparison = cJSON_GetObjectItemCaseSensitive(condition, "comparison");
        if (!cJSON_IsString(comparison) ||
            (strcmp(comparison->valuestring, "below") && strcmp(comparison->valuestring, "above"))) return false;
        out[i].below = strcmp(comparison->valuestring, "below") == 0;
        if (!number(condition, "threshold", i == 0 ? 0 : -55, i == 0 ? 4095 : 125, &out[i].threshold)) return false;
    }
    return true;
}

bool pool_server_config_parse(const cJSON *object, const char *device_id, pool_server_config_t *out)
{
    if (!out || !cJSON_IsObject(object)) return false;
    memset(out, 0, sizeof(*out));
    out->version = 1;
    const cJSON *id = cJSON_GetObjectItemCaseSensitive(object, "device_id");
    double revision, minimum, maximum;
    if (!cJSON_IsString(id) || strcmp(id->valuestring, device_id) ||
        !number(object, "revision", 1, 9007199254740991.0, &revision) || floor(revision) != revision ||
        !number(object, "temperature_min", -55, 125, &out->temperature_min) ||
        !number(object, "temperature_max", -55, 125, &out->temperature_max) ||
        out->temperature_min >= out->temperature_max ||
        !number(object, "water_adc_min", 0, 4095, &minimum) || floor(minimum) != minimum ||
        !number(object, "water_adc_max", 0, 4095, &maximum) || floor(maximum) != maximum || minimum >= maximum ||
        !number(object, "water_cm_min", 0, 100000, &out->water_cm_min) ||
        !number(object, "water_cm_max", 0, 100000, &out->water_cm_max) || out->water_cm_min >= out->water_cm_max ||
        !boolean(object, "interlock", &out->interlock)) return false;
    out->revision = (uint64_t)revision;
    out->water_adc_min = (int32_t)minimum;
    out->water_adc_max = (int32_t)maximum;
    for (int i = 0; i < 2; i++) {
        out->automation[i].stop_mode = PUMP_STOP_THRESHOLD;
        out->automation[i].duration_seconds = 300;
    }
    const cJSON *rules = cJSON_GetObjectItemCaseSensitive(object, "automation");
    if (!cJSON_IsArray(rules) || cJSON_GetArraySize(rules) > 2) return false;
    bool seen[2] = {false, false};
    const cJSON *rule;
    cJSON_ArrayForEach(rule, rules) {
        double channel, duration;
        if (!number(rule, "channel", 1, 2, &channel) || floor(channel) != channel || seen[(int)channel-1]) return false;
        int index = (int)channel - 1;
        seen[index] = true;
        pump_auto_config_t *config = &out->automation[index];
        const cJSON *mode = cJSON_GetObjectItemCaseSensitive(rule, "stop_mode");
        if (!boolean(rule, "enabled", &config->enabled) || !cJSON_IsString(mode) ||
            (strcmp(mode->valuestring, "duration") && strcmp(mode->valuestring, "threshold")) ||
            !number(rule, "duration_seconds", 1, 86400, &duration) || floor(duration) != duration ||
            !conditions(cJSON_GetObjectItemCaseSensitive(rule, "start_conditions"), config->start) ||
            !conditions(cJSON_GetObjectItemCaseSensitive(rule, "stop_conditions"), config->stop)) return false;
        config->stop_mode = strcmp(mode->valuestring, "duration") == 0 ? PUMP_STOP_DURATION : PUMP_STOP_THRESHOLD;
        config->duration_seconds = (uint32_t)duration;
        if (config->enabled && !pump_auto_config_valid(config)) return false;
    }
    return true;
}

bool pool_server_config_equal(const pool_server_config_t *a, const pool_server_config_t *b)
{
    if (a->revision != b->revision || a->temperature_min != b->temperature_min ||
        a->temperature_max != b->temperature_max || a->water_adc_min != b->water_adc_min ||
        a->water_adc_max != b->water_adc_max || a->water_cm_min != b->water_cm_min ||
        a->water_cm_max != b->water_cm_max || a->interlock != b->interlock) return false;
    for (int i = 0; i < 2; i++) {
        const pump_auto_config_t *x = &a->automation[i], *y = &b->automation[i];
        if (x->enabled != y->enabled || x->stop_mode != y->stop_mode || x->duration_seconds != y->duration_seconds) return false;
        for (int sensor = 0; sensor < PUMP_SENSOR_COUNT; sensor++) {
            const pump_auto_condition_t *xs = &x->start[sensor], *ys = &y->start[sensor];
            const pump_auto_condition_t *xe = &x->stop[sensor], *ye = &y->stop[sensor];
            if (xs->enabled != ys->enabled || xe->enabled != ye->enabled ||
                (xs->enabled && (xs->below != ys->below || xs->threshold != ys->threshold)) ||
                (xe->enabled && (xe->below != ye->below || xe->threshold != ye->threshold))) return false;
        }
    }
    return true;
}

esp_err_t pool_server_config_load(nvs_handle_t nvs, pool_server_config_t *out)
{
    size_t size = sizeof(*out);
    esp_err_t error = nvs_get_blob(nvs, "server_cfg", out, &size);
    if (error == ESP_OK) {
        if (size != sizeof(*out) || out->version != 1 || !out->revision || out->revision > 9007199254740991ULL ||
            !isfinite(out->temperature_min) || !isfinite(out->temperature_max) ||
            out->temperature_min < -55 || out->temperature_max > 125 || out->temperature_min >= out->temperature_max ||
            out->water_adc_min < 0 || out->water_adc_max > 4095 || out->water_adc_min >= out->water_adc_max ||
            !isfinite(out->water_cm_min) || !isfinite(out->water_cm_max) ||
            out->water_cm_min < 0 || out->water_cm_max > 100000 || out->water_cm_min >= out->water_cm_max)
            return ESP_ERR_INVALID_STATE;
        for (int i = 0; i < 2; i++) {
            if (out->automation[i].enabled && !pump_auto_config_valid(&out->automation[i])) return ESP_ERR_INVALID_STATE;
        }
    }
    return error;
}

esp_err_t pool_server_config_save(nvs_handle_t nvs, const pool_server_config_t *config)
{
    esp_err_t error = nvs_set_blob(nvs, "server_cfg", config, sizeof(*config));
    return error == ESP_OK ? nvs_commit(nvs) : error;
}
