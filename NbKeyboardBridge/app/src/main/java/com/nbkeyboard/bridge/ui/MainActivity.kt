package com.nbkeyboard.bridge.ui

import android.content.ActivityNotFoundException
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.nbkeyboard.bridge.R
import com.nbkeyboard.bridge.data.ShortcutItem
import com.nbkeyboard.bridge.data.Shortcuts
import com.nbkeyboard.bridge.data.SpeedMode
import com.nbkeyboard.bridge.databinding.ActivityMainBinding
import com.nbkeyboard.bridge.databinding.DialogSingleKeyBinding
import com.nbkeyboard.bridge.databinding.ItemDeviceBinding
import com.nbkeyboard.bridge.databinding.ItemSingleKeyBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var rendered: UiState = UiState()
    private var shortcutsRendered = false

    /** 长按连续发送用的复读状态。 */
    private var repeatKeyView: View? = null
    private var repeatShortcut: ShortcutItem? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            viewModel.startScan()
        } else {
            toast(getString(R.string.permission_rationale))
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startScan()
        } else {
            toast(getString(R.string.bluetooth_off))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpeedSelector()
        setupInput()
        setupDeviceSection()
        setupToolbar()
        setupOptions()

        viewModel.state.observe(this) { render(it) }
        viewModel.toast.observe(this) { event -> event.getIfNotHandled()?.let { toast(it) } }
        viewModel.error.observe(this) { event ->
            event.getIfNotHandled()?.let {
                toast(it)
                refreshDeviceHint(rendered)
            }
        }
    }

    // ================================================================ 速度档位

    private fun setupSpeedSelector() {
        binding.rbStable.setOnClickListener { viewModel.setSpeed(SpeedMode.STABLE) }
        binding.rbBalanced.setOnClickListener { viewModel.setSpeed(SpeedMode.BALANCED) }
        binding.rbFast.setOnClickListener { viewModel.setSpeed(SpeedMode.FAST) }
    }

    // ================================================================ 输入区

    private fun setupInput() {
        binding.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.onInputChanged(s?.toString().orEmpty())
            }
        })

        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip == null || clip.itemCount == 0) {
                toast("剪贴板是空的")
                return@setOnClickListener
            }
            // 标记已读，避免部分机型反复弹出「已读取剪贴板」提示
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                clip.description?.extras?.let { extras ->
                    extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
                }
            }
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isBlank()) {
                toast("剪贴板里没有文字")
                return@setOnClickListener
            }
            binding.etInput.setText(text)
            binding.etInput.setSelection(binding.etInput.text?.length ?: 0)
        }

        binding.btnClear.setOnClickListener {
            binding.etInput.setText("")
            viewModel.clearInput()
        }

        binding.btnSend.setOnClickListener {
            if (!rendered.connected) {
                toast(getString(R.string.action_send_need_device))
                startScanFlow()
                return@setOnClickListener
            }
            if (rendered.sending) {
                toast(getString(R.string.action_cancel_send))
                viewModel.cancelSending()
                return@setOnClickListener
            }
            hideKeyboard()
            viewModel.sendInput()
        }
    }

    // ================================================================ 设备区

    private fun setupDeviceSection() {
        binding.btnScan.setOnClickListener {
            if (rendered.scanning) {
                viewModel.stopScan()
            } else if (rendered.connected || rendered.connecting) {
                viewModel.disconnect()
            } else {
                startScanFlow()
            }
        }

        binding.btnQuickReconnect.setOnClickListener {
            if (rendered.connected || rendered.connecting) {
                viewModel.disconnect()
            } else if (viewModel.hasLastDevice()) {
                viewModel.quickReconnect()
            } else {
                toast(getString(R.string.no_last_device))
            }
        }

        binding.tvDeviceEmpty.setOnClickListener { startScanFlow() }
    }

    private fun startScanFlow() {
        if (!viewModel.hasPermissions()) {
            permissionLauncher.launch(viewModel.requiredPermissions())
            return
        }
        if (!viewModel.isBluetoothEnabled()) {
            try {
                enableBluetoothLauncher.launch(viewModel.enableBluetoothIntent())
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.bluetooth_off))
            }
            return
        }
        viewModel.startScan()
    }

    // ================================================================ 顶部菜单

    private fun setupToolbar() {
        binding.btnMore.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, MENU_SINGLE_KEY, 0, getString(R.string.shortcuts_subtitle))
            popup.menu.add(0, MENU_RECONNECT, 1, getString(R.string.quick_reconnect))
            popup.menu.add(0, MENU_DISCONNECT, 2, getString(R.string.action_disconnect))
            popup.menu.add(0, MENU_PROTOCOL, 3, "协议说明")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SINGLE_KEY -> {
                        showSingleKeyDialog()
                        true
                    }
                    MENU_RECONNECT -> {
                        if (viewModel.hasLastDevice()) viewModel.quickReconnect()
                        else toast(getString(R.string.no_last_device))
                        true
                    }
                    MENU_DISCONNECT -> {
                        viewModel.disconnect()
                        true
                    }
                    MENU_PROTOCOL -> {
                        showProtocolDialog()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        binding.btnSingleKeys.setOnClickListener { showSingleKeyDialog() }
    }

    private fun setupOptions() {
        binding.switchFold.setOnCheckedChangeListener { _, checked ->
            viewModel.setFoldFullWidth(checked)
        }
        binding.switchReplace.setOnCheckedChangeListener { _, checked ->
            viewModel.setReplaceSelection(checked)
        }
    }

    // ================================================================ 渲染

    private fun render(state: UiState) {
        rendered = state

        renderHero(state)
        renderDevices(state)
        renderContent(state)
        renderSpeed(state)

        if (!shortcutsRendered) {
            buildShortcutButtons()
            shortcutsRendered = true
        }

        // 选项开关只在值不同步时回写，避免打断用户操作
        if (binding.switchFold.isChecked != state.foldFullWidth) {
            binding.switchFold.isChecked = state.foldFullWidth
        }
        if (binding.switchReplace.isChecked != state.replaceSelection) {
            binding.switchReplace.isChecked = state.replaceSelection
        }
    }

    private fun renderHero(state: UiState) {
        val connected = state.connected
        val connecting = state.connecting
        val scanning = state.scanning

        val (label, dotColor) = when {
            connected -> getString(R.string.status_connected) to R.color.status_ok
            connecting -> getString(R.string.status_connecting) to R.color.status_warn
            scanning -> getString(R.string.status_scanning) to R.color.brand_blue
            else -> getString(R.string.status_disconnected) to R.color.status_off
        }
        binding.tvStatus.text = label
        binding.statusDot.background?.mutate()?.setTint(color(dotColor))

        when {
            connected && state.bridge.hostConnected -> {
                binding.tvHeroTitle.setText(R.string.hero_host_online_title)
                binding.tvHeroDesc.setText(R.string.hero_host_online_desc)
            }
            connected -> {
                binding.tvHeroTitle.setText(R.string.hero_ready_title)
                binding.tvHeroDesc.text = state.bridge.device?.let { device ->
                    "${device.displayName} · ${device.address}"
                } ?: getString(R.string.hero_ready_desc)
            }
            connecting -> {
                binding.tvHeroTitle.setText(R.string.status_connecting)
                binding.tvHeroDesc.text = state.bridge.device?.let { device ->
                    "${device.displayName} · ${device.address}"
                } ?: getString(R.string.hero_waiting_desc)
            }
            else -> {
                binding.tvHeroTitle.setText(R.string.hero_waiting_title)
                binding.tvHeroDesc.setText(R.string.hero_waiting_desc)
            }
        }

        binding.btnScan.text = when {
            connected || connecting -> getString(R.string.action_disconnect)
            scanning -> getString(R.string.action_stop_scan)
            else -> getString(R.string.action_scan)
        }
        binding.btnQuickReconnect.text = when {
            connected || connecting -> getString(R.string.action_disconnect)
            else -> state.lastDeviceLabel ?: getString(R.string.quick_reconnect)
        }
        binding.btnQuickReconnect.isEnabled = connected || connecting || viewModel.hasLastDevice()

        binding.tvProtocolInfo.text = buildString {
            append("协议通道 FFF0 / FFF1(写) / FFF2(通知)　·　帧长 15 字节\n")
            append("帧头 57 AB　·　命令 02　·　数据 8 字节　·　校验和 SUM = 前 14 字节之和取低 8 位\n")
            append("当前 MTU ${state.bridge.mtu} 字节　·　")
            append("状态 ${state.bridge.phase.name}")
        }
    }

    private fun renderDevices(state: UiState) {
        val devices = state.bridge.devices
        val showList = devices.isNotEmpty()

        binding.deviceDivider.visibility = if (showList) View.VISIBLE else View.GONE
        binding.tvFoundLabel.visibility = if (showList) View.VISIBLE else View.GONE
        binding.deviceListContainer.visibility = if (showList) View.VISIBLE else View.GONE
        binding.tvDeviceEmpty.visibility = if (showList) View.GONE else View.VISIBLE

        if (!showList) {
            refreshDeviceHint(state)
            return
        }

        val container = binding.deviceListContainer
        // 设备数量不多（通常 1-3 个），直接重建比做 diff 更简单可靠
        if (container.childCount != devices.size) {
            container.removeAllViews()
            devices.forEach { device ->
                val item = ItemDeviceBinding.inflate(layoutInflater, container, false)
                item.root.setOnClickListener { viewModel.connect(device.address) }
                container.addView(item.root)
                if (container.childCount > 1) {
                    (item.root.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(8)
                }
            }
        }
        devices.forEachIndexed { index, device ->
            val child = container.getChildAt(index) ?: return@forEachIndexed
            val name = child.findViewById<android.widget.TextView>(R.id.tvDeviceName)
            val address = child.findViewById<android.widget.TextView>(R.id.tvDeviceAddress)
            val rssi = child.findViewById<android.widget.TextView>(R.id.tvDeviceRssi)
            name.text = device.displayName
            address.text = device.address
            rssi.text = "${device.rssi} dBm"
        }
    }

    private fun refreshDeviceHint(state: UiState) {
        val needsPermission = !viewModel.hasPermissions()
        val needsBluetooth = viewModel.hasPermissions() && !viewModel.isBluetoothEnabled()
        binding.tvDeviceEmpty.text = when {
            state.scanning -> "正在扫描名称以 NB- 开头的设备…"
            needsPermission -> getString(R.string.permission_rationale) + "\n" + getString(R.string.action_grant)
            needsBluetooth -> getString(R.string.bluetooth_off) + "\n" + getString(R.string.action_open_bluetooth)
            state.bridge.error != null -> state.bridge.error + "\n点击重试"
            else -> getString(R.string.empty_devices)
        }
    }

    private fun renderContent(state: UiState) {
        if (binding.etInput.text?.toString() != state.input) {
            binding.etInput.setText(state.input)
        }
        binding.tvCounter.text = getString(R.string.counter_format, state.input.length)
        binding.btnClear.isEnabled = state.input.isNotEmpty()
        binding.btnPaste.isEnabled = !state.sending

        val canSend = state.canSend
        binding.btnSend.isEnabled = canSend || state.sending
        binding.btnSend.text = when {
            state.sending -> getString(R.string.action_sending, state.progressPercent)
            !state.connected -> getString(R.string.action_send_need_device)
            state.input.isEmpty() -> getString(R.string.action_send)
            state.unmappedCount > 0 ->
                "${getString(R.string.action_send)}（可发送 ${state.sendableCount} 字）"

            else -> "${getString(R.string.action_send)}（${state.sendableCount} 字）"
        }
    }

    private fun renderSpeed(state: UiState) {
        val target = when (state.speed) {
            SpeedMode.STABLE -> binding.rbStable
            SpeedMode.BALANCED -> binding.rbBalanced
            SpeedMode.FAST -> binding.rbFast
        }
        if (!target.isChecked) target.isChecked = true
        binding.tvSpeedHint.text = when (state.speed) {
            SpeedMode.STABLE -> "间隔较宽，老电脑也能跟上"
            SpeedMode.BALANCED -> getString(R.string.speed_hint)
            SpeedMode.FAST -> "最快，若丢字请改用均衡"
        }
        binding.tvSpeedDesc.text = when (state.speed) {
            SpeedMode.STABLE -> getString(R.string.speed_stable_desc)
            SpeedMode.BALANCED -> getString(R.string.speed_balanced_desc)
            SpeedMode.FAST -> getString(R.string.speed_fast_desc)
        }
    }

    // ================================================================ 快捷键

    private fun buildShortcutButtons() {
        fillShortcuts(binding.flowCommon, Shortcuts.COMMON)
        fillShortcuts(binding.flowEdit, Shortcuts.EDIT)
        fillShortcuts(binding.flowMedia, Shortcuts.MEDIA)
    }

    private fun fillShortcuts(container: com.nbkeyboard.bridge.ui.widget.FlowLayout, items: List<ShortcutItem>) {
        container.removeAllViews()
        items.forEach { shortcut ->
            val button = MaterialButton(
                this,
                null,
                0,
                com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton,
            ).apply {
                text = shortcut.title
                textSize = 14f
                setTextColor(color(R.color.text_primary))
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(12)
                strokeWidth = dp(1)
                strokeColor = colorStateList(R.color.stroke)
                backgroundTintList = colorStateList(R.color.card_bg)
                contentDescription = "${shortcut.title} ${shortcut.hint}"
                setPadding(dp(14), dp(4), dp(14), dp(4))
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(40),
                )
                setOnClickListener { sendShortcut(shortcut) }
                setOnLongClickListener {
                    startRepeat(shortcut, this)
                    true
                }
                setOnTouchListener { view, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP ||
                        event.action == android.view.MotionEvent.ACTION_CANCEL
                    ) {
                        if (repeatKeyView === view) stopRepeat()
                    }
                    false
                }
            }
            container.addView(button)
        }
    }

    private fun startRepeat(shortcut: ShortcutItem, view: View) {
        stopRepeat()
        repeatShortcut = shortcut
        repeatKeyView = view
        val runnable = object : Runnable {
            override fun run() {
                if (repeatKeyView !== view) return
                viewModel.sendShortcut(shortcut)
                view.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        view.postDelayed(runnable, REPEAT_START_DELAY_MS)
    }

    private fun stopRepeat() {
        repeatKeyView = null
        repeatShortcut = null
    }

    private fun sendShortcut(shortcut: ShortcutItem) {
        if (!rendered.connected) {
            toast(getString(R.string.action_send_need_device))
            return
        }
        viewModel.sendShortcut(shortcut)
    }

    // ================================================================ 对话框

    private fun showSingleKeyDialog() {
        val dialogBinding = DialogSingleKeyBinding.inflate(layoutInflater)
        val keys = Shortcuts.SINGLE_KEYS
        val adapter = SingleKeyAdapter(keys)

        dialogBinding.rvKeys.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvKeys.adapter = adapter

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_single_key_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .also { dialog ->
                adapter.onClick = click@ { label, hidCode ->
                    if (!rendered.connected) {
                        toast(getString(R.string.action_send_need_device))
                        return@click
                    }
                    val repeat = dialogBinding.etRepeat.text?.toString()?.toIntOrNull() ?: 1
                    viewModel.sendSingleKey(label, hidCode, repeat.coerceIn(1, MAX_REPEAT))
                }
                dialog.show()
            }
    }

    private fun showProtocolDialog() {
        val message = """
            服务 UUID：0000FFF0
            写特征：  0000FFF1
            通知特征：0000FFF2

            发送帧（共 15 字节）
              57 AB | 00 | 02 | 08 | 8 字节数据 | SUM
              8 字节数据 = 修饰键位图 + 00 + 最多 6 个 HID 键码
              SUM = 前 14 字节之和的低 8 位

            接收帧
              57 AB | 00 | 82 | 01 | 状态字节 | SUM
              通知通道还会上报 +CONNECTED / +DISCONN 文本

            电脑端请把键盘布局设为「英语(美国)」，才能正确输出符号。
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("NB- 模块协议")
            .setMessage(message)
            .setPositiveButton(R.string.action_confirm, null)
            .show()
    }

    /** 单个按键列表适配器。 */
    private inner class SingleKeyAdapter(
        private val keys: List<Pair<String, Int>>,
    ) : RecyclerView.Adapter<SingleKeyAdapter.Holder>() {

        var onClick: ((String, Int) -> Unit)? = null

        inner class Holder(val itemBinding: ItemSingleKeyBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemSingleKeyBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = keys.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (label, code) = keys[position]
            holder.itemBinding.tvKeyLabel.text = "$label　（HID 0x%02X）".format(code)
            holder.itemBinding.root.setOnClickListener { onClick?.invoke(label, code) }
        }
    }

    // ================================================================ 小工具

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun colorStateList(resId: Int) = ContextCompat.getColorStateList(this, resId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
        binding.etInput.clearFocus()
    }

    override fun onDestroy() {
        stopRepeat()
        super.onDestroy()
    }

    private companion object {
        const val MENU_SINGLE_KEY = 1
        const val MENU_RECONNECT = 2
        const val MENU_DISCONNECT = 3
        const val MENU_PROTOCOL = 4
        const val REPEAT_START_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 120L
        const val MAX_REPEAT = 50
    }
}
