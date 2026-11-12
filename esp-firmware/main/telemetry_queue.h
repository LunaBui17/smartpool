#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

#define TELEMETRY_HAS_TEMPERATURE 0x01
#define TELEMETRY_HAS_WATER       0x02
#define TELEMETRY_WATER_NORMAL    0x04
#define TELEMETRY_PUMP_IN             0x08
#define TELEMETRY_PUMP_OUT            0x10
#define TELEMETRY_TEMPERATURE_ENABLED 0x20
#define TELEMETRY_WATER_ENABLED       0x40
#define TELEMETRY_ONLINE              0x80
#define TELEMETRY_QUEUE_CAPACITY  128

typedef struct {
    uint64_t id;
    float temperature;
    int16_t water_raw;
    uint8_t flags;
    uint8_t status_valid; // Uses v1 padding; historical v1 samples have no state.
} telemetry_sample_t;

esp_err_t telemetry_queue_init(void);
esp_err_t telemetry_queue_push(const telemetry_sample_t *sample, bool *dropped_oldest);
bool telemetry_queue_peek(telemetry_sample_t *sample);
bool telemetry_queue_peek_at(unsigned int offset, telemetry_sample_t *sample);
esp_err_t telemetry_queue_pop(void);
esp_err_t telemetry_queue_pop_many(unsigned int count);
unsigned int telemetry_queue_count(void);
