#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define POOL_MQTT_PAYLOAD_MAX 4096
#define POOL_MQTT_TOPIC_MAX 80
#define POOL_CFG_THRESHOLDS 1u
#define POOL_CFG_WIFI 2u
#define POOL_CFG_DEVICE 4u
#define POOL_CFG_HTTP 8u

typedef struct {
    double temp_min, temp_max, water_min, water_max;
    char ssid[33], password[64];
    char id[65], name[129], location[129];
    char api_url[257];
    bool interlock; // Occupies previously unused padding in the v1 settings blob.
    uint64_t timestamps[4];
} pool_mqtt_settings_t;

typedef enum {
    POOL_MQTT_INVALID, POOL_MQTT_PUMP_IN, POOL_MQTT_PUMP_OUT,
    POOL_MQTT_TEMPERATURE, POOL_MQTT_WATER, POOL_MQTT_SCAN, POOL_MQTT_CONFIG
} pool_mqtt_command_kind_t;

typedef struct {
    pool_mqtt_command_kind_t kind;
    bool on, retained, wifi_skipped;
    const char *error_message;
    unsigned fields;
    char req_id[97], action[32];
    pool_mqtt_settings_t settings;
} pool_mqtt_command_t;

typedef struct {
    char topic[POOL_MQTT_TOPIC_MAX];
    char payload[POOL_MQTT_PAYLOAD_MAX + 1];
    size_t received, total;
    bool retained;
} pool_mqtt_rx_t;

// Returns 1 for a complete frame, 0 for a partial frame, -1 for an invalid frame.
int pool_mqtt_rx_feed(pool_mqtt_rx_t *rx, const char *topic, int topic_len,
                      const char *data, int length, int total, int offset, bool retained);
bool pool_mqtt_decode(const char *topic, const char *payload, size_t length, bool retained,
                       const pool_mqtt_settings_t *base, pool_mqtt_command_t *command);
int pool_mqtt_water_state(double raw, const pool_mqtt_settings_t *settings);

bool pool_mqtt_config_is_stale(const pool_mqtt_command_t *command, const pool_mqtt_settings_t *current);
