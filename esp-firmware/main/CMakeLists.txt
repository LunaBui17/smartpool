idf_component_register(SRCS "main.c" "relay.c" "pool_network.c" "water_level.c"
                            "wifi_setup.c" "telemetry_queue.c"
                       INCLUDE_DIRS "."
                       PRIV_REQUIRES esp_driver_gpio esp_adc esp_wifi esp_event esp_netif esp_hw_support nvs_flash mbedtls
                                     esp_http_client esp_http_server mqtt
                                     espressif__onewire_bus espressif__ds18b20)
