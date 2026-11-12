#include "pool_mqtt_protocol.h"
#include "cJSON.h"
#include <stdlib.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>

static pool_mqtt_settings_t defaults(void)
{
    return (pool_mqtt_settings_t){.temp_min=20, .temp_max=35, .water_min=1500,
        .water_max=4095, .id="pool", .name="Pool", .api_url="http://192.168.60.112/pool_api.php"};
}
static bool decode(const char *topic, const char *payload, bool retained, const pool_mqtt_settings_t *base, pool_mqtt_command_t *c)
{
    return pool_mqtt_decode(topic, payload, strlen(payload), retained, base, c);
}
static void test_commands(void)
{
    pool_mqtt_settings_t base=defaults(); pool_mqtt_command_t c;
    const char *topics[]={"smartpool/cmd/bom_vao","smartpool/cmd/bom_ra",
        "smartpool/cmd/temperature_enabled","smartpool/cmd/water_level_enabled"};
    for(int i=0;i<4;i++) {
        assert(decode(topics[i],"1",false,&base,&c) && c.on);
        assert(decode(topics[i],"0",false,&base,&c) && !c.on);
        assert(!decode(topics[i],"true",false,&base,&c));
        assert(!decode(topics[i],"1\n",false,&base,&c));
        assert(!decode(topics[i],"\"1\"",false,&base,&c));
    }
    assert(!decode(topics[0],"1",true,&base,&c));
    assert(!decode(topics[1],"1",true,&base,&c));
    assert(decode(topics[0],"0",true,&base,&c));
    assert(decode(topics[2],"1",true,&base,&c));
    assert(decode("smartpool/cmd/wifi_scan","{\"cmd\":\"SCAN_WIFI\",\"timestamp\":1718000000}",false,&base,&c));
    assert(c.kind==POOL_MQTT_SCAN);
    assert(!decode("smartpool/cmd/wifi_scan","{\"cmd\":\"SCAN_WIFI\"}",true,&base,&c));
}
static const char *rule="{\"action\":\"UPDATE_THRESHOLDS\",\"req_id\":\"req_thresh_1718000000\",\"timestamp\":1718000000,\"temp_min\":20.1,\"temp_max\":35.2,\"water_min\":1500,\"water_max\":3500,\"interlock\":1}";
static void test_config_and_replay(void)
{
    pool_mqtt_settings_t base=defaults(); pool_mqtt_command_t c;
    assert(decode("smartpool/config/thresholds",rule,true,&base,&c));
    assert(c.kind==POOL_MQTT_CONFIG && c.fields==POOL_CFG_THRESHOLDS && c.settings.interlock);
    assert(!strcmp(c.req_id,"req_thresh_1718000000") && !strcmp(c.action,"UPDATE_THRESHOLDS"));
    assert(c.settings.temp_min==20.1 && c.settings.water_max==3500);
    assert(!strcmp(c.settings.api_url,base.api_url) && base.water_max==4095);
    assert(!pool_mqtt_config_is_stale(&c,&base));
    base=c.settings;
    assert(decode("smartpool/config/thresholds",rule,true,&base,&c));
    assert(!pool_mqtt_config_is_stale(&c,&base));
    c.settings.timestamps[0]--;
    assert(pool_mqtt_config_is_stale(&c,&base));
    c.settings.timestamps[0]++;
    c.settings.water_max=3000;
    assert(pool_mqtt_config_is_stale(&c,&base));
    c.retained = false;
    assert(!pool_mqtt_config_is_stale(&c,&base)); // Live changes within one second are ordered by receipt.
    c.retained = true;
    c.settings.timestamps[0]++;
    assert(!pool_mqtt_config_is_stale(&c,&base));
    assert(pool_mqtt_water_state(1499,&base)==0);
    assert(pool_mqtt_water_state(1500,&base)==1);
    assert(pool_mqtt_water_state(3500,&base)==1);
    assert(pool_mqtt_water_state(3501,&base)==2);
    assert(!decode("smartpool/config/thresholds","{\"action\":\"UPDATE_THRESHOLDS\",\"req_id\":\"bad\",\"timestamp\":1,\"temp_min\":40,\"temp_max\":30,\"water_min\":0,\"water_max\":4095}",false,&base,&c));
    assert(!strcmp(c.req_id,"bad"));
    assert(!decode("smartpool/config/thresholds","{\"action\":\"UPDATE_THRESHOLDS\",\"timestamp\":1,\"temp_min\":20,\"temp_max\":30,\"water_min\":0,\"water_max\":4096}",false,&base,&c));
    char trailing[512]; snprintf(trailing,sizeof(trailing),"%s garbage",rule);
    assert(!decode("smartpool/config/thresholds",trailing,false,&base,&c));
    snprintf(trailing,sizeof(trailing),"%s \r\n",rule);
    assert(decode("smartpool/config/thresholds",trailing,false,&base,&c));
    assert(!decode("smartpool/config/wifi",rule,false,&base,&c));
}
static void test_interlock_config(void)
{
    pool_mqtt_settings_t base=defaults(); pool_mqtt_command_t c;
    assert(!base.interlock);
    assert(decode("smartpool/config/thresholds",rule,true,&base,&c) && c.settings.interlock);
    base=c.settings;
    const char *off="{\"action\":\"UPDATE_THRESHOLDS\",\"timestamp\":1718000000,\"temp_min\":20.1,\"temp_max\":35.2,\"water_min\":1500,\"water_max\":3500,\"interlock\":0}";
    assert(decode("smartpool/config/thresholds",off,false,&base,&c) && !c.settings.interlock);
    assert(!pool_mqtt_config_is_stale(&c,&base));
    assert(decode("smartpool/config/thresholds",off,true,&base,&c));
    assert(pool_mqtt_config_is_stale(&c,&base));
    const char *missing="{\"action\":\"UPDATE_THRESHOLDS\",\"timestamp\":1718000001,\"temp_min\":20,\"temp_max\":35,\"water_min\":1000,\"water_max\":3000}";
    assert(decode("smartpool/config/thresholds",missing,false,&base,&c) && c.settings.interlock);
    const char *invalid="{\"action\":\"UPDATE_THRESHOLDS\",\"timestamp\":1718000001,\"temp_min\":20,\"temp_max\":35,\"water_min\":1000,\"water_max\":3000,\"interlock\":2}";
    assert(!decode("smartpool/config/thresholds",invalid,false,&base,&c));
    assert(decode("smartpool/config/device","{\"action\":\"UPDATE_DEVICE\",\"timestamp\":1,\"id\":\"pool\",\"name\":\"Pool\",\"location\":\"\"}",false,&base,&c));
    assert(c.settings.interlock);
}
static void test_app_payloads(void)
{
    pool_mqtt_settings_t base=defaults(); pool_mqtt_command_t c;
    assert(decode("smartpool/config/wifi","{\"action\":\"UPDATE_WIFI\",\"req_id\":\"req_wifi_1\",\"timestamp\":1,\"ssid\":\"Home\",\"password\":\"Password123\"}",true,&base,&c));
    assert(c.fields==POOL_CFG_WIFI && !strcmp(c.settings.ssid,"Home"));
    assert(!decode("smartpool/config/wifi","{\"action\":\"UPDATE_WIFI\",\"timestamp\":1,\"ssid\":\"Home\",\"password\":\"short\"}",true,&base,&c));
    assert(decode("smartpool/config/device","{\"action\":\"UPDATE_DEVICE\",\"timestamp\":1,\"id\":\"pool-1\",\"name\":\"B\u1ec3 ch\u00ednh\",\"location\":\"T\u1ea7ng 5\"}",true,&base,&c));
    assert(!strcmp(c.settings.name,"B\u1ec3 ch\u00ednh"));
    assert(decode("smartpool/config/http","{\"action\":\"UPDATE_HTTP\",\"timestamp\":1,\"api_url\":\"https://example.com/pool_api.php\",\"poll_interval_s\":3,\"control_mode\":\"http\"}",true,&base,&c));
    assert(c.fields==POOL_CFG_HTTP);
    assert(!decode("smartpool/config/http","{\"action\":\"UPDATE_HTTP\",\"timestamp\":1,\"api_url\":\"file:///tmp/a\"}",true,&base,&c));
    const char *all="{\"action\":\"CONFIG_UPDATE_ALL\",\"req_id\":\"req_all_2\",\"timestamp\":2,\"device\":{\"id\":\"pool-1\",\"name\":\"Pool\",\"location\":\"Garden\"},\"wifi\":{\"ssid\":\"Home\",\"password\":\"Password123\"},\"thresholds\":{\"temp_min\":20,\"temp_max\":35,\"water_min\":1000,\"water_max\":3500,\"interlock\":0},\"mqtt\":{\"broker\":\"ssl://host:8883\"},\"http\":{\"api_url\":\"http://host/pool_api.php\",\"poll_interval_s\":3},\"control_mode\":\"mqtt\"}";
    assert(decode("smartpool/config",all,true,&base,&c));
    assert(c.fields==15 && !strcmp(c.req_id,"req_all_2") && c.settings.water_min==1000);
    for(int i=0;i<4;i++) assert(c.settings.timestamps[i]==2);
    assert(base.water_min==1500 && !base.ssid[0]);
    assert(!decode("smartpool/config","{\"action\":\"CONFIG_UPDATE_ALL\",\"req_id\":\"bad-all\",\"timestamp\":2,\"device\":{\"id\":\"pool\",\"name\":\"New\",\"location\":\"\"}}",true,&base,&c));
    assert(!strcmp(c.req_id,"bad-all") && !strcmp(base.name,"Pool"));
}
static void test_fragmentation(void)
{
    pool_mqtt_rx_t rx={0}; const char *topic="smartpool/config/thresholds"; int total=strlen(rule);
    for(int split=1;split<total;split++) {
        assert(pool_mqtt_rx_feed(&rx,topic,strlen(topic),rule,split,total,0,true)==0);
        assert(pool_mqtt_rx_feed(&rx,NULL,0,rule+split,total-split,total,split,false)==1);
        assert(!strcmp(rx.payload,rule) && rx.retained && !strcmp(rx.topic,topic));
    }
    assert(pool_mqtt_rx_feed(&rx,topic,strlen(topic),rule,2,total,0,false)==0);
    assert(pool_mqtt_rx_feed(&rx,NULL,0,rule+2,2,total,3,false)==-1);
    assert(pool_mqtt_rx_feed(&rx,NULL,0,rule+2,total-2,total,2,false)==-1);
    assert(pool_mqtt_rx_feed(&rx,topic,strlen(topic),rule,2,POOL_MQTT_PAYLOAD_MAX+1,0,false)==-1);
    assert(pool_mqtt_rx_feed(&rx,topic,strlen(topic),"1\0",2,2,0,false)==-1);
    char maximum[POOL_MQTT_PAYLOAD_MAX]; memset(maximum,'x',sizeof(maximum));
    assert(pool_mqtt_rx_feed(&rx,topic,strlen(topic),maximum,sizeof(maximum),sizeof(maximum),0,false)==1);
    assert(rx.payload[POOL_MQTT_PAYLOAD_MAX]==0);
    // A new message resets an abandoned partial frame.
    assert(pool_mqtt_rx_feed(&rx,topic,strlen(topic),rule,2,total,0,false)==0);
    assert(pool_mqtt_rx_feed(&rx,"smartpool/cmd/bom_vao",21,"0",1,1,0,false)==1);
}
static void test_sync_all_empty_wifi(const char *path)
{
    char payload[POOL_MQTT_PAYLOAD_MAX + 1];
    FILE *file = fopen(path, "rb");
    assert(file);
    size_t size = fread(payload, 1, sizeof(payload) - 1, file);
    assert(!ferror(file));
    fclose(file);
    payload[size] = 0;
    pool_mqtt_settings_t base = defaults();
    strcpy(base.ssid, "ExistingNetwork");
    strcpy(base.password, "ExistingPassword");
    base.timestamps[1] = 1795000000; // A skipped Wi-Fi section must not make the other groups stale.
    pool_mqtt_command_t c;
    assert(decode("smartpool/config", payload, true, &base, &c));
    assert(c.wifi_skipped && c.fields == (POOL_CFG_THRESHOLDS | POOL_CFG_DEVICE | POOL_CFG_HTTP));
    assert(!strcmp(c.req_id, "req_all_1794468306"));
    assert(!strcmp(c.settings.ssid, base.ssid) && !strcmp(c.settings.password, base.password));
    assert(c.settings.timestamps[1] == base.timestamps[1]);
    assert(c.settings.timestamps[0] == 1794468306 && c.settings.interlock);
    assert(c.settings.water_min == 20 && c.settings.water_max == 85);
    assert(!strcmp(c.settings.id, "ESP32S3_SMARTPOOL_01"));
    assert(!strcmp(c.settings.api_url, "http://192.168.60.112/pool_api.php"));
    assert(!pool_mqtt_config_is_stale(&c, &base));
    cJSON *root = cJSON_Parse(payload);
    assert(root);
    cJSON *wifi = cJSON_GetObjectItemCaseSensitive(root, "wifi");
    cJSON_ReplaceItemInObjectCaseSensitive(wifi, "password", cJSON_CreateString("secret123"));
    char *changed = cJSON_PrintUnformatted(root);
    assert(changed && !decode("smartpool/config", changed, false, &base, &c));
    assert(c.error_message && !strcmp(c.req_id, "req_all_1794468306"));
    free(changed);
    cJSON_ReplaceItemInObjectCaseSensitive(wifi, "ssid", cJSON_CreateString("NewNetwork"));
    changed = cJSON_PrintUnformatted(root);
    assert(changed && decode("smartpool/config", changed, false, &base, &c));
    assert(!c.wifi_skipped && (c.fields & POOL_CFG_WIFI) && !strcmp(c.settings.ssid, "NewNetwork"));
    free(changed);
    cJSON_Delete(root);
    assert(!decode("smartpool/config/wifi", "{\"action\":\"UPDATE_WIFI\",\"timestamp\":1,\"ssid\":\"\",\"password\":\"\"}", false, &base, &c));
    assert(c.error_message);
}

int main(int argc, char **argv)
{
    test_sync_all_empty_wifi(argc > 1 ? argv[1] : "tests/fixtures/sync_all_empty_wifi.json");
    test_commands(); test_interlock_config(); test_config_and_replay(); test_app_payloads(); test_fragmentation();
    puts("PASS: Android MQTT commands, configuration/ACK correlation, raw ADC states, replay protection, fragmented frames");
    return 0;
}
