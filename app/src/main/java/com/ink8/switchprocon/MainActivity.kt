package com.ink8.switchprocon

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ink8.switchprocon.bluetooth.HidService
import com.ink8.switchprocon.databinding.ActivityMainBinding
import com.ink8.switchprocon.protocol.ControllerState.Button

/**
 * The on-screen controller. Binds to [HidService] for the shared controller state and
 * macro engine, maps every on-screen control to a Pro Controller input, and drives the
 * connect / record / loop UI.
 */
class MainActivity : AppCompatActivity(), HidService.StatusListener {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    private var service: HidService? = null
    private var bound = false

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
        binding.btnConnect.setOnClickListener { pickHostAndConnect() }
        ensurePermissionsThenStart()
    }

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

    // ---- Wiring on-screen controls to controller state ----

    private fun wireControls() {
        val svc = service ?: return
        val engine = svc.macroEngine

        wireButton(binding.btnA, Button.A, engine)
        wireButton(binding.btnB, Button.B, engine)
        wireButton(binding.btnX, Button.X, engine)
        wireButton(binding.btnY, Button.Y, engine)
        wireButton(binding.btnUp, Button.UP, engine)
        wireButton(binding.btnDown, Button.DOWN, engine)
        wireButton(binding.btnLeft, Button.LEFT_DPAD, engine)
        wireButton(binding.btnRight, Button.RIGHT_DPAD, engine)
        wireButton(binding.btnL, Button.L, engine)
        wireButton(binding.btnR, Button.R, engine)
        wireButton(binding.btnZl, Button.ZL, engine)
        wireButton(binding.btnZr, Button.ZR, engine)
        wireButton(binding.btnMinus, Button.MINUS, engine)
        wireButton(binding.btnPlus, Button.PLUS, engine)
        wireButton(binding.btnHome, Button.HOME, engine)
        wireButton(binding.btnCapture, Button.CAPTURE, engine)

        binding.stickLeft.onMove = { x, y -> svc.controllerState.setLeftStick(x, y) }
        binding.stickRight.onMove = { x, y -> svc.controllerState.setRightStick(x, y) }

        binding.btnRec.setOnClickListener { toggleRecord(engine) }
        binding.btnPlay.setOnClickListener { togglePlay(engine) }
        binding.btnLoop.setOnClickListener {
            engine.loopEnabled = !engine.loopEnabled
            binding.btnLoop.isSelected = engine.loopEnabled
        }
    }

    /**
     * Press/release on touch, plus long-hold to toggle turbo (auto-fire) on that button.
     * We manage [View.isPressed]/[View.isSelected] ourselves so the tint selector reflects
     * live and turbo state.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireButton(view: View, button: Button, engine: com.ink8.switchprocon.input.MacroEngine) {
        val turboRunnable = Runnable {
            val on = engine.toggleTurbo(button)
            view.isSelected = on
            Toast.makeText(this, "${button.name} turbo ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
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

    private fun toggleRecord(engine: com.ink8.switchprocon.input.MacroEngine) {
        if (engine.isRecording) {
            engine.stopRecording()
            binding.btnRec.isSelected = false
        } else {
            engine.startRecording()
            binding.btnRec.isSelected = true
        }
    }

    private fun togglePlay(engine: com.ink8.switchprocon.input.MacroEngine) {
        if (engine.isPlaying) {
            engine.stopPlayback()
            binding.btnPlay.isSelected = false
        } else {
            engine.startPlayback()
            binding.btnPlay.isSelected = engine.isPlaying
        }
    }

    // ---- Connect ----

    @SuppressLint("MissingPermission")
    private fun pickHostAndConnect() {
        val svc = service ?: return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_LONG).show()
            return
        }
        val bonded: List<BluetoothDevice> = adapter.bondedDevices?.toList() ?: emptyList()
        if (bonded.isEmpty()) {
            Toast.makeText(
                this,
                "No paired devices. On the Switch: System Settings ▸ Controllers ▸ Change Grip/Order, then pair.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val names = bonded.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Connect to Switch")
            .setItems(names) { _, i -> svc.connectTo(bonded[i]) }
            .show()
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

    override fun onDestroy() {
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
