package com.thotapalli.visidock

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class AuthPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun accountModeReflowsAndReturnsWithoutLosingEmail() {
        lateinit var focusManager:androidx.compose.ui.focus.FocusManager
        compose.runOnUiThread {compose.activity.setContent {VisiDockTheme {
            focusManager=androidx.compose.ui.platform.LocalFocusManager.current
            AuthScreen(null,{_,_,_,_->},{})
        }}}
        compose.onNodeWithText("Email address").performTextInput("demo@example.test")
        compose.onNodeWithText("New here? Create an account").performScrollTo().performClick()
        compose.onNodeWithText("Your name").assertExists().performTextInput("Demo Person")
        compose.onNodeWithText("Email address").assertTextContains("demo@example.test")
        compose.runOnUiThread {focusManager.clearFocus(force=true);androidx.core.view.WindowCompat.getInsetsController(compose.activity.window,compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())}
        compose.onNodeWithText("VisiDock").performScrollTo()
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        val target=File(instrumentation.targetContext.getExternalFilesDir(null) ?: instrumentation.targetContext.filesDir,"ui-review/phone-auth-register.png").apply {parentFile?.mkdirs()}
        try {target.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
        // Clear the editor focus before measuring the scroll target. IME insets
        // can otherwise move the footer between scrollTo and the injected tap.
        compose.runOnUiThread {focusManager.clearFocus(force=true);androidx.core.view.WindowCompat.getInsetsController(compose.activity.window,compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())}
        compose.onNodeWithText("Already have an account? Sign in").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Already have an account? Sign in").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {compose.onAllNodesWithText("Your name").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("Your name").assertDoesNotExist()
        compose.onNodeWithText("Email address").assertTextContains("demo@example.test")
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
    }
}
