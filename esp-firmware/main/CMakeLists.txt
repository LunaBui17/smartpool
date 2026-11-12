idf_component_register(SRCS "main.c" "relay.c" "pool_network.c" "water_level.c"
                            "wifi_setup.c" "telemetry_queue.c" "pump_auto.c"
                       INCLUDE_DIRS "."
                       EMBED_TXTFILES "web/index.html"
                       PRIV_REQUIRES esp_driver_gpio esp_adc esp_wifi esp_event esp_netif esp_hw_support nvs_flash mbedtls json
                                     esp_http_client esp_http_server esp_timer mqtt
                                     espressif__onewire_bus espressif__ds18b20)
