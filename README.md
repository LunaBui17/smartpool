# Smart Pool

## Data flow

- ESP32-S3 reads DS18B20 on GPIO15 and the analog water sensor on GPIO18.
- ESP32-S3 sends `nhiet_do` and `muc_nuoc` to `pool_api.php` by HTTP POST. The Android app reads those fields from the API by HTTP GET every 3 seconds.
- ESP32-S3 also publishes the same telemetry to `smartpool/telemetry` on MQTT. The Android app receives temperature and water status immediately when connected.
- The app publishes `0` or `1` to `smartpool/cmd/bom_vao` and `smartpool/cmd/bom_ra`. ESP32-S3 switches active-LOW relays on GPIO16 and GPIO17 and publishes actual state to `smartpool/status`.

`muc_nuoc` is `1` when the ADC reading is at or above the configured threshold and `0` when it is below. The raw reading is printed on the ESP serial monitor and included in MQTT telemetry as `muc_nuoc_raw` for calibration. If an ADC read fails, ESP does not send a new water value.

## Setup

1. Import the updated `web-backend/pool_db.sql` into MySQL using phpMyAdmin. It creates the `pool_status` row and the `pool_history` table. Importing it again preserves existing rows.
2. Replace `xampp/htdocs/pool_api/pool_api.php` with the updated `web-backend/pool_api.php`. Verify `http://192.168.10.214/pool_api/pool_api.php` returns JSON from the Android phone.
3. Start Mosquitto with `mqtt-broker/mosquitto.conf`, listening on port `1767` of `192.168.10.214`.
4. In `esp-firmware`, use `idf.py menuconfig` to adjust the API URL or MQTT URI if the server IP changes. Under **Water Level Configuration**, calibrate the threshold from serial ADC readings. Build and flash the ESP32-S3.
5. Put the ESP32-S3, Android phone, and server on the same LAN. Allow inbound TCP ports 80 and 1767 on the server firewall.

## Wi-Fi setup

On first setup, connect a phone or laptop to **SmartPool-Setup** with the default password **12345678**, then open **http://192.168.4.1**. Choose a Wi-Fi network from the scanned list (or enter a hidden SSID), enter its password, and submit. The ESP32-S3 stores the credentials in flash. After it joins the LAN, the setup access point turns off; the same configuration page remains available at the ESP's LAN IP shown in the serial log (for example, `http://192.168.10.x/`). Reserve that IP in your router if you want a stable address.

After three failed Wi-Fi connection attempts, the setup access point turns on again. The ESP keeps retrying the saved network every 30 seconds, so it reconnects automatically when the network returns. The access point turns off again once the ESP gets a LAN IP. The setup SSID and password can be changed under **Smart Pool Network** in `menuconfig`.

## Offline telemetry and history

When Wi-Fi or the API is unavailable, ESP32-S3 saves unsent samples in NVS flash and retries them in order after connectivity returns. The queue survives a restart. The API uses each sample's ID to avoid duplicate history rows if a request succeeded but its response was lost. `pool_history` stores temperature, water status, raw ADC value, and server receipt time; inspect it in phpMyAdmin. The Android app continues to read the latest status from `pool_status`.

The queue holds **128 samples**. At a 1-second sampling interval, this covers about 2 minutes of continuous outage; at 10 seconds, about 21 minutes. Once full, the oldest sample is removed and a warning is logged. For longer outages, increase the queue capacity with flash/storage planning or reduce the sampling frequency. `received_at` is the time the server received a sample, so delayed samples do not have their original measurement time.

GPIO18 is ADC2 channel 7 on ESP32-S3. If ADC reads repeatedly fail while Wi-Fi is active, use an ADC1 pin (GPIO1–10) and change the water GPIO in `menuconfig`.

If the log reports a `1-Wire reset pulse receive timeout` on GPIO15, the firmware continues reporting water level and searches every 5 seconds while no sensor is active. Check DS18B20 VDD/GND/DQ, the 4.7 kΩ pull-up from DQ to 3.3 V, and the configured GPIO. A sensor connected later is discovered without restarting; successful readings appear as `DS18B20[0]: xx.xx C` in the serial log. A reading of exactly 85 °C is ignored because it is the sensor's power-on register value. MQTT starts only after Wi-Fi receives an IP address; if broker connection errors continue after the IP log, check that `192.168.10.214:1767` is reachable from the ESP network.

If a DS18B20 scratchpad read has a CRC error, the firmware waits 20 ms and reads it once more. A recovered read appears in the log. After three consecutive failed readings, that sensor is disabled; other sensors, water level, relays, and networking continue to run. The periodic search reactivates a repaired sensor without restarting the board. Repeated CRC errors still require checking the 1-Wire wiring, pull-up, and sensor power.
