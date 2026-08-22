package com.atan.starkaudio.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

data class UserSettings(
    val onboardingComplete: Boolean = false,
    val appLockEnabled: Boolean = false,
    val lockGraceSeconds: Int = 60,
    val redactNotifications: Boolean = true
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_complete")
        val appLock = booleanPreferencesKey("app_lock_enabled")
        val grace = intPreferencesKey("lock_grace_seconds")
        val redact = booleanPreferencesKey("redact_notifications")
    }
    val settings: Flow<UserSettings> = context.settingsDataStore.data.map { p ->
        UserSettings(p[Keys.onboarding] ?: false, p[Keys.appLock] ?: false, p[Keys.grace] ?: 60, p[Keys.redact] ?: true)
    }
    suspend fun completeOnboarding() = context.settingsDataStore.edit { it[Keys.onboarding] = true }.let { Unit }
    suspend fun setAppLock(enabled: Boolean) = context.settingsDataStore.edit { it[Keys.appLock] = enabled }.let { Unit }
    suspend fun setLockGrace(seconds: Int) = context.settingsDataStore.edit { it[Keys.grace] = seconds.coerceIn(0, 300) }.let { Unit }
}
