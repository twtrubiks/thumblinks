package com.twtrubiks.thumblinks

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class ThemeMode(val label: String) {
    SYSTEM("跟系統"),
    LIGHT("淺色"),
    DARK("深色"),
}

/**
 * 深色模式：跟系統、淺色、深色（清單右上角的按鈕切換），存在 SharedPreferences。
 * 畫面（[ThumbLinksTheme]）與內建瀏覽器的網頁（GeckoView 的 preferredColorScheme，由 [AppContainer] 接上 [onChange]）都跟著。
 */
class ThemeSetting(private val prefs: SharedPreferences) {
    var mode by mutableStateOf(prefs.getString(KEY, null)?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } } ?: ThemeMode.SYSTEM)
        private set

    var onChange: (ThemeMode) -> Unit = {}

    fun set(mode: ThemeMode) {
        this.mode = mode
        prefs.edit { putString(KEY, mode.name) }
        onChange(mode)
    }

    private companion object {
        const val KEY = "theme"
    }
}
