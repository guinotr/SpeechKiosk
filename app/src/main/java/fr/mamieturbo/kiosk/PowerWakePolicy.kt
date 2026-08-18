package fr.mamieturbo.kiosk

internal object PowerWakePolicy {
    fun shouldReturnToSleep(stateInitialized: Boolean, wasPlugged: Boolean, isPlugged: Boolean): Boolean =
        stateInitialized && !wasPlugged && isPlugged
}
