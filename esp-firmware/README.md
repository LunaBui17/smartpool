# SmartPool ESP32-S3 firmware

ESP-IDF firmware with MQTT control, DS18B20 temperature measurement, analog water level measurement, and HTTP storage uploads.

## Wiring

| Function | ESP32-S3 GPIO | Notes |
| --- | --- | --- |
| Relay 1 / pump in | 15 | Active HIGH by default; configurable polarity and boot state |
| Relay 2 / pump out | 16 | Active HIGH by default; configurable polarity and boot state |
| DS18B20 DQ | 9 | External 4.7 kOhm pull-up to 3.3 V; up to two sensors |
| Water level analog output | 10 | ADC1 channel 9; maximum input 3.3 V |

Use a common GND and relay inputs compatible with 3.3 V GPIO. Power the relay coils according to the module specifications. The water level is normal when its 12-bit raw ADC reading is at least `CONFIG_WATER_LOW_THRESHOLD` (default 2000); calibrate this threshold with the actual sensor.

Change pins and thresholds in `idf.py menuconfig`. Temperature and water level are measured and uploaded every 60 seconds when both pumps are OFF, or every 5 seconds when either pump is ON (including both ON). An initial sample is taken shortly after boot. The main loop reevaluates pump state every second, so starting a pump does not wait for the old idle timeout: if the last reading is already at least 5 seconds old, the next loop measures immediately. Stopping both pumps returns to the 60-second interval measured from the last sample. Sensor rediscovery follows the same cadence. Change both intervals in **Sensor Sampling** (`CONFIG_POOL_SENSOR_IDLE_INTERVAL_MS` / `CONFIG_POOL_SENSOR_PUMP_INTERVAL_MS`). Pump commands and state changes still report immediately; previously queued samples continue to drain separately. Sensors are enabled at boot when included through menuconfig. Disabling a sensor stops measurement, not its electrical power supply. Both DS18B20 devices share the temperature enable switch; telemetry reports the first successful temperature reading. If no DS18B20 remains active, discovery is retried while temperature measurement is enabled.

## menuconfig feature switches and initial relay state

Run `idf.py menuconfig`:

- **DS18B20 Configuration > Enable DS18B20 temperature sensors**
- **Water Level Configuration > Enable analog water level sensor**
- **Relay Configuration > Enable relay 1 / Enable relay 2**
- **Relay Configuration > Relay 1/2 ON GPIO level (0=LOW, 1=HIGH)**: default 1 for the current relay module. Set each channel independently to match its module.
- **Relay Configuration > Initial relay state at boot**: both OFF (default), relay 1 ON, relay 2 ON, or both ON. Only enabled relays can start ON.
- **Smart Pool Network > Enable router Wi-Fi connection (STA)**: disable to force AP-only operation.
- **Smart Pool Network > Enable MQTT control and status / Enable HTTP telemetry storage**

All feature switches default to enabled. A sensor disabled in menuconfig is not initialized and cannot be enabled by MQTT. A disabled relay leaves its GPIO untouched and rejects ON commands. MQTT OFF stops broker connection/control/status; HTTP OFF stops POST requests, NVS telemetry queue initialization, and retry storage. The local Wi-Fi AP and dashboard remain available even with both remote transports OFF. Router connectivity is configured separately with the STA option and the dashboard switch.

Relay outputs are first held OFF while saved automation rules are loaded. The selected initial state then applies to channels without automation, before Wi-Fi starts. It is a logical ON/OFF state. With the current active-HIGH configuration, ON drives HIGH and OFF drives LOW; active-LOW modules use the inverse. The same per-channel mapping is used during initialization and every command, so MQTT/web/HTTP states keep their logical meaning (`1` = ON). These are commanded states; no physical relay feedback input is wired. It is independent of MQTT connectivity at boot; subsequent Wi-Fi/MQTT disconnection stops remote-owned pumps. Pumps explicitly controlled through the local web dashboard can continue while MQTT/router reconnection is attempted. Switching router mode or saving new Wi-Fi credentials stops both pumps before the transition. Rebuild and flash after changing menuconfig.

## Local dashboard and AP mode

The ESP32 always exposes the WPA2 access point named by `CONFIG_POOL_SETUP_AP_SSID` (default `SmartPool-Setup`), protected by `CONFIG_POOL_SETUP_AP_PASSWORD` (default `12345678`). Connect a phone/computer to this network and open **http://192.168.4.1**. No Internet, CDN, or external font is needed. This is AP-based web provisioning, not the ESPTouch SmartConfig protocol; there is no automatic captive-portal/DNS redirect.

The Vietnamese dashboard supports mobile and desktop layouts, live sensor readings, sensor enable switches, relay switches, an atomic stop-both-pumps action, Wi-Fi scanning, password visibility, and Wi-Fi credential storage. Controls disabled in menuconfig remain unavailable in the UI and API. It is also accessible through the device's router-assigned LAN IP.

The **router Wi-Fi** switch disconnects the station while keeping the local AP/server running. This preference is stored in NVS and survives reboot. Re-enable it to reconnect using stored credentials, or save new credentials. If `Enable router Wi-Fi connection (STA)` is disabled in menuconfig, the web switch and credential submission cannot enable the station. The AP stays available when the router disconnects and after it reconnects. The AP and router share a radio channel, so phones may briefly reconnect when the router channel changes.

The inlet and outlet pumps operate independently and may both be ON, through either web or MQTT. A router/MQTT disconnection stops remotely operated pumps, but does not repeatedly interrupt a pump started through the local dashboard while the router is unavailable. Any explicit mode transition or Wi-Fi credential change stops both pumps. Control ownership is tracked per pump: a later explicit MQTT command takes control of only its target channel. When one pump is local and the other remote, loss of router/MQTT connectivity stops only the remote channel. Sensor switches use the same runtime state across web and MQTT. Local sensor runtime settings reset on reboot according to menuconfig.

Local endpoints (JSON requests require `Content-Type: application/json`):

| Endpoint | Purpose / request |
| --- | --- |
| `GET /` | Embedded dashboard |
| `GET /api/status` | Current readings, connectivity, feature availability, and sample age; no passwords |
| `POST /api/control` | `{"target":"relay1","enabled":true}`; targets: `relay1`, `relay2`, `temperature`, `water`, or `stop` (`enabled:false`) |
| `POST /api/network` | `{"enabled":false}` disables router connection; true reconnects |
| `GET /api/networks` | Scan nearby networks on demand (may be temporarily unavailable while connecting) |
| `POST /wifi` | `{"ssid":"Home Wi-Fi","password":"password"}` stores settings and reconnects |

## MQTT control

Configure the broker in `CONFIG_POOL_MQTT_URI` and credentials in `main/mqtt_credentials.h` using the adjacent example file. Wi-Fi setup is available through the SmartPool setup AP and `http://192.168.4.1`. TLS MQTT starts after SNTP clock synchronization.

Publish an exact one-byte payload: `1` means ON/enabled; `0` means OFF/disabled.

| Topic | Control |
| --- | --- |
| `smartpool/cmd/bom_vao` | Relay 1 / pump in |
| `smartpool/cmd/bom_ra` | Relay 2 / pump out |
| `smartpool/cmd/temperature_enabled` | DS18B20 measurement |
| `smartpool/cmd/water_level_enabled` | Analog water level measurement |

Pump commands must use retain=false. Retained ON messages delivered after subscription are ignored, preventing an old command from starting a pump after reconnect. Sensor commands may be retained to reapply the desired measurement settings after reconnect or reboot. Local sensor enable settings are otherwise reset to enabled at boot.

Both pumps may run simultaneously when interlock is OFF (the firmware default). Android `interlock:1` in thresholds or Sync All persists a mutual-exclusion rule for MQTT, local web, automatic control and boot; `interlock:0` permits concurrent operation. Each MQTT topic changes only its own relay, preserving the other relay state. Loss of Wi-Fi or MQTT stops remote-owned pumps; a pump explicitly operated from the local dashboard continues independently of the broker. The local dashboard can enable independent sensor-based automation for either pump. Manual relay commands pause that channel's automation for the current boot.

Subscribe to `smartpool/status` for retained state (QoS 1):

```json
{"online":1,"bom_vao_control":0,"bom_ra_control":0,"temperature_enabled":1,"water_level_enabled":1}
```

The MQTT last will reports only `online:0`. Relay and sensor fields are omitted because they may still be controlled locally after the broker disconnects. `smartpool/telemetry` publishes measurement samples without retain; unavailable readings are JSON null on MQTT. State-only changes use MQTT status so Android does not clear its sensor display. MQTT water states are 0/1/2 (below/in range/above the saved raw ADC bounds); HTTP storage retains its existing binary water contract.

## HTTP storage contract

The external HTTP API is used for storage uploads only; remote commands come from MQTT. The separate on-device web server provides local control regardless of the HTTP storage switch. The previous HTTP command polling mode is removed. Configure the initial backend endpoint with `CONFIG_POOL_API_URL`; Android MQTT `UPDATE_HTTP` can persistently override the upload URL.

The firmware POSTs `application/json` on each measurement interval, including when both sensors are disabled or unavailable. It also creates a state-only sample when a command changes device state or the MQTT connection changes. Example payload:

```json
{"sample_id":"1234567800000001","nhiet_do":26.50,"muc_nuoc":1,"muc_nuoc_raw":2400,"bom_control":0,"van_control":0,"temperature_enabled":1,"water_level_enabled":1,"online":1}
```

`bom_control` and `van_control` retain their existing API names and correspond to relay 1 and relay 2. For HTTP uploads, unavailable sensor fields are omitted, including in state-only samples. This supports the deployed PHP API, which rejects JSON null in numeric fields. Missing fields must not be interpreted as a new zero reading. MQTT telemetry and the local dashboard still use JSON null for missing readings. A disabled sensor has its enable field set to 0; an enabled but unavailable sensor has its enable field set to 1 and its reading set to null. `online` records Wi-Fi connectivity when the sample was captured. Each sample captures relay and sensor state at that moment, so retries do not rewrite historical state using current relay values.

Uploads run in a separate task. Failed uploads enter the existing NVS queue (128 samples); the oldest is dropped when full. A 16-sample RAM inbox buffers new samples while HTTP is busy; if that inbox fills, new samples are dropped with a warning. The inbox is not retained across reboot. Pending NVS samples are retried even when both sensors are disabled. Firmware v1 queued readings are migrated without inventing historical device state; their POST payload omits the state fields.

The server must accept these fields, treat omitted readings as unavailable, deduplicate retries by `sample_id`, and return HTTP 200 only after persistence. Store historical samples separately from current device state when replaying a backlog. A device cannot send HTTP while disconnected: infer current offline status on the server using a last-seen timeout or consume the MQTT will. This repository contains firmware only; the PHP API/database schema must support this contract separately.

## Build and verify

From an ESP-IDF 5.5.6 terminal:

```text
idf.py build
idf.py -p COM5 flash monitor
```

Replace `COM5` with the board port. The target is ESP32-S3. The large single-app partition (1500 KiB) accommodates MQTT/TLS and HTTP; NVS and PHY offsets are unchanged. Flash the partition table along with the app using the command above. For a clean checkout without `sdkconfig`, run `idf.py set-target esp32s3` first.

Feature configuration checks (in an ESP-IDF terminal, after building):

```text
python tests/check_feature_config.py
```

This checks 512 Kconfig combinations and uses the target compiler to check seven changed source files under ten mixed configurations, including all four per-channel relay polarity combinations.

On hardware, verify relays start in the selected initial state (both OFF by default) and follow their MQTT topics, both pumps can run simultaneously and switching either OFF leaves the other running, and disconnecting MQTT/Wi-Fi stops remote-owned pumps. Toggle both sensor topics and check that readings stop/resume while HTTP continues reporting enable and relay state. Disconnect the API, change states, restore the API, and verify queued samples preserve the original states and deduplicate on retry. Test ADC raw values above/below the calibrated threshold and DS18B20 removal/reconnection.

For browser tests with a local simulated API (Windows with Chrome installed):

```text
python tests/check_dashboard.py
```

Desktop/mobile screenshots are saved under `build/ui-check`. Test AP association, wrong router credentials, router loss/recovery, STA-off persistence across reboot, Wi-Fi scanning while connected to the AP, and physical relay behavior on the ESP32 separately.

## HTTP 404 and queue troubleshooting

The configured endpoint is `http://192.168.60.112/pool_api.php`. The PHP file is deployed at the XAMPP document root, not in a `pool_api` subdirectory. `/phpmyadmin` is the database administration UI and is not the telemetry endpoint. A successful HTTP transport with status 404 means the server was reached but the requested path was not found.

After a corrected URL is flashed, the existing queue retries automatically. No NVS erase is needed. A full queue drops the oldest sample while new samples arrive; fixing the endpoint allows the backlog to drain. The current legacy PHP backend stores the existing temperature, water and relay fields, but does not persist `temperature_enabled`, `water_level_enabled`, or `online`; extending that server/schema is a separate backend change.

## MQTT and HTTP status on the dashboard

The **Wi-Fi & sync** panel refreshes from `/api/status` every 2.5 seconds. MQTT distinguishes disabled, missing credentials, waiting for router Wi-Fi, waiting for SNTP time, connecting, connected, disconnected, and connection error. HTTP distinguishes disabled, waiting for Wi-Fi, not yet sent, sending, last send succeeded, and last send failed. HTTP is a per-request result, not a persistent connection indicator.

The panel includes the last HTTP response code or transport error, ages of the last completed request and last successful send, and the number of waiting samples (NVS backlog plus the RAM inbox; the NVS count updates when a delivered batch is removed). Historical success remains visible with its age, while losing router Wi-Fi changes the current badge to waiting rather than leaving a green status. Losing contact with the device itself clears both service badges to unknown. Local status polling does not create extra telemetry uploads or change the 60/5-second sampling intervals.

Additional `/api/status` fields: `mqtt_state`, `http_state`, `http_status_code`, `http_error`, `http_last_attempt_age_seconds`, `http_last_success_age_seconds`, and `http_queued_samples`. An age of -1 means no such request/success has occurred since boot.

## MQTT waiting for SNTP time

MQTT TLS waits for a plausible UTC clock (2024-2099). The firmware now initializes SNTP without starting it, then starts/restarts it after the station receives an IP address. It accepts router-provided NTP servers from DHCP, preserving two configured public fallbacks: `pool.ntp.org` and `time.cloudflare.com`. Configure hostnames or a LAN NTP IP in **Smart Pool Network > Primary/Backup NTP server**. The checked-in configuration enables three SNTP slots and DHCP NTP support. Certificate verification remains enabled.

If DNS or outgoing UDP port 123 is blocked, connect to the local dashboard and use **Sync time from browser** under the MQTT waiting-for-time status. Set the phone/computer clock correctly first. The button submits UTC epoch seconds to `POST /api/time` as `{ "unix_time": 1794355200 }`; the API requires an integer in 2024-2099 and updates the system clock. MQTT then starts within the normal five-second retry cycle when router Wi-Fi is connected. There is no automatic browser time submission. This manual fallback is needed again after a cold reboot if NTP remains unavailable. AP-only operation can still control relays, but cannot connect to the Internet MQTT broker.

Expected logs: `SNTP started after IP`, followed by `SNTP synchronized`, then `MQTT TLS started after time synchronization`. The manual path logs `Clock set from local browser`. If TLS still fails after a correct clock, check the separate MQTT TLS/broker error instead of disabling verification.

## Per-pump automatic control

Open **Automatic pump setup** on the local dashboard. Each pump has a separate enable checkbox, sensor (water ADC or temperature), start direction (at/below or at/above), start threshold, stop mode, stop threshold, and run-time limit in seconds (1-86400). Automation is disabled by default. Water thresholds use raw ADC values 0-4095, not centimeters or percent; temperature thresholds use Celsius (-55 to 125). Calibrate the water sensor first. Analog ADC readings alone cannot reliably distinguish a disconnected sensor from a valid dry/low reading.

- **Duration:** start on the selected sensor condition and run for the configured seconds. Invalid/stale sensor data still stops an automatic run early.
- **Threshold:** start on the selected condition and stop at the opposite threshold. The duration field is the maximum run time even if the target is never reached. For a below-start rule, stop must be greater than start; for an above-start rule, stop must be smaller.
- After a completed, timed-out, or sensor-fault run, the trigger condition must clear before a new automatic run is armed. This prevents repeated timed runs while the same condition remains asserted.
- Time limits and sensor validity are evaluated every 250 ms in a separate task. Sensor thresholds are evaluated against the existing measurement cadence (60 seconds idle / 5 seconds pumping by default); the timer does not wait for a sensor measurement to stop a pump.
- **Save and apply** persists both rule configurations atomically to NVS, stops the selected automatic/transitioning channel, requests a fresh sample, and rearms that channel. Enabled rules wait for a fresh reading after boot. Automation takes precedence over an initial-ON boot setting for that channel until a valid reading is available.
- Manual web/MQTT relay commands pause automation for the target channel, including OFF commands. Stop-all and explicit Wi-Fi mode/credential transitions pause both. Parameters and enable settings remain saved; save/apply to resume, or reboot to resume an enabled saved rule. To keep automation disabled across reboot, uncheck it and save. Manual runs are not subject to the automatic rule's timer/threshold.
- Automatic runs continue without MQTT or router connectivity. Both pumps may operate simultaneously when interlock is OFF; otherwise a blocked automatic pump waits without consuming its trigger or run-time budget. A disabled relay or sensor in menuconfig cannot be used to enable a rule; disabling a sensor at runtime stops any automatic run that depends on it.

Example: inlet starts when water ADC <=1500, stops at >=2500, with a 300-second maximum; outlet may independently use temperature >=30 C and stop at <=28 C, or run for a fixed duration. Defaults are examples only and remain OFF until explicitly enabled.

`POST /api/automation` accepts the complete rule for one channel:

```json
{"channel":1,"enabled":true,"sensor":"water","comparison":"below","start_threshold":1500,"stop_mode":"threshold","stop_threshold":2500,"duration_seconds":300}
```

Use channel 1 or 2; sensor `water` or `temperature`; comparison `below` or `above`; stop mode `duration` or `threshold`. `stop_threshold` is required but unused in duration mode (the browser normalizes it to the start threshold). `/api/status` includes an `automation` array with configuration, paused/running flags, current and last-stop reasons, and remaining seconds. Runtime state and timers reset on reboot; configuration survives in NVS. HTTP/MQTT telemetry continues reporting actual commanded relay states.

Host policy tests run the actual C implementation independently of ESP hardware:

```sh
cc -std=c11 -Wall -Wextra -Werror -Imain main/pump_auto.c tests/test_pump_auto.c -o build/pump_auto_tests
./build/pump_auto_tests
```

On Windows the same test can be compiled/run in Ubuntu WSL. The dashboard test covers both forms, unsaved edits across polling, separate sensor choices, invalid hysteresis, timed mode, manual override, stop-all, and rearming. Board tests are still needed for physical relay operation, sensor disconnection, ADC calibration, and NVS persistence across power cycles.


## Android MQTT configuration and review

See [the Android compatibility review](docs/MQTT_ANDROID_REVIEW.md) for the actual app payloads, ACK handling, retained replay behavior, raw ADC units, and remaining app-side issues (sensor timeout, HTTP control mode, and ACK subscriptions).

The firmware now accepts `smartpool/config/thresholds`, `/wifi`, `/device`, `/http`, and `smartpool/config` (`CONFIG_UPDATE_ALL`), saves validated settings in NVS, and publishes `smartpool/ack` with the same `req_id`, expected `action`, and explicit `SUCCESS`/`ERROR`. ACKs and scan results are not retained. Wi-Fi scanning runs outside the MQTT callback. HTTP URL changes preserve the configured sampling intervals and storage-only role. Android alert thresholds classify MQTT readings and do not modify per-pump automation rules.


## Android interlock switch

`interlock:0/1` is applied from `smartpool/config/thresholds` and the `thresholds` object in Sync All. Omission preserves the current setting. It is stored in NVS; MQTT status/ACK report its actual value, and local `/api/status` returns boolean `interlock`. The dashboard shows the current mode.

When ON, a manual command to start the second pump is rejected and the first pump keeps running. Automatic starts wait for the other channel to stop; existing run timers and sensor-stop checks continue. When both automatic rules trigger together, channel 1 is evaluated first. If interlock is enabled while both pumps are already ON, both are stopped and automation is paused until save/apply or reboot. A saved interlock takes precedence over a BOTH_ON boot setting, keeping both outputs OFF at boot.

Version 1 MQTT settings migrate without losing Wi-Fi, HTTP or device values. Their interlock defaults OFF until a retained/new threshold configuration arrives; that group's old timestamp is reset once because v1 ignored the field. New settings use version 2, in the same blob layout. Live updates in one second are accepted in receipt order; retained replays with the same timestamp and conflicting values remain rejected.

Sync All may include `"wifi":{"ssid":"","password":""}` when Android has no saved router credentials. That exact blank section is skipped without modifying Wi-Fi or its timestamp; device, thresholds and HTTP settings still apply. Standalone `UPDATE_WIFI` with an empty SSID remains invalid. The MQTT regression test includes the reported Android payload in `tests/fixtures/sync_all_empty_wifi.json`.


## MQTT control availability

The Android pump buttons and live interlock switch require a connected MQTT client and ESP32 `online:1` from the current session. They lock on startup, disconnect, or ESP32 offline. HTTP read/publish callbacks cannot reopen them; the configured HTTP control transport also requires MQTT readiness. Pending command timers and queued HTTP control requests are cancelled when readiness is lost. Reconnect waits for device status rather than reusing the previous session's online flag. Settings configuration/scan send buttons require that screen's MQTT connection; broker setup and local drafts remain accessible.

ESP32 validates MQTT/Wi-Fi/STA state for both ON and OFF commands and for remote sensor toggles. It ignores incoming/queued MQTT work when disconnected. Internal disconnect cleanup bypasses the remote-command gate so remote-owned pumps can always be stopped. As requested, the local/AP web and automatic operation remain independent of broker availability, subject to the configured interlock and sensor rules.

`python tests/check_mqtt_control.py` generates `build/mqtt-control-test.c` from the actual firmware control functions. Compile it with `main/pump_auto.c` and `-Imain`, then run on a host C compiler (optionally ASan/UBSan). It checks all eight connectivity combinations, remote ON/OFF and sensors, local/AP controls while offline, disconnect cleanup preserving a local-owned pump, reconnect, and interlock. Android gate unit tests are in `../android-app/app/src/test/java/com/buiducluan23011767/ck/MqttControlGateTest.java`. Building Android requires a configured JDK and Android SDK.
