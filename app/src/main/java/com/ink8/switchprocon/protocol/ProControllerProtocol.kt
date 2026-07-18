package com.ink8.switchprocon.protocol

/**
 * Identity and HID descriptor the app advertises so a Switch treats it as a genuine
 * Pro Controller. Values come from the Nintendo Switch reverse-engineering docs.
 *
 * NOTE: registering this descriptor makes the console *pair* with us. Actually completing
 * the connection needs the subcommand handshake (0x01/0x02/0x08/0x10/0x21/0x30/0x40...),
 * which lives in [SubcommandHandler]. Switch 2 may additionally require controller
 * authentication that is not yet reverse-engineered — see the project brain notes.
 */
object ProControllerProtocol {

    const val VENDOR_ID = 0x057E     // Nintendo
    const val PRODUCT_ID = 0x2009    // Pro Controller
    const val DEVICE_NAME = "Pro Controller"
    const val SDP_PROVIDER = "Nintendo"
    const val SDP_DESCRIPTION = "Wireless Gamepad"

    const val REPORT_ID_INPUT_FULL = 0x30   // standard full input report we push at 60 Hz
    const val REPORT_ID_INPUT_SIMPLE = 0x3F // simple input mode used before the handshake
    const val REPORT_ID_INPUT_REPLY = 0x21  // subcommand reply
    const val REPORT_ID_OUTPUT_RUMBLE_SUB = 0x01
    const val REPORT_ID_OUTPUT_RUMBLE = 0x10

    /** Nintendo Switch Pro Controller HID report descriptor. */
    val HID_REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x01,                    // Usage Page (Generic Desktop)
        0x15, 0x00,                    // Logical Minimum (0)
        0x09, 0x04,                    // Usage (Joystick)
        0xA1.toByte(), 0x01,           // Collection (Application)
        0x85.toByte(), 0x30,           //   Report ID (0x30)
        0x05, 0x01,                    //   Usage Page (Generic Desktop)
        0x05, 0x09,                    //   Usage Page (Button)
        0x19, 0x01,                    //   Usage Minimum (1)
        0x29, 0x0A,                    //   Usage Maximum (10)
        0x15, 0x00,                    //   Logical Minimum (0)
        0x25, 0x01,                    //   Logical Maximum (1)
        0x75, 0x01,                    //   Report Size (1)
        0x95.toByte(), 0x0A,           //   Report Count (10)
        0x55, 0x00,                    //   Unit Exponent (0)
        0x65, 0x00,                    //   Unit (None)
        0x81.toByte(), 0x02,           //   Input (Data,Var,Abs)
        0x05, 0x09,                    //   Usage Page (Button)
        0x19, 0x0B,                    //   Usage Minimum (11)
        0x29, 0x0E,                    //   Usage Maximum (14)
        0x15, 0x00,                    //   Logical Minimum (0)
        0x25, 0x01,                    //   Logical Maximum (1)
        0x75, 0x01,                    //   Report Size (1)
        0x95.toByte(), 0x04,           //   Report Count (4)
        0x81.toByte(), 0x02,           //   Input (Data,Var,Abs)
        0x75, 0x01,                    //   Report Size (1)
        0x95.toByte(), 0x02,           //   Report Count (2)
        0x81.toByte(), 0x03,           //   Input (Const,Var,Abs)
        0x0B, 0x01, 0x00, 0x01, 0x00,  //   Usage (0x00010001)
        0xA1.toByte(), 0x00,           //   Collection (Physical)
        0x0B, 0x30, 0x00, 0x01, 0x00,  //     Usage (0x00010030) X
        0x0B, 0x31, 0x00, 0x01, 0x00,  //     Usage (0x00010031) Y
        0x0B, 0x32, 0x00, 0x01, 0x00,  //     Usage (0x00010032) Z
        0x0B, 0x35, 0x00, 0x01, 0x00,  //     Usage (0x00010035) Rz
        0x15, 0x00,                    //     Logical Minimum (0)
        0x27, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, // Logical Maximum (65534)
        0x75, 0x10,                    //     Report Size (16)
        0x95.toByte(), 0x04,           //     Report Count (4)
        0x81.toByte(), 0x02,           //     Input (Data,Var,Abs)
        0xC0.toByte(),                 //   End Collection
        0x0B, 0x39, 0x00, 0x01, 0x00,  //   Usage (0x00010039) Hat switch
        0x15, 0x00,                    //   Logical Minimum (0)
        0x25, 0x07,                    //   Logical Maximum (7)
        0x35, 0x00,                    //   Physical Minimum (0)
        0x46, 0x3B, 0x01,              //   Physical Maximum (315)
        0x65, 0x14,                    //   Unit (Eng Rot: Degrees)
        0x75, 0x04,                    //   Report Size (4)
        0x95.toByte(), 0x01,           //   Report Count (1)
        0x81.toByte(), 0x02,           //   Input (Data,Var,Abs)
        0x05, 0x09,                    //   Usage Page (Button)
        0x19, 0x0F,                    //   Usage Minimum (15)
        0x29, 0x12,                    //   Usage Maximum (18)
        0x15, 0x00,                    //   Logical Minimum (0)
        0x25, 0x01,                    //   Logical Maximum (1)
        0x75, 0x01,                    //   Report Size (1)
        0x95.toByte(), 0x04,           //   Report Count (4)
        0x81.toByte(), 0x02,           //   Input (Data,Var,Abs)
        0x75, 0x08,                    //   Report Size (8)
        0x95.toByte(), 0x34,           //   Report Count (52)
        0x81.toByte(), 0x03,           //   Input (Const,Var,Abs)
        0x05, 0x08,                    //   Usage Page (LEDs)
        0x19, 0x01,                    //   Usage Minimum (1)
        0x29, 0x04,                    //   Usage Maximum (4)
        0x15, 0x00,                    //   Logical Minimum (0)
        0x25, 0x01,                    //   Logical Maximum (1)
        0x75, 0x01,                    //   Report Size (1)
        0x95.toByte(), 0x04,           //   Report Count (4)
        0x91.toByte(), 0x02,           //   Output (Data,Var,Abs)
        0x75, 0x04,                    //   Report Size (4)
        0x95.toByte(), 0x01,           //   Report Count (1)
        0x91.toByte(), 0x03,           //   Output (Const,Var,Abs)
        0x05, 0x09,                    //   Usage Page (Button)
        0x19, 0x05,                    //   Usage Minimum (5)
        0x29, 0x08,                    //   Usage Maximum (8)
        0x15, 0x00,                    //   Logical Minimum (0)
        0x25, 0x01,                    //   Logical Maximum (1)
        0x75, 0x01,                    //   Report Size (1)
        0x95.toByte(), 0x04,           //   Report Count (4)
        0x91.toByte(), 0x02,           //   Output (Data,Var,Abs)
        0x75, 0x04,                    //   Report Size (4)
        0x95.toByte(), 0x01,           //   Report Count (1)
        0x91.toByte(), 0x03,           //   Output (Const,Var,Abs)
        0x85.toByte(), 0x21,           //   Report ID (0x21)
        0x09, 0x01,                    //   Usage (1)
        0x75, 0x08,                    //   Report Size (8)
        0x95.toByte(), 0x30,           //   Report Count (48)
        0x81.toByte(), 0x02,           //   Input (Data,Var,Abs)
        0x85.toByte(), 0x81.toByte(),  //   Report ID (0x81)
        0x09, 0x02,                    //   Usage (2)
        0x75, 0x08,                    //   Report Size (8)
        0x95.toByte(), 0x30,           //   Report Count (48)
        0x81.toByte(), 0x02,           //   Input (Data,Var,Abs)
        0x85.toByte(), 0x01,           //   Report ID (0x01)
        0x09, 0x03,                    //   Usage (3)
        0x75, 0x08,                    //   Report Size (8)
        0x95.toByte(), 0x30,           //   Report Count (48)
        0x91.toByte(), 0x02,           //   Output (Data,Var,Abs)
        0x85.toByte(), 0x10,           //   Report ID (0x10)
        0x09, 0x04,                    //   Usage (4)
        0x75, 0x08,                    //   Report Size (8)
        0x95.toByte(), 0x30,           //   Report Count (48)
        0x91.toByte(), 0x02,           //   Output (Data,Var,Abs)
        0x85.toByte(), 0x80.toByte(),  //   Report ID (0x80)
        0x09, 0x05,                    //   Usage (5)
        0x75, 0x08,                    //   Report Size (8)
        0x95.toByte(), 0x30,           //   Report Count (48)
        0x91.toByte(), 0x02,           //   Output (Data,Var,Abs)
        0x85.toByte(), 0x82.toByte(),  //   Report ID (0x82)
        0x09, 0x06,                    //   Usage (6)
        0x75, 0x08,                    //   Report Size (8)
        0x95.toByte(), 0x30,           //   Report Count (48)
        0x91.toByte(), 0x02,           //   Output (Data,Var,Abs)
        0xC0.toByte()                  // End Collection
    )
}
