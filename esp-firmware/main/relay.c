#include "relay.h"

#include "driver/gpio.h"
#include "sdkconfig.h"

static gpio_num_t relay_gpio(relay_channel_t channel)
{
    return channel == RELAY_1 ? CONFIG_RELAY_1_GPIO : CONFIG_RELAY_2_GPIO;
}

esp_err_t relay_init(void)
{
    if (CONFIG_RELAY_1_GPIO == CONFIG_RELAY_2_GPIO ||
        CONFIG_RELAY_1_GPIO == CONFIG_DS18B20_GPIO ||
        CONFIG_RELAY_2_GPIO == CONFIG_DS18B20_GPIO) {
        return ESP_ERR_INVALID_ARG;
    }

    // Set output latches HIGH before enabling the pins as outputs (active LOW).
    esp_err_t err = gpio_set_level(CONFIG_RELAY_1_GPIO, 1);
    if (err != ESP_OK) {
        return err;
    }
    err = gpio_set_level(CONFIG_RELAY_2_GPIO, 1);
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
    return gpio_set_level(relay_gpio(channel), on ? 0 : 1);
}
