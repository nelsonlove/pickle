package com.callumalpass.wickle.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.callumalpass.wickle.theme.WickleTheme
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun inboxTitle_exists() {
    composeTestRule.setContent { WickleTheme { MainScreen() } }
    composeTestRule.onNodeWithText("Wickle").assertExists()
  }
}
