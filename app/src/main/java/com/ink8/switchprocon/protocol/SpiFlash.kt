package com.ink8.switchprocon.protocol

/**
 * A virtual Pro Controller SPI flash. During the pairing handshake the Switch issues
 * `0x10` "SPI flash read" subcommands for the controller's identity and calibration; we
 * answer from this table. Addresses and factory-calibration byte values come from
 * dekuNukem's spi_flash_notes and match what joycontrol/NXBT serve.
 *
 * Anything not populated reads back as 0xFF (which the Switch treats as "unset" and falls
 * back to its own defaults). Providing real factory stick calibration keeps the sticks
 * centered with correct range instead of drifting.
 */
object SpiFlash {

    // Two 256-byte pages cover every address the Switch reads during setup.
    private const val FACTORY_BASE = 0x6000
    private const val USER_BASE = 0x8000
    private val factory = ByteArray(0x100) { 0xFF.toByte() }
    private val user = ByteArray(0x100) { 0xFF.toByte() }

    init {
        // Controller type: Pro Controller.
        factory[0x12] = 0x03

        // Factory 6-axis (IMU) calibration @0x6020 (24 bytes) — neutral defaults.
        put(0x6020, intArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40,
            0x00, 0x40, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x3B, 0x34, 0x3B, 0x34, 0x3B, 0x34
        ))

        // Factory stick calibration (from a real dump) — centered, full range.
        put(0x603D, intArrayOf(0xBA, 0x15, 0x62, 0x11, 0xB8, 0x7F, 0x9C, 0x81, 0x5A)) // left
        put(0x6046, intArrayOf(0x16, 0xD8, 0x7D, 0xF2, 0xB5, 0x5F, 0x86, 0x65, 0x5E)) // right

        // Colors flag + body/button/grip colors @0x6050.
        factory[0x1B] = 0x01                                   // 0x601B: SPI colors present
        put(0x6050, intArrayOf(0x32, 0x32, 0x32))              // body: dark grey
        put(0x6053, intArrayOf(0xFF, 0xFF, 0xFF))              // buttons: white
        put(0x6056, intArrayOf(0x46, 0x46, 0x46))              // left grip
        put(0x6059, intArrayOf(0x46, 0x46, 0x46))              // right grip

        // Six-axis horizontal offsets @0x6080, and stick parameters @0x6086 / @0x6098.
        put(0x6080, intArrayOf(0x50, 0xFD, 0x00, 0x00, 0xC6, 0x0F))
        val stickParams = intArrayOf(
            0x0F, 0x30, 0x61, 0x96, 0x30, 0xF3, 0xD4, 0x14, 0x54,
            0x41, 0x15, 0x54, 0xC7, 0x79, 0x9C, 0x33, 0x36, 0x63
        )
        put(0x6086, stickParams)
        put(0x6098, stickParams)

        // User calibration left as 0xFF (magic bytes absent → Switch uses factory cal).
    }

    /** Read [length] bytes starting at [address], filling unknown bytes with 0xFF. */
    fun read(address: Int, length: Int): ByteArray {
        val out = ByteArray(length) { 0xFF.toByte() }
        for (i in 0 until length) {
            val addr = address + i
            when (addr) {
                in FACTORY_BASE until FACTORY_BASE + factory.size -> out[i] = factory[addr - FACTORY_BASE]
                in USER_BASE until USER_BASE + user.size -> out[i] = user[addr - USER_BASE]
            }
        }
        return out
    }

    private fun put(address: Int, bytes: IntArray) {
        val page = if (address >= USER_BASE) user else factory
        val base = if (address >= USER_BASE) USER_BASE else FACTORY_BASE
        for (i in bytes.indices) page[address - base + i] = bytes[i].toByte()
    }
}
