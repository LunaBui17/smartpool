/*
 * SPDX-FileCopyrightText: 2022-2025 Espressif Systems (Shanghai) CO LTD
 *
 * SPDX-License-Identifier: Unlicense OR CC0-1.0
 */

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "onewire_bus.h"
#include "ds18b20.h"
#include "pool_network.h"
#include "relay.h"
#include "sdkconfig.h"
#include "water_level.h"
#include <stdint.h>

#define MAX_SENSOR_FAILURES 3
#define MAX_SEARCH_ATTEMPTS 32
#define SENSOR_RESCAN_INTERVAL_MS 5000

static const char *TAG = "smartpool";

typedef struct {
    ds18b20_device_handle_t handle;
    onewire_device_address_t address;
    uint8_t failures;
    bool active;
} sensor_slot_t;

static void discover_sensors(onewire_bus_handle_t bus, sensor_slot_t *sensors,
                             int *active_count)
{
    onewire_device_iter_handle_t iter = NULL;
    esp_err_t err = onewire_new_device_iter(bus, &iter);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "DS18B20 search unavailable: %s", esp_err_to_name(err));
        return;
    }

    for (int attempt = 0; attempt < MAX_SEARCH_ATTEMPTS; attempt++) {
        onewire_device_t device;
        err = onewire_device_iter_get_next(iter, &device);
        if (err == ESP_ERR_NOT_FOUND) break;
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "DS18B20 search stopped: %s; check GPIO%d and pull-up",
                     esp_err_to_name(err), CONFIG_DS18B20_GPIO);
            break;
        }

        int slot = -1;
        for (int i = 0; i < CONFIG_DS18B20_MAX_DEVICES; i++) {
            if (sensors[i].handle && sensors[i].address == device.address) {
                slot = i;
                break;
            }
        }
        if (slot >= 0) {
            if (!sensors[slot].active) {
                sensors[slot].active = true;
                sensors[slot].failures = 0;
                (*active_count)++;
                ESP_LOGI(TAG, "DS18B20[%d] reconnected: %016llX", slot, device.address);
            }
            continue;
        }

        for (int i = 0; i < CONFIG_DS18B20_MAX_DEVICES; i++) {
            if (!sensors[i].handle || !sensors[i].active) {
                slot = i;
                break;
            }
        }
        if (slot < 0) continue;

        ds18b20_device_handle_t new_handle = NULL;
        ds18b20_config_t config = {};
        err = ds18b20_new_device_from_enumeration(&device, &config, &new_handle);
        if (err != ESP_OK) {
            if (err != ESP_ERR_NOT_SUPPORTED) {
                ESP_LOGW(TAG, "Cannot open 1-Wire device %016llX: %s",
                         device.address, esp_err_to_name(err));
            }
            continue;
        }
        if (sensors[slot].handle) ds18b20_del_device(sensors[slot].handle);
        sensors[slot] = (sensor_slot_t){
            .handle = new_handle,
            .address = device.address,
            .active = true,
        };
        (*active_count)++;
        ESP_LOGI(TAG, "Found DS18B20[%d], address: %016llX", slot, device.address);
    }
    onewire_del_device_iter(iter);
}

void app_main(void)
{
    ESP_ERROR_CHECK(relay_init());
    ESP_ERROR_CHECK(water_level_init());
    ESP_ERROR_CHECK(pool_network_init());

    // install 1-wire bus
    onewire_bus_handle_t bus = NULL;
    onewire_bus_config_t bus_config = {
        .bus_gpio_num = CONFIG_DS18B20_GPIO,
        .flags = {
            .en_pull_up = true, // enable the internal pull-up resistor in case the external device didn't have one
        }
    };
    onewire_bus_rmt_config_t rmt_config = {
        .max_rx_bytes = 10, // 1byte ROM command + 8byte ROM number + 1byte device command
    };
    sensor_slot_t sensors[CONFIG_DS18B20_MAX_DEVICES] = {0};
    int active_sensor_count = 0;
    uint8_t conversion_failures = 0;
    esp_err_t bus_err = onewire_new_bus_rmt(&bus_config, &rmt_config, &bus);
    if (bus_err != ESP_OK) {
        ESP_LOGW(TAG, "1-Wire bus unavailable: %s; continuing without temperature",
                 esp_err_to_name(bus_err));
    } else {
        ESP_LOGI(TAG, "1-Wire bus installed on GPIO%d", CONFIG_DS18B20_GPIO);
        ESP_LOGI(TAG, "Searching for DS18B20 sensors...");
        discover_sensors(bus, sensors, &active_sensor_count);
    }
    ESP_LOGI(TAG, "Searching done, %d active DS18B20 device(s)", active_sensor_count);
    if (active_sensor_count == 0 && bus) {
        ESP_LOGW(TAG, "No DS18B20 found on GPIO%d; retrying every %d seconds",
                 CONFIG_DS18B20_GPIO, SENSOR_RESCAN_INTERVAL_MS / 1000);
    }
    TickType_t last_search_tick = xTaskGetTickCount();
    TickType_t last_read_tick = last_search_tick;

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        TickType_t now = xTaskGetTickCount();

        if (bus && active_sensor_count == 0 &&
            (TickType_t)(now - last_search_tick) >=
                   pdMS_TO_TICKS(SENSOR_RESCAN_INTERVAL_MS)) {
            last_search_tick = now;
            int previous_active_count = active_sensor_count;
            discover_sensors(bus, sensors, &active_sensor_count);
            if (previous_active_count == 0 && active_sensor_count > 0) {
                conversion_failures = 0;
            }
        }

        if ((TickType_t)(now - last_read_tick) <
            pdMS_TO_TICKS(CONFIG_DS18B20_READ_INTERVAL_MS)) continue;
        last_read_tick = now;

        bool has_temperature = false;
        float temperature = 0;
        if (active_sensor_count > 0) {
            esp_err_t err = ds18b20_trigger_temperature_conversion_for_all(bus);
            if (err == ESP_OK) {
                conversion_failures = 0;
                for (int i = 0; i < CONFIG_DS18B20_MAX_DEVICES; i++) {
                    if (!sensors[i].active) {
                        continue;
                    }
                    float reading;
                    err = ds18b20_get_temperature(sensors[i].handle, &reading);
                    if (err == ESP_ERR_INVALID_CRC) {
                        vTaskDelay(pdMS_TO_TICKS(20));
                        err = ds18b20_get_temperature(sensors[i].handle, &reading);
                        if (err == ESP_OK) {
                            ESP_LOGI(TAG, "DS18B20[%d] read recovered after CRC retry", i);
                        }
                    }
                    if (err == ESP_OK && reading == 85.0f) {
                        ESP_LOGW(TAG, "DS18B20[%d] returned power-on value 85 C; sample ignored", i);
                        err = ESP_ERR_INVALID_RESPONSE;
                    }
                    if (err == ESP_OK) {
                        sensors[i].failures = 0;
                        ESP_LOGI(TAG, "DS18B20[%d]: %.2f C", i, reading);
                        if (!has_temperature) {
                            temperature = reading;
                            has_temperature = true;
                        }
                    } else {
                        ESP_LOGW(TAG, "DS18B20[%d] read failed: %s", i, esp_err_to_name(err));
                        if (++sensors[i].failures >= MAX_SENSOR_FAILURES) {
                            sensors[i].active = false;
                            active_sensor_count--;
                            ESP_LOGW(TAG, "DS18B20[%d] disabled after repeated failures", i);
                        }
                    }
                }
            } else {
                ESP_LOGW(TAG, "Temperature conversion failed: %s", esp_err_to_name(err));
                if (++conversion_failures >= MAX_SENSOR_FAILURES) {
                    active_sensor_count = 0;
                    for (int i = 0; i < CONFIG_DS18B20_MAX_DEVICES; i++) {
                        sensors[i].active = false;
                    }
                    ESP_LOGW(TAG, "Temperature readings disabled after repeated bus failures");
                }
            }
        }

        int water_raw = 0;
        bool water_normal = false;
        esp_err_t water_err = water_level_read(&water_raw, &water_normal);
        bool has_water = water_err == ESP_OK;
        if (has_water) {
            ESP_LOGI(TAG, "Water ADC: %d (%s)", water_raw, water_normal ? "normal" : "low");
        } else {
            ESP_LOGW(TAG, "Water ADC read failed: %s", esp_err_to_name(water_err));
        }
        pool_network_send_telemetry(has_temperature, temperature,
                                    has_water, water_normal, water_raw);
    }
}
