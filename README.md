# SwitchProCon

An Android app that turns your phone into a **Nintendo Switch Pro Controller** over
Bluetooth, with an on-screen gamepad plus **macro recording, loop, and turbo**. An
[ink8](https://github.com/ink8) project.

> **Status: early scaffold (v0.1).** The on-screen controller, Bluetooth HID Device
> registration (advertises as a real "Pro Controller"), and the macro/turbo/loop engine
> are in place. The Switch pairing handshake is implemented as a first pass and needs
> iteration against real hardware.

## Platform reality (read before planning)
- **Android only.** Emulating a Bluetooth-Classic HID gamepad needs Android's
  `BluetoothHidDevice` API (Android 9 / API 28+). The device's Bluetooth stack must
  support the HID Device role — most modern phones do, but not literally all.
- **iOS is not supported and likely can't be.** Apple's public APIs (CoreBluetooth) only
  allow BLE peripheral mode, not emulating a Classic HID gamepad. There is no App Store
  path today.
- **Switch 1** is the known-good target. **Switch 2** added controller authentication that
  may reject emulated controllers — unverified.
- Prior art: the open-source *Joy-Con Droid* proves the Android approach works on Switch 1.

## Build
Requires the Android SDK (platform 35, build-tools 35) and a JDK 17–21 (Android Studio's
bundled JBR works well).

```bash
# Debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Install to a connected phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## How to use (once pairing is working)
1. Launch the app and grant Bluetooth permission.
2. On the Switch: **System Settings ▸ Controllers ▸ Change Grip/Order** to enter pairing.
3. Tap **Connect** in the app and pick the console.
4. Play with the on-screen controller. **Long-press** any button to toggle **turbo**;
   **REC** records a macro, **PLAY** replays it, **LOOP** repeats it.

## Project layout
```
app/src/main/java/com/ink8/switchprocon/
  MainActivity.kt              on-screen controller + wiring
  bluetooth/HidService.kt      Bluetooth HID Device role, 60 Hz input stream
  protocol/
    ProControllerProtocol.kt   identity + HID report descriptor
    ControllerState.kt         button/stick state → 0x30 input report
    SubcommandHandler.kt       0x21 handshake replies
  input/MacroEngine.kt         record / loop / turbo
  ui/JoystickView.kt           touch analog stick
```
