package com.personal.captionbridge

import android.content.Context

object CaptionBuffer {

    private const val PREFS = "settings"
    private const val BUFFER_KEY = "recent_captions"
    private const val MAX_LINES = 5

    @Synchronized
    fun appendLines(context: Context, lines: List<String>) {

        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val recent = prefs.getString(BUFFER_KEY, "")
            .orEmpty()
            .lines()
            .filter { it.isNotBlank() }
            .toMutableList()

        for (rawLine in lines) {

            val line = rawLine
                .replace(Regex("\\s+"), " ")
                .trim()

            if (line.length < 2) continue

            // Avoid repeatedly adding the same visible caption.
            if (recent.lastOrNull() == line) continue

            // Avoid duplicating a line still in the buffer.
            if (line in recent) continue

            recent.add(line)

            while (recent.size > MAX_LINES) {
                recent.removeAt(0)
            }
        }

        prefs.edit()
            .putString(BUFFER_KEY, recent.joinToString("\n"))
            .apply()
    }

    @Synchronized
    fun read(context: Context): String {

        return context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getString(BUFFER_KEY, "")
            .orEmpty()
    }

    @Synchronized
    fun clear(context: Context) {

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .remove(BUFFER_KEY)
            .apply()
    }
}
