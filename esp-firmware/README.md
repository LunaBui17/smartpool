# ESP32-S3 DS18B20 temperature reader

This ESP-IDF project discovers up to two DS18B20 sensors on a 1-Wire bus and prints their temperatures to the serial monitor once per second. The DS18B20 component is downloaded by the ESP-IDF Component Manager during configuration.

## Wiring

Connect each sensor's VDD to 3.3 V, GND to GND, and DQ (data) to ESP32-S3 GPIO15. Add a 4.7 kΩ resistor between DQ and 3.3 V. The internal pull-up is enabled in software, but the external resistor is recommended for reliable communication. All sensors can share the same data line.

Use `idf.py menuconfig` and open **DS18B20 Configuration** to change the data GPIO, maximum number of sensors, or read interval. The default data pin is GPIO15 and the default maximum is two sensors.

## Dual relay

Connect relay module IN1 to GPIO16 and IN2 to GPIO17, with a common GND. Both inputs are active LOW. `relay_init()` configures both pins as outputs and leaves both relays OFF. Use `relay_set(RELAY_1, true)` or `relay_set(RELAY_2, true)` to turn a channel ON, and pass `false` to turn it OFF. The functions are declared in `main/relay.h`. The program does not automatically switch relays based on temperature.

The relay GPIOs can be changed in `idf.py menuconfig` under **Relay Configuration**. Use a relay module with input voltage levels compatible with ESP32-S3 3.3 V GPIOs; power the relay coils according to the module's specifications.

## Build and run

From an ESP-IDF terminal in this directory:

```text
idf.py set-target esp32s3
idf.py build
idf.py -p PORT flash monitor
```

Replace `PORT` with the board's serial port (for example, `COM5`). A working sensor produces a message like `temperature read from DS18B20[0]: 25.00C`. If no sensor is found, check the wiring and restart the board.
