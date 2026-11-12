#include "pump_auto.h"

#include <math.h>

bool pump_auto_config_valid(const pump_auto_config_t *config)
{
    if (!config || (config->stop_mode != PUMP_STOP_DURATION && config->stop_mode != PUMP_STOP_THRESHOLD) ||
        config->duration_seconds < 1 || config->duration_seconds > 86400) return false;
    bool has_start = false, has_stop = false;
    for (int sensor = 0; sensor < PUMP_SENSOR_COUNT; sensor++) {
        const pump_auto_condition_t *start = &config->start[sensor], *stop = &config->stop[sensor];
        double minimum = sensor == PUMP_SENSOR_WATER ? 0 : -55;
        double maximum = sensor == PUMP_SENSOR_WATER ? 4095 : 125;
        if (start->enabled) {
            has_start = true;
            if (!isfinite(start->threshold) || start->threshold < minimum || start->threshold > maximum) return false;
        }
        if (stop->enabled) {
            has_stop = true;
            if (!isfinite(stop->threshold) || stop->threshold < minimum || stop->threshold > maximum) return false;
        }
        if (config->stop_mode == PUMP_STOP_THRESHOLD && start->enabled && stop->enabled) {
            if (start->below == stop->below ||
                (start->below ? stop->threshold <= start->threshold : stop->threshold >= start->threshold)) return false;
        }
    }
    return has_start && (config->stop_mode == PUMP_STOP_DURATION || has_stop);
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
                    const bool valid[PUMP_SENSOR_COUNT], const float value[PUMP_SENSOR_COUNT], uint64_t now_ms)
{
    pump_auto_step_allowed(config, runtime, valid, value, now_ms, true);
}

void pump_auto_step_allowed(const pump_auto_config_t *config, pump_auto_runtime_t *runtime,
                            const bool valid[PUMP_SENSOR_COUNT], const float value[PUMP_SENSOR_COUNT],
                            uint64_t now_ms, bool start_allowed)
{
    if (!config->enabled || runtime->paused) {
        runtime->running = false;
        if (!runtime->paused) runtime->reason = PUMP_AUTO_DISABLED;
        return;
    }
    bool stop_valid = true, start_seen = false, trigger = false, start_clear = true, target = false;
    for (int sensor = 0; sensor < PUMP_SENSOR_COUNT; sensor++) {
        bool measured = valid[sensor] && isfinite(value[sensor]);
        const pump_auto_condition_t *start = &config->start[sensor], *stop = &config->stop[sensor];
        if (start->enabled) {
            start_seen |= measured;
            if (!measured) start_clear = false;
            else {
                bool met = start->below ? value[sensor] <= start->threshold : value[sensor] >= start->threshold;
                trigger |= met;
                if (met) start_clear = false;
            }
        }
        if (config->stop_mode == PUMP_STOP_THRESHOLD && stop->enabled) {
            if (!measured) stop_valid = false;
            else target |= stop->below ? value[sensor] <= stop->threshold : value[sensor] >= stop->threshold;
        }
    }
    if (runtime->running) {
        if (!stop_valid || !start_seen) {
            runtime->running = false;
            runtime->reason = PUMP_AUTO_SENSOR_ERROR;
        } else if (now_ms - runtime->started_ms >= (uint64_t)config->duration_seconds * 1000) {
            runtime->running = false;
            runtime->reason = config->stop_mode == PUMP_STOP_DURATION
                ? PUMP_AUTO_DURATION_DONE : PUMP_AUTO_TIMEOUT;
        } else if (config->stop_mode == PUMP_STOP_THRESHOLD && target) {
            runtime->running = false;
            runtime->reason = PUMP_AUTO_TARGET_REACHED;
        }
        if (!runtime->running) runtime->last_stop = runtime->reason;
        return;
    }
    if (!start_seen || !stop_valid) {
        runtime->reason = PUMP_AUTO_WAIT_SENSOR;
        return;
    }
    if (!trigger) {
        if (start_clear) runtime->armed = true;
        runtime->reason = PUMP_AUTO_READY;
    } else if (config->stop_mode == PUMP_STOP_THRESHOLD && target) {
        runtime->reason = PUMP_AUTO_WAIT_RESET;
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
