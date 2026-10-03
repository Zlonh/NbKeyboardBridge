package com.nbkeyboard.bridge.protocol

import java.text.Normalizer

/** 一次按键动作：修饰键位图 + 普通按键。 */
data class Keystroke(
    val modifier: Int,
    val keys: IntArray,
    val label: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Keystroke) return false
        return modifier == other.modifier && keys.contentEquals(other.keys)
    }

    override fun hashCode(): Int = 31 * modifier + keys.contentHashCode()
}

/** 一个无法直接映射到 HID 键码的字符。 */
data class UnmappedChar(val char: Char, val index: Int)

/** 文本转换结果。 */
data class TextMapping(val strokes: List<Keystroke>, val unmapped: List<UnmappedChar>) {
    val hasUnmapped: Boolean get() = unmapped.isNotEmpty()
}

/**
 * 文本 → HID 按键序列转换。
 *
 * 模块模拟的是 **US 键盘布局** 的物理按键，因此这里按 US QWERTY 映射表换算，
 * 电脑端请把输入法/键盘布局设为「英语(美国)」才能得到正确字符。
 *
 * 无法表示的内容（中文、日文、韩文等非 ASCII 字符）会被收集到 [TextMapping.unmapped]，
 * 由界面提示用户，而不会被静默丢弃。
 */
object TextToHid {

    /** 无需 Shift 的字符 → HID Code。 */
    private val PLAIN: Map<Char, Int> = buildMap {
        // 字母 a-z
        for (i in 0..25) put('a' + i, HidKeyMap.A + i)
        // 数字 1-0
        "1234567890".forEachIndexed { i, c -> put(c, HidKeyMap.NUM_1 + i) }
        // 符号
        put(' ', HidKeyMap.SPACE)
        put('-', HidKeyMap.MINUS)
        put('=', HidKeyMap.EQUALS)
        put('[', HidKeyMap.LEFT_BRACKET)
        put(']', HidKeyMap.RIGHT_BRACKET)
        put('\\', HidKeyMap.BACKSLASH)
        put(';', HidKeyMap.SEMICOLON)
        put('\'', HidKeyMap.QUOTE)
        put('`', HidKeyMap.GRAVE)
        put(',', HidKeyMap.COMMA)
        put('.', HidKeyMap.PERIOD)
        put('/', HidKeyMap.SLASH)
        put('\t', HidKeyMap.TAB)
        put('\n', HidKeyMap.ENTER)
        put('\r', HidKeyMap.ENTER)
        put('\b', HidKeyMap.BACKSPACE)
    }

    /** 需要 Shift 的字符 → HID Code（US 布局 Shift 组合）。 */
    private val SHIFTED: Map<Char, Int> = buildMap {
        // Shift + 数字行
        put('!', HidKeyMap.NUM_1)
        put('@', HidKeyMap.NUM_2)
        put('#', HidKeyMap.NUM_3)
        put('$', HidKeyMap.NUM_4)
        put('%', HidKeyMap.NUM_5)
        put('^', HidKeyMap.NUM_6)
        put('&', HidKeyMap.NUM_7)
        put('*', HidKeyMap.NUM_8)
        put('(', HidKeyMap.NUM_9)
        put(')', HidKeyMap.NUM_0)
        // Shift + 符号
        put('_', HidKeyMap.MINUS)
        put('+', HidKeyMap.EQUALS)
        put('{', HidKeyMap.LEFT_BRACKET)
        put('}', HidKeyMap.RIGHT_BRACKET)
        put('|', HidKeyMap.BACKSLASH)
        put(':', HidKeyMap.SEMICOLON)
        put('"', HidKeyMap.QUOTE)
        put('~', HidKeyMap.GRAVE)
        put('<', HidKeyMap.COMMA)
        put('>', HidKeyMap.PERIOD)
        put('?', HidKeyMap.SLASH)
    }

    /**
     * 常见全角标点 → 半角等价字符。
     * 中文输入法下打出的标点大多是全角，自动折半角比直接报错更有用。
     * 注意：这里**不做**简繁转换，也不猜测中文拼音。
     */
    private val FULLWIDTH_FALLBACK: Map<Char, Char> = mapOf(
        '，' to ',', '。' to '.', '、' to ',', '；' to ';', '：' to ':',
        '？' to '?', '！' to '!', '“' to '"', '”' to '"', '‘' to '\'', '’' to '\'',
        '（' to '(', '）' to ')', '【' to '[', '】' to ']', '《' to '<', '》' to '>',
        '〈' to '<', '〉' to '>', '「' to '[', '」' to ']', '『' to '[', '』' to ']',
        '—' to '-', '－' to '-', '–' to '-', '…' to '.', '·' to '.',
        '＠' to '@', '＃' to '#', '＄' to '$', '％' to '%', '＆' to '&',
        '＊' to '*', '＋' to '+', '＝' to '=', '／' to '/', '＼' to '\\',
        '｜' to '|', '～' to '~', '＾' to '^', '￥' to '$', '　' to ' ',
        '〔' to '[', '〕' to ']', '［' to '[', '］' to ']', '｛' to '{', '｝' to '}',
    )

    /**
     * 把整段文本转换成按键序列。
     *
     * @param normalizeFullWidth 是否启用全角标点折半角
     */
    fun map(text: String, normalizeFullWidth: Boolean = true): TextMapping {
        val strokes = ArrayList<Keystroke>(text.length)
        val unmapped = ArrayList<UnmappedChar>()

        text.forEachIndexed { index, raw ->
            val keystroke = mapChar(raw, normalizeFullWidth)
            if (keystroke != null) {
                strokes += keystroke
            } else {
                unmapped += UnmappedChar(raw, index)
            }
        }
        return TextMapping(strokes, unmapped)
    }

    /** 单个字符 → 按键动作；无法映射时返回 null。 */
    fun mapChar(raw: Char, normalizeFullWidth: Boolean = true): Keystroke? {
        val lower = raw.lowercaseChar()
        PLAIN[lower]?.let { code ->
            val modifier = if (raw.isUpperCase() && raw.isLetter()) NbProtocol.BTN_LEFT_SHIFT else 0
            return Keystroke(modifier, intArrayOf(code), describe(raw, modifier, code))
        }
        SHIFTED[raw]?.let { code ->
            return Keystroke(NbProtocol.BTN_LEFT_SHIFT, intArrayOf(code), describe(raw, NbProtocol.BTN_LEFT_SHIFT, code))
        }
        if (normalizeFullWidth) {
            val folded = normalizeChar(raw)
            if (folded != null && folded != raw) return mapChar(folded, normalizeFullWidth = false)
        }
        return null
    }

    /** 全角标点、全角字母数字 → 半角等价字符。 */
    private fun normalizeChar(raw: Char): Char? {
        FULLWIDTH_FALLBACK[raw]?.let { return it }
        // NFKC 能把全角 ＡＢＣ１２３ 折成半角
        val normalized = Normalizer.normalize(raw.toString(), Normalizer.Form.NFKC)
        val first = normalized.firstOrNull() ?: return null
        return if (first != raw && normalized.length == 1) first else null
    }

    private fun describe(raw: Char, modifier: Int, code: Int): String = when {
        raw == '\n' || raw == '\r' -> "Enter"
        raw == '\t' -> "Tab"
        raw == ' ' -> "Space"
        modifier != 0 -> "Shift+${raw.uppercaseChar()}"
        else -> raw.toString()
    }

    val isSupportedChar: (Char) -> Boolean = { mapChar(it) != null }
}
