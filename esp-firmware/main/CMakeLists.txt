idf_component_register(SRCS "ds18b20_example_main.c" "relay.c"
                       INCLUDE_DIRS "."
                       PRIV_REQUIRES esp_driver_gpio espressif__onewire_bus espressif__ds18b20)
