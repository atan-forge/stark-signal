package com.atan.starkaudio.ui

/** Pure policy for deciding when a private-vault session must authenticate again. */
internal object AppLockPolicy {
    fun locksOnReturn(appLockEnabled: Boolean, elapsedBackgroundMs: Long, graceSeconds: Int): Boolean =
        appLockEnabled && elapsedBackgroundMs >= graceSeconds.coerceIn(0, 300) * 1_000L
}
