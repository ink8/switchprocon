package com.ink8.switchprocon.input

import com.ink8.switchprocon.protocol.ControllerState
import com.ink8.switchprocon.protocol.ControllerState.Button
import java.util.Collections

/**
 * The three "power" features layered on top of raw button input:
 *
 *  - **Record / replay**: capture a timed sequence of button presses and play it back.
 *  - **Loop**: replay the recorded macro continuously until stopped.
 *  - **Turbo**: auto-fire — while a turbo button is held, toggle it on/off rapidly.
 *
 * All button input from the UI should flow through [press] so it can be recorded. Turbo and
 * playback drive [ControllerState] directly on background threads.
 */
class MacroEngine(private val state: ControllerState) {

    data class Event(val offsetMs: Long, val button: Button, val pressed: Boolean)

    private val recorded = mutableListOf<Event>()
    private val turboButtons: MutableSet<Button> = Collections.synchronizedSet(mutableSetOf())

    @Volatile var isRecording = false
        private set
    @Volatile var isPlaying = false
        private set
    @Volatile var loopEnabled = false

    var turboIntervalMs: Long = 33 // ~15 presses/sec

    private var recordStartMs = 0L
    private var playThread: Thread? = null
    private var turboThread: Thread? = null

    /** Apply a live button press from the UI, recording it if a recording is in progress. */
    fun press(button: Button, pressed: Boolean) {
        state.setButton(button, pressed)
        if (isRecording) {
            synchronized(recorded) {
                recorded.add(Event(System.currentTimeMillis() - recordStartMs, button, pressed))
            }
        }
    }

    fun startRecording() {
        stopPlayback()
        synchronized(recorded) { recorded.clear() }
        recordStartMs = System.currentTimeMillis()
        isRecording = true
    }

    fun stopRecording() {
        isRecording = false
    }

    fun hasRecording(): Boolean = synchronized(recorded) { recorded.isNotEmpty() }

    /** Replay the recorded macro once, or forever if [loopEnabled] is set. */
    fun startPlayback() {
        if (isPlaying || !hasRecording()) return
        val events = synchronized(recorded) { recorded.toList() }
        isPlaying = true
        playThread = Thread {
            try {
                do {
                    var last = 0L
                    for (e in events) {
                        if (!isPlaying) break
                        val wait = e.offsetMs - last
                        if (wait > 0) Thread.sleep(wait)
                        last = e.offsetMs
                        state.setButton(e.button, e.pressed)
                    }
                } while (isPlaying && loopEnabled)
            } catch (_: InterruptedException) {
                // stopped
            } finally {
                isPlaying = false
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stopPlayback() {
        isPlaying = false
        playThread?.interrupt()
        playThread = null
    }

    fun toggleTurbo(button: Button): Boolean {
        val nowOn = if (turboButtons.contains(button)) {
            turboButtons.remove(button); false
        } else {
            turboButtons.add(button); true
        }
        ensureTurboThread()
        return nowOn
    }

    fun isTurbo(button: Button): Boolean = turboButtons.contains(button)

    private fun ensureTurboThread() {
        if (turboThread != null) return
        turboThread = Thread {
            var on = false
            try {
                while (true) {
                    on = !on
                    val snapshot = synchronized(turboButtons) { turboButtons.toList() }
                    for (b in snapshot) state.setButton(b, on)
                    Thread.sleep(turboIntervalMs)
                }
            } catch (_: InterruptedException) {
                // stopped
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun shutdown() {
        stopPlayback()
        turboThread?.interrupt()
        turboThread = null
        turboButtons.clear()
    }
}
