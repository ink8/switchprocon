package com.ink8.switchprocon.protocol

import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe snapshot of the emulated Pro Controller: which buttons are held and where
 * the two analog sticks sit. The Bluetooth thread reads this at ~60 Hz to build input
 * reports; the UI thread writes to it as the user touches the screen.
 *
 * Button encoding follows the Switch Pro Controller "standard full" input report (0x30).
 * See dekuNukem/Nintendo_Switch_Reverse_Engineering for the byte layout.
 */
class ControllerState {

    // Three button bytes, matching report bytes 3 (right), 4 (shared), 5 (left).
    @Volatile private var right = 0
    @Volatile private var shared = 0
    @Volatile private var left = 0

    // Analog sticks, 12-bit unsigned, 0x800 (2048) = centered.
    @Volatile private var leftStickX = CENTER
    @Volatile private var leftStickY = CENTER
    @Volatile private var rightStickX = CENTER
    @Volatile private var rightStickY = CENTER

    private val timer = AtomicInteger(0)

    fun setButton(button: Button, pressed: Boolean) {
        val bit = 1 shl button.bit
        when (button.byte) {
            ByteGroup.RIGHT -> right = if (pressed) right or bit else right and bit.inv()
            ByteGroup.SHARED -> shared = if (pressed) shared or bit else shared and bit.inv()
            ByteGroup.LEFT -> left = if (pressed) left or bit else left and bit.inv()
        }
    }

    /** x and y in [-1f, 1f]; up/right are positive on screen and mapped to the Switch axes. */
    fun setLeftStick(x: Float, y: Float) {
        leftStickX = toAxis(x)
        leftStickY = toAxis(y)
    }

    fun setRightStick(x: Float, y: Float) {
        rightStickX = toAxis(x)
        rightStickY = toAxis(y)
    }

    fun reset() {
        right = 0; shared = 0; left = 0
        leftStickX = CENTER; leftStickY = CENTER
        rightStickX = CENTER; rightStickY = CENTER
    }

    /**
     * Build the 0x30 standard full input report body (without the leading report id, which
     * the HID layer prepends). Layout: timer, battery/conn, 3 button bytes, 6 stick bytes,
     * vibrator ack, then IMU (left zeroed for now).
     */
    fun buildInputReport(): ByteArray {
        val data = ByteArray(48)
        data[0] = (timer.getAndIncrement() and 0xFF).toByte()
        data[1] = 0x90.toByte()            // battery full (0x9) + connection info
        data[2] = (right and 0xFF).toByte()
        data[3] = (shared and 0xFF).toByte()
        data[4] = (left and 0xFF).toByte()
        packStick(data, 5, leftStickX, leftStickY)
        packStick(data, 8, rightStickX, rightStickY)
        data[11] = 0x00                    // vibrator input report
        return data
    }

    /**
     * Build the 0x3F "simple" input report body (without report id) sent during the
     * Change Grip/Order phase before the console switches us to 0x30. Buttons use the
     * simple mapping; sticks are reported as centered 8-direction hat + 16-bit axes.
     */
    fun buildSimpleReport(): ByteArray = byteArrayOf(
        (right and 0xFF).toByte(),
        (left and 0xFF).toByte(),
        0x08,                              // hat: neutral
        0x00, 0x80.toByte(), 0x00, 0x80.toByte(), // left stick centered (16-bit)
        0x00, 0x80.toByte(), 0x00, 0x80.toByte()  // right stick centered
    )

    private fun packStick(out: ByteArray, offset: Int, x: Int, y: Int) {
        out[offset] = (x and 0xFF).toByte()
        out[offset + 1] = (((y and 0x0F) shl 4) or ((x shr 8) and 0x0F)).toByte()
        out[offset + 2] = ((y shr 4) and 0xFF).toByte()
    }

    private fun toAxis(v: Float): Int {
        val clamped = v.coerceIn(-1f, 1f)
        return (CENTER + clamped * RANGE).toInt().coerceIn(0, 0xFFF)
    }

    enum class ByteGroup { RIGHT, SHARED, LEFT }

    /** Every Pro Controller button with its (byte group, bit) position in the 0x30 report. */
    enum class Button(val byte: ByteGroup, val bit: Int) {
        Y(ByteGroup.RIGHT, 0),
        X(ByteGroup.RIGHT, 1),
        B(ByteGroup.RIGHT, 2),
        A(ByteGroup.RIGHT, 3),
        R(ByteGroup.RIGHT, 6),
        ZR(ByteGroup.RIGHT, 7),

        MINUS(ByteGroup.SHARED, 0),
        PLUS(ByteGroup.SHARED, 1),
        R_STICK(ByteGroup.SHARED, 2),
        L_STICK(ByteGroup.SHARED, 3),
        HOME(ByteGroup.SHARED, 4),
        CAPTURE(ByteGroup.SHARED, 5),

        DOWN(ByteGroup.LEFT, 0),
        UP(ByteGroup.LEFT, 1),
        RIGHT_DPAD(ByteGroup.LEFT, 2),
        LEFT_DPAD(ByteGroup.LEFT, 3),
        L(ByteGroup.LEFT, 6),
        ZL(ByteGroup.LEFT, 7),
    }

    companion object {
        const val CENTER = 0x800
        private const val RANGE = 1920f // keeps a little headroom below the 12-bit max (0x7FF)
    }
}
