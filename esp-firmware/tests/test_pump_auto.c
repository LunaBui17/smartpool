#include "pump_auto.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>

static pump_auto_config_t rule(void)
{
    pump_auto_config_t c = {.enabled = true, .stop_mode = PUMP_STOP_THRESHOLD, .duration_seconds = 5};
    c.start[PUMP_SENSOR_WATER] = (pump_auto_condition_t){true, true, 1500};
    c.start[PUMP_SENSOR_TEMPERATURE] = (pump_auto_condition_t){true, false, 30};
    c.stop[PUMP_SENSOR_WATER] = (pump_auto_condition_t){true, false, 2500};
    c.stop[PUMP_SENSOR_TEMPERATURE] = (pump_auto_condition_t){true, true, 28};
    return c;
}

static void step(const pump_auto_config_t *c, pump_auto_runtime_t *r,
                 bool water_ok, float water, bool temp_ok, float temp, uint64_t ms, bool allowed)
{
    const bool valid[2] = {water_ok, temp_ok};
    const float values[2] = {water, temp};
    pump_auto_step_allowed(c, r, valid, values, ms, allowed);
}

int main(void)
{
    pump_auto_config_t c = rule();
    pump_auto_runtime_t r;
    assert(pump_auto_config_valid(&c));
    c.start[0].enabled = c.start[1].enabled = false;
    assert(!pump_auto_config_valid(&c));
    c = rule(); c.stop[0].enabled = c.stop[1].enabled = false;
    assert(!pump_auto_config_valid(&c));
    c.stop_mode = PUMP_STOP_DURATION;
    assert(pump_auto_config_valid(&c));
    c = rule(); c.stop[0].threshold = 1000;
    assert(!pump_auto_config_valid(&c));
    c = rule(); c.start[1].threshold = NAN;
    assert(!pump_auto_config_valid(&c));
    c = rule();
    pump_auto_reset(&r);
    step(&c, &r, true, 2000, true, 29, 0, true);
    assert(!r.running && r.armed);
    step(&c, &r, true, 1400, true, 29, 250, true);
    assert(r.running); // Water starts.
    step(&c, &r, true, 2000, true, 27, 500, true);
    assert(!r.running && r.reason == PUMP_AUTO_TARGET_REACHED); // Temperature stops.
    step(&c, &r, true, 1400, true, 29, 750, true);
    assert(!r.running && r.reason == PUMP_AUTO_WAIT_RESET);
    step(&c, &r, true, 2000, true, 29, 1000, true);
    assert(r.armed);
    step(&c, &r, true, 2000, true, 31, 1250, true);
    assert(r.running); // Temperature starts.
    step(&c, &r, true, 2500, true, 29, 1500, true);
    assert(!r.running && r.reason == PUMP_AUTO_TARGET_REACHED); // Water stops.
    pump_auto_reset(&r);
    step(&c, &r, true, 1400, true, 29, 2000, false);
    assert(!r.running && r.reason == PUMP_AUTO_INTERLOCK && r.armed);
    step(&c, &r, true, 1400, true, 29, 2250, true);
    assert(r.running);
    step(&c, &r, true, 1400, false, 0, 2500, true);
    assert(!r.running && r.reason == PUMP_AUTO_SENSOR_ERROR);
    pump_auto_reset(&r);
    step(&c, &r, true, 1400, true, 29, 3000, true);
    step(&c, &r, true, 1400, true, 29, 8000, true);
    assert(!r.running && r.reason == PUMP_AUTO_TIMEOUT);
    pump_auto_pause(&r);
    step(&c, &r, true, 1400, true, 29, 8250, true);
    assert(!r.running && r.paused);
    puts("PASS: dual-sensor OR automation, validation, safety, timeout, interlock");
    return 0;
}
