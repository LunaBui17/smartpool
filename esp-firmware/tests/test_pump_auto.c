// Host test: cc -std=c11 -Wall -Wextra -Werror -Imain main/pump_auto.c
//            tests/test_pump_auto.c -o build/pump_auto_tests && build/pump_auto_tests
#include "pump_auto.h"

#include <assert.h>
#include <math.h>
#include <stdio.h>

static pump_auto_config_t water_rule(void)
{
    return (pump_auto_config_t){.enabled = true, .sensor = PUMP_SENSOR_WATER,
        .start_below = true, .start_threshold = 1500, .stop_mode = PUMP_STOP_THRESHOLD,
        .stop_threshold = 2500, .duration_seconds = 3};
}

static void test_validation(void)
{
    pump_auto_config_t c = water_rule();
    assert(pump_auto_config_valid(&c));
    assert(!pump_auto_config_valid(0));
    c.stop_threshold = c.start_threshold;
    assert(!pump_auto_config_valid(&c));
    c.stop_mode = PUMP_STOP_DURATION;
    assert(pump_auto_config_valid(&c));
    c.duration_seconds = 0;
    assert(!pump_auto_config_valid(&c));
    c.duration_seconds = 86401;
    assert(!pump_auto_config_valid(&c));
    c.duration_seconds = 86400;
    c.start_threshold = NAN;
    assert(!pump_auto_config_valid(&c));
    c.start_threshold = 4096;
    assert(!pump_auto_config_valid(&c));
    c = water_rule();
    c.sensor = PUMP_SENSOR_TEMPERATURE;
    c.start_below = false;
    c.start_threshold = 30;
    c.stop_threshold = 28;
    assert(pump_auto_config_valid(&c));
    c.stop_threshold = 31;
    assert(!pump_auto_config_valid(&c));
    c.stop_threshold = 28;
    c.start_threshold = 126;
    assert(!pump_auto_config_valid(&c));
    c.start_threshold = 30;
    c.sensor = (pump_sensor_t)42;
    assert(!pump_auto_config_valid(&c));
    c = water_rule();
    c.stop_mode = (pump_stop_mode_t)42;
    assert(!pump_auto_config_valid(&c));
}

static void test_threshold_hysteresis(void)
{
    pump_auto_config_t c = water_rule();
    pump_auto_runtime_t r;
    pump_auto_reset(&r);
    pump_auto_step(&c, &r, false, 0, 0);
    assert(!r.running && r.reason == PUMP_AUTO_WAIT_SENSOR);
    pump_auto_step(&c, &r, true, 1500, 1000);
    assert(r.running && !r.armed);
    pump_auto_step(&c, &r, true, 2000, 2000);
    assert(r.running);
    pump_auto_step(&c, &r, true, 2500, 3000);
    assert(!r.running && r.reason == PUMP_AUTO_TARGET_REACHED);
    pump_auto_step(&c, &r, true, 2500, 3250);
    assert(!r.running && r.armed);
    pump_auto_step(&c, &r, true, 1500, 3500);
    assert(r.running);
}

static void test_timer_and_rearm(void)
{
    pump_auto_config_t c = water_rule();
    c.stop_mode = PUMP_STOP_DURATION;
    pump_auto_runtime_t r;
    pump_auto_reset(&r);
    uint64_t start = (uint64_t)UINT32_MAX - 1000;
    pump_auto_step(&c, &r, true, 1000, start);
    assert(r.running);
    pump_auto_step(&c, &r, true, 1000, start + 2999);
    assert(r.running);
    pump_auto_step(&c, &r, true, 1000, start + 3000);
    assert(!r.running && r.reason == PUMP_AUTO_DURATION_DONE);
    pump_auto_step(&c, &r, true, 1000, start + 5000);
    assert(!r.running && r.reason == PUMP_AUTO_WAIT_RESET);
    pump_auto_step(&c, &r, true, 1501, start + 6000);
    assert(!r.running && r.armed);
    pump_auto_step(&c, &r, true, 1500, start + 7000);
    assert(r.running);
}

static void test_temperature_timeout_and_sensor_loss(void)
{
    pump_auto_config_t c = water_rule();
    c.sensor = PUMP_SENSOR_TEMPERATURE;
    c.start_below = false;
    c.start_threshold = 30;
    c.stop_threshold = 28;
    pump_auto_runtime_t r;
    pump_auto_reset(&r);
    pump_auto_step(&c, &r, true, 30, 0);
    assert(r.running);
    pump_auto_step(&c, &r, true, 29, 1000);
    assert(r.running);
    pump_auto_step(&c, &r, true, 28, 2000);
    assert(!r.running && r.reason == PUMP_AUTO_TARGET_REACHED);
    pump_auto_reset(&r);
    pump_auto_step(&c, &r, true, 31, 0);
    pump_auto_step(&c, &r, true, 31, 3000);
    assert(!r.running && r.reason == PUMP_AUTO_TIMEOUT);
    pump_auto_step(&c, &r, true, 31, 3250);
    assert(!r.running);
    pump_auto_step(&c, &r, true, 29, 3500);
    pump_auto_step(&c, &r, true, 30, 4000);
    assert(r.running);
    pump_auto_step(&c, &r, false, 30, 4250);
    assert(!r.running && r.reason == PUMP_AUTO_SENSOR_ERROR);
    pump_auto_step(&c, &r, true, 31, 4500);
    assert(!r.running);
    pump_auto_reset(&r);
    pump_auto_step(&c, &r, true, 30, 5000);
    pump_auto_step(&c, &r, true, INFINITY, 5250);
    assert(!r.running && r.reason == PUMP_AUTO_SENSOR_ERROR);
}

static void test_fractional_temperature_thresholds(void)
{
    pump_auto_config_t c = water_rule();
    c.sensor = PUMP_SENSOR_TEMPERATURE;
    c.start_below = false;
    c.start_threshold = 30.1;
    c.stop_threshold = 28.2;
    assert(pump_auto_config_valid(&c));
    assert(c.start_threshold == 30.1 && c.stop_threshold == 28.2);
    pump_auto_runtime_t r;
    pump_auto_reset(&r);
    pump_auto_step(&c, &r, true, 30.0625f, 0);
    assert(!r.running);
    pump_auto_step(&c, &r, true, 30.125f, 250);
    assert(r.running);
    pump_auto_step(&c, &r, true, 28.25f, 500);
    assert(r.running);
    pump_auto_step(&c, &r, true, 28.1875f, 750);
    assert(!r.running && r.reason == PUMP_AUTO_TARGET_REACHED);
}

static void test_independent_channels_and_manual_pause(void)
{
    pump_auto_config_t inlet = water_rule(), outlet = water_rule();
    outlet.start_below = false;
    outlet.start_threshold = 3000;
    outlet.stop_threshold = 2000;
    pump_auto_runtime_t a, b;
    pump_auto_reset(&a);
    pump_auto_reset(&b);
    pump_auto_step(&inlet, &a, true, 1000, 0);
    pump_auto_step(&outlet, &b, true, 3500, 0);
    assert(a.running && b.running);
    pump_auto_pause(&a);
    pump_auto_step(&inlet, &a, true, 1000, 1000);
    pump_auto_step(&outlet, &b, true, 3500, 1000);
    assert(!a.running && a.paused && b.running);
    pump_auto_reset(&a);
    inlet.enabled = false;
    pump_auto_step(&inlet, &a, true, 1000, 2000);
    assert(!a.running && a.reason == PUMP_AUTO_DISABLED);
    pump_auto_reset(&b);
    pump_auto_step(&outlet, &b, true, NAN, 2000);
    assert(!b.running);
}

static void test_interlocked_automation(void)
{
    pump_auto_config_t inlet = water_rule(), outlet = water_rule();
    inlet.stop_mode = PUMP_STOP_DURATION;
    outlet.stop_mode = PUMP_STOP_DURATION;
    outlet.start_below = false;
    outlet.start_threshold = 3000;
    pump_auto_runtime_t a, b;
    pump_auto_reset(&a);
    pump_auto_reset(&b);
    pump_auto_step_allowed(&inlet, &a, true, 1000, 1000, true);
    pump_auto_step_allowed(&outlet, &b, true, 3500, 1000, !a.running);
    assert(a.running && !b.running && b.armed && b.reason == PUMP_AUTO_INTERLOCK);
    pump_auto_step_allowed(&outlet, &b, true, 3500, 10000, !a.running);
    assert(!b.running && b.armed); // Waiting must not consume its run-time budget or trigger.
    pump_auto_step_allowed(&inlet, &a, true, 1000, 10000, false);
    assert(!a.running && a.reason == PUMP_AUTO_DURATION_DONE); // Stop checks always run.
    pump_auto_step_allowed(&outlet, &b, true, 3500, 10250, !a.running);
    assert(b.running && b.started_ms == 10250);
    pump_auto_step_allowed(&outlet, &b, true, 3500, 13249, false);
    assert(b.running);
    pump_auto_step_allowed(&outlet, &b, true, 3500, 13250, false);
    assert(!b.running && b.reason == PUMP_AUTO_DURATION_DONE);
    pump_auto_step_allowed(&outlet, &b, true, 3500, 13500, true);
    assert(!b.running && b.reason == PUMP_AUTO_WAIT_RESET);
    pump_auto_reset(&b);
    pump_auto_step_allowed(&outlet, &b, true, 3500, 14000, false);
    pump_auto_step_allowed(&outlet, &b, false, 3500, 14250, false);
    assert(!b.running && b.reason == PUMP_AUTO_WAIT_SENSOR);
    pump_auto_step_allowed(&outlet, &b, true, 3500, 14500, true);
    assert(b.running);
    pump_auto_step_allowed(&outlet, &b, false, 3500, 14750, false);
    assert(!b.running && b.reason == PUMP_AUTO_SENSOR_ERROR);
    assert(pump_auto_reason_name(PUMP_AUTO_INTERLOCK)[0] == 'i');
}

int main(void)
{
    test_interlocked_automation();
    test_validation();
    test_threshold_hysteresis();
    test_timer_and_rearm();
    test_temperature_timeout_and_sensor_loss();
    test_fractional_temperature_thresholds();
    test_independent_channels_and_manual_pause();
    puts("PASS: automation thresholds, timers, hysteresis, sensor faults, manual pause, independent pumps");
    return 0;
}
