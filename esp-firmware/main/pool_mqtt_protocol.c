#include "pool_mqtt_protocol.h"
#include "cJSON.h"
#include <math.h>
#include <string.h>

int pool_mqtt_rx_feed(pool_mqtt_rx_t *rx, const char *topic, int topic_len,
                      const char *data, int length, int total, int offset, bool retained)
{
    if (offset == 0) {
        memset(rx, 0, sizeof(*rx));
        if (!topic || topic_len <= 0 || (size_t)topic_len >= sizeof(rx->topic) ||
            memchr(topic, 0, topic_len) || total < 1 || total > POOL_MQTT_PAYLOAD_MAX) return -1;
        memcpy(rx->topic, topic, topic_len);
        rx->total = total;
        rx->retained = retained;
    }
    if (!rx->total || offset < 0 || length <= 0 || !data ||
        (size_t)offset != rx->received || (size_t)total != rx->total ||
        (size_t)length > rx->total - rx->received || memchr(data, 0, length)) {
        rx->total = 0;
        return -1;
    }
    memcpy(rx->payload + rx->received, data, length);
    rx->received += length;
    rx->payload[rx->received] = 0;
    return rx->received == rx->total ? 1 : 0;
}

static bool string_field(const cJSON *obj, const char *key, char *out, size_t capacity, bool nonempty)
{
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (!cJSON_IsString(v)) return false;
    size_t n = strlen(v->valuestring);
    if (n >= capacity || (nonempty && !n)) return false;
    memcpy(out, v->valuestring, n + 1);
    return true;
}

static bool number_field(const cJSON *obj, const char *key, double *out, double min, double max)
{
    const cJSON *v = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (!cJSON_IsNumber(v) || !isfinite(v->valuedouble) || v->valuedouble < min || v->valuedouble > max) return false;
    *out = v->valuedouble;
    return true;
}

static bool thresholds(const cJSON *o, pool_mqtt_command_t *c)
{
    pool_mqtt_settings_t *s = &c->settings;
    const cJSON *interlock = cJSON_GetObjectItemCaseSensitive(o, "interlock");
    if (interlock && !(cJSON_IsNumber(interlock) &&
        (interlock->valuedouble == 0 || interlock->valuedouble == 1))) return false;
    if (interlock) s->interlock = interlock->valuedouble == 1;
    return number_field(o, "temp_min", &s->temp_min, -55, 125) &&
        number_field(o, "temp_max", &s->temp_max, -55, 125) && s->temp_min < s->temp_max &&
        number_field(o, "water_min", &s->water_min, 0, 4095) &&
        number_field(o, "water_max", &s->water_max, 0, 4095) && s->water_min < s->water_max;
}

static bool wifi(const cJSON *o, pool_mqtt_settings_t *s)
{
    return string_field(o, "ssid", s->ssid, sizeof(s->ssid), true) &&
        string_field(o, "password", s->password, sizeof(s->password), false) &&
        (!s->password[0] || strlen(s->password) >= 8);
}

static bool device(const cJSON *o, pool_mqtt_settings_t *s)
{
    return string_field(o, "id", s->id, sizeof(s->id), true) &&
        string_field(o, "name", s->name, sizeof(s->name), false) &&
        string_field(o, "location", s->location, sizeof(s->location), false);
}

static bool http(const cJSON *o, pool_mqtt_settings_t *s)
{
    if (!string_field(o, "api_url", s->api_url, sizeof(s->api_url), true)) return false;
    const char *host = NULL;
    if (strncmp(s->api_url, "http://", 7) == 0) host = s->api_url + 7;
    if (strncmp(s->api_url, "https://", 8) == 0) host = s->api_url + 8;
    if (!host || !*host || *host == '/' || strchr(host, '@') || strchr(host, '#')) return false;
    for (const char *p = host; *p; p++) if ((unsigned char)*p <= 32) return false;
    return true;
}

static bool automation_condition(const cJSON *group, const char *name,
                                 pump_auto_condition_t *condition)
{
    const cJSON *item = cJSON_GetObjectItemCaseSensitive(group, name);
    const cJSON *enabled = cJSON_GetObjectItemCaseSensitive(item, "enabled");
    const cJSON *comparison = cJSON_GetObjectItemCaseSensitive(item, "comparison");
    const cJSON *threshold = cJSON_GetObjectItemCaseSensitive(item, "threshold");
    if (!cJSON_IsObject(item) || !cJSON_IsBool(enabled) || !cJSON_IsString(comparison) ||
        (strcmp(comparison->valuestring, "below") != 0 &&
         strcmp(comparison->valuestring, "above") != 0) || !cJSON_IsNumber(threshold)) return false;
    condition->enabled = cJSON_IsTrue(enabled);
    condition->below = strcmp(comparison->valuestring, "below") == 0;
    condition->threshold = threshold->valuedouble;
    return true;
}

static bool automation(const cJSON *root, pool_mqtt_command_t *command)
{
    const cJSON *channel = cJSON_GetObjectItemCaseSensitive(root, "channel");
    const cJSON *enabled = cJSON_GetObjectItemCaseSensitive(root, "enabled");
    const cJSON *mode = cJSON_GetObjectItemCaseSensitive(root, "stop_mode");
    const cJSON *duration = cJSON_GetObjectItemCaseSensitive(root, "duration_seconds");
    const cJSON *starts = cJSON_GetObjectItemCaseSensitive(root, "start_conditions");
    const cJSON *stops = cJSON_GetObjectItemCaseSensitive(root, "stop_conditions");
    if (!cJSON_IsNumber(channel) || (channel->valuedouble != 1 && channel->valuedouble != 2) ||
        !cJSON_IsBool(enabled) || !cJSON_IsString(mode) ||
        (strcmp(mode->valuestring, "duration") != 0 && strcmp(mode->valuestring, "threshold") != 0) ||
        !cJSON_IsNumber(duration) || duration->valuedouble < 1 || duration->valuedouble > 86400 ||
        floor(duration->valuedouble) != duration->valuedouble || !cJSON_IsObject(starts) ||
        !cJSON_IsObject(stops)) return false;

    command->automation_channel = (int)channel->valuedouble;
    command->automation.enabled = cJSON_IsTrue(enabled);
    command->automation.stop_mode = strcmp(mode->valuestring, "duration") == 0
        ? PUMP_STOP_DURATION : PUMP_STOP_THRESHOLD;
    command->automation.duration_seconds = (uint32_t)duration->valuedouble;
    for (int sensor = 0; sensor < PUMP_SENSOR_COUNT; sensor++) {
        const char *name = sensor == PUMP_SENSOR_WATER ? "water" : "temperature";
        if (!automation_condition(starts, name, &command->automation.start[sensor]) ||
            !automation_condition(stops, name, &command->automation.stop[sensor])) return false;
    }
    return pump_auto_config_valid(&command->automation);
}

bool pool_mqtt_decode(const char *topic, const char *payload, size_t length, bool retained,
                       const pool_mqtt_settings_t *base, pool_mqtt_command_t *c)
{
    memset(c, 0, sizeof(*c));
    c->retained = retained;
    if (!topic || !payload || !base || !length || length > POOL_MQTT_PAYLOAD_MAX || memchr(payload, 0, length)) return false;
    if (strcmp(topic, "smartpool/cmd/bom_vao") == 0) c->kind = POOL_MQTT_PUMP_IN;
    else if (strcmp(topic, "smartpool/cmd/bom_ra") == 0) c->kind = POOL_MQTT_PUMP_OUT;
    else if (strcmp(topic, "smartpool/cmd/temperature_enabled") == 0) c->kind = POOL_MQTT_TEMPERATURE;
    else if (strcmp(topic, "smartpool/cmd/water_level_enabled") == 0) c->kind = POOL_MQTT_WATER;
    if (c->kind != POOL_MQTT_INVALID) {
        if (length != 1 || (payload[0] != '0' && payload[0] != '1')) return false;
        c->on = payload[0] == '1';
        return !(retained && c->on && (c->kind == POOL_MQTT_PUMP_IN || c->kind == POOL_MQTT_PUMP_OUT));
    }
    const char *end = NULL;
    cJSON *root = cJSON_ParseWithLengthOpts(payload, length, &end, false);
    while (end && end < payload + length && (*end == ' ' || *end == '\r' || *end == '\n' || *end == '\t')) end++;
    if (!cJSON_IsObject(root) || end != payload + length) { cJSON_Delete(root); return false; }
    const char *action = NULL;
    if (strcmp(topic, "smartpool/config/thresholds") == 0) { action = "UPDATE_THRESHOLDS"; c->fields = POOL_CFG_THRESHOLDS; }
    else if (strcmp(topic, "smartpool/config/automation") == 0) {
        action = "UPDATE_AUTOMATION";
        c->kind = POOL_MQTT_AUTOMATION;
    }
    else if (strcmp(topic, "smartpool/config/wifi") == 0) { action = "UPDATE_WIFI"; c->fields = POOL_CFG_WIFI; }
    else if (strcmp(topic, "smartpool/config/device") == 0) { action = "UPDATE_DEVICE"; c->fields = POOL_CFG_DEVICE; }
    else if (strcmp(topic, "smartpool/config/http") == 0) { action = "UPDATE_HTTP"; c->fields = POOL_CFG_HTTP; }
    else if (strcmp(topic, "smartpool/config") == 0) { action = "CONFIG_UPDATE_ALL"; c->fields = 15; }
    else if (strcmp(topic, "smartpool/cmd/wifi_scan") == 0) {
        const cJSON *cmd = cJSON_GetObjectItemCaseSensitive(root, "cmd");
        bool ok = !retained && cJSON_IsString(cmd) && strcmp(cmd->valuestring, "SCAN_WIFI") == 0;
        c->kind = ok ? POOL_MQTT_SCAN : POOL_MQTT_INVALID;
        cJSON_Delete(root);
        return ok;
    }
    const cJSON *request = cJSON_GetObjectItemCaseSensitive(root, "req_id");
    // Keep a valid request ID even on validation failure so the app receives an ERROR ACK.
    bool ok = !request || string_field(root, "req_id", c->req_id, sizeof(c->req_id), true);
    const cJSON *a = cJSON_GetObjectItemCaseSensitive(root, "action");
    if (!action) ok = false;
    else { strcpy(c->action, action); ok = ok && cJSON_IsString(a) && strcmp(a->valuestring, action) == 0; }
    double timestamp = 0;
    ok = ok && number_field(root, "timestamp", &timestamp, 0, 4102444799.0) && timestamp == floor(timestamp);
    if (c->kind == POOL_MQTT_AUTOMATION) {
        ok = ok && request && c->req_id[0] && !retained && automation(root, c);
        if (!ok) c->error_message = retained
            ? "Automation commands must not be retained"
            : "Invalid automation rule; nothing applied";
        cJSON_Delete(root);
        return ok;
    }
    c->settings = *base;
    bool all = c->fields == 15;
    if (ok && (c->fields & POOL_CFG_THRESHOLDS)) ok = thresholds(all ? cJSON_GetObjectItemCaseSensitive(root, "thresholds") : root, c);
    if (ok && (c->fields & POOL_CFG_WIFI)) {
        const cJSON *section = all ? cJSON_GetObjectItemCaseSensitive(root, "wifi") : root;
        const cJSON *ssid = cJSON_GetObjectItemCaseSensitive(section, "ssid");
        const cJSON *password = cJSON_GetObjectItemCaseSensitive(section, "password");
        if (all && cJSON_IsString(ssid) && !ssid->valuestring[0] &&
            cJSON_IsString(password) && !password->valuestring[0]) {
            // Android Sync All includes blank credentials when Wi-Fi was never entered in the app.
            // This is not a request to erase or change the ESP32's existing network.
            c->fields &= ~POOL_CFG_WIFI;
            c->wifi_skipped = true;
        } else {
            ok = wifi(section, &c->settings);
            if (!ok) c->error_message = "Invalid Wi-Fi: SSID must be 1-32 bytes and password empty or 8-63 bytes";
        }
    }
    if (ok && (c->fields & POOL_CFG_DEVICE)) ok = device(all ? cJSON_GetObjectItemCaseSensitive(root, "device") : root, &c->settings);
    if (ok && (c->fields & POOL_CFG_HTTP)) ok = http(all ? cJSON_GetObjectItemCaseSensitive(root, "http") : root, &c->settings);
    if (ok) {
        c->kind = POOL_MQTT_CONFIG;
        for (int i = 0; i < 4; i++) if (c->fields & (1u << i)) c->settings.timestamps[i] = (uint64_t)timestamp;
    }
    cJSON_Delete(root);
    return ok;
}

int pool_mqtt_water_state(double raw, const pool_mqtt_settings_t *settings)
{
    return raw < settings->water_min ? 0 : raw > settings->water_max ? 2 : 1;
}

bool pool_mqtt_config_is_stale(const pool_mqtt_command_t *command, const pool_mqtt_settings_t *current)
{
    const pool_mqtt_settings_t *next = &command->settings;
    bool differs[4] = {
        next->temp_min != current->temp_min || next->temp_max != current->temp_max ||
            next->water_min != current->water_min || next->water_max != current->water_max || next->interlock != current->interlock,
        strcmp(next->ssid, current->ssid) || strcmp(next->password, current->password),
        strcmp(next->id, current->id) || strcmp(next->name, current->name) || strcmp(next->location, current->location),
        strcmp(next->api_url, current->api_url),
    };
    for (int i = 0; i < 4; i++) if (command->fields & (1u << i)) {
        if (next->timestamps[i] < current->timestamps[i] ||
            (command->retained && current->timestamps[i] && next->timestamps[i] == current->timestamps[i] && differs[i])) return true;
    }
    return false;
}
