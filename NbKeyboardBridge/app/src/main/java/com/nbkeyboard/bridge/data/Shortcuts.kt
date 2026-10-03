package com.nbkeyboard.bridge.data

import com.nbkeyboard.bridge.protocol.HidKeyMap
import com.nbkeyboard.bridge.protocol.NbProtocol

/** 常用快捷键面板的数据与发送逻辑。 */
object Shortcuts {

    /** 界面分组标题。 */
    const val GROUP_COMMON = "常用"
    const val GROUP_EDIT = "编辑"
    const val GROUP_MEDIA = "媒体与系统"

    val ALL: List<ShortcutItem> = listOf(
        // ---------------- 常用 ----------------
        ShortcutItem("复制", "Ctrl + C", NbProtocol.BTN_LEFT_CTRL, intArrayOf(HidKeyMap.C)),
        ShortcutItem("粘贴", "Ctrl + V", NbProtocol.BTN_LEFT_CTRL, intArrayOf(HidKeyMap.V)),
        ShortcutItem("剪切", "Ctrl + X", NbProtocol.BTN_LEFT_CTRL, intArrayOf(HidKeyMap.X)),
        ShortcutItem("撤销", "Ctrl + Z", NbProtocol.BTN_LEFT_CTRL, intArrayOf(HidKeyMap.Z)),
        ShortcutItem("重做", "Ctrl + Y", NbProtocol.BTN_LEFT_CTRL, intArrayOf(HidKeyMap.Y)),
        ShortcutItem("全选", "Ctrl + A", NbProtocol.BTN_LEFT_CTRL, intArrayOf(HidKeyMap.A)),
        ShortcutItem("保存", "Ctrl + S", NbProtocol.BTN_LEFT_CTRL, intArrayOf(HidKeyMap.S)),
        ShortcutItem("查找", "Ctrl + F", NbProtocol.BTN_LEFT_CTRL, intArrayOf(HidKeyMap.F)),
        ShortcutItem("新建", "Ctrl + N", NbProtocol.BTN_LEFT_CTRL, intArrayOf(HidKeyMap.N)),
        ShortcutItem("打印", "Ctrl + P", NbProtocol.BTN_LEFT_CTRL, intArrayOf(HidKeyMap.P)),
        ShortcutItem("切换窗口", "Alt + Tab", NbProtocol.BTN_LEFT_ALT, intArrayOf(HidKeyMap.TAB)),
        ShortcutItem("关闭窗口", "Alt + F4", NbProtocol.BTN_LEFT_ALT, intArrayOf(HidKeyMap.F4)),
        ShortcutItem("任务管理器", "Ctrl + Shift + Esc",
            NbProtocol.BTN_LEFT_CTRL or NbProtocol.BTN_LEFT_SHIFT, intArrayOf(HidKeyMap.ESC)),
        ShortcutItem("锁屏", "Win + L", NbProtocol.BTN_LEFT_GUI, intArrayOf(HidKeyMap.L)),
        ShortcutItem("显示桌面", "Win + D", NbProtocol.BTN_LEFT_GUI, intArrayOf(HidKeyMap.D)),
        ShortcutItem("资源管理器", "Win + E", NbProtocol.BTN_LEFT_GUI, intArrayOf(HidKeyMap.E)),
        ShortcutItem("运行", "Win + R", NbProtocol.BTN_LEFT_GUI, intArrayOf(HidKeyMap.R)),
        ShortcutItem("截图", "Win + Shift + S",
            NbProtocol.BTN_LEFT_GUI or NbProtocol.BTN_LEFT_SHIFT, intArrayOf(HidKeyMap.S)),

        // ---------------- 编辑 / 导航 ----------------
        ShortcutItem("回车", "Enter", 0, intArrayOf(HidKeyMap.ENTER)),
        ShortcutItem("退格", "Backspace", 0, intArrayOf(HidKeyMap.BACKSPACE)),
        ShortcutItem("删除", "Delete", 0, intArrayOf(HidKeyMap.DELETE)),
        ShortcutItem("Tab", "Tab", 0, intArrayOf(HidKeyMap.TAB)),
        ShortcutItem("Esc", "Esc", 0, intArrayOf(HidKeyMap.ESC)),
        ShortcutItem("向上", "↑", 0, intArrayOf(HidKeyMap.ARROW_UP), longPressHint = "按住连续上移"),
        ShortcutItem("向下", "↓", 0, intArrayOf(HidKeyMap.ARROW_DOWN), longPressHint = "按住连续下移"),
        ShortcutItem("向左", "←", 0, intArrayOf(HidKeyMap.ARROW_LEFT)),
        ShortcutItem("向右", "→", 0, intArrayOf(HidKeyMap.ARROW_RIGHT)),
        ShortcutItem("行首", "Home", 0, intArrayOf(HidKeyMap.HOME)),
        ShortcutItem("行尾", "End", 0, intArrayOf(HidKeyMap.END)),
        ShortcutItem("上一页", "Page Up", 0, intArrayOf(HidKeyMap.PAGE_UP)),
        ShortcutItem("下一页", "Page Down", 0, intArrayOf(HidKeyMap.PAGE_DOWN)),

        // ---------------- 媒体与系统 ----------------
        // 说明：说明书只定义了 0x00–0xFF 的单字节键码，且未定义 HID 使用页 0x0C
        // （Consumer）的媒体控制，因此这里不提供「播放/上一曲/下一曲」，
        // 避免发出电脑无法识别的键码。音量键使用 0x80/0x81（说明书第 5 页列出）。
        ShortcutItem("音量 +", "Volume Up", 0, intArrayOf(HidKeyMap.VOLUME_UP)),
        ShortcutItem("音量 −", "Volume Down", 0, intArrayOf(HidKeyMap.VOLUME_DOWN)),
        ShortcutItem("静音", "Mute", 0, intArrayOf(HidKeyMap.MUTE)),
        ShortcutItem("睡眠", "Sleep", 0, intArrayOf(HidKeyMap.SYS_SLEEP)),
        ShortcutItem("唤醒", "Wake Up", 0, intArrayOf(HidKeyMap.SYS_WAKE)),
        ShortcutItem("大写锁定", "Caps Lock", 0, intArrayOf(HidKeyMap.CAPS_LOCK)),
        ShortcutItem("小键盘锁定", "Num Lock", 0, intArrayOf(HidKeyMap.NUM_LOCK)),
        ShortcutItem("滚动锁定", "Scroll Lock", 0, intArrayOf(HidKeyMap.SCROLL_LOCK)),
        ShortcutItem("Insert", "Insert", 0, intArrayOf(HidKeyMap.INSERT)),
        ShortcutItem("Pause", "Pause", 0, intArrayOf(HidKeyMap.PAUSE)),
        ShortcutItem("打印屏幕", "Print Screen", 0, intArrayOf(HidKeyMap.PRINT_SCREEN)),
        ShortcutItem("菜单键", "Application", 0, intArrayOf(HidKeyMap.APP)),
    )

    val COMMON: List<ShortcutItem> = ALL.take(18)
    val EDIT: List<ShortcutItem> = ALL.subList(18, 31)
    val MEDIA: List<ShortcutItem> = ALL.drop(31)

    /** 可单独发送的单个按键（供「自定义按键」列表使用）。 */
    val SINGLE_KEYS: List<Pair<String, Int>> = listOf(
        "Enter" to HidKeyMap.ENTER,
        "Esc" to HidKeyMap.ESC,
        "Tab" to HidKeyMap.TAB,
        "空格" to HidKeyMap.SPACE,
        "退格" to HidKeyMap.BACKSPACE,
        "删除" to HidKeyMap.DELETE,
        "Home" to HidKeyMap.HOME,
        "End" to HidKeyMap.END,
        "PageUp" to HidKeyMap.PAGE_UP,
        "PageDown" to HidKeyMap.PAGE_DOWN,
        "↑" to HidKeyMap.ARROW_UP,
        "↓" to HidKeyMap.ARROW_DOWN,
        "←" to HidKeyMap.ARROW_LEFT,
        "→" to HidKeyMap.ARROW_RIGHT,
        "F1" to HidKeyMap.F1,
        "F2" to HidKeyMap.F2,
        "F5" to HidKeyMap.F5,
        "F11" to HidKeyMap.F11,
        "F12" to HidKeyMap.F12,
        "CapsLock" to HidKeyMap.CAPS_LOCK,
        "PrintScreen" to HidKeyMap.PRINT_SCREEN,
        "Win" to HidKeyMap.LEFT_GUI,
        "左Ctrl" to HidKeyMap.LEFT_CTRL,
        "左Shift" to HidKeyMap.LEFT_SHIFT,
        "左Alt" to HidKeyMap.LEFT_ALT,
    )

    /**
     * 把「修饰键 HID Code + 普通键 HID Code」组合换算成协议需要的
     * （修饰键位图, 普通键数组）。
     */
    fun resolve(modifierHidCodes: Int, keyHidCodes: Int): Pair<Int, IntArray> {
        var bits = 0
        var key = keyHidCodes
        for (code in intArrayOf(
            HidKeyMap.LEFT_CTRL, HidKeyMap.LEFT_SHIFT, HidKeyMap.LEFT_ALT, HidKeyMap.LEFT_GUI,
            HidKeyMap.RIGHT_CTRL, HidKeyMap.RIGHT_SHIFT, HidKeyMap.RIGHT_ALT, HidKeyMap.RIGHT_GUI,
        )) {
            if (modifierHidCodes and code != 0) bits = bits or (HidKeyMap.MODIFIER_BITS[code] ?: 0)
        }
        if (HidKeyMap.isModifier(key)) {
            bits = bits or (HidKeyMap.MODIFIER_BITS[key] ?: 0)
            key = 0
        }
        return bits to intArrayOf(key)
    }
}
