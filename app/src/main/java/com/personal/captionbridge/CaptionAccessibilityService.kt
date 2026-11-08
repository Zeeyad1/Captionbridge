package com.personal.captionbridge

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast

class CaptionAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingButton: TextView? = null
    private var settings: SharedPreferences? = null

    private var failedFullscreenChecks = 0

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->

            if (key == "enabled") {

                val enabled =
                    settings?.getBoolean("enabled", false) ?: false

                if (!enabled) {
                    setFullscreenState(false)
                    hideButton()
                    CaptionBuffer.clear(this)
                }
            }
        }

    override fun onServiceConnected() {

        super.onServiceConnected()

        windowManager =
            getSystemService(Context.WINDOW_SERVICE)
                as WindowManager

        settings = getSharedPreferences(
            "settings",
            MODE_PRIVATE
        )

        settings?.registerOnSharedPreferenceChangeListener(
            preferenceListener
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null) return

        val enabled =
            settings?.getBoolean("enabled", false) ?: false

        if (!enabled) {
            setFullscreenState(false)
            hideButton()
            return
        }

        val packageName = event.packageName?.toString().orEmpty()

        if (packageName.isEmpty()) return

        if (packageName != "com.google.android.youtube") {

            failedFullscreenChecks = 0

            setFullscreenState(false)
            hideButton()

            return
        }

        val root = rootInActiveWindow ?: return

        val bounds = Rect()

        root.getBoundsInScreen(bounds)

        val metrics = resources.displayMetrics

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // Fullscreen detection is independent of caption recognition.
        val fullscreen =
            screenWidth > screenHeight &&
            bounds.width() >= screenWidth * 0.88f &&
            bounds.height() >= screenHeight * 0.82f

        if (fullscreen) {

            failedFullscreenChecks = 0

            setFullscreenState(true)
            showButton()

        } else {

            failedFullscreenChecks++

            // Allow transient accessibility-bound changes.
            if (failedFullscreenChecks >= 3) {

                setFullscreenState(false)
                hideButton()
            }
        }
    }

    private fun setFullscreenState(fullscreen: Boolean) {

        settings?.edit()
            ?.putBoolean("youtube_fullscreen", fullscreen)
            ?.apply()
    }

    private fun showButton() {

        if (floatingButton != null) return

        val button = TextView(this).apply {

            text = "CB"
            textSize = 15f

            setTextColor(Color.WHITE)

            typeface = Typeface.DEFAULT_BOLD

            gravity = Gravity.CENTER

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(30, 105, 220))
                setStroke(2, Color.WHITE)
            }

            contentDescription =
                "Send recent captions to ChatGPT"

            setOnClickListener {
                sendToChatGPT()
            }
        }

        val params = WindowManager.LayoutParams(

            dp(54),
            dp(54),

            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,

            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,

            PixelFormat.TRANSLUCENT

        ).apply {

            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL

            x = dp(14)
            y = 0
        }

        try {

            windowManager?.addView(button, params)

            floatingButton = button

        } catch (_: Exception) {

            floatingButton = null
        }
    }

    private fun hideButton() {

        floatingButton?.let {

            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
                // Already removed.
            }
        }

        floatingButton = null
    }

    private fun sendToChatGPT() {

        val text = CaptionBuffer.read(this)

        if (text.isBlank()) {

            Toast.makeText(
                this,
                "No captions captured yet",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE)
                as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "Recent YouTube captions",
                text
            )
        )

        val launchIntent =
            packageManager.getLaunchIntentForPackage(
                "com.openai.chatgpt"
            )

        if (launchIntent != null) {

            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            try {

                startActivity(launchIntent)

            } catch (_: Exception) {

                openShareChooser(text)
            }

        } else {

            openShareChooser(text)
        }
    }

    private fun openShareChooser(text: String) {

        val sendIntent = Intent(Intent.ACTION_SEND).apply {

            type = "text/plain"

            putExtra(Intent.EXTRA_TEXT, text)

            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {

            startActivity(
                Intent.createChooser(
                    sendIntent,
                    "Send captions"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

        } catch (_: Exception) {

            Toast.makeText(
                this,
                "Could not open a sharing application",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun dp(value: Int): Int {

        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    override fun onInterrupt() {

        setFullscreenState(false)
        hideButton()
    }

    override fun onDestroy() {

        setFullscreenState(false)
        hideButton()

        settings?.unregisterOnSharedPreferenceChangeListener(
            preferenceListener
        )

        super.onDestroy()
    }
}
