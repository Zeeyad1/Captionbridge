package com.personal.captionbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val captureRequestCode = 1001

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

                if (!checked) {
                    stopService(
                        Intent(
                            this@MainActivity,
                            ScreenCaptureService::class.java
                        )
                    )
                }
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

        val captureButton = Button(this).apply {
            text = "Start Screen Capture"

            setOnClickListener {

                if (!prefs.getBoolean("enabled", false)) {

                    Toast.makeText(
                        this@MainActivity,
                        "Enable caption capture first",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setOnClickListener
                }

                val manager =
                    getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE
                    ) as MediaProjectionManager

                startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    captureRequestCode
                )
            }
        }

        val status = TextView(this).apply {
            text = """
                SETUP

                1. Enable the Caption Bridge accessibility service.
                2. Return to this application.
                3. Turn caption capture ON.
                4. Press Start Screen Capture.
                5. Approve Android's screen-capture permission.
                6. Open YouTube and enter fullscreen.

                The floating CB button will appear
                when YouTube fullscreen is detected.

                Press CB to transfer the latest five
                recognized caption lines to ChatGPT.
            """.trimIndent()

            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 18, 0, 0)
        }

        layout.addView(title)
        layout.addView(description)
        layout.addView(toggle)
        layout.addView(accessibilityButton)
        layout.addView(captureButton)
        layout.addView(status)

        setContentView(layout)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == captureRequestCode) {

            if (resultCode == RESULT_OK && data != null) {

                val serviceIntent = Intent(
                    this,
                    ScreenCaptureService::class.java
                ).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }

                startForegroundService(serviceIntent)

                Toast.makeText(
                    this,
                    "Screen capture permission granted",
                    Toast.LENGTH_SHORT
                ).show()

            } else {

                Toast.makeText(
                    this,
                    "Screen capture permission denied",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
