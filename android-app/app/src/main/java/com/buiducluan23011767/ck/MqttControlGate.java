package com.buiducluan23011767.ck;

/** Broker connectivity alone does not prove that ESP32 is online. */
final class MqttControlGate {
    private MqttControlGate() { }

    static boolean canControl(boolean activityVisible, boolean brokerConnected, boolean espOnline) {
        return activityVisible && brokerConnected && espOnline;
    }
}
