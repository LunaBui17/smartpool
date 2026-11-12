#include "relay.h"

#include "driver/gpio.h"
#include "pool_features.h"

static bool relay_enabled(relay_channel_t channel)
{
    return channel == RELAY_1 ? POOL_RELAY_1_ENABLED : POOL_RELAY_2_ENABLED;
}

static gpio_num_t relay_gpio(relay_channel_t channel)
{
    return channel == RELAY_1 ? CONFIG_RELAY_1_GPIO : CONFIG_RELAY_2_GPIO;
}

static int relay_level(bool on)
{
#if CONFIG_RELAY_ACTIVE_HIGH
    return on ? 1 : 0;
#else
    return on ? 0 : 1;
#endif
}

esp_err_t relay_init(void)
{
    uint64_t pin_mask = 0;
    for (relay_channel_t channel = RELAY_1; channel <= RELAY_2; channel++) {
        if (!relay_enabled(channel)) continue;
        gpio_num_t pin = relay_gpio(channel);
        if ((POOL_TEMPERATURE_ENABLED && pin == CONFIG_DS18B20_GPIO) ||
            (POOL_WATER_ENABLED && pin == CONFIG_WATER_LEVEL_GPIO) ||
            (pin_mask & (1ULL << pin))) return ESP_ERR_INVALID_ARG;
        pin_mask |= 1ULL << pin;
        // Stay OFF until saved automation rules are loaded. This prevents an
        // initial-ON pulse on channels that must wait for a fresh sensor sample.
        esp_err_t err = gpio_set_level(pin, relay_output_level(channel, false));
        if (err != ESP_OK) return err;
    }

    // Set both output latches to the inactive level before enabling the pins.
    esp_err_t err = gpio_set_level(CONFIG_RELAY_1_GPIO, relay_level(false));
    if (err != ESP_OK) {
        return err;
        return err;
    }

    const gpio_config_t config = {
        .pin_bit_mask = pin_mask,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
}

esp_err_t relay_set(relay_channel_t channel, bool on)
{
    if (channel != RELAY_1 && channel != RELAY_2) {
        return ESP_ERR_INVALID_ARG;
    }
    return gpio_set_level(relay_gpio(channel), relay_level(on));
}
