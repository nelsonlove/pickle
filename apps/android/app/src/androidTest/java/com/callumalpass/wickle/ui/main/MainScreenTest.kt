package com.callumalpass.wickle.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.callumalpass.wickle.data.WickleRequest
import com.callumalpass.wickle.data.WickleUiState
import com.callumalpass.wickle.theme.WickleTheme
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun pagesAreSeparateAndNavigable() {
    composeTestRule.setContent {
      WickleTheme {
        WickleApp(
          state =
            WickleUiState(
              requests =
                listOf(
                  WickleRequest(
                    id = "req_test",
                    source = "agent",
                    kind = "approval",
                    title = "Approve test",
                    status = "pending",
                    createdAt = "2026-05-20T00:00:00Z",
                    updatedAt = "2026-05-20T00:00:00Z",
                  ),
                ),
            ),
          onRefresh = {},
          onSelect = {},
          onSaveSettings = {},
          onTestConnection = {},
          onStartRealtime = {},
          onStopRealtime = {},
          onRespond = { _, _ -> },
          onSendMessage = { _, _, _, done -> done() },
          onNoticeShown = {},
        )
      }
    }
    composeTestRule.onNodeWithText("Wickle").assertExists()
    composeTestRule.onNodeWithContentDescription("Inbox tab").assertExists()
    composeTestRule.onNodeWithText("Approve test").performClick()
    composeTestRule.onNodeWithText("Detail").assertExists()
    composeTestRule.onNodeWithContentDescription("Back").performClick()
    composeTestRule.onNodeWithContentDescription("Post tab").performClick()
    composeTestRule.onNodeWithText("Title").performTextInput("hello agents")
    composeTestRule.onNodeWithText("Tags").performTextInput("ops follow-up")
    composeTestRule.onNodeWithText("#ops").assertExists()
    composeTestRule.onNodeWithContentDescription("Settings tab").performClick()
    composeTestRule.onNodeWithText("Server").assertExists()
  }
}
