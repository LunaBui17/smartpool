#include "telemetry_queue.h"

#include <string.h>
#include <stdlib.h>

#include "nvs.h"

#define QUEUE_VERSION 3

typedef struct {
    uint64_t id;
    float temperature;
    int16_t water_raw;
    uint8_t flags;
    uint8_t status_valid;
} legacy_sample_t;
_Static_assert(sizeof(legacy_sample_t) == 16, "Persisted v1/v2 layout");
_Static_assert(sizeof(telemetry_sample_t) == 24, "Persisted v3 layout");
typedef struct {
    uint32_t version;
    uint16_t head, count;
    legacy_sample_t samples[TELEMETRY_QUEUE_CAPACITY];
} legacy_queue_t;

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

esp_err_t telemetry_queue_check_device(const char *device_id, const char *legacy_device_id)
{
    if (!device_id || !device_id[0] || !legacy_device_id) return ESP_ERR_INVALID_ARG;
    nvs_handle_t handle;
    esp_err_t error = nvs_open("poolq", NVS_READONLY, &handle);
    if (error == ESP_ERR_NVS_NOT_FOUND) return ESP_OK;
    if (error != ESP_OK) return error;
    char owner[65];
    size_t size = sizeof(owner);
    error = nvs_get_str(handle, "device_id", owner, &size);
    if (error == ESP_OK) {
        error = strcmp(owner, device_id) ? ESP_ERR_INVALID_STATE : ESP_OK;
    } else if (error == ESP_ERR_NVS_NOT_FOUND) {
        size = 0;
        error = nvs_get_blob(handle, "queue", NULL, &size);
        if (error == ESP_ERR_NVS_NOT_FOUND) error = ESP_OK;
        else if (error == ESP_OK && (size == sizeof(legacy_queue_t) || size == sizeof(queue_state_t))) {
            void *blob = malloc(size);
            if (!blob) error = ESP_ERR_NO_MEM;
            else {
                error = nvs_get_blob(handle, "queue", blob, &size);
                if (error == ESP_OK) {
                    uint16_t count;
                    memcpy(&count, (const uint8_t *)blob + 6, sizeof(count));
                    if (count && strcmp(device_id, legacy_device_id)) error = ESP_ERR_INVALID_STATE;
                }
                free(blob);
            }
        } else if (error == ESP_OK) error = ESP_ERR_INVALID_STATE;
    }
    nvs_close(handle);
    return error;
}

esp_err_t telemetry_queue_init(const char *device_id, const char *legacy_device_id)
{
    esp_err_t binding = telemetry_queue_check_device(device_id, legacy_device_id);
    if (binding != ESP_OK) return binding;
    esp_err_t err = nvs_open("poolq", NVS_READWRITE, &s_nvs);
    if (err != ESP_OK) return err;

    size_t size = 0;
    err = nvs_get_blob(s_nvs, "queue", NULL, &size);
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        memset(&s_queue, 0, sizeof(s_queue));
        s_queue.version = QUEUE_VERSION;
    } else if (err == ESP_OK && size == sizeof(legacy_queue_t)) {
        legacy_queue_t *old = calloc(1, sizeof(*old));
        if (!old) { nvs_close(s_nvs); return ESP_ERR_NO_MEM; }
        err = nvs_get_blob(s_nvs, "queue", old, &size);
        if (err == ESP_OK && (old->version == 1 || old->version == 2) &&
            old->head < TELEMETRY_QUEUE_CAPACITY && old->count <= TELEMETRY_QUEUE_CAPACITY) {
            memset(&s_queue, 0, sizeof(s_queue));
            s_queue.version = QUEUE_VERSION;
            s_queue.head = old->head;
            s_queue.count = old->count;
            for (unsigned int i = 0; i < TELEMETRY_QUEUE_CAPACITY; i++) {
                s_queue.samples[i].id = old->samples[i].id;
                s_queue.samples[i].temperature = old->samples[i].temperature;
                s_queue.samples[i].water_raw = old->samples[i].water_raw;
                s_queue.samples[i].flags = old->samples[i].flags;
                s_queue.samples[i].status_valid = old->version == 1 ? 0 : old->samples[i].status_valid;
            }
            err = save_queue();
        } else if (err == ESP_OK) err = ESP_ERR_INVALID_STATE;
        free(old);
        if (err != ESP_OK) { nvs_close(s_nvs); return err; }
    } else if (err == ESP_OK && size == sizeof(s_queue)) {
        err = nvs_get_blob(s_nvs, "queue", &s_queue, &size);
    } else {
        nvs_close(s_nvs);
        return err == ESP_OK ? ESP_ERR_INVALID_STATE : err;
    }
    if (err != ESP_OK && err != ESP_ERR_NVS_NOT_FOUND) {
        nvs_close(s_nvs);
        return err;
    }
    if (s_queue.version != QUEUE_VERSION ||
               s_queue.head >= TELEMETRY_QUEUE_CAPACITY ||
               s_queue.count > TELEMETRY_QUEUE_CAPACITY) {
        nvs_close(s_nvs);
        return ESP_ERR_INVALID_STATE;
    }
    err = nvs_set_str(s_nvs, "device_id", device_id);
    if (err == ESP_OK) err = nvs_commit(s_nvs);
    if (err != ESP_OK) { nvs_close(s_nvs); return err; }
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
