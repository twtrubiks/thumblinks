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
 * 使用者在清單右上角選的設定（深色模式 [ThemeMode]、排序 [SortOrder]），存在 SharedPreferences（存名字，不存序號）。
 * [value] 是 Compose 的狀態，畫面直接讀；改了要通知 Compose 以外的（例如 GeckoView 的深淺）用 [onChange]。
 */
class EnumSetting<E : Enum<E>>(private val prefs: SharedPreferences, private val key: String, entries: List<E>, default: E) {
    var value by mutableStateOf(prefs.getString(key, null)?.let { saved -> entries.firstOrNull { it.name == saved } } ?: default)
        private set

    var onChange: (E) -> Unit = {}

    fun set(value: E) {
        this.value = value
        prefs.edit { putString(key, value.name) }
        onChange(value)
    }
}
