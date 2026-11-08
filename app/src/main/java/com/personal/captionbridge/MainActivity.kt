package com.personal.captionbridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Caption Bridge"
            textSize = 26f
            setTextColor(Color.BLACK)
        }

        val description = TextView(this).apply {
            text = "Capture the latest five YouTube caption lines and send them to ChatGPT."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, 18, 0, 20)
        }

        val toggle = Switch(this).apply {
            text = "Enable caption capture"
            textSize = 17f

            isChecked = prefs.getBoolean("enabled", false)

            setOnCheckedChangeListener { _, checked ->
                prefs.edit()
                    .putBoolean("enabled", checked)
                    .apply()
            }
        }

        val accessibilityButton = Button(this).apply {
            text = "Enable Accessibility Service"

            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        val status = TextView(this).apply {
            text = """
                SETUP

                1. Enable the Caption Bridge accessibility service.
                2. Return to this application.
                3. Turn caption capture ON.
                4. Open YouTube.
                5. Enable video captions.
                6. Enter fullscreen.

                The floating CB button will appear when
                YouTube fullscreen is detected.

                Press CB to copy the latest five lines
                and open ChatGPT.
            """.trimIndent()

            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 18, 0, 0)
        }

        layout.addView(title)
        layout.addView(description)
        layout.addView(toggle)
        layout.addView(accessibilityButton)
        layout.addView(status)

        setContentView(layout)
    }
}
