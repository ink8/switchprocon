package com.ink8.switchprocon.bluetooth

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ink8.switchprocon.R
import com.ink8.switchprocon.input.MacroEngine
import com.ink8.switchprocon.protocol.ControllerState
import com.ink8.switchprocon.protocol.ProControllerProtocol
import com.ink8.switchprocon.protocol.SubcommandHandler
import java.util.concurrent.Executors

/**
 * Foreground service that owns the Bluetooth HID Device role. It registers the app as a
 * Pro Controller, answers the console's handshake subcommands, and — once connected —
 * streams the current [ControllerState] as 0x30 input reports at ~60 Hz.
 *
 * The Activity binds to this service to reach the shared [ControllerState] / [MacroEngine]
 * and to observe connection status.
 */
class HidService : Service() {

    val controllerState = ControllerState()
    val macroEngine = MacroEngine(controllerState)
    private val subcommandHandler = SubcommandHandler(controllerState)

    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    @Volatile private var registered = false
    @Volatile private var streaming = false
    // 0x3F simple mode until the console's 0x03 subcommand switches us to 0x30 full mode.
    @Volatile private var inputMode = ProControllerProtocol.REPORT_ID_INPUT_SIMPLE
    private var senderThread: Thread? = null

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var listener: StatusListener? = null

    interface StatusListener {
        fun onStatus(status: Status, device: BluetoothDevice?)
    }

    enum class Status { UNSUPPORTED, IDLE, REGISTERED, CONNECTING, CONNECTED, DISCONNECTED }

    inner class LocalBinder : Binder() {
        val service: HidService get() = this@HidService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = manager?.adapter
        if (adapter == null) {
            notify(Status.UNSUPPORTED, null)
        } else {
            registerHidProfile()
        }
        // When the console flips us to full mode, speed up and nudge it with an L+R press.
        subcommandHandler.onInputModeChanged = { mode ->
            if (mode == ProControllerProtocol.REPORT_ID_INPUT_FULL) {
                inputMode = ProControllerProtocol.REPORT_ID_INPUT_FULL
                pulseLR()
            }
        }
    }

    /**
     * After the handshake, briefly hold L+R. Emulators must send an L+R press once the
     * controller is accepted or the Switch won't register it (documented NXBT quirk).
     */
    private fun pulseLR() {
        Thread {
            try {
                controllerState.setButton(ControllerState.Button.L, true)
                controllerState.setButton(ControllerState.Button.R, true)
                Thread.sleep(120)
                controllerState.setButton(ControllerState.Button.L, false)
                controllerState.setButton(ControllerState.Button.R, false)
            } catch (_: InterruptedException) {
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun setListener(l: StatusListener?) {
        listener = l
    }

    @SuppressLint("MissingPermission")
    private fun registerHidProfile() {
        adapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hidDevice = proxy as BluetoothHidDevice
                registerApp()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null
                    registered = false
                    notify(Status.DISCONNECTED, null)
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            ProControllerProtocol.DEVICE_NAME,
            ProControllerProtocol.SDP_DESCRIPTION,
            ProControllerProtocol.SDP_PROVIDER,
            BluetoothHidDevice.SUBCLASS1_COMBO,
            ProControllerProtocol.HID_REPORT_DESCRIPTOR
        )
        val ok = hidDevice?.registerApp(sdp, null, null, ioExecutor, callback) ?: false
        Log.i(TAG, "registerApp requested: $ok")
    }

    /**
     * Drop any current link and re-advertise from scratch — the "reset connection" the UI
     * exposes. Disconnects the host, re-registers the HID app (fresh SDP), and returns to
     * simple input mode so the next Change Grip/Order attempt starts clean.
     */
    @SuppressLint("MissingPermission")
    fun resetConnection() {
        streaming = false
        senderThread?.interrupt()
        inputMode = ProControllerProtocol.REPORT_ID_INPUT_SIMPLE
        val hid = hidDevice
        runCatching { host?.let { hid?.disconnect(it) } }
        host = null
        notify(Status.IDLE, null)
        ioExecutor.execute {
            runCatching {
                hid?.unregisterApp()
                Thread.sleep(400)
                registerApp()
            }
        }
    }

    /** Ask the console (a bonded device) to connect to us. */
    @SuppressLint("MissingPermission")
    fun connectTo(device: BluetoothDevice) {
        host = device
        notify(Status.CONNECTING, device)
        hidDevice?.connect(device)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        streaming = false
        host?.let { hidDevice?.disconnect(it) }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registeredNow: Boolean) {
            registered = registeredNow
            notify(if (registeredNow) Status.REGISTERED else Status.IDLE, pluggedDevice)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    inputMode = ProControllerProtocol.REPORT_ID_INPUT_SIMPLE
                    notify(Status.CONNECTED, device)
                    startStreaming()
                }
                BluetoothProfile.STATE_CONNECTING -> notify(Status.CONNECTING, device)
                else -> {
                    streaming = false
                    notify(Status.DISCONNECTED, device)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hidDevice?.replyReport(device, type, id, controllerState.buildInputReport())
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            handleOutput(device, data)
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            // Console → controller: rumble + subcommands arrive here as report 0x01.
            val full = ByteArray(data.size + 1)
            full[0] = reportId
            System.arraycopy(data, 0, full, 1, data.size)
            handleOutput(device, full)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleOutput(device: BluetoothDevice, output: ByteArray) {
        val reply = subcommandHandler.handle(output) ?: return
        hidDevice?.sendReport(device, ProControllerProtocol.REPORT_ID_INPUT_REPLY, reply)
    }

    @SuppressLint("MissingPermission")
    private fun startStreaming() {
        if (streaming) return
        streaming = true
        senderThread = Thread {
            try {
                while (streaming) {
                    val device = host ?: break
                    if (inputMode == ProControllerProtocol.REPORT_ID_INPUT_FULL) {
                        // Full mode: stream 0x30 at ~60 Hz.
                        hidDevice?.sendReport(
                            device,
                            ProControllerProtocol.REPORT_ID_INPUT_FULL,
                            controllerState.buildInputReport()
                        )
                        Thread.sleep(15)
                    } else {
                        // Change Grip/Order phase: 0x3F at ~15 Hz. Going faster here makes
                        // firmware 12+ drop the connection.
                        hidDevice?.sendReport(
                            device,
                            ProControllerProtocol.REPORT_ID_INPUT_SIMPLE,
                            controllerState.buildSimpleReport()
                        )
                        Thread.sleep(66)
                    }
                }
            } catch (_: InterruptedException) {
                // stopped
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun notify(status: Status, device: BluetoothDevice?) {
        listener?.onStatus(status, device)
    }

    private fun startAsForeground() {
        val channelId = "switchprocon_hid"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Controller link", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running))
            .setSmallIcon(R.drawable.ic_stat_controller)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        streaming = false
        senderThread?.interrupt()
        macroEngine.shutdown()
        try {
            hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (e: Exception) {
            Log.w(TAG, "closeProfileProxy failed", e)
        }
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HidService"
        private const val NOTIF_ID = 1001
    }
}
