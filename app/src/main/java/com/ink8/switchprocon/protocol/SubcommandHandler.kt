package com.ink8.switchprocon.protocol

/**
 * Builds the 0x21 subcommand replies the Switch expects during the pairing handshake.
 *
 * The console sends output reports (report id 0x01) carrying a subcommand in byte 10; we
 * answer with a 0x21 input report whose tail is an ACK + the requested data. The set below
 * covers the handshake a Switch 1 walks through before it will accept 0x30 input. Values
 * that would normally come from the controller's flash (stick calibration, colors) are
 * returned as sane defaults.
 *
 * This is a first pass — exact flash contents and Switch 2 behavior need verification on
 * real hardware. See the project brain notes.
 */
class SubcommandHandler(private val state: ControllerState) {

    /** A fixed, locally-administered MAC we present as the controller's address. */
    private val macAddress = byteArrayOf(0x7E, 0x00, 0x00, 0x00, 0x00, 0x01)

    /**
     * @param output the full output report from the console (byte 0 is the report id).
     * @return a 0x21 reply body (without the leading report id) or null if no reply is due.
     */
    fun handle(output: ByteArray): ByteArray? {
        if (output.isEmpty()) return null
        // Output report 0x01 = "rumble + subcommand". Subcommand id sits at byte 10.
        if ((output[0].toInt() and 0xFF) != ProControllerProtocol.REPORT_ID_OUTPUT_RUMBLE_SUB) return null
        if (output.size < 11) return null

        val subcommand = output[10].toInt() and 0xFF
        return when (subcommand) {
            0x02 -> reply(subcommand, ack = 0x82, data = deviceInfo())
            0x10 -> reply(subcommand, ack = 0x90, data = spiFlashRead(output))
            0x03, // set input report mode
            0x04, // trigger buttons elapsed time
            0x08, // shipment low-power state
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
        val body = ByteArray(13 + 2 + data.size)
        System.arraycopy(input, 0, body, 0, 12)
        body[12] = ack.toByte()
        body[13] = subcommand.toByte()
        System.arraycopy(data, 0, body, 14, data.size)
        return body
    }

    /** Subcommand 0x02 reply: firmware, controller type (0x03 = Pro), MAC, color flags. */
    private fun deviceInfo(): ByteArray = byteArrayOf(
        0x03, 0x48,             // firmware version 3.72
        0x03,                   // controller type: Pro Controller
        0x02,                   // unknown, always 0x02
        macAddress[0], macAddress[1], macAddress[2], macAddress[3], macAddress[4], macAddress[5],
        0x01,                   // unknown
        0x01                    // uses SPI colors
    )

    /**
     * Subcommand 0x10 reply: SPI flash read. The Switch reads stick calibration and colors
     * from flash; returning neutral calibration keeps sticks usable. Request layout:
     * bytes 11..14 = little-endian address, byte 15 = length.
     */
    private fun spiFlashRead(output: ByteArray): ByteArray {
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
        // Neutral factory calibration: 0xFF means "use defaults" for most calibration blocks.
        val payload = ByteArray(length) { 0xFF.toByte() }
        return header + payload
    }
}
