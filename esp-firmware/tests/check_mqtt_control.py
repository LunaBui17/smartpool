"""Generate a host harness using the actual network control functions (no ESP hardware).
Compile build/mqtt-control-test.c together with main/pump_auto.c, with -Imain.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / "main/pool_network.c").read_text(encoding="utf-8")

def function(name):
    import re
    match = re.search(r"^(?:static )?(?:bool|esp_err_t) " + name + r"\([^;]*?\)\n\{", source, re.M)
    assert match, name
    pos = source.index("{", match.start())
    depth = 1
    end = pos + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[match.start():end] + "\n"

stub = r"""
#include <assert.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include "pump_auto.h"
typedef int esp_err_t;
typedef int relay_channel_t;
#define ESP_OK 0
#define ESP_FAIL 1
#define ESP_ERR_INVALID_STATE 2
#define ESP_ERR_NOT_SUPPORTED 3
#define ESP_ERR_INVALID_ARG 4
#define RELAY_1 0
#define RELAY_2 1
#define POOL_RELAY_1_ENABLED 1
#define POOL_RELAY_2_ENABLED 1
#define POOL_TEMPERATURE_ENABLED 1
#define POOL_WATER_ENABLED 1
#define pdTRUE 1
#define portMAX_DELAY 1
static int s_relay_lock, held, gpio_calls;
static bool physical[2], s_mqtt_connected, s_wifi_connected, s_station_enabled;
static bool s_pump_in, s_pump_out, s_local_pump_in, s_local_pump_out;
static bool s_temperature_enabled, s_water_enabled, s_sample_requested;
static struct { bool interlock; } s_mqtt_settings;
static struct { bool has_temperature, has_water; } s_readings;
static pump_auto_runtime_t s_auto_runtime[2];
static int xSemaphoreTake(int lock, int timeout) { (void)lock; (void)timeout; assert(!held); held=1; return pdTRUE; }
static void xSemaphoreGive(int lock) { (void)lock; assert(held); held=0; }
static esp_err_t relay_set(int channel, bool on) { assert(held); gpio_calls++; physical[channel]=on; return ESP_OK; }
static void publish_status(void) { assert(!held); }
static void pool_network_send_telemetry(bool a, float b, bool c, bool d, int e) {
    (void)a; (void)b; (void)c; (void)d; (void)e; assert(!held);
}
"""
tests = r"""
static void reset(void) {
    assert(!held);
    gpio_calls=0;
    memset(physical,0,sizeof(physical));
    s_pump_in=s_pump_out=s_local_pump_in=s_local_pump_out=false;
    s_temperature_enabled=s_water_enabled=s_sample_requested=false;
    s_readings.has_temperature=s_readings.has_water=false;
    s_mqtt_settings.interlock=false;
    for(int i=0;i<2;i++) pump_auto_reset(&s_auto_runtime[i]);
}
int main(void) {
    // No broker session, no Wi-Fi, or disabled STA: no remote ON, OFF or sensor commands.
    for(int mask=0;mask<8;mask++) {
        reset();
        s_mqtt_connected=(mask&1)!=0;
        s_wifi_connected=(mask&2)!=0;
        s_station_enabled=(mask&4)!=0;
        bool ready=mask==7;
        assert(mqtt_control_ready()==ready);
        esp_err_t expected=ready?ESP_OK:ESP_ERR_INVALID_STATE;
        assert(set_pumps(true,false,false,false,1)==expected);
        assert(physical[0]==ready && !physical[1]);
        assert(set_pumps(false,false,false,false,1)==expected);
        assert(!physical[0] && !physical[1]);
        assert(set_sensor_enabled("temperature",true,true)==expected);
        assert(set_sensor_enabled("water",true,true)==expected);
        assert(s_temperature_enabled==ready && s_water_enabled==ready);
        assert(set_sensor_enabled("temperature",false,true)==expected);
        assert(set_sensor_enabled("water",false,true)==expected);
        assert(!s_temperature_enabled && !s_water_enabled);
        if(!ready) assert(gpio_calls==0 && !s_auto_runtime[0].paused);
        // AP/local stays functional for every connection combination.
        assert(pool_network_local_control("relay1",true)==ESP_OK);
        assert(pool_network_local_control("relay2",true)==ESP_OK);
        assert(physical[0] && physical[1]);
        assert(pool_network_local_control("temperature",true)==ESP_OK);
        assert(pool_network_local_control("water",true)==ESP_OK);
        assert(s_temperature_enabled && s_water_enabled);
        assert(pool_network_local_control("stop",false)==ESP_OK);
        assert(!physical[0] && !physical[1]);
    }
    reset();
    s_mqtt_connected=s_wifi_connected=s_station_enabled=true;
    assert(set_pumps(true,false,false,false,1)==ESP_OK); // Remote pump in.
    assert(pool_network_local_control("relay2",true)==ESP_OK); // Local pump out.
    s_mqtt_connected=false;
    int before=gpio_calls;
    assert(set_pumps(false,false,false,false,1)==ESP_ERR_INVALID_STATE);
    assert(gpio_calls==before && physical[0] && physical[1]);
    assert(set_pumps(false,false,false,true,0)==ESP_OK); // Internal disconnect cleanup bypasses remote gate.
    assert(!physical[0] && physical[1]); // Local ownership survives.
    assert(pool_network_local_control("relay2",false)==ESP_OK);
    assert(!physical[1]);
    s_mqtt_connected=true;
    assert(set_pumps(true,false,false,false,1)==ESP_OK); // Reconnect restores remote controls.
    s_mqtt_settings.interlock=true;
    assert(pool_network_local_control("relay2",true)==ESP_ERR_INVALID_STATE);
    assert(physical[0] && !physical[1]);
    assert(pool_network_local_control("stop",false)==ESP_OK);
    puts("PASS: remote relay/sensor gate, disconnected OFF commands, AP/local independence, disconnect cleanup, reconnect, interlock");
}
"""
out = root / "build/mqtt-control-test.c"
out.parent.mkdir(exist_ok=True)
out.write_text(stub + "\n".join(function(n) for n in
    ("mqtt_control_ready", "set_pumps", "set_sensor_enabled", "pool_network_local_control")) + tests, encoding="utf-8")
print("Prepared actual-function host test:", out)
