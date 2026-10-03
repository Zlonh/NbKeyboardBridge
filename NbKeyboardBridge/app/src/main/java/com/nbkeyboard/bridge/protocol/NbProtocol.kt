package com.nbkeyboard.bridge.protocol

import java.util.Locale
import java.util.UUID

/**
 * NB- 系列 BLE 转 USB-HID 模块的串口协议实现。
 *
 * 协议来自《蓝牙键盘接口说明书》：
 *
 * ```
 * 字段   HEAD(2)   ADDR(1)  CMD(1)  LEN(1)  DATA     SUM(1)
 * 取值   0x57 0xAB 0x00     0x02    0x08    见下     0x??
 * ```
 *
 * 关于帧长有一个容易踩坑的地方，这里说明清楚：
 *
 * 说明书表格写的是「DATA = 8 个字节数据」，照字面算整帧应为 14 字节；
 * 但说明书**实际打印出来的每一帧都是 15 字节**，而且给出的校验和只有在 15 字节时才算得通。
 * 以说明书示例 1.1（模拟按下 A 键）逐字节对照：
 *
 * ```
 * 下标   0  1  2  3  4  5  6  7  8 ... 12 13 14
 *        57 AB 00 02 08 00 00 04 00 ... 00 00 10
 *                          ^^^^^ HID 键码 'A' = 0x04
 * sum(前 14 字节) = 0x110 -> & 0xFF = 0x10   与说明书一致
 * ```
 *
 * 若整帧只有 14 字节，键码 'A' 会落在下标 6，校验和应为 0x0F，与说明书的 0x10 不符。
 * 因此真实布局是：`HEAD(2) + ADDR + CMD + LEN + 0x00 + 8 字节 HID 报文 + SUM`，
 * 也就是 8 字节 HID 报文右对齐落在一个 9 字节的数据区里，下标 13 恒为 0x00，
 * 校验和覆盖前 14 个字节。本实现即按此布局，可逐字节复现说明书全部示例。
 */
object NbProtocol {

    /** 蓝牙模块广播名的固定前缀，例如 NB-C791F32xxxxx。 */
    const val DEVICE_NAME_PREFIX = "NB-"

    /** 默认 GATT 参数（说明书：串口 115200bps、广播间隔 200ms、连接间隔 30ms、发射功率 0dBm）。 */
    const val DEFAULT_BAUD = 115200

    val SERVICE_UUID: UUID = uuid16(0xFFF0)
    val WRITE_CHAR_UUID: UUID = uuid16(0xFFF1)
    val NOTIFY_CHAR_UUID: UUID = uuid16(0xFFF2)

    /** 标准蓝牙串口透传服务，作为 FFF0 缺失时的兜底。 */
    val FALLBACK_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    const val HEAD_0: Int = 0x57
    const val HEAD_1: Int = 0xAB
    const val ADDR: Int = 0x00
    const val CMD_KEY_REPORT: Int = 0x02
    const val CMD_ACK: Int = 0x82
    const val LEN: Int = 0x08

    /** 整帧长度：HEAD(2) + ADDR(1) + CMD(1) + LEN(1) + DATA(8) + SUM(1)。 */
    const val FRAME_SIZE: Int = 15

    /** 键值区第一个字节：修饰键位图（Ctrl / Shift / Alt / Win）。 */
    const val BTN_LEFT_CTRL: Int = 0x01
    const val BTN_LEFT_SHIFT: Int = 0x02
    const val BTN_LEFT_ALT: Int = 0x04
    const val BTN_LEFT_GUI: Int = 0x08
    const val BTN_RIGHT_CTRL: Int = 0x10
    const val BTN_RIGHT_SHIFT: Int = 0x20
    const val BTN_RIGHT_ALT: Int = 0x40
    const val BTN_RIGHT_GUI: Int = 0x80

    fun uuid16(value: Int): UUID =
        UUID.fromString(String.format(Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", value))

    /** SUM = 前 14 字节之和的低 8 位。 */
    fun checksum(bytes: ByteArray, from: Int = 0, until: Int = 14): Int {
        var sum = 0
        for (i in from until until) {
            sum += bytes[i].toInt() and 0xFF
        }
        return sum and 0xFF
    }

    /**
     * 构造 8 字节 HID 键盘报文。
     *
     * @param modifier 修饰键位图，见 BTN_* 常量
     * @param keys 普通按键 HID Code，最多 6 个，多余部分忽略；无按键时传空数组
     */
    fun buildKeyData(modifier: Int, keys: IntArray): ByteArray {
        val data = ByteArray(LEN)
        data[0] = (modifier and 0xFF).toByte()
        data[1] = 0x00 // 说明书要求该字节固定为 0x00
        var slot = 2
        for (key in keys) {
            if (key == 0) continue
            if (slot >= LEN) break
            data[slot++] = (key and 0xFF).toByte()
        }
        return data
    }

    fun buildFrame(data: ByteArray): ByteArray {
        require(data.size == LEN) { "DATA 必须为 $LEN 字节，实际 ${data.size}" }
        val frame = ByteArray(FRAME_SIZE)
        frame[0] = HEAD_0.toByte()
        frame[1] = HEAD_1.toByte()
        frame[2] = ADDR.toByte()
        frame[3] = CMD_KEY_REPORT.toByte()
        frame[4] = LEN.toByte()
        // 8 字节 HID 报文写到 [5..12]，下标 13 保持 0x00，SUM 落在 [14]。
        // 这样校验和才与说明书的 0x10 / 0x0C / 0x12 完全一致，详见类注释。
        System.arraycopy(data, 0, frame, 5, LEN)
        frame[14] = checksum(frame).toByte()
        return frame
    }

    /** 按下若干按键（修饰键 + 最多 6 个普通键）。 */
    fun frameKeyPress(modifier: Int = 0, vararg keys: Int): ByteArray =
        buildFrame(buildKeyData(modifier, keys))

    /** 松开全部按键。 */
    fun frameReleaseAll(): ByteArray = buildFrame(buildKeyData(0, IntArray(0)))

    /** 解析芯片回包。 */
    fun parse(frame: ByteArray): NbResponse? {
        if (frame.size < 5) return null
        if ((frame[0].toInt() and 0xFF) != HEAD_0 || (frame[1].toInt() and 0xFF) != HEAD_1) return null
        val cmd = frame[3].toInt() and 0xFF
        val len = frame[4].toInt() and 0xFF
        val data = if (frame.size >= 5 + len) frame.copyOfRange(5, 5 + len) else ByteArray(0)
        return NbResponse(cmd, data, frame)
    }

    /** 校验一帧是否自洽（长度、帧头、累加和）。 */
    fun isValidFrame(frame: ByteArray): Boolean {
        if (frame.size != FRAME_SIZE) return false
        if ((frame[0].toInt() and 0xFF) != HEAD_0 || (frame[1].toInt() and 0xFF) != HEAD_1) return false
        return checksum(frame) == (frame[14].toInt() and 0xFF)
    }

    /** 把字节数组转成便于日志阅读的十六进制字符串。 */
    fun toHex(frame: ByteArray, separator: String = " "): String =
        frame.joinToString(separator) { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

    /**
     * 芯片通过 FFF2 通知通道上报的串口文本，说明书格式：
     * - `+CONNECTED:TYPE,MAC\r\n`  电脑侧已识别到键盘
     * - `+DISCONN:TYPE,MAC\r\n`    电脑侧断开
     */
    fun parseSerialLine(line: String): SerialEvent? {
        val text = line.trim()
        if (text.isEmpty()) return null
        return when {
            text.startsWith("+CONNECTED") -> SerialEvent.HostConnected(extractMac(text))
            text.startsWith("+DISCONN") -> SerialEvent.HostDisconnected(extractMac(text))
            else -> SerialEvent.Other(text)
        }
    }

    private fun extractMac(text: String): String {
        val body = text.substringAfter(':', "")
        val parts = body.split(',')
        return parts.getOrNull(1)?.trim().orEmpty()
    }

    /** 从通知原始字节中提取可读的 ASCII 文本（无终止符时按 0x0D/0x0A 切分）。 */
    fun decodeSerialChunk(raw: ByteArray): List<String> =
        String(raw, Charsets.US_ASCII)
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

data class NbResponse(val cmd: Int, val data: ByteArray, val raw: ByteArray) {
    val status: Int get() = if (data.isNotEmpty()) data[0].toInt() and 0xFF else -1
    val isAck: Boolean get() = cmd == NbProtocol.CMD_ACK

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NbResponse) return false
        return cmd == other.cmd && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * cmd + data.contentHashCode()
}

sealed interface SerialEvent {
    data class HostConnected(val mac: String) : SerialEvent
    data class HostDisconnected(val mac: String) : SerialEvent
    data class Other(val text: String) : SerialEvent
}
