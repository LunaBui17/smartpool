#!/bin/sh
set -eu
umask 077
# Hash via a temporary file so passwords never appear in command arguments.
printf 'esp32:%s\nandroid:%s\n' "$MQTT_ESP_PASSWORD" "$MQTT_APP_PASSWORD" > /auth/passwords.new
mosquitto_passwd -U /auth/passwords.new
chown mosquitto:mosquitto /auth/passwords.new
chmod 600 /auth/passwords.new
mv /auth/passwords.new /auth/passwords
