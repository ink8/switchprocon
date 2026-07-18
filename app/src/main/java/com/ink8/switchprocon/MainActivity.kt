package com.ink8.switchprocon

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.widget.ArrayAdapter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.ink8.switchprocon.bluetooth.HidService
import com.ink8.switchprocon.config.AppSettings
import com.ink8.switchprocon.databinding.ActivityMainBinding
import com.ink8.switchprocon.input.MacroEngine
import com.ink8.switchprocon.protocol.ControllerState.Button as PadButton
import com.ink8.switchprocon.update.UpdateChecker

/**
 * The on-screen controller — the app's answer to the Manba One's interactive screen.
 * Binds to [HidService] for controller state, and layers on the customization features:
 * live accent ("light") color, M1–M4 macro keys, P1–P3 profiles, turbo, haptics.
 */
class MainActivity : AppCompatActivity(), HidService.StatusListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private val handler = Handler(Looper.getMainLooper())

    private var service: HidService? = null
    private var bound = false
    private var accentTargets: List<Button> = emptyList()
    private var macroButtons: List<Button> = emptyList()
    private var profileButtons: List<Button> = emptyList()

    private var scanReceiver: BroadcastReceiver? = null
    private var deviceDialog: AlertDialog? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, ib: IBinder?) {
            service = (ib as HidService.LocalBinder).service.also { it.setListener(this@MainActivity) }
            bound = true
            wireControls()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startAndBindService()
        else binding.txtStatus.text = getString(R.string.need_bluetooth_permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)

        accentTargets = listOf(
            binding.btnA, binding.btnB, binding.btnX, binding.btnY,
            binding.btnUp, binding.btnDown, binding.btnLeft, binding.btnRight,
            binding.btnL, binding.btnR, binding.btnZl, binding.btnZr,
            binding.btnMinus, binding.btnPlus, binding.btnHome, binding.btnCapture,
            binding.btnConnect, binding.btnReset, binding.btnP1, binding.btnP2,
            binding.btnP3, binding.btnSettings, binding.btnM1, binding.btnM2,
            binding.btnM3, binding.btnM4, binding.btnLoop
        )
        macroButtons = listOf(binding.btnM1, binding.btnM2, binding.btnM3, binding.btnM4)
        profileButtons = listOf(binding.btnP1, binding.btnP2, binding.btnP3)

        binding.btnConnect.setOnClickListener { pickHostAndConnect() }
        binding.btnReset.setOnClickListener {
            service?.resetConnection()
            toast(getString(R.string.reset_done))
        }
        binding.btnSettings.setOnClickListener { showSettings() }
        applyAccent(settings.accent)

        ensurePermissionsThenStart()
        UpdateChecker(this).checkAsync()
    }

    // ---- Accent ("light color") theming ----

    private fun applyAccent(color: Int) {
        val keyColor = ContextCompat.getColor(this, R.color.key)
        val selected = ColorUtils.blendARGB(color, 0xFF000000.toInt(), 0.35f)
        val csl = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(android.R.attr.state_selected),
                intArrayOf()
            ),
            intArrayOf(color, selected, keyColor)
        )
        accentTargets.forEach { it.backgroundTintList = csl }
        binding.stickLeft.setAccent(color)
        binding.stickRight.setAccent(color)
        binding.logoIcon.imageTintList = ColorStateList.valueOf(color)
        binding.logoProcon.setTextColor(color)
    }

    private fun haptic(view: View) {
        if (settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // ---- Wiring on-screen controls ----

    private fun wireControls() {
        val svc = service ?: return
        val engine = svc.macroEngine
        engine.turboIntervalMs = settings.turboMs

        wireButton(binding.btnA, PadButton.A, engine)
        wireButton(binding.btnB, PadButton.B, engine)
        wireButton(binding.btnX, PadButton.X, engine)
        wireButton(binding.btnY, PadButton.Y, engine)
        wireButton(binding.btnUp, PadButton.UP, engine)
        wireButton(binding.btnDown, PadButton.DOWN, engine)
        wireButton(binding.btnLeft, PadButton.LEFT_DPAD, engine)
        wireButton(binding.btnRight, PadButton.RIGHT_DPAD, engine)
        wireButton(binding.btnL, PadButton.L, engine)
        wireButton(binding.btnR, PadButton.R, engine)
        wireButton(binding.btnZl, PadButton.ZL, engine)
        wireButton(binding.btnZr, PadButton.ZR, engine)
        wireButton(binding.btnMinus, PadButton.MINUS, engine)
        wireButton(binding.btnPlus, PadButton.PLUS, engine)
        wireButton(binding.btnHome, PadButton.HOME, engine)
        wireButton(binding.btnCapture, PadButton.CAPTURE, engine)

        binding.stickLeft.onMove = { x, y ->
            svc.controllerState.setLeftStick(x * settings.sensitivity, y * settings.sensitivity)
        }
        binding.stickRight.onMove = { x, y ->
            svc.controllerState.setRightStick(x * settings.sensitivity, y * settings.sensitivity)
        }

        macroButtons.forEachIndexed { slot, button -> wireMacroButton(button, slot, engine) }

        binding.btnLoop.setOnClickListener {
            engine.loopEnabled = !engine.loopEnabled
            binding.btnLoop.isSelected = engine.loopEnabled
            haptic(it)
        }

        profileButtons.forEachIndexed { index, button -> wireProfileButton(button, index, engine) }
        refreshProfileChips()
    }

    /** Live press/release plus long-hold turbo toggle, exactly like a Manba turbo key. */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireButton(view: View, button: PadButton, engine: MacroEngine) {
        val turboRunnable = Runnable {
            val on = engine.toggleTurbo(button)
            view.isSelected = on
            Toast.makeText(this, "${button.name} turbo ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    haptic(v)
                    engine.press(button, true)
                    handler.postDelayed(turboRunnable, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    engine.press(button, false)
                    handler.removeCallbacks(turboRunnable)
                }
            }
            true
        }
    }

    /** M1–M4: tap = fire the macro; long-press = record into the slot (tap again to save). */
    private fun wireMacroButton(button: Button, slot: Int, engine: MacroEngine) {
        val label = button.text.toString()
        button.setOnClickListener {
            haptic(it)
            when {
                engine.recordingSlot == slot -> {
                    engine.toggleRecording(slot)
                    button.isSelected = false
                    button.text = label
                    toast(getString(R.string.macro_saved, label))
                }
                engine.hasMacro(slot) -> engine.playSlot(slot)
                else -> toast(getString(R.string.macro_empty, label))
            }
        }
        button.setOnLongClickListener {
            if (engine.recordingSlot < 0) {
                engine.toggleRecording(slot)
                button.isSelected = true
                button.text = getString(R.string.rec_indicator)
                toast(getString(R.string.macro_recording, label))
            }
            true
        }
    }

    /** P1–P3: tap = load profile; long-press = save current setup into it. */
    private fun wireProfileButton(button: Button, index: Int, engine: MacroEngine) {
        button.setOnClickListener {
            haptic(it)
            if (settings.loadProfile(index, engine)) {
                settings.activeProfile = index
                applyAccent(settings.accent)
                refreshProfileChips()
                toast(getString(R.string.profile_loaded, index + 1))
            } else {
                toast(getString(R.string.profile_empty, index + 1))
            }
        }
        button.setOnLongClickListener {
            settings.saveProfile(index, engine)
            settings.activeProfile = index
            refreshProfileChips()
            toast(getString(R.string.profile_saved, index + 1))
            true
        }
    }

    private fun refreshProfileChips() {
        profileButtons.forEachIndexed { i, b -> b.isSelected = (i == settings.activeProfile) }
    }

    // ---- Settings hub (the "2-inch screen") ----

    private fun showSettings() {
        val engine = service?.macroEngine
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val accentRow = view.findViewById<android.widget.LinearLayout>(R.id.accent_row)
        val turboSeek = view.findViewById<SeekBar>(R.id.turbo_seek)
        val turboLabel = view.findViewById<TextView>(R.id.turbo_label)
        val sensSeek = view.findViewById<SeekBar>(R.id.sens_seek)
        val sensLabel = view.findViewById<TextView>(R.id.sens_label)
        val hapticsSwitch =
            view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.haptics_switch)

        // Accent swatches
        val size = (32 * resources.displayMetrics.density).toInt()
        val gap = (10 * resources.displayMetrics.density).toInt()
        AppSettings.ACCENT_CHOICES.forEach { color ->
            val swatch = View(this)
            val lp = android.widget.LinearLayout.LayoutParams(size, size).apply { rightMargin = gap }
            swatch.layoutParams = lp
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
                if (color == settings.accent) setStroke(6, 0xFFFFFFFF.toInt())
            }
            swatch.background = bg
            swatch.setOnClickListener {
                settings.accent = color
                applyAccent(color)
                // refresh selection rings
                for (i in 0 until accentRow.childCount) {
                    val child = accentRow.getChildAt(i)
                    (child.background as android.graphics.drawable.GradientDrawable)
                        .setStroke(if (child == swatch) 6 else 0, 0xFFFFFFFF.toInt())
                }
            }
            accentRow.addView(swatch)
        }

        fun pps() = (1000 / settings.turboMs).toInt().coerceIn(5, 30)
        turboSeek.progress = pps() - 5
        turboLabel.text = getString(R.string.settings_turbo) + " — " +
            getString(R.string.settings_turbo_value, pps())
        turboSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val presses = progress + 5
                settings.turboMs = (1000L / presses).coerceAtLeast(33L)
                engine?.turboIntervalMs = settings.turboMs
                turboLabel.text = getString(R.string.settings_turbo) + " — " +
                    getString(R.string.settings_turbo_value, presses)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        fun sensPct() = (settings.sensitivity * 100).toInt()
        sensSeek.progress = sensPct() - 50
        sensLabel.text = getString(R.string.settings_sens) + " — " +
            getString(R.string.settings_sens_value, sensPct())
        sensSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                settings.sensitivity = (progress + 50) / 100f
                sensLabel.text = getString(R.string.settings_sens) + " — " +
                    getString(R.string.settings_sens_value, progress + 50)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        hapticsSwitch.isChecked = settings.haptics
        hapticsSwitch.setOnCheckedChangeListener { _, checked -> settings.haptics = checked }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- Connect ----

    private fun ensurePermissionsThenStart() {
        val needed = requiredBtPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startAndBindService() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requiredBtPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

    private fun startAndBindService() {
        val intent = Intent(this, HidService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Live device picker. Shows previously-paired devices AND actively scans for nearby
     * ones, and offers to put the tablet into discoverable mode — because the Switch pairs
     * by *finding the controller*, so the reliable path is making us discoverable and then
     * opening Change Grip/Order on the console.
     */
    @SuppressLint("MissingPermission")
    private fun pickHostAndConnect() {
        val svc = service ?: return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Enable Bluetooth first")
            return
        }

        val devices = mutableListOf<BluetoothDevice>()
        val labels = mutableListOf<String>()
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)

        fun addDevice(d: BluetoothDevice, suffix: String) {
            if (devices.any { it.address == d.address }) return
            devices.add(d)
            labels.add("${d.name ?: "Unknown device"}   ${d.address}$suffix")
            listAdapter.notifyDataSetChanged()
        }

        adapter.bondedDevices?.forEach { addDevice(it, "   • paired") }

        scanReceiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context?, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_FOUND) {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null) addDevice(d, "   • nearby")
                }
            }
        }
        registerReceiver(scanReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        adapter.startDiscovery()

        deviceDialog = AlertDialog.Builder(this)
            .setTitle("Scanning for nearby devices…")
            .setAdapter(listAdapter) { _, i -> stopScan(adapter); svc.connectTo(devices[i]) }
            .setNeutralButton("Make tablet discoverable") { _, _ ->
                stopScan(adapter)
                requestDiscoverable()
            }
            .setNegativeButton("Close") { _, _ -> stopScan(adapter) }
            .setOnDismissListener { stopScan(adapter) }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(adapter: BluetoothAdapter?) {
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        scanReceiver?.let { runCatching { unregisterReceiver(it) } }
        scanReceiver = null
    }

    /** Ask Android to make us discoverable so the Switch's Change Grip/Order scan finds us. */
    private fun requestDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        runCatching { startActivity(intent) }
        toast("Now open Change Grip/Order on your Switch")
    }

    // ---- Status ----

    override fun onStatus(status: HidService.Status, device: BluetoothDevice?) {
        runOnUiThread {
            binding.txtStatus.text = when (status) {
                HidService.Status.UNSUPPORTED -> getString(R.string.status_unsupported)
                HidService.Status.IDLE -> getString(R.string.status_idle)
                HidService.Status.REGISTERED -> getString(R.string.status_registered)
                HidService.Status.CONNECTING -> getString(R.string.status_connecting)
                HidService.Status.CONNECTED -> getString(R.string.status_connected)
                HidService.Status.DISCONNECTED -> getString(R.string.status_disconnected)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        deviceDialog?.dismiss()
        scanReceiver?.let { runCatching { unregisterReceiver(it) } }
        scanReceiver = null
        if (bound) {
            service?.setListener(null)
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val LONG_PRESS_MS = 600L
    }
}
