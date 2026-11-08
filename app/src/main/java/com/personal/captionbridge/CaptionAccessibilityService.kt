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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque

class CaptionAccessibilityService : AccessibilityService() {

    // Maximum number of stored lines.
    private val recent = ArrayDeque<String>(5)

    private var windowManager: WindowManager? = null

    private var floatingButton: TextView? = null

    private var settings: SharedPreferences? = null

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->

            if (key == "enabled") {
                val enabled =
                    settings?.getBoolean("enabled", false) ?: false

                if (!enabled) {
                    hideButton()
                }
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()

        windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        settings = getSharedPreferences("settings", MODE_PRIVATE)

        settings?.registerOnSharedPreferenceChangeListener(
            preferenceListener
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null) return

        val enabled =
            settings?.getBoolean("enabled", false) ?: false

        if (!enabled) {
            hideButton()
            return
        }

        val packageName = event.packageName?.toString() ?: ""

        // Only operate while YouTube is the active app.
        if (packageName != "com.google.android.youtube") {
            if (packageName.isNotEmpty()) {
                hideButton()
            }
            return
        }

        val root = rootInActiveWindow

        if (root == null) {
            hideButton()
            return
        }

        val screenBounds = Rect()

        root.getBoundsInScreen(screenBounds)

        val metrics = resources.displayMetrics

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        /*
         * Fullscreen heuristic:
         * The active YouTube window must occupy most
         * of the available display in both dimensions.
         *
         * This is not a guaranteed fullscreen detector.
         */

        val fullscreen =
            screenBounds.width() >= screenWidth * 0.88f &&
            screenBounds.height() >= screenHeight * 0.82f

        if (fullscreen) {

            scanCaptionNodes(root, screenHeight)

            showButton()

        } else {

            hideButton()
        }
    }

    private fun scanCaptionNodes(
        root: AccessibilityNodeInfo,
        screenHeight: Int
    ) {

        val found = mutableListOf<Pair<Int, String>>()

        fun walk(node: AccessibilityNodeInfo?) {

            if (node == null) return

            val text = node.text?.toString()?.trim().orEmpty()

            if (
                text.isNotBlank() &&
                text.length in 2..180 &&
                node.isVisibleToUser &&
                !node.isClickable &&
                !node.isFocusable
            ) {

                val bounds = Rect()

                node.getBoundsInScreen(bounds)

                // Only inspect the lower portion of the screen.
                if (
                    bounds.centerY() > screenHeight * 0.45f &&
                    bounds.width() > 20 &&
                    bounds.height() > 5
                ) {

                    val cleaned = text.replace(
                        Regex("\\s+"),
                        " "
                    )

                    if (!looksLikeControlLabel(cleaned)) {

                        found.add(
                            Pair(bounds.centerY(), cleaned)
                        )
                    }
                }
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }

        walk(root)

        // Sort text from top to bottom.
        val ordered = found
            .sortedBy { it.first }
            .map { it.second }
            .distinct()

        for (line in ordered) {
            appendLine(line)
        }
    }

    private fun looksLikeControlLabel(text: String): Boolean {

        val lower = text.lowercase()

        val controls = listOf(
            "play",
            "pause",
            "settings",
            "subtitles",
            "captions",
            "skip ads",
            "more options",
            "seek",
            "volume",
            "youtube"
        )

        return controls.any {
            lower == it || lower.startsWith("$it ")
        }
    }

    private fun appendLine(line: String) {

        // Avoid adding the same currently buffered line again.
        if (recent.contains(line)) return

        recent.addLast(line)

        // Maintain exactly five or fewer lines.
        while (recent.size > 5) {
            recent.removeFirst()
        }
    }

    private fun showButton() {

        if (floatingButton != null) return

        val button = TextView(this).apply {

            text = "CB"

            textSize = 15f

            setTextColor(Color.WHITE)

            typeface = Typeface.DEFAULT_BOLD

            gravity = Gravity.CENTER

            val backgroundShape = GradientDrawable().apply {

                shape = GradientDrawable.OVAL

                setColor(Color.rgb(30, 105, 220))

                setStroke(2, Color.WHITE)
            }

            background = backgroundShape

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
                // View may already have been removed.
            }
        }

        floatingButton = null
    }

    private fun sendToChatGPT() {

        val text = recent.toList().joinToString("\n")

        if (text.isBlank()) {

            Toast.makeText(
                this,
                "No captions captured yet",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        // Copy captured captions.
        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE)
                as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "Recent YouTube captions",
                text
            )
        )

        // Open the installed ChatGPT application.
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

        hideButton()
    }

    override fun onDestroy() {

        hideButton()

        settings?.unregisterOnSharedPreferenceChangeListener(
            preferenceListener
        )

        super.onDestroy()
    }
}
