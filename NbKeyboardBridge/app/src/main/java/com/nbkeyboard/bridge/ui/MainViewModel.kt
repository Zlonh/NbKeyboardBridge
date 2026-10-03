package com.nbkeyboard.bridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.nbkeyboard.bridge.ble.BleBridge
import com.nbkeyboard.bridge.data.DeviceStore
import com.nbkeyboard.bridge.data.ShortcutItem
import com.nbkeyboard.bridge.data.SpeedMode
import com.nbkeyboard.bridge.engine.SendProgress
import com.nbkeyboard.bridge.engine.SendReport
import com.nbkeyboard.bridge.engine.TypingEngine
import com.nbkeyboard.bridge.protocol.SerialEvent
import com.nbkeyboard.bridge.protocol.TextMapping
import com.nbkeyboard.bridge.protocol.TextToHid
import com.nbkeyboard.bridge.protocol.UnmappedChar

/** 界面完整状态。 */
data class UiState(
    val bridge: BleBridge.State = BleBridge.State(),
    val input: String = "",
    val speed: SpeedMode = SpeedMode.BALANCED,
    val foldFullWidth: Boolean = true,
    val replaceSelection: Boolean = false,
    val lastDeviceLabel: String? = null,
    val sending: Boolean = false,
    val sentStrokes: Int = 0,
    val totalStrokes: Int = 0,
    val unmapped: List<UnmappedChar> = emptyList(),
) {
    val connected: Boolean get() = bridge.phase == BleBridge.Phase.READY
    val connecting: Boolean
        get() = bridge.phase == BleBridge.Phase.CONNECTING || bridge.phase == BleBridge.Phase.DISCOVERING
    val scanning: Boolean get() = bridge.phase == BleBridge.Phase.SCANNING

    /**
     * 文本 → 按键序列的映射结果。
     *
     * 用 lazy 缓存：界面每次重绘都会读 [sendableCount] / [unmappedCount] /
     * [canSend]，若每次都重新映射，5000 字的输入会在每次按键时产生几万个临时对象。
     */
    private val mapping: TextMapping by lazy(LazyThreadSafetyMode.NONE) {
        TextToHid.map(input, foldFullWidth)
    }

    /** 仅统计能真正发送的字符数。 */
    val sendableCount: Int get() = mapping.strokes.size

    val unmappedCount: Int get() = mapping.unmapped.size

    val canSend: Boolean get() = connected && !sending && input.isNotEmpty() && sendableCount > 0

    val progressPercent: Int
        get() = if (totalStrokes <= 0) 0 else (sentStrokes * 100 / totalStrokes)
}

class MainViewModel(app: Application) : AndroidViewModel(app), BleBridge.Listener {

    private val store = DeviceStore(app)
    private val bridge = BleBridge(app)
    private val engine = TypingEngine(bridge)

    private val _state = MutableLiveData(UiState())
    val state: LiveData<UiState> = _state

    private val _toast = MutableLiveData<Event<String>>()
    val toast: LiveData<Event<String>> = _toast

    private val _error = MutableLiveData<Event<String>>()
    val error: LiveData<Event<String>> = _error

    init {
        bridge.attach(this)
        val current = _state.value ?: UiState()
        _state.value = current.copy(
            bridge = bridge.currentState(),
            speed = store.speedMode,
            foldFullWidth = store.foldFullWidth,
            replaceSelection = store.replaceSelection,
            lastDeviceLabel = store.lastDeviceLabel(),
        )
    }

    // ------------------------------------------------------------ 蓝牙操作

    fun requiredPermissions(): Array<String> = bridge.requiredPermissions()

    fun hasPermissions(): Boolean = bridge.hasPermissions()

    fun isBluetoothEnabled(): Boolean = bridge.isBluetoothEnabled()

    fun enableBluetoothIntent() = bridge.enableBluetoothIntent()

    fun startScan() {
        if (!bridge.hasPermissions()) {
            _error.value = Event("请先授予蓝牙权限")
            return
        }
        bridge.startScan()
    }

    fun stopScan() = bridge.stopScan()

    fun connect(address: String) {
        if (!bridge.hasPermissions()) {
            _error.value = Event("请先授予蓝牙权限")
            return
        }
        store.lastDeviceAddress = address
        bridge.connect(address)
    }

    fun quickReconnect() {
        val address = store.lastDeviceAddress
        if (address.isNullOrBlank()) {
            _toast.value = Event("还没有可重连的设备，请先扫描")
            return
        }
        connect(address)
    }

    fun disconnect() = bridge.disconnect()

    fun hasLastDevice(): Boolean = !store.lastDeviceAddress.isNullOrBlank()

    // ------------------------------------------------------------ 内容与设置

    fun onInputChanged(text: String) {
        val current = _state.value ?: return
        if (current.input == text) return
        _state.value = current.copy(input = text)
    }

    fun clearInput() {
        val current = _state.value ?: return
        _state.value = current.copy(input = "")
    }

    fun setSpeed(mode: SpeedMode) {
        store.speedMode = mode
        val current = _state.value ?: return
        _state.value = current.copy(speed = mode)
    }

    fun setFoldFullWidth(enabled: Boolean) {
        store.foldFullWidth = enabled
        val current = _state.value ?: return
        _state.value = current.copy(foldFullWidth = enabled)
    }

    fun setReplaceSelection(enabled: Boolean) {
        store.replaceSelection = enabled
        val current = _state.value ?: return
        _state.value = current.copy(replaceSelection = enabled)
    }

    // ------------------------------------------------------------ 发送

    fun sendInput() {
        val current = _state.value ?: return
        if (!current.connected) {
            _error.value = Event("请先连接 NB- 设备")
            return
        }
        if (current.sending) {
            engine.cancel()
            return
        }
        val text = current.input
        if (text.isBlank()) {
            _toast.value = Event("请先输入要发送的内容")
            return
        }
        engine.sendText(
            text = text,
            speed = current.speed,
            replaceSelection = current.replaceSelection,
            foldFullWidth = current.foldFullWidth,
            progress = progressCallback,
        )
    }

    fun sendShortcut(item: ShortcutItem, repeat: Int = 1) {
        val current = _state.value ?: return
        if (!current.connected) {
            _error.value = Event("请先连接 NB- 设备")
            return
        }
        engine.sendKeystroke(
            modifier = item.modifier,
            keys = item.keys,
            speed = current.speed,
            repeat = repeat,
            progress = progressCallback,
        )
    }

    fun sendSingleKey(label: String, hidCode: Int, repeat: Int = 1) {
        val current = _state.value ?: return
        if (!current.connected) {
            _error.value = Event("请先连接 NB- 设备")
            return
        }
        val (modifier, keys) = com.nbkeyboard.bridge.data.Shortcuts.resolve(0, hidCode)
        engine.sendKeystroke(modifier, keys, current.speed, repeat, progressCallback)
    }

    fun cancelSending() = engine.cancel()

    private val progressCallback = object : SendProgress {
        override fun onStart(totalStrokes: Int, unmapped: List<UnmappedChar>) {
            val current = _state.value ?: return
            _state.value = current.copy(
                sending = true,
                sentStrokes = 0,
                totalStrokes = totalStrokes,
                unmapped = unmapped,
            )
        }

        override fun onProgress(sent: Int, total: Int) {
            val current = _state.value ?: return
            _state.value = current.copy(sentStrokes = sent, totalStrokes = total)
        }

        override fun onFinish(report: SendReport) {
            val current = _state.value ?: return
            _state.value = current.copy(
                sending = false,
                sentStrokes = report.sentStrokes,
                totalStrokes = report.totalStrokes,
            )
            val message = when {
                report.failed -> "发送失败，请检查连接后重试"
                report.cancelled -> "已停止发送（${report.sentStrokes}/${report.totalStrokes}）"
                report.unmapped.isNotEmpty() -> {
                    val chars = report.unmapped.map { it.char }.distinct().joinToString("")
                    "已发送 ${report.sentStrokes} 个按键；${chars} 等字符无法用键盘输入，已跳过"
                }
                else -> "已发送完成，共 ${report.sentStrokes} 个按键"
            }
            _toast.value = Event(message)
        }
    }

    // ------------------------------------------------------------ BleBridge.Listener

    override fun onStateChanged(state: BleBridge.State) {
        val current = _state.value ?: UiState()
        // 只在拿到真实名称时写回，避免把「未知设备」覆盖掉历史名称
        state.device?.name?.takeIf { it.isNotBlank() }?.let { store.lastDeviceName = it }
        val label = state.device?.let { device ->
            if (device.name.isNotBlank()) device.name else store.lastDeviceLabel()
        } ?: store.lastDeviceLabel()

        state.error?.let { _error.value = Event(it) }
        state.message?.let { _toast.value = Event(it) }

        _state.value = current.copy(bridge = state, lastDeviceLabel = label)
    }

    override fun onSerialEvent(event: SerialEvent) {
        // 目前只有模块主动上报的其它文本，记录到日志即可
    }

    override fun onCleared() {
        engine.shutdown()
        bridge.release()
        super.onCleared()
    }
}

/** 一次性事件包装，避免旋转屏幕后重复弹窗。 */
class Event<out T>(private val content: T) {
    private var handled = false

    fun getIfNotHandled(): T? = if (handled) null else {
        handled = true
        content
    }
}
