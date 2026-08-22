package com.atan.starkaudio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StarkSignalUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun firstLaunchExplainsPrivateVault() {
        compose.onNodeWithText("Keep control of the recording").assertIsDisplayed()
        compose.onNodeWithText("Get started").assertIsDisplayed()
    }
}
