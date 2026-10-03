package com.nbkeyboard.bridge.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.nbkeyboard.bridge.protocol.NbProtocol
import com.nbkeyboard.bridge.protocol.SerialEvent
import java.util.ArrayDeque

/**
 * 与 NB- 蓝牙模块的 GATT 通道管理。
 *
 * 负责：扫描 → 连接 → 发现服务 → 订阅通知 → 写入 FFF1；
 * 所有写入串行排队，避免 BLE 栈同时发起多个写请求导致丢包。
 */
class BleBridge(private val context: Context) {

    enum class Phase { IDLE, SCANNING, CONNECTING, DISCOVERING, READY, DISCONNECTED }

    data class DeviceItem(
        val address: String,
        val name: String,
        val rssi: Int,
    ) {
        val displayName: String get() = name.ifBlank { "未知设备" }
        val shortAddress: String get() = address.takeLast(8)
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val device: DeviceItem? = null,
        val hostConnected: Boolean = false,
        val hostConnectedKnown: Boolean = false,
        val mtu: Int = 23,
        val devices: List<DeviceItem> = emptyList(),
        val message: String? = null,
        val error: String? = null,
    )

    interface Listener {
        fun onStateChanged(state: State)
        /** 收到的原始通知数据（用于协议日志）。 */
        fun onNotification(raw: ByteArray) {}
        /** 说明书定义的串口上报事件。 */
        fun onSerialEvent(event: SerialEvent) {}
        /** 每个数据包写入完成（用于发送进度统计）。 */
        fun onPacketSent(packets: Int) {}
    }

    private val handler = Handler(Looper.getMainLooper())

    private val manager: BluetoothManager? =
        ContextCompat.getSystemService(context, BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var listener: Listener? = null
    private var state = State()
    private var stopScanningRunnable: Runnable? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var device: BluetoothDevice? = null

    private val writeQueue = ArrayDeque<QueuedWrite>()
    private var writeInFlight = false

    /** 手动断开时置位，避免把主动断开当成意外掉线而触发重连。 */
    private var manualDisconnect = false

    private val scannedDevices = LinkedHashMap<String, DeviceItem>()

    private class QueuedWrite(val data: ByteArray, val callback: ((Boolean) -> Unit)?)

    // ------------------------------------------------------------------ 生命周期

    fun attach(listener: Listener) {
        this.listener = listener
        listener.onStateChanged(state)
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    fun detach() {
        listener = null
        stopScan()
        runCatching { context.unregisterReceiver(bondReceiver) }
    }

    /** 页面销毁时的完整释放。 */
    fun release() {
        detach()
        closeGatt()
    }

    // ------------------------------------------------------------------ 权限

    /** 当前系统版本下完成扫描/连接所需的运行时权限。 */
    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /** 打开系统蓝牙设置页，由 Activity 用 registerForActivityResult 拉起。 */
    fun enableBluetoothIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    // ------------------------------------------------------------------ 扫描

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) {
            publish(error = "缺少蓝牙权限，请先授权")
            return
        }
        val bleAdapter = adapter
        if (bleAdapter == null || !bleAdapter.isEnabled) {
            publish(error = "蓝牙未开启")
            return
        }
        val scanner = bleAdapter.bluetoothLeScanner
        if (scanner == null) {
            publish(error = "当前设备不支持 BLE 扫描")
            return
        }

        stopScan()
        scannedDevices.clear()
        publish(phase = Phase.SCANNING, devices = emptyList(), error = null)

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(NbProtocol.SERVICE_UUID))
                .build(),
            ScanFilter.Builder()
                .setDeviceName(NbProtocol.DEVICE_NAME_PREFIX)
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        try {
            // 先按服务 UUID / 名称过滤；部分模块不广播 FFF0，再用无过滤扫描兜底。
            scanner.startScan(filters, settings, scanCallback)
            handler.postDelayed({
                runCatching { scanner.stopScan(scanCallback) }
                runCatching { scanner.startScan(emptyList(), settings, scanCallback) }
            }, 4_000L)
        } catch (e: SecurityException) {
            publish(error = "扫描被系统拒绝：${e.message}")
            return
        }

        val stopper = Runnable {
            stopScan()
            if (state.devices.isEmpty()) {
                publish(phase = Phase.IDLE, message = "未发现 NB- 设备，请确认模块已上电")
            } else {
                publish(phase = Phase.IDLE)
            }
        }
        stopScanningRunnable = stopper
        handler.postDelayed(stopper, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        stopScanningRunnable?.let { handler.removeCallbacks(it) }
        stopScanningRunnable = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            publish(error = "扫描失败，错误码 $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        val device = result.device ?: return
        val name = runCatching { device.name }.getOrNull().orEmpty()
        val address = device.address ?: return

        val matches = name.startsWith(NbProtocol.DEVICE_NAME_PREFIX) ||
            name.startsWith("NB") ||
            result.scanRecord?.serviceUuids?.any { it.uuid == NbProtocol.SERVICE_UUID } == true

        if (!matches) return

        val item = DeviceItem(address, name, result.rssi)
        val previous = scannedDevices[address]
        // 名称后到，或信号更强时更新
        if (previous == null || previous.name.isBlank() || result.rssi > previous.rssi) {
            scannedDevices[address] = item
        }
        publish(devices = scannedDevices.values.sortedByDescending { it.rssi }.toList())
    }

    // ------------------------------------------------------------------ 连接

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasPermissions()) {
            publish(error = "缺少蓝牙权限，请先授权")
            return
        }
        val bleAdapter = adapter ?: return
        if (!bleAdapter.isEnabled) {
            publish(error = "蓝牙未开启")
            return
        }
        stopScan()
        val target = runCatching { bleAdapter.getRemoteDevice(address) }.getOrNull()
        if (target == null) {
            publish(error = "设备地址无效：$address")
            return
        }
        closeGatt()
        manualDisconnect = false
        device = target
        val item = scannedDevices[address] ?: DeviceItem(address, "", -127)
        publish(
            phase = Phase.CONNECTING,
            device = item,
            hostConnected = false,
            hostConnectedKnown = false,
            error = null,
        )
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            target.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            target.connectGatt(context, false, gattCallback)
        }
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val connectTimeout = Runnable {
        if (state.phase == Phase.CONNECTING || state.phase == Phase.DISCOVERING) {
            publish(error = "连接超时，请靠近模块后重试")
            closeGatt()
            publish(phase = Phase.IDLE)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        manualDisconnect = true
        handler.removeCallbacks(connectTimeout)
        val current = gatt
        if (current != null) {
            runCatching { current.disconnect() }
        } else {
            publish(phase = Phase.IDLE, device = null, hostConnected = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(writeTimeout)
        writeQueue.clear()
        writeInFlight = false
        writeChar = null
        notifyChar = null
        val current = gatt
        gatt = null
        if (current != null) {
            runCatching { current.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handler.removeCallbacks(connectTimeout)
                    publish(phase = Phase.DISCOVERING, error = null)
                    runCatching { g.requestMtu(REQUESTED_MTU) }
                    // 少数机型不回调 onMtuChanged，兜底继续发现服务
                    handler.postDelayed({ g.discoverServices() }, 600L)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    handler.removeCallbacks(connectTimeout)
                    closeGatt()
                    val reason = when (status) {
                        BluetoothGatt.GATT_SUCCESS -> null
                        else -> "连接中断（状态码 $status）"
                    }
                    publish(
                        phase = Phase.IDLE,
                        device = null,
                        hostConnected = false,
                        hostConnectedKnown = false,
                        error = if (manualDisconnect) null else reason,
                    )
                    manualDisconnect = false
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                publish(mtu = mtu)
            }
            runCatching { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                publish(error = "服务发现失败（状态码 $status）")
                return
            }
            val service = findService(g)
            if (service == null) {
                publish(error = "未找到 FFF0 服务，请确认固件版本")
                return
            }
            writeChar = service.getCharacteristic(NbProtocol.WRITE_CHAR_UUID)
                ?: service.characteristics.firstOrNull { hasWrite(it) }
            notifyChar = service.getCharacteristic(NbProtocol.NOTIFY_CHAR_UUID)
                ?: service.characteristics.firstOrNull { hasNotify(it) }

            if (writeChar == null) {
                publish(error = "未找到 FFF1 写通道")
                return
            }
            enableNotifications(g, notifyChar)
            publish(
                phase = Phase.READY,
                hostConnected = false,
                hostConnectedKnown = false,
                error = null,
            )
        }

        @Deprecated("Android 13 起使用带参数的重载")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = c.value ?: return
            dispatchNotification(value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatchNotification(value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD_UUID && status != BluetoothGatt.GATT_SUCCESS) {
                publish(message = "通知订阅失败（状态码 $status），仍可单向发送")
            }
        }

        @Deprecated("Android 13 起使用带参数的重载")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            onWriteFinished(status)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            onWriteFinished(status)
        }
    }

    /** 写入完成超时兜底：个别机型/特征在 NO_RESPONSE 模式下不回调 onCharacteristicWrite。 */
    private val writeTimeout = Runnable {
        if (writeInFlight) {
            writeInFlight = false
            publish(message = "写入超时，已继续后续发送")
            drainQueue()
        }
    }

    private fun onWriteFinished(status: Int) {
        handler.removeCallbacks(writeTimeout)
        val queued = if (writeQueue.isEmpty()) null else writeQueue.poll()
        if (writeQueue.isEmpty()) writeInFlight = false
        val ok = status == BluetoothGatt.GATT_SUCCESS
        queued?.callback?.invoke(ok)
        if (ok) {
            listener?.onPacketSent(1)
        } else {
            publish(error = "数据写入失败（状态码 $status）")
        }
        drainQueue()
    }

    private fun findService(g: BluetoothGatt): BluetoothGattService? =
        g.getService(NbProtocol.SERVICE_UUID)
            ?: g.getService(NbProtocol.FALLBACK_SERVICE_UUID)
            ?: g.services.firstOrNull { service ->
                service.characteristics.any { hasWrite(it) && hasNotify(it) }
            }

    private fun hasWrite(c: BluetoothGattCharacteristic): Boolean =
        (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

    private fun hasNotify(c: BluetoothGattCharacteristic): Boolean =
        (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic?) {
        if (c == null) return
        runCatching {
            g.setCharacteristicNotification(c, true)
            val cccd = c.getDescriptor(CCCD_UUID) ?: return@runCatching
            val value = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    // ------------------------------------------------------------------ 通知解析

    /** 通知里可能是二进制回包，也可能是 "+CONNECTED:..." 这样的 ASCII 串口文本。 */
    private fun dispatchNotification(raw: ByteArray) {
        listener?.onNotification(raw)
        if (raw.size >= 5 &&
            (raw[0].toInt() and 0xFF) == NbProtocol.HEAD_0 &&
            (raw[1].toInt() and 0xFF) == NbProtocol.HEAD_1
        ) {
            NbProtocol.parse(raw)
            return
        }
        NbProtocol.decodeSerialChunk(raw).forEach { line ->
            val event = NbProtocol.parseSerialLine(line) ?: return@forEach
            when (event) {
                is SerialEvent.HostConnected ->
                    publish(hostConnected = true, hostConnectedKnown = true)

                is SerialEvent.HostDisconnected ->
                    publish(hostConnected = false, hostConnectedKnown = true)

                is SerialEvent.Other -> listener?.onSerialEvent(event)
            }
        }
    }

    // ------------------------------------------------------------------ 发送

    /** 是否已具备发送条件（GATT 就绪）。 */
    fun isReady(): Boolean = state.phase == Phase.READY && gatt != null && writeChar != null

    /**
     * 写入数据，自动按当前 MTU 分包。所有写入串行执行。
     *
     * @param onComplete 全部分包写入结束（true 表示全部成功）
     */
    fun send(payload: ByteArray, onComplete: ((Boolean) -> Unit)? = null) {
        val g = gatt
        val characteristic = writeChar
        if (g == null || characteristic == null) {
            onComplete?.invoke(false)
            return
        }
        val chunks = splitByMtu(payload, state.mtu)
        var remaining = chunks.size
        var allOk = true
        chunks.forEach { chunk ->
            enqueueWrite(g, characteristic, chunk) { ok ->
                if (!ok) allOk = false
                remaining -= 1
                if (remaining == 0) onComplete?.invoke(allOk)
            }
        }
    }

    private fun enqueueWrite(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        callback: ((Boolean) -> Unit)?,
    ) {
        writeQueue.add(QueuedWrite(data, callback))
        drainQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainQueue() {
        if (writeInFlight) return
        val g = gatt ?: return
        val characteristic = writeChar ?: return
        val queued = writeQueue.poll() ?: return
        writeInFlight = true
        handler.removeCallbacks(writeTimeout)
        handler.postDelayed(writeTimeout, WRITE_TIMEOUT_MS)
        val ok = runCatching {
            val writeType = if ((characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            ) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(characteristic, queued.data, writeType) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.value = queued.data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)

        if (!ok) {
            handler.removeCallbacks(writeTimeout)
            writeInFlight = false
            queued.callback?.invoke(false)
            publish(error = "数据写入失败")
            writeQueue.clear()
        }
    }

    private fun splitByMtu(payload: ByteArray, mtu: Int): List<ByteArray> {
        val maxChunk = (mtu - ATT_OVERHEAD).coerceAtLeast(20)
        if (payload.size <= maxChunk) return listOf(payload)
        val chunks = ArrayList<ByteArray>((payload.size + maxChunk - 1) / maxChunk)
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + maxChunk, payload.size)
            chunks += payload.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }

    // ------------------------------------------------------------------ 配对广播

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> publish(message = "配对完成")
                BluetoothDevice.BOND_NONE -> publish(message = "配对已取消")
            }
        }
    }

    // ------------------------------------------------------------------ 状态发布

    private fun publish(
        phase: Phase = state.phase,
        device: DeviceItem? = state.device,
        hostConnected: Boolean = state.hostConnected,
        hostConnectedKnown: Boolean = state.hostConnectedKnown,
        mtu: Int = state.mtu,
        devices: List<DeviceItem> = state.devices,
        message: String? = null,
        error: String? = null,
    ) {
        state = State(phase, device, hostConnected, hostConnectedKnown, mtu, devices, message, error)
        listener?.onStateChanged(state)
    }

    /** 供 ViewModel 读取当前快照。 */
    fun currentState(): State = state

    companion object {
        const val SCAN_DURATION_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val WRITE_TIMEOUT_MS = 2_000L
        private const val REQUESTED_MTU = 247
        private const val ATT_OVERHEAD = 3
        val CCCD_UUID: java.util.UUID =
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
