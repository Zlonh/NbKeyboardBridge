package com.nbkeyboard.bridge.data

import android.content.Context
import androidx.core.content.edit

/** 发送速度档位（对应说明书中「稳定 / 均衡 / 极速」）。 */
enum class SpeedMode(
    val key: String,
    val title: String,
    val subtitle: String,
    /** 按下与松开之间的间隔，模拟真实按键时长。 */
    val holdMillis: Long,
    /** 两个按键之间的间隔。 */
    val gapMillis: Long,
) {
    STABLE("stable", "稳定", "兼容优先", holdMillis = 14L, gapMillis = 34L),
    BALANCED("balanced", "均衡", "推荐", holdMillis = 8L, gapMillis = 14L),
    FAST("fast", "极速", "关键帧保护", holdMillis = 4L, gapMillis = 5L);

    companion object {
        fun fromKey(key: String?): SpeedMode = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

/** 常用快捷键定义。 */
data class ShortcutItem(
    val title: String,
    val hint: String,
    val modifier: Int,
    val keys: IntArray,
    val longPressHint: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShortcutItem) return false
        return title == other.title && modifier == other.modifier && keys.contentEquals(other.keys)
    }

    override fun hashCode(): Int = (31 * title.hashCode() + modifier) * 31 + keys.contentHashCode()
}

/** 使用 SharedPreferences 保存上次设备与用户偏好。 */
class DeviceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_LAST_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_LAST_ADDRESS, value) }

    var lastDeviceName: String?
        get() = prefs.getString(KEY_LAST_NAME, null)
        set(value) = prefs.edit { putString(KEY_LAST_NAME, value) }

    var speedMode: SpeedMode
        get() = SpeedMode.fromKey(prefs.getString(KEY_SPEED, null))
        set(value) = prefs.edit { putString(KEY_SPEED, value.key) }

    /** 是否把「全角标点」自动折成半角。 */
    var foldFullWidth: Boolean
        get() = prefs.getBoolean(KEY_FOLD, true)
        set(value) = prefs.edit { putBoolean(KEY_FOLD, value) }

    /** 连接成功后自动重发一次「全选并删除」，用于覆盖电脑上已选中的内容。 */
    var replaceSelection: Boolean
        get() = prefs.getBoolean(KEY_REPLACE, false)
        set(value) = prefs.edit { putBoolean(KEY_REPLACE, value) }

    fun lastDeviceLabel(): String? {
        val address = lastDeviceAddress ?: return null
        val name = lastDeviceName?.takeIf { it.isNotBlank() }
        return name ?: address
    }

    private companion object {
        const val PREFS_NAME = "nb_bridge_prefs"
        const val KEY_LAST_ADDRESS = "last_device_address"
        const val KEY_LAST_NAME = "last_device_name"
        const val KEY_SPEED = "speed_mode"
        const val KEY_FOLD = "fold_fullwidth"
        const val KEY_REPLACE = "replace_selection"
    }
}
