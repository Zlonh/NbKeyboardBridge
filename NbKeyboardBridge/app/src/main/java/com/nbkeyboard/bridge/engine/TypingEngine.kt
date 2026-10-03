package com.nbkeyboard.bridge.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.nbkeyboard.bridge.ble.BleBridge
import com.nbkeyboard.bridge.data.SpeedMode
import com.nbkeyboard.bridge.protocol.HidKeyMap
import com.nbkeyboard.bridge.protocol.Keystroke
import com.nbkeyboard.bridge.protocol.NbProtocol
import com.nbkeyboard.bridge.protocol.TextToHid
import com.nbkeyboard.bridge.protocol.UnmappedChar

/** 发送结果统计。 */
data class SendReport(
    val totalStrokes: Int,
    val sentStrokes: Int,
    val unmapped: List<UnmappedChar>,
    val cancelled: Boolean,
    val failed: Boolean,
) {
    val completed: Boolean get() = !cancelled && !failed && sentStrokes == totalStrokes
}

/** 发送进度回调（全部在主线程调用）。 */
interface SendProgress {
    fun onStart(totalStrokes: Int, unmapped: List<UnmappedChar>)
    fun onProgress(sent: Int, total: Int)
    fun onFinish(report: SendReport)
}

/**
 * 按键发送引擎。
 *
 * 每个字符发送两帧：按下（带修饰键）→ 松开全部按键。帧与帧之间按 [SpeedMode]
 * 留出间隔，所以速度档位是真实的按键节奏控制，而不是简单地把帧一次性灌进队列。
 *
 * 发送在独立线程的 [Handler] 上串行推进：只有上一帧写入完成后才安排下一帧，
 * 天然形成流控，避免 BLE 协议栈缓冲区溢出丢帧。
 */
class TypingEngine(private val bridge: BleBridge) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("nb-typing").apply { start() }
    private val handler = Handler(worker.looper)

    @Volatile
    private var sending = false

    @Volatile
    private var cancelled = false

    val isSending: Boolean get() = sending

    fun shutdown() {
        cancel()
        worker.quitSafely()
    }

    fun cancel() {
        if (!sending) return
        cancelled = true
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 发送一段文本。
     *
     * @param replaceSelection 发送前先按 Ctrl+A、Delete，覆盖电脑上已选中的内容
     * @param foldFullWidth 是否把全角标点自动折成半角
     */
    fun sendText(
        text: String,
        speed: SpeedMode,
        replaceSelection: Boolean,
        foldFullWidth: Boolean,
        progress: SendProgress?,
    ) {
        val mapping = TextToHid.map(text, normalizeFullWidth = foldFullWidth)
        val leadIn = if (replaceSelection) {
            listOf(
                Keystroke(NbProtocol.BTN_LEFT_CTRL, intArrayOf(HidKeyMap.A), "Ctrl+A"),
                Keystroke(0, intArrayOf(HidKeyMap.DELETE), "Delete"),
            )
        } else {
            emptyList()
        }
        runSequence(leadIn + mapping.strokes, mapping.unmapped, speed, progress)
    }

    /** 发送单个按键（快捷键面板 / 单个按键模式）。 */
    fun sendKeystroke(
        modifier: Int,
        keys: IntArray,
        speed: SpeedMode,
        repeat: Int = 1,
        progress: SendProgress? = null,
    ) {
        val label = "mod=$modifier keys=${keys.joinToString("+") { String.format("%02X", it) }}"
        val strokes = List(repeat.coerceIn(1, MAX_REPEAT)) { Keystroke(modifier, keys, label) }
        runSequence(strokes, emptyList(), speed, progress)
    }

    private fun runSequence(
        strokes: List<Keystroke>,
        unmapped: List<UnmappedChar>,
        speed: SpeedMode,
        progress: SendProgress?,
    ) {
        if (sending) {
            report(progress, SendReport(strokes.size, 0, unmapped, cancelled = true, failed = false))
            return
        }
        if (!bridge.isReady()) {
            report(progress, SendReport(strokes.size, 0, unmapped, cancelled = false, failed = true))
            return
        }

        sending = true
        cancelled = false
        mainHandler.post { progress?.onStart(strokes.size, unmapped) }

        if (strokes.isEmpty()) {
            finish(0, strokes.size, unmapped, progress)
            return
        }

        var index = 0
        var lastNoticeAt = 0L

        fun advance() {
            if (cancelled || index >= strokes.size) {
                finish(index, strokes.size, unmapped, progress)
                return
            }
            val stroke = strokes[index]
            val pressFrame = NbProtocol.frameKeyPress(stroke.modifier, *stroke.keys)

            bridge.send(pressFrame) { pressOk ->
                if (!pressOk) {
                    finish(index, strokes.size, unmapped, progress, failed = true)
                    return@send
                }
                handler.postDelayed({
                    if (cancelled) {
                        finish(index, strokes.size, unmapped, progress)
                        return@postDelayed
                    }
                    bridge.send(NbProtocol.frameReleaseAll()) { releaseOk ->
                        if (!releaseOk) {
                            finish(index, strokes.size, unmapped, progress, failed = true)
                            return@send
                        }
                        index += 1
                        val now = System.currentTimeMillis()
                        if (index == strokes.size || now - lastNoticeAt >= PROGRESS_INTERVAL_MS) {
                            lastNoticeAt = now
                            val snapshot = index
                            val total = strokes.size
                            mainHandler.post { progress?.onProgress(snapshot, total) }
                        }
                        handler.postDelayed({ advance() }, speed.gapMillis)
                    }
                }, speed.holdMillis)
            }
        }

        handler.post { advance() }
    }

    private fun finish(
        sent: Int,
        total: Int,
        unmapped: List<UnmappedChar>,
        progress: SendProgress?,
        failed: Boolean = false,
    ) {
        val wasCancelled = cancelled
        sending = false
        cancelled = false
        handler.removeCallbacksAndMessages(null)
        report(progress, SendReport(total, sent, unmapped, wasCancelled, failed))
    }

    private fun report(progress: SendProgress?, report: SendReport) {
        mainHandler.post { progress?.onFinish(report) }
    }

    private companion object {
        const val MAX_REPEAT = 50
        const val PROGRESS_INTERVAL_MS = 80L
    }
}
