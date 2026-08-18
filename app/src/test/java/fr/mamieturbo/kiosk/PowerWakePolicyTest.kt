package fr.mamieturbo.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerWakePolicyTest {
    @Test fun firstLaunchNeverForcesSleep() {
        assertFalse(PowerWakePolicy.shouldReturnToSleep(false, false, true))
    }

    @Test fun unpluggedToPluggedReturnsToSleep() {
        assertTrue(PowerWakePolicy.shouldReturnToSleep(true, false, true))
    }

    @Test fun normalUnlockWhileAlreadyPluggedStaysAwake() {
        assertFalse(PowerWakePolicy.shouldReturnToSleep(true, true, true))
    }

    @Test fun unpluggedWakeStaysAwake() {
        assertFalse(PowerWakePolicy.shouldReturnToSleep(true, false, false))
    }
}
