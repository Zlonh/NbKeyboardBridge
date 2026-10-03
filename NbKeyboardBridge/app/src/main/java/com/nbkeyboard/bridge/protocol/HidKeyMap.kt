package com.nbkeyboard.bridge.protocol

/**
 * USB HID Usage ID（Keyboard/Keypad Page 0x07）常量表。
 *
 * 数值全部对照《蓝牙键盘接口说明书》第 2 页「普通按键及对应的键码表」，例如
 * A=0x04、Enter_L=0x28、Left Arrow=0x50、F1=0x3A、L_WIN=0xE3。
 */
object HidKeyMap {

    const val NONE = 0x00

    // 字母
    const val A = 0x04
    const val B = 0x05
    const val C = 0x06
    const val D = 0x07
    const val E = 0x08
    const val F = 0x09
    const val G = 0x0A
    const val H = 0x0B
    const val I = 0x0C
    const val J = 0x0D
    const val K = 0x0E
    const val L = 0x0F
    const val M = 0x10
    const val N = 0x11
    const val O = 0x12
    const val P = 0x13
    const val Q = 0x14
    const val R = 0x15
    const val S = 0x16
    const val T = 0x17
    const val U = 0x18
    const val V = 0x19
    const val W = 0x1A
    const val X = 0x1B
    const val Y = 0x1C
    const val Z = 0x1D

    // 数字
    const val NUM_1 = 0x1E
    const val NUM_2 = 0x1F
    const val NUM_3 = 0x20
    const val NUM_4 = 0x21
    const val NUM_5 = 0x22
    const val NUM_6 = 0x23
    const val NUM_7 = 0x24
    const val NUM_8 = 0x25
    const val NUM_9 = 0x26
    const val NUM_0 = 0x27

    // 编辑与空白
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val MINUS = 0x2D
    const val EQUALS = 0x2E
    const val LEFT_BRACKET = 0x2F
    const val RIGHT_BRACKET = 0x30
    const val BACKSLASH = 0x31
    const val SEMICOLON = 0x33
    const val QUOTE = 0x34
    const val GRAVE = 0x35
    const val COMMA = 0x36
    const val PERIOD = 0x37
    const val SLASH = 0x38
    const val CAPS_LOCK = 0x39

    // 功能键
    const val F1 = 0x3A
    const val F2 = 0x3B
    const val F3 = 0x3C
    const val F4 = 0x3D
    const val F5 = 0x3E
    const val F6 = 0x3F
    const val F7 = 0x40
    const val F8 = 0x41
    const val F9 = 0x42
    const val F10 = 0x43
    const val F11 = 0x44
    const val F12 = 0x45

    const val PRINT_SCREEN = 0x46
    const val SCROLL_LOCK = 0x47
    const val PAUSE = 0x48
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val ARROW_RIGHT = 0x4F
    const val ARROW_LEFT = 0x50
    const val ARROW_DOWN = 0x51
    const val ARROW_UP = 0x52

    const val NUM_LOCK = 0x53
    const val KEYPAD_SLASH = 0x54
    const val KEYPAD_ASTERISK = 0x55
    const val KEYPAD_MINUS = 0x56
    const val KEYPAD_PLUS = 0x57
    const val KEYPAD_ENTER = 0x58
    const val KEYPAD_1 = 0x59
    const val KEYPAD_2 = 0x5A
    const val KEYPAD_3 = 0x5B
    const val KEYPAD_4 = 0x5C
    const val KEYPAD_5 = 0x5D
    const val KEYPAD_6 = 0x5E
    const val KEYPAD_7 = 0x5F
    const val KEYPAD_8 = 0x60
    const val KEYPAD_9 = 0x61
    const val KEYPAD_0 = 0x62
    const val KEYPAD_PERIOD = 0x63

    /** 说明书备注：Keycode45 仅部分键盘支持，对应「非美式键盘」的反斜杠键，此处仍用 Backslash。 */
    const val NON_US_BACKSLASH = 0x64

    const val APP = 0x65 // 菜单键
    const val POWER = 0x66
    const val KEYPAD_EQUALS = 0x67

    const val F13 = 0x68
    const val F14 = 0x69
    const val F15 = 0x6A
    const val F16 = 0x6B
    const val F17 = 0x6C
    const val F18 = 0x6D
    const val F19 = 0x6E
    const val F20 = 0x6F
    const val F21 = 0x70
    const val F22 = 0x71
    const val F23 = 0x72
    const val F24 = 0x73

    const val EXECUTE = 0x74
    const val HELP = 0x75
    const val MENU = 0x76
    const val SELECT = 0x77
    const val STOP = 0x78
    const val AGAIN = 0x79
    const val UNDO = 0x7A
    const val CUT = 0x7B
    const val COPY = 0x7C
    const val PASTE = 0x7D
    const val FIND = 0x7E
    const val MUTE = 0x7F

    // 音量 / 媒体
    const val VOLUME_UP = 0x80
    const val VOLUME_DOWN = 0x81
    const val LOCKING_CAPS_LOCK = 0x82
    const val LOCKING_NUM_LOCK = 0x83
    const val LOCKING_SCROLL_LOCK = 0x84
    const val KEYPAD_COMMA = 0x85
    const val KEYPAD_EQUAL_SIGN = 0x86

    const val INTERNATIONAL1 = 0x87 // 日文键盘 Ro / 下划线
    const val INTERNATIONAL2 = 0x88 // 日文 かな
    const val INTERNATIONAL3 = 0x89 // 日文 変換
    const val INTERNATIONAL4 = 0x8A // 日文 無変換
    const val INTERNATIONAL5 = 0x8B // 日文 ひらがな

    const val LANG1 = 0x90 // 韩文 Hangul
    const val LANG2 = 0x91 // 韩文 Hanja

    const val ALT_ERASE = 0x99
    const val SYS_REQ = 0x9A
    const val CANCEL = 0x9B
    const val CLEAR = 0x9C
    const val PRIOR = 0x9D
    const val RETURN = 0x9E
    const val SEPARATOR = 0x9F
    const val OUT = 0xA0
    const val OPER = 0xA1
    const val CLEAR_AGAIN = 0xA2
    const val CR_SEL = 0xA3
    const val EX_SEL = 0xA4

    // 注意：HID 使用页 0x0C（Consumer）与本表不是同一个使用页，且本协议每个按键值只有
    // 1 个字节，所以只能使用 0x00–0xFF 之间的键码。

    /** 0xE8 起为「伪修饰键」，说明书未定义，保留常量但不在快捷键中使用。 */
    const val LEFT_CTRL_ALT = 0xE8

    // 系统 / ACPI
    const val SYS_POWER = 0x81
    const val SYS_SLEEP = 0x82
    const val SYS_WAKE = 0x83

    // 修饰键（同时给出位图）
    const val LEFT_CTRL = 0xE0
    const val LEFT_SHIFT = 0xE1
    const val LEFT_ALT = 0xE2
    const val LEFT_GUI = 0xE3
    const val RIGHT_CTRL = 0xE4
    const val RIGHT_SHIFT = 0xE5
    const val RIGHT_ALT = 0xE6
    const val RIGHT_GUI = 0xE7

    /**
     * 修饰键 HID Code → 说明书定义的位图。
     * 说明书中右 Windows / 右 Alt / 右 Shift / 右 Ctrl 依次位于 BIT7..BIT4，
     * 换算成十六进制即 0x80 / 0x40 / 0x20 / 0x10。
     */
    val MODIFIER_BITS: Map<Int, Int> = mapOf(
        LEFT_CTRL to NbProtocol.BTN_LEFT_CTRL,
        LEFT_SHIFT to NbProtocol.BTN_LEFT_SHIFT,
        LEFT_ALT to NbProtocol.BTN_LEFT_ALT,
        LEFT_GUI to NbProtocol.BTN_LEFT_GUI,
        RIGHT_CTRL to NbProtocol.BTN_RIGHT_CTRL,
        RIGHT_SHIFT to NbProtocol.BTN_RIGHT_SHIFT,
        RIGHT_ALT to NbProtocol.BTN_RIGHT_ALT,
        RIGHT_GUI to NbProtocol.BTN_RIGHT_GUI,
    )

    fun isModifier(hidCode: Int): Boolean = hidCode in LEFT_CTRL..RIGHT_GUI
}
