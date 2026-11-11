#include "relay.h"

#include "driver/gpio.h"
#include "sdkconfig.h"

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
    if (CONFIG_RELAY_1_GPIO == CONFIG_RELAY_2_GPIO ||
        CONFIG_RELAY_1_GPIO == CONFIG_DS18B20_GPIO ||
        CONFIG_RELAY_2_GPIO == CONFIG_DS18B20_GPIO) {
        return ESP_ERR_INVALID_ARG;
    }

    // Set both output latches to the inactive level before enabling the pins.
    esp_err_t err = gpio_set_level(CONFIG_RELAY_1_GPIO, relay_level(false));
    if (err != ESP_OK) {
        return err;
    }
    err = gpio_set_level(CONFIG_RELAY_2_GPIO, relay_level(false));
    if (err != ESP_OK) {
        return err;
    }

    const gpio_config_t config = {
        .pin_bit_mask = (1ULL << CONFIG_RELAY_1_GPIO) | (1ULL << CONFIG_RELAY_2_GPIO),
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    return gpio_config(&config);
}

esp_err_t relay_set(relay_channel_t channel, bool on)
{
    if (channel != RELAY_1 && channel != RELAY_2) {
        return ESP_ERR_INVALID_ARG;
    }
    return gpio_set_level(relay_gpio(channel), relay_level(on));
}
