package com.atan.starkaudio.ui

import com.atan.starkaudio.storage.UserSettings

sealed interface SettingsLoadState {
    data object Loading : SettingsLoadState
    data class Ready(val value: UserSettings) : SettingsLoadState
}
