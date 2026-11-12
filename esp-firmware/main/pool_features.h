#pragma once

#include "sdkconfig.h"

// Kconfig omits disabled bool symbols from sdkconfig.h.
#ifdef CONFIG_POOL_TEMPERATURE_ENABLED
#define POOL_TEMPERATURE_ENABLED 1
#else
#define POOL_TEMPERATURE_ENABLED 0
#endif

#ifdef CONFIG_POOL_WATER_ENABLED
#define POOL_WATER_ENABLED 1
#else
#define POOL_WATER_ENABLED 0
#endif

#ifdef CONFIG_POOL_RELAY_1_ENABLED
#define POOL_RELAY_1_ENABLED 1
#else
#define POOL_RELAY_1_ENABLED 0
#endif

#ifdef CONFIG_POOL_RELAY_2_ENABLED
#define POOL_RELAY_2_ENABLED 1
#else
#define POOL_RELAY_2_ENABLED 0
#endif

#ifdef CONFIG_POOL_MQTT_ENABLED
#define POOL_MQTT_ENABLED 1
#else
#define POOL_MQTT_ENABLED 0
#endif

#ifdef CONFIG_POOL_HTTP_ENABLED
#define POOL_HTTP_ENABLED 1
#else
#define POOL_HTTP_ENABLED 0
#endif

#if defined(CONFIG_POOL_RELAY_INITIAL_1_ON) || defined(CONFIG_POOL_RELAY_INITIAL_BOTH_ON)
#define POOL_RELAY_INITIAL_1_ON 1
#else
#define POOL_RELAY_INITIAL_1_ON 0
#endif

#if defined(CONFIG_POOL_RELAY_INITIAL_2_ON) || defined(CONFIG_POOL_RELAY_INITIAL_BOTH_ON)
#define POOL_RELAY_INITIAL_2_ON 1
#else
#define POOL_RELAY_INITIAL_2_ON 0
#endif

#ifdef CONFIG_POOL_WIFI_STA_ENABLED
#define POOL_WIFI_STA_ENABLED 1
#else
#define POOL_WIFI_STA_ENABLED 0
#endif
