# MQTT và luồng Android Smart Pool

Tài liệu giao thức dùng chung được duy trì tại [README_MQTT_ESP32.md](../README_MQTT_ESP32.md), tránh hai bản đặc tả khác nhau.

- [Luồng toàn hệ thống ESP → Pi → Android](../docs/system-flow.md).
- [Cài đặt, endpoint và vận hành Pi](../deploy/raspberry-pi/README.md).
- [Hướng dẫn tổng thể](../README.md).

App đăng nhập và đọc cảm biến/lịch sử qua HTTPS API; điều khiển bơm và nhận status qua MQTT. PoolMonitorService nhận telemetry để cảnh báo nền. Khi dùng Pi, đổi broker trong Settings sang `mqtt_wss_uri` từ `~/smartpool-endpoints.txt`, user `android` và password tương ứng trên Pi. Broker mặc định trong AppConfig hiện vẫn là HiveMQ; thay API không tự thay broker.

Tên khóa `hivemq.username`/`hivemq.password` trong `mqtt.properties` chỉ là tên cấu hình BuildConfig hiện có; có thể điền tài khoản Mosquitto của app. Credential đã lưu trong Settings ưu tiên giá trị BuildConfig.
