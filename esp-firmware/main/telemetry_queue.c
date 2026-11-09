#include "telemetry_queue.h"

#include <string.h>

#include "nvs.h"

#define QUEUE_VERSION 1

typedef struct {
    uint32_t version;
    uint16_t head;
    uint16_t count;
    telemetry_sample_t samples[TELEMETRY_QUEUE_CAPACITY];
} queue_state_t;

static queue_state_t s_queue;
static nvs_handle_t s_nvs;
static bool s_ready;

static esp_err_t save_queue(void)
{
    esp_err_t err = nvs_set_blob(s_nvs, "queue", &s_queue, sizeof(s_queue));
    if (err == ESP_OK) err = nvs_commit(s_nvs);
    return err;
}

esp_err_t telemetry_queue_init(void)
{
    esp_err_t err = nvs_open("poolq", NVS_READWRITE, &s_nvs);
    if (err != ESP_OK) return err;

    size_t size = sizeof(s_queue);
    err = nvs_get_blob(s_nvs, "queue", &s_queue, &size);
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        memset(&s_queue, 0, sizeof(s_queue));
        s_queue.version = QUEUE_VERSION;
    } else if (err != ESP_OK || size != sizeof(s_queue) ||
               s_queue.version != QUEUE_VERSION ||
               s_queue.head >= TELEMETRY_QUEUE_CAPACITY ||
               s_queue.count > TELEMETRY_QUEUE_CAPACITY) {
        nvs_close(s_nvs);
        return err == ESP_OK ? ESP_ERR_INVALID_STATE : err;
    }
    s_ready = true;
    return ESP_OK;
}

esp_err_t telemetry_queue_push(const telemetry_sample_t *sample, bool *dropped_oldest)
{
    if (!s_ready || !sample || !dropped_oldest) return ESP_ERR_INVALID_ARG;
    uint16_t previous_head = s_queue.head;
    uint16_t previous_count = s_queue.count;
    uint16_t tail = (s_queue.head + s_queue.count) % TELEMETRY_QUEUE_CAPACITY;
    telemetry_sample_t previous_sample = s_queue.samples[tail];
    *dropped_oldest = s_queue.count == TELEMETRY_QUEUE_CAPACITY;
    if (*dropped_oldest) {
        s_queue.head = (s_queue.head + 1) % TELEMETRY_QUEUE_CAPACITY;
        s_queue.count--;
    }
    tail = (s_queue.head + s_queue.count) % TELEMETRY_QUEUE_CAPACITY;
    s_queue.samples[tail] = *sample;
    s_queue.count++;
    esp_err_t err = save_queue();
    if (err != ESP_OK) {
        s_queue.head = previous_head;
        s_queue.count = previous_count;
        s_queue.samples[tail] = previous_sample;
        *dropped_oldest = false;
    }
    return err;
}

bool telemetry_queue_peek(telemetry_sample_t *sample)
{
    return telemetry_queue_peek_at(0, sample);
}

bool telemetry_queue_peek_at(unsigned int offset, telemetry_sample_t *sample)
{
    if (!s_ready || !sample || offset >= s_queue.count) return false;
    *sample = s_queue.samples[(s_queue.head + offset) % TELEMETRY_QUEUE_CAPACITY];
    return true;
}

esp_err_t telemetry_queue_pop(void)
{
    return telemetry_queue_pop_many(1);
}

esp_err_t telemetry_queue_pop_many(unsigned int count)
{
    if (!s_ready || count == 0 || count > s_queue.count) return ESP_ERR_INVALID_STATE;
    uint16_t previous_head = s_queue.head;
    uint16_t previous_count = s_queue.count;
    s_queue.head = (s_queue.head + count) % TELEMETRY_QUEUE_CAPACITY;
    s_queue.count -= count;
    esp_err_t err = save_queue();
    if (err != ESP_OK) {
        s_queue.head = previous_head;
        s_queue.count = previous_count;
    }
    return err;
}

unsigned int telemetry_queue_count(void)
{
    return s_ready ? s_queue.count : 0;
}
