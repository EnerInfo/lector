package app.lector

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.lector.ui.LectorTheme
import app.lector.ui.ReaderScreen
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    private var consumeReaderSpaceUntilUp = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_SPACE) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount != 0) {
                    return consumeReaderSpaceUntilUp
                }

                if (viewModel.uiState.value.screen == Screen.READER) {
                    consumeReaderSpaceUntilUp = true
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (consumeReaderSpaceUntilUp) {
                    consumeReaderSpaceUntilUp = false
                    viewModel.playPause()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // Only ingest the launch intent on a FRESH start. On a configuration
        // change (rotation) the ViewModel — and its reading position — survive,
        // so re-processing getIntent() here would wrongly reset to sentence 0.
        if (savedInstanceState == null) {
            handleIncomingText(intent)
        }
        setContent {
            LectorTheme {
                ReaderScreen(viewModel)
            }
        }
    }

    // launchMode=singleTask: shares arrive here while the app is already open.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingText(intent)
    }

    /**
     * Accept text from the share sheet (SEND) or the selection toolbar
     * (PROCESS_TEXT). EXTRA_TEXT may be a styled CharSequence, so read it with
     * getCharSequenceExtra — getStringExtra returns null for styled text.
     */
    private fun handleIncomingText(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND ->
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!text.isNullOrBlank()) {
            viewModel.setPastedText(text)
        }
    }
}
