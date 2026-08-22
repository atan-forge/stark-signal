package com.atan.starkaudio.service

import com.atan.starkaudio.core.domain.HeavyWorkCoordinator
import com.atan.starkaudio.core.model.HeavyWorkType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HeavyWorkCoordinatorImpl : HeavyWorkCoordinator {
    private val mutex = Mutex()
    private val mutableActive = MutableStateFlow<HeavyWorkType?>(null)
    override val active: StateFlow<HeavyWorkType?> = mutableActive
    override suspend fun acquire(type: HeavyWorkType): Boolean = mutex.withLock {
        val current = mutableActive.value
        val allowed = current == null || type == HeavyWorkType.RECORDING
        if (allowed) mutableActive.value = type
        allowed
    }
    override suspend fun release(type: HeavyWorkType) = mutex.withLock {
        if (mutableActive.value == type) mutableActive.value = null
    }
}
