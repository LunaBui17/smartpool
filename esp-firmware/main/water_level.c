#include "water_level.h"

#include "esp_adc/adc_oneshot.h"
#include "sdkconfig.h"

static adc_oneshot_unit_handle_t s_adc;
static adc_channel_t s_channel;

esp_err_t water_level_init(void)
{
    if (CONFIG_WATER_LEVEL_GPIO == CONFIG_DS18B20_GPIO ||
        CONFIG_WATER_LEVEL_GPIO == CONFIG_RELAY_1_GPIO ||
        CONFIG_WATER_LEVEL_GPIO == CONFIG_RELAY_2_GPIO) {
        return ESP_ERR_INVALID_ARG;
    }

    adc_unit_t unit;
    esp_err_t err = adc_oneshot_io_to_channel(CONFIG_WATER_LEVEL_GPIO, &unit, &s_channel);
    if (err != ESP_OK) {
        return err;
    }

    const adc_oneshot_unit_init_cfg_t unit_config = {
        .unit_id = unit,
    };
    err = adc_oneshot_new_unit(&unit_config, &s_adc);
    if (err != ESP_OK) {
        return err;
    }

    const adc_oneshot_chan_cfg_t channel_config = {
        .atten = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_12,
    };
    return adc_oneshot_config_channel(s_adc, s_channel, &channel_config);
}

esp_err_t water_level_read(int *raw, bool *normal)
{
    if (!s_adc || !raw || !normal) {
        return ESP_ERR_INVALID_STATE;
    }
    esp_err_t err = adc_oneshot_read(s_adc, s_channel, raw);
    if (err == ESP_OK) {
        *normal = *raw >= CONFIG_WATER_LOW_THRESHOLD;
    }
    return err;
}
