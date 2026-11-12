#pragma once

#include <stdbool.h>
#include <stdint.h>

typedef enum { PUMP_SENSOR_WATER, PUMP_SENSOR_TEMPERATURE } pump_sensor_t;
typedef enum { PUMP_STOP_DURATION, PUMP_STOP_THRESHOLD } pump_stop_mode_t;
typedef enum {
    PUMP_AUTO_DISABLED, PUMP_AUTO_WAIT_SENSOR, PUMP_AUTO_READY, PUMP_AUTO_RUNNING,
    PUMP_AUTO_WAIT_RESET, PUMP_AUTO_DURATION_DONE, PUMP_AUTO_TARGET_REACHED,
    PUMP_AUTO_TIMEOUT, PUMP_AUTO_SENSOR_ERROR, PUMP_AUTO_MANUAL, PUMP_AUTO_RELAY_ERROR,
} pump_auto_reason_t;

typedef struct {
    bool enabled;
    pump_sensor_t sensor;
    bool start_below;
    double start_threshold;
    pump_stop_mode_t stop_mode;
    double stop_threshold;
    uint32_t duration_seconds;
} pump_auto_config_t;

typedef struct {
    bool running;
    bool armed;
    bool paused;
    uint64_t started_ms;
    pump_auto_reason_t reason;
    pump_auto_reason_t last_stop;
} pump_auto_runtime_t;

bool pump_auto_config_valid(const pump_auto_config_t *config);
void pump_auto_reset(pump_auto_runtime_t *runtime);
void pump_auto_pause(pump_auto_runtime_t *runtime);
void pump_auto_step(const pump_auto_config_t *config, pump_auto_runtime_t *runtime,
                    bool sensor_valid, float value, uint64_t now_ms);
const char *pump_auto_reason_name(pump_auto_reason_t reason);
