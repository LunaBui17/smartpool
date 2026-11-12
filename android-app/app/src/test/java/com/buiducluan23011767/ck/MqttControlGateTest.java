package com.buiducluan23011767.ck;

import org.junit.Test;
import static org.junit.Assert.*;

public class MqttControlGateTest {
    @Test public void cachedOnlineStatusDoesNotUnlockDisconnectedClient() {
        assertFalse(MqttControlGate.canControl(true, false, true));
    }
    @Test public void brokerConnectionAloneDoesNotUnlockControls() {
        assertFalse(MqttControlGate.canControl(true, true, false));
    }
    @Test public void backgroundActivityCannotControlEvenWithBothConnections() {
        assertFalse(MqttControlGate.canControl(false, true, true));
    }
    @Test public void foregroundControlsUnlockOnlyAfterDeviceStatus() {
        assertFalse(MqttControlGate.canControl(true, false, false));
        assertFalse(MqttControlGate.canControl(true, true, false));
        assertTrue(MqttControlGate.canControl(true, true, true));
        assertFalse(MqttControlGate.canControl(true, false, false));
    }
}
