package com.example.myapplication.pw12.errors

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал помилок мережевих запитів. */
interface ErrorLogger {
    fun log(request: String, error: String, details: String, attempt: String, action: String)
    fun recent(limit: Int = 20): List<String>
    fun clear()
}

/**
 * Реєстрація помилок у файл (filesDir/pw12/network_errors.log) та дублювання в Logcat.
 * Файл можна вивантажити для аналізу (adb pull) або надіслати на сервер аналітики.
 * Розмір обмежено [maxLines] рядками, щоб журнал не ріс безкінечно.
 */
class FileErrorLogger(
    private val file: File,
    private val logcat: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val maxLines: Int = 500,
) : ErrorLogger {

    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    override fun log(request: String, error: String, details: String, attempt: String, action: String) {
        val line = listOf(format.format(Date(now())), request, error, details, "спроба $attempt", action).joinToString(" | ")
        logcat(line)
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
        val lines = file.readLines()
        if (lines.size > maxLines) file.writeText(lines.takeLast(maxLines).joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    override fun recent(limit: Int): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.takeLast(limit).reversed() else emptyList()

    @Synchronized
    override fun clear() {
        file.delete()
    }
}
