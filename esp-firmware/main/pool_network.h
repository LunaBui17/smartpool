#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "pump_auto.h"

typedef struct {
    pump_auto_config_t config;
    bool paused;
    bool running;
    const char *reason;
    const char *last_stop_reason;
    uint32_t remaining_seconds;
} pump_auto_view_t;

typedef struct {
    pump_auto_view_t automation[2];
    bool wifi_connected;
    bool station_enabled;
    bool mqtt_connected;
    bool clock_ready;
    const char *mqtt_state;
    const char *http_state;
    int http_status_code;
    const char *http_error;
    int http_last_attempt_age_seconds;
    int http_last_success_age_seconds;
    unsigned int http_queued_samples;
    bool interlock;
    bool relay_1_on;
    bool relay_2_on;
    bool temperature_enabled;
    bool water_enabled;
    bool has_temperature;
    bool has_last_temperature;
    bool has_water;
    float temperature;
    int water_raw;
    bool water_normal;
    int sample_age_seconds;
} pool_device_state_t;

typedef struct {
    char broker[257];
    char username[129];
    bool password_set;
    char api_url[257];
} pool_connection_config_t;

esp_err_t pool_network_init(void);
void pool_network_get_connection_config(pool_connection_config_t *config);
esp_err_t pool_network_save_http_url(const char *url);
esp_err_t pool_network_save_mqtt_connection(const char *broker, const char *username,
                                            const char *password);
esp_err_t pool_network_set_time(int64_t unix_time);
esp_err_t pool_network_configure_pump(int channel, const pump_auto_config_t *config);
bool pool_network_take_sample_request(void);
void pool_network_get_state(pool_device_state_t *state);
esp_err_t pool_network_local_control(const char *target, bool on);
esp_err_t pool_network_set_station_enabled(bool enabled);
bool pool_network_temperature_enabled(void);
bool pool_network_water_enabled(void);
void pool_network_update_readings(bool has_temperature, float temperature,
                                  bool has_water, bool water_normal, int water_raw);
void pool_network_send_telemetry(bool has_temperature, float temperature,
                                 bool has_water, bool water_normal, int water_raw);
