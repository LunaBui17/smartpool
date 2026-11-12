#include "pump_auto.h"

#include <math.h>

bool pump_auto_config_valid(const pump_auto_config_t *config)
{
    if (!config || (config->sensor != PUMP_SENSOR_WATER && config->sensor != PUMP_SENSOR_TEMPERATURE) ||
        (config->stop_mode != PUMP_STOP_DURATION && config->stop_mode != PUMP_STOP_THRESHOLD) ||
        !isfinite(config->start_threshold) || !isfinite(config->stop_threshold) ||
        config->duration_seconds < 1 || config->duration_seconds > 86400) return false;
    float minimum = config->sensor == PUMP_SENSOR_WATER ? 0 : -55;
    float maximum = config->sensor == PUMP_SENSOR_WATER ? 4095 : 125;
    if (config->start_threshold < minimum || config->start_threshold > maximum ||
        config->stop_threshold < minimum || config->stop_threshold > maximum) return false;
    if (config->stop_mode == PUMP_STOP_THRESHOLD) {
        if (config->start_below && config->stop_threshold <= config->start_threshold) return false;
        if (!config->start_below && config->stop_threshold >= config->start_threshold) return false;
    }
    return true;
}

void pump_auto_reset(pump_auto_runtime_t *runtime)
{
    *runtime = (pump_auto_runtime_t){.armed = true, .reason = PUMP_AUTO_WAIT_SENSOR};
}

void pump_auto_pause(pump_auto_runtime_t *runtime)
{
    runtime->running = false;
    runtime->armed = false;
    runtime->paused = true;
    runtime->reason = PUMP_AUTO_MANUAL;
    runtime->last_stop = PUMP_AUTO_MANUAL;
}

void pump_auto_step(const pump_auto_config_t *config, pump_auto_runtime_t *runtime,
                    bool sensor_valid, float value, uint64_t now_ms)
{
    pump_auto_step_allowed(config, runtime, sensor_valid, value, now_ms, true);
}

void pump_auto_step_allowed(const pump_auto_config_t *config, pump_auto_runtime_t *runtime,
                            bool sensor_valid, float value, uint64_t now_ms, bool start_allowed)
{
    if (!config->enabled || runtime->paused) {
        runtime->running = false;
        if (!runtime->paused) runtime->reason = PUMP_AUTO_DISABLED;
        return;
    }
    sensor_valid = sensor_valid && isfinite(value);
    if (runtime->running) {
        if (!sensor_valid) {
            runtime->running = false;
            runtime->reason = PUMP_AUTO_SENSOR_ERROR;
        } else if (now_ms - runtime->started_ms >= (uint64_t)config->duration_seconds * 1000) {
            runtime->running = false;
            runtime->reason = config->stop_mode == PUMP_STOP_DURATION
                ? PUMP_AUTO_DURATION_DONE : PUMP_AUTO_TIMEOUT;
        } else if (config->stop_mode == PUMP_STOP_THRESHOLD &&
                   (config->start_below ? value >= config->stop_threshold : value <= config->stop_threshold)) {
            runtime->running = false;
            runtime->reason = PUMP_AUTO_TARGET_REACHED;
        }
        if (!runtime->running) runtime->last_stop = runtime->reason;
        return;
    }
    if (!sensor_valid) {
        runtime->reason = PUMP_AUTO_WAIT_SENSOR;
        return;
    }
    bool trigger = config->start_below ? value <= config->start_threshold : value >= config->start_threshold;
    if (!trigger) {
        runtime->armed = true;
        runtime->reason = PUMP_AUTO_READY;
    } else if (runtime->armed && !start_allowed) {
        runtime->reason = PUMP_AUTO_INTERLOCK;
    } else if (runtime->armed) {
        runtime->running = true;
        runtime->armed = false;
        runtime->started_ms = now_ms;
        runtime->reason = PUMP_AUTO_RUNNING;
    } else {
        // A timed-out/completed run must not immediately restart on the same condition.
        runtime->reason = PUMP_AUTO_WAIT_RESET;
    }
}

const char *pump_auto_reason_name(pump_auto_reason_t reason)
{
    static const char *names[] = {"disabled", "waiting_sensor", "ready", "running", "waiting_reset",
        "duration_done", "target_reached", "timeout", "sensor_error", "manual", "relay_error", "interlock"};
    return (unsigned)reason < sizeof(names) / sizeof(names[0]) ? names[(unsigned)reason] : "unknown";
}
