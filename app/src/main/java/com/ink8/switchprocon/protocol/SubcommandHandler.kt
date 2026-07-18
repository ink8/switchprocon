package com.ink8.switchprocon.protocol

/**
 * Builds the 0x21 subcommand replies the Switch expects during the pairing handshake, and
 * reports back protocol state changes (input-report mode, player LEDs) the HID layer needs.
 *
 * Handshake the console walks through (see [[switch-controller-emulation]] in the brain):
 * request device info (0x02) → set shipment state (0x08) → SPI flash reads (0x10) →
 * set input report mode (0x03) → trigger timers (0x04) → enable IMU (0x40) → enable
 * vibration (0x48) → set player LEDs (0x30). Only after 0x03 sets mode 0x30 do we begin
 * streaming full 0x30 input at speed.
 */
class SubcommandHandler(private val state: ControllerState) {

    /** Notified when the console flips us into a given input-report mode (e.g. 0x30). */
    var onInputModeChanged: ((mode: Int) -> Unit)? = null

    /** A fixed, locally-administered MAC we present as the controller's address. */
    private val macAddress = byteArrayOf(0x7E, 0x00, 0x00, 0x00, 0x00, 0x01)

    /**
     * @param output full output report from the console (byte 0 = report id).
     * @return a 0x21 reply body (without the leading report id) or null if no reply is due.
     */
    fun handle(output: ByteArray): ByteArray? {
        if (output.isEmpty()) return null
        if ((output[0].toInt() and 0xFF) != ProControllerProtocol.REPORT_ID_OUTPUT_RUMBLE_SUB) return null
        if (output.size < 11) return null

        val subcommand = output[10].toInt() and 0xFF
        return when (subcommand) {
            0x02 -> reply(subcommand, ack = 0x82, data = deviceInfo())
            0x10 -> reply(subcommand, ack = 0x90, data = spiFlashRead(output))
            0x03 -> {
                // Set input report mode; byte 11 is the requested mode.
                val mode = if (output.size > 11) output[11].toInt() and 0xFF else 0x30
                onInputModeChanged?.invoke(mode)
                reply(subcommand, ack = 0x80, data = ByteArray(0))
            }
            0x04, // trigger buttons elapsed time
            0x08, // shipment low-power state
            0x21, // set NFC/IR config
            0x30, // set player LEDs
            0x38, // set HOME light
            0x40, // enable IMU
            0x48, // enable vibration
            -> reply(subcommand, ack = 0x80, data = ByteArray(0))
            0x00 -> null
            else -> reply(subcommand, ack = 0x80, data = ByteArray(0))
        }
    }

    /** Assemble a 0x21 reply: standard input prefix + ACK byte + echoed subcommand + data. */
    private fun reply(subcommand: Int, ack: Int, data: ByteArray): ByteArray {
        val input = state.buildInputReport() // timer, battery, buttons, sticks, vibrator
        val body = ByteArray(14 + data.size)
        System.arraycopy(input, 0, body, 0, 12)
        body[12] = ack.toByte()
        body[13] = subcommand.toByte()
        System.arraycopy(data, 0, body, 14, data.size)
        return body
    }

    /** Subcommand 0x02 reply: firmware, controller type (0x03 = Pro), MAC, color flags. */
    private fun deviceInfo(): ByteArray = byteArrayOf(
        0x04, 0x21,             // firmware version 4.33
        0x03,                   // controller type: Pro Controller
        0x02,                   // unknown, always 0x02
        macAddress[0], macAddress[1], macAddress[2], macAddress[3], macAddress[4], macAddress[5],
        0x01,                   // unknown
        0x01                    // uses SPI colors
    )

    /**
     * Subcommand 0x10 reply: SPI flash read, served from [SpiFlash]. Request layout:
     * bytes 11..14 = little-endian address, byte 15 = length. Reply echoes address+length
     * then the bytes.
     */
    private fun spiFlashRead(output: ByteArray): ByteArray {
        if (output.size < 16) return ByteArray(0)
        val address = (output[11].toInt() and 0xFF) or
            ((output[12].toInt() and 0xFF) shl 8) or
            ((output[13].toInt() and 0xFF) shl 16) or
            ((output[14].toInt() and 0xFF) shl 24)
        val length = output[15].toInt() and 0xFF

        val header = byteArrayOf(
            (address and 0xFF).toByte(),
            ((address shr 8) and 0xFF).toByte(),
            ((address shr 16) and 0xFF).toByte(),
            ((address shr 24) and 0xFF).toByte(),
            length.toByte()
        )
        return header + SpiFlash.read(address, length)
    }
}
